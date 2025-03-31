package com.dyx.simpledb.backend.dm;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import com.google.common.primitives.Bytes;

import com.dyx.simpledb.backend.common.SubArray;
import com.dyx.simpledb.backend.dm.dataItem.DataItem;
import com.dyx.simpledb.backend.dm.logger.Logger;
import com.dyx.simpledb.backend.dm.page.Page;
import com.dyx.simpledb.backend.dm.page.PageX;
import com.dyx.simpledb.backend.dm.pageCache.PageCache;
import com.dyx.simpledb.backend.tm.TransactionManager;
import com.dyx.simpledb.backend.utils.Panic;
import com.dyx.simpledb.backend.utils.Parser;

public class Recover {

    private static final byte LOG_TYPE_INSERT = 0;
    private static final byte LOG_TYPE_UPDATE = 1;

    private static final int REDO = 0;
    private static final int UNDO = 1;

    static class InsertLogInfo {
        long xid;
        int pgno;
        short offset;
        byte[] raw;
    }

    static class UpdateLogInfo {
        long xid;
        int pgno;
        short offset;
        byte[] oldRaw;
        byte[] newRaw;
    }

    public static void recover(TransactionManager tm, Logger lg, PageCache pc) {
        System.out.println("Recovering...");

        lg.rewind();
        int maxPgno = 0;
        while(true) {
            byte[] log = lg.next();
            if(log == null) break;
            int pgno;
            if(isInsertLog(log)) {
                InsertLogInfo li = parseInsertLog(log);
                pgno = li.pgno;
            } else {
                UpdateLogInfo li = parseUpdateLog(log);
                pgno = li.pgno;
            }
            if(pgno > maxPgno) {
                maxPgno = pgno;
            }
        }
        if(maxPgno == 0) {
            maxPgno = 1;
        }
        pc.truncateByBgno(maxPgno);
        System.out.println("Truncate to " + maxPgno + " pages.");

        redoTranscations(tm, lg, pc);
        System.out.println("Redo Transactions Over.");

        undoTranscations(tm, lg, pc);
        System.out.println("Undo Transactions Over.");

        System.out.println("Recovery Over.");
    }

    /**
     * 重做已完成的任务
     * @param tm
     * @param lg
     * @param pc
     */
    private static void redoTranscations(TransactionManager tm, Logger lg, PageCache pc) {
        // 重置日志文件的读取位置到开始
        lg.rewind();
        // 循环读取日志文件中的所有日志记录
        while (true) {
            // 读取下一条日志记录
            byte[] log = lg.next();
            // 如果读取到的日志记录为空，表示已经读取到日志文件的末尾，跳出循环
            if (log == null) break;
            // 判断日志记录的类型
            if (isInsertLog(log)) {
                // 如果是插入日志，解析日志记录，获取插入日志信息
                InsertLogInfo li = parseInsertLog(log);
                // 获取事务ID
                long xid = li.xid;
                // 如果当前事务已经提交，进行重做操作
                if (!tm.isActive(xid)) {
                    doInsertLog(pc, log, REDO);
                }
            } else {
                // 如果是更新日志，解析日志记录，获取更新日志信息
                UpdateLogInfo xi = parseUpdateLog(log);
                // 获取事务ID
                long xid = xi.xid;
                // 如果当前事务已经提交，进行重做操作
                if (!tm.isActive(xid)) {
                    doUpdateLog(pc, log, REDO);
                }
            }
        }
    }

    /**
     * 撤销未完成的任务
     * @param tm
     * @param lg
     * @param pc
     */
    private static void undoTranscations(TransactionManager tm, Logger lg, PageCache pc) {
        // 创建一个用于存储日志的映射，键为事务ID，值为日志列表
        Map<Long, List<byte[]>> logCache = new HashMap<>();
        // 将日志文件的读取位置重置到开始
        lg.rewind();
        // 循环读取日志文件中的所有日志记录
        while (true) {
            // 读取下一条日志记录
            byte[] log = lg.next();
            // 如果读取到的日志记录为空，表示已经读取到日志文件的末尾，跳出循环
            if (log == null) break;
            // 判断日志记录的类型
            if (isInsertLog(log)) {
                // 如果是插入日志，解析日志记录，获取插入日志信息
                InsertLogInfo li = parseInsertLog(log);
                // 获取事务ID
                long xid = li.xid;
                // 如果当前事务仍然活跃，将日志记录添加到对应的日志列表中
                if (tm.isActive(xid)) {
                    if (!logCache.containsKey(xid)) {
                        logCache.put(xid, new ArrayList<>());
                    }
                    logCache.get(xid).add(log);
                }
            } else {
                // 如果是更新日志，解析日志记录，获取更新日志信息
                UpdateLogInfo xi = parseUpdateLog(log);
                // 获取事务ID
                long xid = xi.xid;
                // 如果当前事务仍然活跃，将日志记录添加到对应的日志列表中
                if (tm.isActive(xid)) {
                    if (!logCache.containsKey(xid)) {
                        logCache.put(xid, new ArrayList<>());
                    }
                    // 将事务id对应的log添加到集合中
                    logCache.get(xid).add(log);
                }
            }
        }

        // 对所有活跃的事务的日志进行倒序撤销
        for (Entry<Long, List<byte[]>> entry : logCache.entrySet()) {
            List<byte[]> logs = entry.getValue();
            for (int i = logs.size() - 1; i >= 0; i--) {
                byte[] log = logs.get(i);
                // 判断日志记录的类型
                if (isInsertLog(log)) {
                    // 如果是插入日志，进行撤销插入操作
                    doInsertLog(pc, log, UNDO);
                } else {
                    // 如果是更新日志，进行撤销更新操作
                    doUpdateLog(pc, log, UNDO);
                }
            }
            // 中止当前事务
            tm.abort(entry.getKey());
        }
    }

    private static boolean isInsertLog(byte[] log) {
        return log[0] == LOG_TYPE_INSERT;
    }

    // [LogType] [XID] [UID] [OldRaw] [NewRaw]
    private static final int OF_TYPE = 0;
    private static final int OF_XID = OF_TYPE+1;
    private static final int OF_UPDATE_UID = OF_XID+8;
    private static final int OF_UPDATE_RAW = OF_UPDATE_UID+8;

    public static byte[] updateLog(long xid, DataItem di) {
        byte[] logType = {LOG_TYPE_UPDATE};
        byte[] xidRaw = Parser.long2Byte(xid);
        byte[] uidRaw = Parser.long2Byte(di.getUid());
        byte[] oldRaw = di.getOldRaw();
        SubArray raw = di.getRaw();
        byte[] newRaw = Arrays.copyOfRange(raw.raw, raw.start, raw.end);
        return Bytes.concat(logType, xidRaw, uidRaw, oldRaw, newRaw);
    }

    private static UpdateLogInfo parseUpdateLog(byte[] log) {
        UpdateLogInfo li = new UpdateLogInfo();
        li.xid = Parser.parseLong(Arrays.copyOfRange(log, OF_XID, OF_UPDATE_UID));
        long uid = Parser.parseLong(Arrays.copyOfRange(log, OF_UPDATE_UID, OF_UPDATE_RAW));
        li.offset = (short)(uid & ((1L << 16) - 1));
        uid >>>= 32;
        li.pgno = (int)(uid & ((1L << 32) - 1));
        int length = (log.length - OF_UPDATE_RAW) / 2;
        li.oldRaw = Arrays.copyOfRange(log, OF_UPDATE_RAW, OF_UPDATE_RAW+length);
        li.newRaw = Arrays.copyOfRange(log, OF_UPDATE_RAW+length, OF_UPDATE_RAW+length*2);
        return li;
    }

    private static void doUpdateLog(PageCache pc, byte[] log, int flag) {
        int pgno;
        short offset;
        byte[] raw;
        if(flag == REDO) {
            UpdateLogInfo xi = parseUpdateLog(log);
            pgno = xi.pgno;
            offset = xi.offset;
            raw = xi.newRaw;
        } else {
            UpdateLogInfo xi = parseUpdateLog(log);
            pgno = xi.pgno;
            offset = xi.offset;
            raw = xi.oldRaw;
        }
        Page pg = null;
        try {
            pg = pc.getPage(pgno);
        } catch (Exception e) {
            Panic.panic(e);
        }
        try {
            PageX.recoverUpdate(pg, raw, offset);
        } finally {
            pg.release();
        }
    }

    // [LogType] [XID] [Pgno] [Offset] [Raw]
    private static final int OF_INSERT_PGNO = OF_XID+8;
    private static final int OF_INSERT_OFFSET = OF_INSERT_PGNO+4;
    private static final int OF_INSERT_RAW = OF_INSERT_OFFSET+2;

    /**
     * 用于创建插入日志
     * @param xid
     * @param pg
     * @param raw
     * @return
     */
    public static byte[] insertLog(long xid, Page pg, byte[] raw) {
        // 创建一个表示日志类型的字节数组，并设置其值为LOG_TYPE_INSERT
        byte[] logTypeRaw = {LOG_TYPE_INSERT};
        // 将事务ID转换为字节数组
        byte[] xidRaw = Parser.long2Byte(xid);
        // 将页面编号转换为字节数组
        byte[] pgnoRaw = Parser.int2Byte(pg.getPageNumber());
        // 获取页面的第一个空闲空间的偏移量，并将其转换为字节数组
        byte[] offsetRaw = Parser.short2Byte(PageX.getFSO(pg));
        // 将所有字节数组连接在一起，形成一个完整的插入日志，并返回这个日志
        return Bytes.concat(logTypeRaw, xidRaw, pgnoRaw, offsetRaw, raw);
    }

    private static InsertLogInfo parseInsertLog(byte[] log) {
        InsertLogInfo li = new InsertLogInfo();
        li.xid = Parser.parseLong(Arrays.copyOfRange(log, OF_XID, OF_INSERT_PGNO));
        li.pgno = Parser.parseInt(Arrays.copyOfRange(log, OF_INSERT_PGNO, OF_INSERT_OFFSET));
        li.offset = Parser.parseShort(Arrays.copyOfRange(log, OF_INSERT_OFFSET, OF_INSERT_RAW));
        li.raw = Arrays.copyOfRange(log, OF_INSERT_RAW, log.length);
        return li;
    }

    private static void doInsertLog(PageCache pc, byte[] log, int flag) {
        InsertLogInfo li = parseInsertLog(log);
        Page pg = null;
        try {
            pg = pc.getPage(li.pgno);
        } catch(Exception e) {
            Panic.panic(e);
        }
        try {
            if(flag == UNDO) {
                DataItem.setDataItemRawInvalid(li.raw);
            }
            PageX.recoverInsert(pg, li.raw, li.offset);
        } finally {
            pg.release();
        }
    }
}
