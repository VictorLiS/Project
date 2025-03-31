package com.dyx.simpledb.backend.tm;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import com.dyx.simpledb.backend.utils.Panic;
import com.dyx.simpledb.backend.utils.Parser;
import com.dyx.simpledb.common.Error;

public class TransactionManagerImpl implements TransactionManager {

    // XID文件头长度
    static final int LEN_XID_HEADER_LENGTH = 8;
    // 每个事务的占用长度
    private static final int XID_FIELD_SIZE = 1;

    // 用于记录所有正在运行中的事务及其超时时间
    private final ConcurrentMap<Long, TransactionInfo> activeXidMap = new ConcurrentHashMap<>();

    // 默认超时的时间是30s
    private long defaultTimeoutMillis = 30000;

    // 事务的三种状态
    private static final byte FIELD_TRAN_ACTIVE   = 0;
	private static final byte FIELD_TRAN_COMMITTED = 1;
	private static final byte FIELD_TRAN_ABORTED  = 2;

    // 超级事务，永远为commited状态
    public static final long SUPER_XID = 0;

    static final String XID_SUFFIX = ".xid";
    
    private RandomAccessFile file;
    private FileChannel fc;
    private long xidCounter; // 代表当前系统中已经分配的最大事务 ID。也就是说，xidCounter 记录了当前文件中已有的事务 ID 的最大值
    private Lock counterLock;

    TransactionManagerImpl(RandomAccessFile raf, FileChannel fc) {
        this.file = raf;
        this.fc = fc;
        counterLock = new ReentrantLock();
        checkXIDCounter();
    }

    /**
     * 检查XID文件是否合法
     * 读取XID_FILE_HEADER中的xidcounter，根据它计算文件的理论长度，对比实际长度
     */
    private void checkXIDCounter() {
        long fileLen = 0;
        // 检查文件长度
        try {
            fileLen = file.length();
        } catch (IOException e1) {
            Panic.panic(Error.BadXIDFileException);
        }

        // 检查文件长度是否大于文件头的长度
        if(fileLen < LEN_XID_HEADER_LENGTH) {
            Panic.panic(Error.BadXIDFileException);
        }

        // 读取XID文件头
        ByteBuffer buf = ByteBuffer.allocate(LEN_XID_HEADER_LENGTH);
        try {
            fc.position(0);
            fc.read(buf);
        } catch (IOException e) {
            Panic.panic(e);
        }
        // 解析文件头中的数据
        this.xidCounter = Parser.parseLong(buf.array());
        // 返回XID对应的文件的位置
        long end = getXidPosition(this.xidCounter + 1);

        // 校验文件的实际长度
        if(end != fileLen) {
            Panic.panic(Error.BadXIDFileException);
        }
    }

    // 根据事务xid取得其在xid文件中对应的位置
    private long getXidPosition(long xid) {
        return LEN_XID_HEADER_LENGTH + (xid-1)*XID_FIELD_SIZE;
    }

    // 更新xid事务的状态为status
    private void updateXID(long xid, byte status) {
        long offset = getXidPosition(xid);
        // 存储将要写入文件的数据
        byte[] tmp = new byte[XID_FIELD_SIZE];
        tmp[0] = status;
        // 将tmp包装到ByteBuffer当中
        ByteBuffer buf = ByteBuffer.wrap(tmp);

        // 设置文件通道（fc）的读取/写入位置为 offset
        try {
            fc.position(offset);
            fc.write(buf);
        } catch (IOException e) {
            Panic.panic(e);
        }
        try {
            // 强制将文件中的更改刷新到磁盘上
            fc.force(false);
        } catch (IOException e) {
            Panic.panic(e);
        }
    }

    // 将XID加一，并更新XID Header
    private void incrXIDCounter() {
        xidCounter ++;
        ByteBuffer buf = ByteBuffer.wrap(Parser.long2Byte(xidCounter));

        // 写入数据
        try {
            fc.position(0);
            fc.write(buf);
        } catch (IOException e) {
            Panic.panic(e);
        }
        try {
            fc.force(false);
        } catch (IOException e) {
            Panic.panic(e);
        }
    }

    // 开始一个事务，并返回XID
    public long begin() {
        // 获取锁，防止多个线程同时修改 xidCounter 或写入文件，确保线程安全
        counterLock.lock();
        try {
            // 计算当前事务的 ID。xidCounter 是最后一个事务的编号，新事务 ID 是 xidCounter + 1
            long xid = xidCounter + 1;

            // 把该事务的状态设置为 "ACTIVE"，并将状态写入 XID 文件中对应位置
            updateXID(xid, FIELD_TRAN_ACTIVE);

            // 将事务计数器 +1，并更新写入到 XID 文件头部，持久化记录新事务总数
            incrXIDCounter();

            // ---------- 以下为超时检测功能新增逻辑 ----------

            // 获取当前系统时间（单位：毫秒），用于记录事务的开始时间
            long now = System.currentTimeMillis();

            // 把该事务的超时信息保存到 activeXidMap 中：
            // key：事务ID；value：TransactionInfo（包含 xid、开始时间、超时时间）
            activeXidMap.put(xid, new TransactionInfo(xid, now, defaultTimeoutMillis));

            // 返回事务id
            return xid;
        } finally {
            counterLock.unlock();
        }
    }

    // 提交XID事务
    public void commit(long xid) {
        updateXID(xid, FIELD_TRAN_COMMITTED);
    }

    // 回滚XID事务
    public void abort(long xid) {
        updateXID(xid, FIELD_TRAN_ABORTED);
    }

    // 定义一个方法，接收一个事务ID（xid）和一个状态（status）作为参数
    private boolean checkXID(long xid, byte status) {
        // 计算事务ID在XID文件中的位置
        long offset = getXidPosition(xid);
        // 创建一个新的字节缓冲区（ByteBuffer），长度为XID_FIELD_SIZE
        ByteBuffer buf = ByteBuffer.wrap(new byte[XID_FIELD_SIZE]);
        try {
            // 将文件通道的位置设置为offset
            fc.position(offset);
            // 从文件通道读取数据到字节缓冲区
            fc.read(buf);
        } catch (IOException e) {
            // 如果出现异常，调用Panic.panic方法处理
            Panic.panic(e);
        }
        // 检查字节缓冲区的第一个字节是否等于给定的状态
        // 如果等于，返回true，否则返回false
        return buf.array()[0] == status;
    }

    public boolean isActive(long xid) {
        if(xid == SUPER_XID) return false;
        return checkXID(xid, FIELD_TRAN_ACTIVE);
    }

    public boolean isCommitted(long xid) {
        if(xid == SUPER_XID) return true;
        return checkXID(xid, FIELD_TRAN_COMMITTED);
    }

    public boolean isAborted(long xid) {
        if(xid == SUPER_XID) return false;
        return checkXID(xid, FIELD_TRAN_ABORTED);
    }

    public void close() {
        try {
            fc.close();
            file.close();
        } catch (IOException e) {
            Panic.panic(e);
        }
    }

    // 实现超时自动回滚
    @Override
    public void startAutoAbortScheduler(long checkIntervalMillis, long defaultTimeoutMillis) {
        // 创建一个新的线程，用于定期检测超时未提交的事务，并自动执行回滚
        Thread timeoutChecker = new Thread(() -> {
            // 持续监控事务状态
            while (true) {
                long now = System.currentTimeMillis();

                // 遍历当前所有活跃事务
                for (TransactionInfo info : activeXidMap.values()) {
                    // 判断事务是否已经超时
                    if (now - info.startTime >= info.timeout) {
                        // 超时，打印日志提示
                        System.out.println("[Timeout] Transaction " + info.xid + " exceeded timeout, auto-aborting.");

                        // 调用 abort 方法主动回滚该事务（并会从 activeXidMap 中移除）
                        abort(info.xid);
                    }
                }

                try {
                    // 每次检测之后休眠一段时间，避免线程频繁占用 CPU
                    Thread.sleep(checkIntervalMillis);
                } catch (InterruptedException e) {
                    // 如果线程被中断，则设置中断标志并跳出循环，结束线程
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        });
        // 设置该线程为守护线程：当所有用户线程结束时，该线程也会自动终止
        timeoutChecker.setDaemon(true);
        // 启动超时检测线程
        timeoutChecker.start();
    }

}
