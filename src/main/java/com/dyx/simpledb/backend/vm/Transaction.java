package com.dyx.simpledb.backend.vm;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import com.dyx.simpledb.backend.tbm.Table;
import com.dyx.simpledb.backend.tm.TransactionManagerImpl;

// vm对一个事务的抽象
public class Transaction {
    public long xid; // 事务的ID
    public IsolationLevel isolationLevel; // 事务的隔离级别
    public Map<Long, Boolean> snapshot; // 事务的快照，用于存储活跃事务的ID
    public Exception err; // 事务执行过程中的错误
    public boolean autoAborted; // 标志事务是否自动中止
    public long startTime; // 添加开始时间属性
    // 新增字段：记录事务中修改的表
    private Set<Table> modifiedTables = new HashSet<>();

    // 添加修改表的方法
    public void addModifiedTable(Table table) {
        modifiedTables.add(table);
    }

    // 获取被修改的表
    public Set<Table> getModifiedTables() {
        return modifiedTables;
    }

    /**
     * 该方法用于创建一个新的事务。它接收一个事务ID（xid）、事务隔离级别（IsolationLevel isolationLevel）和当前活跃事务的映射（active）。
     * @param xid 事务ID
     * @param isolationLevel 事务隔离级别
     * @param active 当前活跃事务的映射
     * @return
     */
    public static Transaction newTransaction(long xid, IsolationLevel isolationLevel, Map<Long, Transaction> active) {
        Transaction t = new Transaction();
        t.xid = xid;
        t.isolationLevel = isolationLevel;
        t.startTime = System.currentTimeMillis();
        // 当隔离级别等于可重复读和串行化时需要创建快照
        if(isolationLevel != IsolationLevel.READ_COMMITTED && isolationLevel != IsolationLevel.READ_UNCOMMITTED) {
            t.snapshot = new HashMap<>();
            for(Long x : active.keySet()) {
                t.snapshot.put(x, true);
            }
        }
        return t;
    }

    /**
     * 该方法用于检查给定的事务ID是否存在于当前事务的快照中
     * @param xid
     * @return
     */
    public boolean isInSnapshot(long xid) {
        if(xid == TransactionManagerImpl.SUPER_XID) {
            return false;
        }
        return snapshot.containsKey(xid);
    }
}
