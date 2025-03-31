package com.dyx.simpledb.backend.dm.pageIndex;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import com.dyx.simpledb.backend.dm.pageCache.PageCache;

public class PageIndex {
    // 将一页划成40个区间
    private static final int INTERVALS_NO = 40;
    private static final int THRESHOLD = PageCache.PAGE_SIZE / INTERVALS_NO;

    private Lock lock;
    // 存储与每个页面相关的信息
    private List<PageInfo>[] lists;

    @SuppressWarnings("unchecked")
    public PageIndex() {
        lock = new ReentrantLock();
        lists = new List[INTERVALS_NO+1];
        for (int i = 0; i < INTERVALS_NO+1; i ++) {
            lists[i] = new ArrayList<>();
        }
    }

    public void add(int pgno, int freeSpace) {
        lock.lock();
        try {
            int number = freeSpace / THRESHOLD;
            lists[number].add(new PageInfo(pgno, freeSpace));
        } finally {
            lock.unlock();
        }
    }

    /**
     * 该方法根据请求的空间大小spaceSize查找合适的页面来存储数据项
     * @param spaceSize 请求的空间大小
     * @return
     */
    public PageInfo select(int spaceSize) {
        lock.lock(); // 获取锁，确保线程安全
        try {
            int number = spaceSize / THRESHOLD; // 计算对应的区间编号
            if (number < INTERVALS_NO) number++; // 向上取整，以确保找到足够大的空间
            while (number <= INTERVALS_NO) { // 从当前区间向上查找
                if (lists[number].size() == 0) { // 当前区间没有可用页面，继续查找下一个区间
                    number++;
                    continue;
                }
                return lists[number].remove(0); // 返回并移除找到的页面信息
            }
            return null; // 没有找到合适的页面，返回 null
        } finally {
            lock.unlock(); // 释放锁
        }
    }

}
