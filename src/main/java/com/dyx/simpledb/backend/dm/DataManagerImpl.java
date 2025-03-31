package com.dyx.simpledb.backend.dm;

import com.dyx.simpledb.backend.common.AbstractCache;
import com.dyx.simpledb.backend.dm.dataItem.DataItem;
import com.dyx.simpledb.backend.dm.dataItem.DataItemImpl;
import com.dyx.simpledb.backend.dm.logger.Logger;
import com.dyx.simpledb.backend.dm.page.Page;
import com.dyx.simpledb.backend.dm.page.PageOne;
import com.dyx.simpledb.backend.dm.page.PageX;
import com.dyx.simpledb.backend.dm.pageCache.PageCache;
import com.dyx.simpledb.backend.dm.pageIndex.PageIndex;
import com.dyx.simpledb.backend.dm.pageIndex.PageInfo;
import com.dyx.simpledb.backend.tm.TransactionManager;
import com.dyx.simpledb.backend.utils.Panic;
import com.dyx.simpledb.backend.utils.Parser;
import com.dyx.simpledb.backend.utils.Types;
import com.dyx.simpledb.common.Error;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class DataManagerImpl extends AbstractCache<DataItem> implements DataManager {

    TransactionManager tm;
    PageCache pc;
    Logger logger;
    PageIndex pIndex;
    Page pageOne;

    public DataManagerImpl(PageCache pc, Logger logger, TransactionManager tm) {
        super(0);
        this.pc = pc;
        this.logger = logger;
        this.tm = tm;
        this.pIndex = new PageIndex();
    }

    @Override
    public DataItem read(long uid) throws Exception {
        DataItemImpl di = (DataItemImpl)super.get(uid);
        if(!di.isValid()) {
            di.release();
            return null;
        }
        return di;
    }

    @Override
    public long insert(long xid, byte[] data) throws Exception {
        // 包装输入的数据 转成DataItem格式
        byte[] raw = DataItem.wrapDataItemRaw(data);
        //  检查数据项是否超出页面最大空闲空间
        if(raw.length > PageX.MAX_FREE_SPACE) {
            throw Error.DataTooLargeException;
        }

        PageInfo pi = null;
        // 尝试找到合适的页面插入数据
        for(int i = 0; i < 5; i ++) {
            pi = pIndex.select(raw.length);
            if (pi != null) {
                break;
            } else {
                // 如果未找到合适的页面，系统会创建一个新的页面，并将其添加到页面索引中
                int newPgno = pc.newPage(PageX.initRaw());
                pIndex.add(newPgno, PageX.MAX_FREE_SPACE);
            }
        }
        if(pi == null) {
            throw Error.DatabaseBusyException;
        }

        Page pg = null;
        int freeSpace = 0;
        // 插入数据到页面
        try {
            // 获取页面信息对象中的页面
            pg = pc.getPage(pi.pgno);
            // 生成插入日志 这个日志记录了事务ID、页面编号和数据项插入的偏移量等信息
            byte[] log = Recover.insertLog(xid, pg, raw);
            // 将日志写入日志文件
            logger.log(log);

            // 在页面中插入新的数据项，并获取其在页面中的偏移量
            short offset = PageX.insert(pg, raw);

            // 释放页面
            pg.release();

            // 返回新插入的数据项的唯一标识符
            return Types.addressToUid(pi.pgno, offset);

        } finally {
            // 无论操作成功与否，都将页面重新添加回页面索引（以便其他插入操作可以继续使用该页面）
            // 将取出的pg重新插入pIndex
            if(pg != null) {
                pIndex.add(pi.pgno, PageX.getFreeSpace(pg));
            } else {
                pIndex.add(pi.pgno, freeSpace);
            }
        }
    }

    @Override
    public void close() {
        super.close();
        logger.close();

        PageOne.setVcClose(pageOne);
        pageOne.release();
        pc.close();
    }

    /**
     * 定位到该 UID 对应的页和偏移
     * 将这段数据区域“清零”
     * 更新页的可用空间信息
     * @param uid
     * @throws Exception
     */
    @Override
    public void physicalDelete(Long uid) throws Exception {
        // 解析出页号和偏移量
        short offset = (short) (uid & ((1L << 16) - 1));
        // 右移32位，将高32位对齐到低位
        uid >>>= 32;
        // 提取页面编号，页面编号占 Uid 的高32位
        int pgno = (int) (uid & ((1L << 32) - 1)); // 按位与操作提取出页面编号

        // 获取目标页
        Page pg = pc.getPage(pgno);
        try {
            // 获取页中的数据
            byte[] data = pg.getData();

            // 计算数据项的大小
            short size = Parser.parseShort(Arrays.copyOfRange(data, offset + DataItemImpl.OF_SIZE, offset + DataItemImpl.OF_DATA));
            int dataItemLength = DataItemImpl.OF_DATA + size;

            // 清除数据项的内容（将数据项所在区域的字节清零）
            Arrays.fill(data, offset, offset + dataItemLength, (byte) 0);

            // 更新该页的可用空间信息
            pIndex.add(pgno, PageX.getFreeSpace(pg));

        } finally {
            // 释放页
            pg.release();
        }
    }

    // 为xid生成update日志
    public void logDataItem(long xid, DataItem di) {
        byte[] log = Recover.updateLog(xid, di);
        logger.log(log);
    }

    public void releaseDataItem(DataItem di) {
        super.release(di.getUid());
    }

    @Override
    protected DataItem getForCache(long uid) throws Exception {
        // 从 uid 中提取出偏移量（offset），这是通过位操作实现的，偏移量是 uid 的低16位
        short offset = (short) (uid & ((1L << 16) - 1));
        // 将 uid 右移32位，以便接下来提取出页面编号（pgno）
        uid >>>= 32;
        // 从 uid 中提取出页面编号（pgno），页面编号是 uid 的高32位
        int pgno = (int) (uid & ((1L << 32) - 1));
        // 使用页面缓存（pc）的 getPage(int pgno) 方法根据页面编号获取一个 Page 对象
        Page pg = pc.getPage(pgno);
        // 使用 DataItem 接口的静态方法 parseDataItem(Page pg, short offset, DataManagerImpl dm)
        // 根据获取到的 Page 对象、偏移量和当前的 DataManagerImpl 对象（this）解析出一个 DataItem 对象，并返回这个对象
        return DataItem.parseDataItem(pg, offset, this);
    }

    @Override
    protected void releaseForCache(DataItem di) {
        di.page().release();
    }

    // 在创建文件时初始化PageOne
    void initPageOne() {
        int pgno = pc.newPage(PageOne.InitRaw());
        assert pgno == 1;
        try {
            pageOne = pc.getPage(pgno);
        } catch (Exception e) {
            Panic.panic(e);
        }
        pc.flushPage(pageOne);
    }

    // 在打开已有文件时时读入PageOne，并验证正确性
    boolean loadCheckPageOne() {
        try {
            pageOne = pc.getPage(1);
        } catch (Exception e) {
            Panic.panic(e);
        }
        return PageOne.checkVc(pageOne);
    }

    // 初始化pageIndex
    void fillPageIndex() {
        int pageNumber = pc.getPageNumber();
        for(int i = 2; i <= pageNumber; i ++) {
            Page pg = null;
            try {
                pg = pc.getPage(i);
            } catch (Exception e) {
                Panic.panic(e);
            }
            pIndex.add(pg.getPageNumber(), PageX.getFreeSpace(pg));
            pg.release();
        }
    }

    // 获取所有的
    @Override
    public List<Long> getAllEntryUids() {
        List<Long> uids = new ArrayList<>();
        int pageCount = pc.getPageNumber();

        // 从页2开始，跳过PageOne
        for (int pageNo = 2; pageNo <= pageCount; pageNo++) {
            Page pg = null;
            try {
                pg = pc.getPage(pageNo);
                byte[] raw = pg.getData();
                int offset = 0;

                while (offset < raw.length) {
                    // 判断是否为有效数据项（DataItemImpl 的首字节为 0 表示无效）
                    if (raw[offset] == (byte) 0) {
                        offset++; // 无效项，跳过1字节
                        continue;
                    }

                    // 读取该数据项的长度字段（2字节）
                    if (offset + DataItemImpl.OF_DATA > raw.length) break; // 数据不足
                    short size = Parser.parseShort(Arrays.copyOfRange(
                            raw, offset + DataItemImpl.OF_SIZE, offset + DataItemImpl.OF_DATA));
                    int entryLen = DataItemImpl.OF_DATA + size;

                    // 检查越界
                    if (offset + entryLen > raw.length) break;

                    // 构造 UID 并加入列表
                    long uid = Types.addressToUid(pageNo, (short) offset);
                    uids.add(uid);

                    offset += entryLen;
                }

            } catch (Exception e) {
                e.printStackTrace(); // 或记录日志
            } finally {
                if (pg != null) {
                    pg.release();
                }
            }
        }

        return uids;
    }
}
