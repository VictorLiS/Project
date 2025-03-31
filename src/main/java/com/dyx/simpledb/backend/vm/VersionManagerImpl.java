package com.dyx.simpledb.backend.vm;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import com.dyx.simpledb.backend.common.AbstractCache;
import com.dyx.simpledb.backend.dm.DataManager;
import com.dyx.simpledb.backend.tbm.Table;
import com.dyx.simpledb.backend.tm.TransactionManager;
import com.dyx.simpledb.backend.tm.TransactionManagerImpl;
import com.dyx.simpledb.backend.utils.Panic;
import com.dyx.simpledb.common.Error;

public class VersionManagerImpl extends AbstractCache<Entry> implements VersionManager {

    TransactionManager tm;
    DataManager dm;
    Map<Long, Transaction> activeTransaction;
    Lock lock;
    LockTable lt;
    private final Lock globalLock = new ReentrantLock();


    public VersionManagerImpl(TransactionManager tm, DataManager dm) {
        super(0);
        this.tm = tm;
        this.dm = dm;
        this.activeTransaction = new ConcurrentHashMap<>();
        activeTransaction.put(TransactionManagerImpl.SUPER_XID, Transaction.newTransaction(TransactionManagerImpl.SUPER_XID, IsolationLevel.READ_COMMITTED, null));
        this.lock = new ReentrantLock();
        this.lt = new LockTable();

        // 启动后台垃圾回收机制
        startGarbageCollector();
    }

    @Override
    public byte[] read(long xid, long uid) throws Exception {
        lock.lock();
        Transaction t = activeTransaction.get(xid);
        lock.unlock();

        if (t.err != null) {
            throw t.err;
        }

        Entry entry;
        try {
            entry = super.get(uid); // 获取数据项
        } catch (Exception e) {
            if (e == Error.NullEntryException) {
                return null;
            } else {
                throw e;
            }
        }

        try {
            if (Visibility.isVisible(tm, t, entry)) {
                return entry.data(); // 如果可见，返回数据
            } else {
                return null;
            }
        } finally {
            entry.release(); // 释放数据项
        }
    }

    @Override
    public long insert(long xid, byte[] data) throws Exception {
        lock.lock();
        Transaction t = activeTransaction.get(xid);
        lock.unlock();

        if (t.err != null) {
            throw t.err;
        }

        byte[] raw = Entry.wrapEntryRaw(xid, data); // 包装数据
        return dm.insert(xid, raw); // 插入数据并返回唯一标识符
    }

    @Override
    public Transaction getActiveTransaction(long xid) {
        return activeTransaction.get(xid);
    }

    @Override
    public boolean delete(long xid, long uid) throws Exception {
        // 获取锁，防止并发问题
        lock.lock();
        // 从活动事务中获取事务对象
        Transaction t = activeTransaction.get(xid);
        // 释放锁
        lock.unlock();

        // 如果事务已经出错，那么抛出错误
        if (t.err != null) {
            throw t.err;
        }
        Entry entry = null;
        try {
            // 尝试获取数据项
            entry = super.get(uid);
        } catch (Exception e) {
            // 如果数据项不存在，那么返回false
            if (e == Error.NullEntryException) {
                return false;
            } else {
                // 如果出现其他错误，那么抛出错误
                throw e;
            }
        }
        try {
            // 如果数据项对当前事务不可见，那么返回false
            if (!Visibility.isVisible(tm, t, entry)) {
                return false;
            }
            Lock l = null;
            try {
                // 尝试为数据项添加锁
                l = lt.add(xid, uid);
            } catch (Exception e) {
                // 如果出现并发更新的错误，那么中止事务，并抛出错误
                t.err = Error.ConcurrentUpdateException;
                internAbort(xid, true);
                t.autoAborted = true;
                throw t.err;
            }
            // 如果成功获取到锁，那么锁定并立即解锁
            if (l != null) {
                l.lock();
                l.unlock();
            }

            // 如果数据项已经被当前事务删除，那么返回false
            if (entry.getXmax() == xid) {
                return false;
            }

            // 如果数据项的版本被跳过，那么中止事务，并抛出错误
            if (Visibility.isVersionSkip(tm, t, entry)) {
                t.err = Error.ConcurrentUpdateException;
                internAbort(xid, true);
                t.autoAborted = true;
                throw t.err;
            }

            // 设置数据项的xmax为当前事务的ID，表示数据项被当前事务删除
            entry.setXmax(xid);
            // 返回true，表示删除操作成功
            return true;

        } finally {
            // 释放数据项
            entry.release();
        }
    }

    /**
     * 方法用于启动一个新事务，并初始化事务的相关结构
     * @param isolationLevel
     * @return
     */
    @Override
    public long begin(IsolationLevel isolationLevel) {
        globalLock.lock(); // 获取全局锁
        lock.lock();
        try {
            if (isolationLevel != IsolationLevel.SERIALIZABLE) {
                globalLock.unlock(); // 解除非全局锁
            }
            long xid = tm.begin();
            Transaction t = Transaction.newTransaction(
                    xid, isolationLevel == null ? IsolationLevel.READ_COMMITTED : isolationLevel, activeTransaction);
            // 创建的 Transaction 对象 t 会被加入到 activeTransaction 中
            activeTransaction.put(xid, t);

            return xid;
        } finally {
            lock.unlock();
        }
    }

    /**
     * 方法用于提交事务并释放事务持有的锁，若事务的隔离级别是串行化，还需释放全局锁。
     * @param xid
     * @throws Exception
     */
    @Override
    public void commit(long xid) throws Exception {
        lock.lock();
        Transaction t = activeTransaction.get(xid);
        lock.unlock();

        try {
            if (t.err != null) {
                throw t.err;
            }
        } catch (NullPointerException n) {
            System.out.println(xid);
            System.out.println(activeTransaction.keySet());
            Panic.panic(n);
        }

        lock.lock();
        activeTransaction.remove(xid);
        lock.unlock();

        // 锁表中移除锁
        lt.remove(xid);
        tm.commit(xid);

        // 通知所有关联的表进行索引提交
        for (Table table : t.getModifiedTables()) {
            table.commit(xid);
        }

        if (t.isolationLevel == IsolationLevel.SERIALIZABLE && globalLock.tryLock()) {
            globalLock.unlock();  // 释放全局锁
        }
    }

    @Override
    public void abort(long xid) {
        internAbort(xid, false);
    }

    private void internAbort(long xid, boolean autoAborted) {
        lock.lock();
        Transaction t = activeTransaction.get(xid);
        // 如果当前不是自动回滚（autoAborted == false），
        // 则从 activeTransaction 中移除该事务，表示该事务不再处于活动状态
        if (!autoAborted) {
            activeTransaction.remove(xid);
        }
        // 使用 lock.lock() 和 lock.unlock() 确保对 activeTransaction 的访问是线程安全的。
        lock.unlock();

        // 判断事务是否已经被自动回滚
        if (t.autoAborted){
            // 如果 t.autoAborted 为 true，说明事务已经被自动回滚过一次，
            // 不需要再次进行回滚操作。此时直接释放串行化隔离级别下的全局锁（如果有）并返回。
            if (t.isolationLevel == IsolationLevel.SERIALIZABLE) globalLock.unlock();  // 释放全局锁
            return;
        }
        // 删除锁表中的事务记录
        lt.remove(xid);
        // 回滚事务
        tm.abort(xid);

        // 通知所有关联的表进行索引提交
        for (Table table : t.getModifiedTables()) {
            table.rollback(xid);
        }

        if (t.isolationLevel == IsolationLevel.SERIALIZABLE) globalLock.unlock();  // 释放全局锁
    }

    public void releaseEntry(Entry entry) {
        super.release(entry.getUid());
    }

    @Override
    protected Entry getForCache(long uid) throws Exception {
        Entry entry = Entry.loadEntry(this, uid);
        if (entry == null) {
            throw Error.NullEntryException;
        }
        return entry;
    }

    @Override
    protected void releaseForCache(Entry entry) {
        entry.remove();
    }


    // 获取当前活跃的事务
    @Override
    public Set<Long> getActiveTransactionXids() {
        return new HashSet<>(activeTransaction.keySet());
    }

    public void runGarbageCollector() {
        Set<Long> activeXids = getActiveTransactionXids();

        // 获取所有的 Entry（从缓存或数据页）
        List<Long> allUids = dm.getAllEntryUids(); //

        for (Long uid : allUids) {
            try {
                Entry entry = super.get(uid);
                if (entry.isGarbage(activeXids)) {
                    physicalDelete(0, uid); // 超时 GC 可用 0 表示系统事务
                    System.out.println("[GC] Entry " + uid + " 已被回收");
                }
                entry.release();
            } catch (Exception e) {
                // 可记录日志
            }
        }
    }

    /**
     * 物理删除
     * @param xid 当前执行删除操作的事务 ID
     * @param uid 要删除的数据项的唯一标识符
     * @throws Exception
     */
    @Override
    public void physicalDelete(long xid, Long uid) throws Exception {
        lock.lock();
        Transaction t = activeTransaction.get(xid);
        lock.unlock();

        if (t.err != null) {
            throw t.err;
        }
        // 调用dm层的删除
        dm.physicalDelete(uid);

        super.release(uid);
    }

    // 启动后台垃圾回收线程
    private void startGarbageCollector() {
        Thread gcThread = new Thread(() -> {
            while (true) {
                try {
                    Thread.sleep(10000); // 每10秒执行一次
                    runGarbageCollector();
                } catch (InterruptedException e) {
                    break;
                }
            }
        });
        gcThread.setDaemon(true); // 设置为守护线程
        gcThread.start();
    }
}
