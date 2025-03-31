package com.dyx.simpledb.backend.dm.pageCache;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import com.dyx.simpledb.backend.common.AbstractCache;
import com.dyx.simpledb.backend.dm.page.Page;
import com.dyx.simpledb.backend.dm.page.PageImpl;
import com.dyx.simpledb.backend.utils.Panic;
import com.dyx.simpledb.common.Error;

public class PageCacheImpl extends AbstractCache<Page> implements PageCache {
    
    private static final int MEM_MIN_LIM = 10;
    public static final String DB_SUFFIX = ".db";

    private RandomAccessFile file;
    private FileChannel fc;
    private Lock fileLock;

    private AtomicInteger pageNumbers;

    PageCacheImpl(RandomAccessFile file, FileChannel fileChannel, int maxResource) {
        super(maxResource);
        if(maxResource < MEM_MIN_LIM) {
            Panic.panic(Error.MemTooSmallException);
        }
        long length = 0;
        try {
            length = file.length();
        } catch (IOException e) {
            Panic.panic(e);
        }
        this.file = file;
        this.fc = fileChannel;
        this.fileLock = new ReentrantLock();
        this.pageNumbers = new AtomicInteger((int)length / PAGE_SIZE);
    }

    // 新建页面
    public int newPage(byte[] initData) {
        int pgno = pageNumbers.incrementAndGet();
        Page pg = new PageImpl(pgno, initData, null);
        flush(pg);
        return pgno;
    }

    public Page getPage(int pgno) throws Exception {
        return get((long)pgno);
    }

    /**
     * 根据pageNumber从数据库文件中读取页数据，并包裹成Page
     */
    @Override
    protected Page getForCache(long key) throws Exception {
        // 将key转换为页码
        int pgno = (int) key;
        // 计算页码对应的偏移量
        long offset = PageCacheImpl.pageOffset(pgno);

        // 分配一个大小为PAGE_SIZE的ByteBuffer
        ByteBuffer buf = ByteBuffer.allocate(PAGE_SIZE);
        // 锁定文件，确保线程安全
        fileLock.lock();
        try {
            // 设置文件通道的位置为计算出的偏移量
            fc.position(offset);
            // 从文件通道读取数据到ByteBuffer
            fc.read(buf);
        } catch (IOException e) {
            // 如果发生异常，调用Panic.panic方法处理
            Panic.panic(e);
        }
        // 无论是否发生异常，都要解锁
        fileLock.unlock();
        // 使用读取到的数据、页码和当前对象创建一个新的PageImpl对象并返回
        return new PageImpl(pgno, buf.array(), this);
    }

    @Override
    protected void releaseForCache(Page pg) {
        if(pg.isDirty()) {
            flush(pg);
            pg.setDirty(false);
        }
    }

    public void release(Page page) {
        release((long)page.getPageNumber());
    }

    public void flushPage(Page pg) {
        flush(pg);
    }

    private void flush(Page pg) {
        int pgno = pg.getPageNumber(); // 获取页码
        long offset = pageOffset(pgno); // 计算偏移量

        fileLock.lock();
        try {
            ByteBuffer buf = ByteBuffer.wrap(pg.getData()); // 将Page的数据包装成ByteBuffer
            fc.position(offset); // 设置文件通道的位置
            fc.write(buf); // // 将数据写入到文件中
            fc.force(false); // 强制将数据从操作系统的缓存刷新到磁盘
        } catch(IOException e) {
            Panic.panic(e);
        } finally {
            fileLock.unlock();
        }
    }

    public void truncateByBgno(int maxPgno) {
        long size = pageOffset(maxPgno + 1);
        try {
            file.setLength(size);
        } catch (IOException e) {
            Panic.panic(e);
        }
        pageNumbers.set(maxPgno);
    }

    @Override
    public void close() {
        super.close();
        try {
            fc.close();
            file.close();
        } catch (IOException e) {
            Panic.panic(e);
        }
    }

    public int getPageNumber() {
        return pageNumbers.intValue();
    }

    private static long pageOffset(int pgno) {
        return (pgno-1) * PAGE_SIZE;
    }
    
}
