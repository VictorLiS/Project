package com.dyx.simpledb.backend.dm.logger;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.Arrays;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import com.google.common.primitives.Bytes;

import com.dyx.simpledb.backend.utils.Panic;
import com.dyx.simpledb.backend.utils.Parser;
import com.dyx.simpledb.common.Error;

/**
 * 日志文件读写
 * 
 * 日志文件标准格式为：
 * [XChecksum] [Log1] [Log2] ... [LogN] [BadTail]
 * XChecksum 为后续所有日志计算的Checksum，int类型
 * 
 * 每条正确日志的格式为：
 * [Size] [Checksum] [Data]
 * Size 4字节int 标识Data长度
 * Checksum 4字节int
 */
public class LoggerImpl implements Logger {

    private static final int SEED = 13331;

    private static final int OF_SIZE = 0;
    private static final int OF_CHECKSUM = OF_SIZE + 4;
    private static final int OF_DATA = OF_CHECKSUM + 4;
    
    public static final String LOG_SUFFIX = ".log";

    private RandomAccessFile file;
    private FileChannel fc;
    private Lock lock;

    private long position;  // 当前日志指针的位置
    private long fileSize;  // 初始化时记录，log操作不更新
    private int xChecksum; // 校验和

    LoggerImpl(RandomAccessFile raf, FileChannel fc) {
        this.file = raf;
        this.fc = fc;
        lock = new ReentrantLock();
    }

    LoggerImpl(RandomAccessFile raf, FileChannel fc, int xChecksum) {
        this.file = raf;
        this.fc = fc;
        this.xChecksum = xChecksum;
        lock = new ReentrantLock();
    }

    void init() {
        long size = 0;
        try {
            size = file.length();
        } catch (IOException e) {
            Panic.panic(e);
        }
        if(size < 4) {
            Panic.panic(Error.BadLogFileException);
        }

        ByteBuffer raw = ByteBuffer.allocate(4);
        try {
            fc.position(0);
            fc.read(raw);
        } catch (IOException e) {
            Panic.panic(e);
        }
        int xChecksum = Parser.parseInt(raw.array());
        this.fileSize = size;
        this.xChecksum = xChecksum;

        checkAndRemoveTail();
    }

    // 检查并移除bad tail
    private void checkAndRemoveTail() {
        // 将当前位置重置为文件的开始位置
        // [XChecksum][Log1][Log2]...[LogN][BadTail] --> [Log1][Log2]...[LogN][BadTail]
        rewind();
        // 初始化校验和为 0
        int xCheck = 0;
        // 循环读取日志，直到没有更多的日志可以读取
        while (true) {
            // 读取下一条日志
            byte[] log = internNext();

            // 如果读取到的日志为 null，说明没有更多的日志可以读取，跳出循环
            if (log == null) break;

            // 计算校验和
            xCheck = calChecksum(xCheck, log);
        }

        // 比较计算得到的校验和和文件中的校验和，如果不相等，说明日志已经被破坏，抛出异常
        if (xCheck != xChecksum) {
            Panic.panic(Error.BadLogFileException);
        }

        // 尝试将文件截断到当前位置，移除 "bad tail"
        try {
            truncate(position);
        } catch (Exception e) {
            // 如果发生异常，调用 Panic.panic 方法处理异常
            Panic.panic(e);
        }

        // 尝试将文件的读取位置设置为当前位置
        try {
            file.seek(position);
        } catch (IOException e) {
            // 如果发生 IO 异常，调用 Panic.panic 方法处理异常
            Panic.panic(e);
        }

        // 将当前位置重置为文件的开始位置
        rewind();
    }

    // 计算日志文件的校验和
    private int calChecksum(int xCheck, byte[] log) {
        for (byte b : log) {
            xCheck = xCheck * SEED + b;
        }
        return xCheck;
    }

    /**
     * 数据封装成日志格式
     * @param data
     */
    @Override
    public void log(byte[] data) {
        // 解析成一条完整的log日志
        byte[] log = wrapLog(data);
        ByteBuffer buf = ByteBuffer.wrap(log);
        lock.lock();
        try {
            //写入到指定位置
            fc.position(fc.size());
            fc.write(buf);
        } catch (IOException e) {
            Panic.panic(e);
        } finally {
            lock.unlock();
        }
        // 更新总校验值
        updateXChecksum(log);
    }

    /**
     * 更新总校验值
     */
    private void updateXChecksum(byte[] log) {
        //计算总校验值
        this.xChecksum = calChecksum(this.xChecksum, log);
        try {
            fc.position(0);
            fc.write(ByteBuffer.wrap(Parser.int2Byte(xChecksum)));
            fc.force(false);
        } catch (IOException e) {
            Panic.panic(e);
        }
    }

    /**
     * 将数据解析成完整log
     */
    private byte[] wrapLog(byte[] data) {
        // 使用 calChecksum 方法计算数据的校验和，然后将校验和转换为字节数组
        byte[] checksum = Parser.int2Byte(calChecksum(0, data));
        // 将数据的长度转换为字节数组
        byte[] size = Parser.int2Byte(data.length);
        // 使用 Bytes.concat 方法将 size、checksum 和 data 连接成一个新的字节数组，然后返回这个字节数组
        return Bytes.concat(size, checksum, data);
    }

    @Override
    public void truncate(long x) throws Exception {
        lock.lock();
        try {
            fc.truncate(x);
        } finally {
            lock.unlock();
        }
    }

    /**
     * 获取下一条日志
     */
    private byte[] internNext() {
        // 检查当前位置是否已经超过了文件的大小，如果超过了，说明没有更多的日志可以读取，返回 null
        if (position + OF_DATA >= fileSize) {
            return null;
        }

        // 创建一个大小为 4 的 ByteBuffer，用于读取日志的大小
        ByteBuffer tmp = ByteBuffer.allocate(4);
        try {
            // 将文件通道的位置设置为当前位置
            fc.position(position);
            // 从文件通道中读取 4 个字节的数据到 ByteBuffer 中，即Size日志文件的大小
            fc.read(tmp);
        } catch (IOException e) {
            // 如果发生 IO 异常，调用 Panic.panic 方法处理异常
            Panic.panic(e);
        }

        // 使用 Parser.parseInt 方法将读取到的 4 个字节的数据转换为 int 类型，得到日志的大小
        int size = Parser.parseInt(tmp.array());

        // 检查当前位置加上日志的大小是否超过了文件的大小，如果超过了，说明日志不完整，返回 null
        if (position + size + OF_DATA > fileSize) {
            return null;
        }

        // 创建一个大小为 OF_DATA + size 的 ByteBuffer，用于读取完整的日志
        ByteBuffer buf = ByteBuffer.allocate(OF_DATA + size);
        try {
            // 将文件通道的位置设置为当前位置
            fc.position(position);
            // 从文件通道中读取 OF_DATA + size 个字节的数据到 ByteBuffer 中
            // 读取整条日志 [Size][Checksum][Data]
            fc.read(buf);
        } catch (IOException e) {
            // 如果发生 IO 异常，调用 Panic.panic 方法处理异常
            Panic.panic(e);
        }

        // 将 ByteBuffer 中的数据转换为字节数组
        byte[] log = buf.array();

        // 计算日志数据的校验和
        int checkSum1 = calChecksum(0, Arrays.copyOfRange(log, OF_DATA, log.length));
        // 从日志中读取校验和
        int checkSum2 = Parser.parseInt(Arrays.copyOfRange(log, OF_CHECKSUM, OF_DATA));

        // 比较计算得到的校验和和日志中的校验和，如果不相等，说明日志已经被破坏，返回 null
        if (checkSum1 != checkSum2) {
            return null;
        }

        // 更新当前位置
        position += log.length;

        // 返回读取到的日志
        return log;
    }

    @Override
    public byte[] next() {
        lock.lock();
        try {
            byte[] log = internNext();
            if(log == null) return null;
            return Arrays.copyOfRange(log, OF_DATA, log.length);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void rewind() {
        position = 4;
    }

    @Override
    public void close() {
        try {
            fc.close();
            file.close();
        } catch(IOException e) {
            Panic.panic(e);
        }
    }
    
}
