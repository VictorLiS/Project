package com.dyx.simpledb.backend.vm;

import java.util.Arrays;
import java.util.Set;

import com.google.common.primitives.Bytes;

import com.dyx.simpledb.backend.common.SubArray;
import com.dyx.simpledb.backend.dm.dataItem.DataItem;
import com.dyx.simpledb.backend.utils.Parser;

/**
 * VM向上层抽象出entry
 * entry结构：
 * [XMIN] [XMAX] [data]
 */
public class Entry {
    private static final int OF_XMIN = 0;     // XMIN 的偏移量 创建该记录的事务编号
    private static final int OF_XMAX = OF_XMIN + 8;  // XMAX 的偏移量 删除该记录的事务编号
    private static final int OF_DATA = OF_XMAX + 8;  // DATA 的偏移量

    private long uid;              // 唯一标识符
    private DataItem dataItem;     // 记录的数据项
    private VersionManager vm;     // 版本管理器

    public static Entry newEntry(VersionManager vm, DataItem dataItem, long uid) {
        if (dataItem == null) {
            return null;
        }
        Entry entry = new Entry();
        entry.uid = uid;
        entry.dataItem = dataItem;
        entry.vm = vm;
        return entry;
    }

    public static Entry loadEntry(VersionManager vm, long uid) throws Exception {
        DataItem di = ((VersionManagerImpl)vm).dm.read(uid);
        return newEntry(vm, di, uid);
    }

    public static byte[] wrapEntryRaw(long xid, byte[] data) {
        byte[] xmin = Parser.long2Byte(xid);  // 将事务 ID 转为 8 字节数组
        byte[] xmax = new byte[8];  // 预留 8 字节空间给 XMAX，初始为空
        return Bytes.concat(xmin, xmax, data);  // 合并为完整的 Entry 数据结构
    }

    public void release() {
        ((VersionManagerImpl)vm).releaseEntry(this);
    }

    public void remove() {
        dataItem.release();
    }

    // 以拷贝的形式返回内容
    public byte[] data() {
        dataItem.rLock();  // 加锁，确保数据访问的安全性
        try {
            SubArray sa = dataItem.data();  // 获取存储的数据
            byte[] data = new byte[sa.end - sa.start - OF_DATA];  // 去除前 16 字节（XMIN 和 XMAX）
            System.arraycopy(sa.raw, sa.start + OF_DATA, data, 0, data.length);  // 复制数据部分
            return data;
        } finally {
            dataItem.rUnLock();  // 释放锁
        }
    }

    public long getXmin() {
        dataItem.rLock();
        try {
            SubArray sa = dataItem.data();
            return Parser.parseLong(Arrays.copyOfRange(sa.raw, sa.start+OF_XMIN, sa.start+OF_XMAX));
        } finally {
            dataItem.rUnLock();
        }
    }

    public long getXmax() {
        dataItem.rLock();
        try {
            SubArray sa = dataItem.data();
            return Parser.parseLong(Arrays.copyOfRange(sa.raw, sa.start+OF_XMAX, sa.start+OF_DATA));
        } finally {
            dataItem.rUnLock();
        }
    }

    public void setXmax(long xid) {
        dataItem.before();  // 备份原始数据，以便支持回滚
        try {
            SubArray sa = dataItem.data();
            System.arraycopy(Parser.long2Byte(xid), 0, sa.raw, sa.start + OF_XMAX, 8);  // 设置 XMAX 为当前事务 ID
        } finally {
            dataItem.after(xid);  // 记录修改操作的日志
        }
    }

    public long getUid() {
        return uid;
    }

    // 判断是否 可清理
    public boolean isGarbage(Set<Long> activeXids) {
        long xmin = getXmin();
        long xmax = getXmax();

        // 条件1：未被删除（XMAX == 0），说明还有可能被读取，不能清理
        if (xmax == 0) return false;

        // 条件2：创建事务或删除事务仍活跃，说明可能还有事务能看到该版本
        if (activeXids.contains(xmin) || activeXids.contains(xmax)) {
            return false;
        }

        // 条件3：确保所有活跃事务的 XID 都 > XMAX，才说明没有人会再看到这个版本
        for (Long xid : activeXids) {
            if (xid <= xmax) return false;
        }

        return true; // 安全清理：已被删除、创建和删除事务都结束、无活跃事务可见
    }

}
