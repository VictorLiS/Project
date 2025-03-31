package com.dyx.simpledb.backend.tm;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;

import com.dyx.simpledb.backend.utils.Panic;
import com.dyx.simpledb.common.Error;

public interface TransactionManager {
    long begin(); // 开始事务
    void commit(long xid); // 提交事务
    void abort(long xid); // 撤销或回滚事务
    boolean isActive(long xid); // 查询一个事务是否运行
    boolean isCommitted(long xid); // 查询一个事务是否已经提交
    boolean isAborted(long xid); // 查询一个事物是否撤销或者回滚
    void close(); // 关闭事务
    void startAutoAbortScheduler(long checkIntervalMillis, long defaultTimeoutMillis); // 超时自动回滚

    /**
     * startTime：事务开始时间（用于判断是否超时）
     * timeout：该事务允许的最长运行时间
     */
    class TransactionInfo {
        long xid;
        long startTime;
        long timeout;

        TransactionInfo(long xid, long startTime, long timeout) {
            this.xid = xid;
            this.startTime = startTime;
            this.timeout = timeout;
        }
    }

    static TransactionManagerImpl create(String path) {
        File f = new File(path+TransactionManagerImpl.XID_SUFFIX);
        try {
            if(!f.createNewFile()) {
                Panic.panic(Error.FileExistsException);
            }
        } catch (Exception e) {
            Panic.panic(e);
        }
        if(!f.canRead() || !f.canWrite()) {
            Panic.panic(Error.FileCannotRWException);
        }

        FileChannel fc = null;
        RandomAccessFile raf = null;
        try {
            raf = new RandomAccessFile(f, "rw");
            fc = raf.getChannel();
        } catch (FileNotFoundException e) {
           Panic.panic(e);
        }

        // 写空XID文件头
        ByteBuffer buf = ByteBuffer.wrap(new byte[TransactionManagerImpl.LEN_XID_HEADER_LENGTH]);
        try {
            fc.position(0);
            fc.write(buf);
        } catch (IOException e) {
            Panic.panic(e);
        }

        TransactionManagerImpl tm = new TransactionManagerImpl(raf, fc);
        // 启动超时回滚调度器（5秒检查一次，事务默认30秒超时）
        tm.startAutoAbortScheduler(5000, 30000);
        
        return new TransactionManagerImpl(raf, fc);
    }

    static TransactionManagerImpl open(String path) {
        File f = new File(path+TransactionManagerImpl.XID_SUFFIX);
        if(!f.exists()) {
            Panic.panic(Error.FileNotExistsException);
        }
        if(!f.canRead() || !f.canWrite()) {
            Panic.panic(Error.FileCannotRWException);
        }

        FileChannel fc = null;
        RandomAccessFile raf = null;
        try {
            raf = new RandomAccessFile(f, "rw");
            fc = raf.getChannel();
        } catch (FileNotFoundException e) {
           Panic.panic(e);
        }

        TransactionManagerImpl tm = new TransactionManagerImpl(raf, fc);
        // 启动超时回滚调度器（5秒检查一次，事务默认30秒超时）
        tm.startAutoAbortScheduler(5000, 30000);

        return new TransactionManagerImpl(raf, fc);
    }
}
