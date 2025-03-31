package com.dyx.simpledb.backend.dm;

import com.dyx.simpledb.backend.dm.dataItem.DataItem;
import com.dyx.simpledb.backend.dm.logger.Logger;
import com.dyx.simpledb.backend.dm.page.PageOne;
import com.dyx.simpledb.backend.dm.pageCache.PageCache;
import com.dyx.simpledb.backend.tm.TransactionManager;

import java.util.List;

public interface DataManager {
    DataItem read(long uid) throws Exception;
    long insert(long xid, byte[] data) throws Exception;
    void physicalDelete(Long uid) throws Exception;
    void close();

    public static DataManager create(String path, long mem, TransactionManager tm) {
        // 创建一个 PageCache 对象 管理磁盘页面缓存
        PageCache pc = PageCache.create(path, mem);
        // 创建一个 Logger 对象
        Logger lg = Logger.create(path);

        // 创建一个 DataManagerImpl 对象，并传入 PageCache、Logger 和 TransactionManager
        DataManagerImpl dm = new DataManagerImpl(pc, lg, tm);
        // 初始化 DataManagerImpl 的第一页。通常，第一页可能是一个特殊的页面，用于存储一些全局的元数据或初始化信息
        dm.initPageOne();
        return dm;
    }

    public static DataManager open(String path, long mem, TransactionManager tm) {
        // 第一步：打开一个已存在的 PageCache 对象
        PageCache pc = PageCache.open(path, mem);

        // 第二步：打开已存在的 Logger 对象
        Logger lg = Logger.open(path);

        // 第三步：创建一个 DataManagerImpl 对象，并传入 PageCache、Logger 和 TransactionManager
        DataManagerImpl dm = new DataManagerImpl(pc, lg, tm);

        // 第四步：检查第一页是否加载成功
        if (!dm.loadCheckPageOne()) {
            // 如果加载第一页失败，进行恢复操作
            Recover.recover(tm, lg, pc);
        }

        // 第五步：填充页面索引
        dm.fillPageIndex();

        // 第六步：设置页面校验
        PageOne.setVcOpen(dm.pageOne);

        // 第七步：将第一页写入磁盘
        dm.pc.flushPage(dm.pageOne);

        // 返回初始化后的 DataManager 实例
        return dm;
    }

    List<Long> getAllEntryUids();
}
