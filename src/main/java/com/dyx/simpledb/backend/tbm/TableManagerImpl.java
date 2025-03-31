package com.dyx.simpledb.backend.tbm;

import java.util.*;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import com.dyx.simpledb.backend.dm.DataManager;
import com.dyx.simpledb.backend.parser.statement.*;
import com.dyx.simpledb.backend.parser.statement.DeleteObj;
import com.dyx.simpledb.backend.tm.TransactionManagerImpl;
import com.dyx.simpledb.backend.utils.Parser;
import com.dyx.simpledb.backend.utils.PrintUtil;
import com.dyx.simpledb.backend.vm.IsolationLevel;
import com.dyx.simpledb.backend.vm.VersionManager;
import com.dyx.simpledb.common.Error;

public class TableManagerImpl implements TableManager {
    VersionManager vm; // 版本管理器，用于处理事务和数据的版本控制
    DataManager dm; // 数据管理器，负责数据的存储和读取操作
    private Booter booter; // 启动信息管理器，用于管理数据库的启动信息
    private Map<String, Table> tableCache; // 表缓存，存储已加载的表对象
    private Map<Long, List<Table>> xidTableCache; // 事务表缓存，记录每个事务修改过的表
    private Lock lock; // 锁，用于确保多线程环境下的操作安全
    private Set<String> prohibitTables;

    TableManagerImpl(VersionManager vm, DataManager dm, Booter booter) {
        this.vm = vm;
        this.dm = dm;
        this.booter = booter;
        this.tableCache = new HashMap<>();
        this.xidTableCache = new HashMap<>();
        lock = new ReentrantLock();
        this.prohibitTables = new HashSet<>();
        Collections.addAll(this.prohibitTables,
                "select", "insert", "update", "delete", "create",
                "drop", "alter", "from", "where", "group", "order", "join",
                "union", "null", "index", "table", "column", "database"
                );
        loadTables();
    }

    /**
     * 加载数据库中的所有表到缓存中。
     */
    private void loadTables() {
        // 获取第一个表的UID
        long uid = firstTableUid();
        // 循环加载所有表，直到没有下一个表
        while (uid != 0) {
            // 通过UID加载表对象
            Table tb = Table.loadTable(this, uid);
            // 获取下一个表的UID
            uid = tb.nextUid;
            // 将表对象加入缓存
            tableCache.put(tb.name, tb);
        }
    }

    /**
     * 获取第一个表的UID，来自Booter文件。
     * @return 第一个表的UID
     */
    private long firstTableUid() {
        byte[] raw = booter.load(); // 加载Booter文件内容
        return Parser.parseLong(raw); // 解析出第一个表的UID
    }

    private void updateFirstTableUid(long uid) {
        byte[] raw = Parser.long2Byte(uid);
        booter.update(raw);
    }

    @Override
    public BeginRes begin(Begin begin) {
        BeginRes res = new BeginRes();
        IsolationLevel isolationLevel = begin.isolationLevel;
        res.xid = vm.begin(isolationLevel);
        res.result = "begin".getBytes();
        return res;
    }

    @Override
    public byte[] commit(long xid) throws Exception {
        vm.commit(xid);
        return "commit".getBytes();
    }

    @Override
    public byte[] abort(long xid) {
        vm.abort(xid);
        return "abort".getBytes();
    }

    @Override
    public byte[] show(long xid, Show stat) {
        lock.lock();
        try {
            List<Map<String, Object>> entries = new ArrayList<>();
            String[] columns = null;
            Map<String, Object> columnData = null;

            if (stat.isTable) {
                columns = Arrays.asList("tables").toArray(new String[0]);
                for (String tableName : tableCache.keySet()) {
                    columnData = new HashMap<>();
                    columnData.put("tables", tableName);
                    entries.add(columnData);
                }
                return PrintUtil.printTable(columns, entries).getBytes();
            }
            if (tableCache.containsKey(stat.tableName)) {
                columns = Arrays.asList("field", "fieldType", "isIndexed", "constraint").toArray(new String[0]);
                Table table = tableCache.get(stat.tableName);
                for (Field field : table.fields) {
                    columnData = new HashMap<>();
                    columnData.put("field", field.fieldName);
                    columnData.put("fieldType", field.fieldType);
                    columnData.put("isIndexed", field.isIndexed() ? "Index" : "NoIndex");
                    columnData.put("constraint", field.printConstraint());
                    entries.add(columnData);
                }
                return PrintUtil.printTable(columns, entries).getBytes();
            } else {
                return "Table not found!".getBytes();
            }
        } finally {
            lock.unlock();
        }

    }

    @Override
    public byte[] create(long xid, Create create) throws Exception {
        lock.lock(); // 加锁以防止并发问题
        try {
            // 检查是否已有同名表存在，避免重复创建
            if (tableCache.containsKey(create.tableName)) {
                throw Error.DuplicatedTableException;
            }
            // 创建新的表对象
            Table table = Table.createTable(this, firstTableUid(), xid, create);
            // 更新表链表的头部UID
            updateFirstTableUid(table.uid);
            // 将新创建的表放入缓存
            tableCache.put(create.tableName, table);
            // 如果事务缓存中没有此事务ID的条目，则创建一个新的条目
            if (!xidTableCache.containsKey(xid)) {
                xidTableCache.put(xid, new ArrayList<>());
            }
            // 将新创建的表添加到当前事务的表列表中
            xidTableCache.get(xid).add(table);
            // 返回创建表的成功消息
            return ("create " + create.tableName).getBytes();
        } finally {
            lock.unlock(); // 解锁以允许其他操作
        }
    }

    @Override
    public byte[] insert(long xid, InsertObj insertObj) throws Exception {
        lock.lock();
        Table table = tableCache.get(insertObj.tableName);
        lock.unlock();
        if (table == null) {
            throw Error.TableNotFoundException;
        }
        table.insert(xid, insertObj);
        return "insert".getBytes();
    }

    @Override
    public byte[] read(long xid, SelectObj read) throws Exception {
        lock.lock();
        Table table = tableCache.get(read.tableName);
        lock.unlock();
        if (table == null) {
            throw Error.TableNotFoundException;
        }
        return table.read(xid, read).getBytes();
    }

    @Override
    public byte[] update(long xid, UpdateObj updateObj) throws Exception {
        lock.lock();
        Table table = tableCache.get(updateObj.tableName);
        lock.unlock();
        if (table == null) {
            throw Error.TableNotFoundException;
        }
        int count = table.update(xid, updateObj);
        return ("update " + count).getBytes();
    }

    @Override
    public byte[] delete(long xid, DeleteObj deleteObj) throws Exception {
        lock.lock();
        Table table = tableCache.get(deleteObj.tableName);
        lock.unlock();
        if (table == null) {
            throw Error.TableNotFoundException;
        }
        int count = table.delete(xid, deleteObj);
        return ("delete " + count).getBytes();
    }

    @Override
    public byte[] drop(long xid, DropObj stat) throws Exception {
        lock.lock();
        Table table = tableCache.get(stat.tableName);
        if (table == null) {
            lock.unlock();
            throw Error.TableNotFoundException;
        }

        try {
            // 执行表的删除操作
            table.drop(xid);
            // 从 `tableCache` 中移除表
            tableCache.remove(stat.tableName);
            // 更新表链中的 `nextUid`
            updateTableChainAfterDrop(table.uid);

            return ("drop " + stat.tableName).getBytes();
        } finally {
            lock.unlock();
        }
    }

    private void updateTableChainAfterDrop(long droppedTableUid) throws Exception {
        long firstUid = firstTableUid();

        if (firstUid == droppedTableUid) {
            // 如果删除的是第一个表，更新 Booter 中的 UID
            long nextUid = tableCache.values().stream()
                    .filter(t -> t.uid != droppedTableUid)
                    .map(t -> t.uid)
                    .findFirst()
                    .orElse(0L);
            updateFirstTableUid(nextUid);
        } else {
            // 如果删除的不是第一个表，更新前一个表的 nextUid
            Table previousTable = null;
            for (Table table : tableCache.values()) {
                if (table.nextUid == droppedTableUid) {
                    previousTable = table;
                    break;
                }
            }

            if (previousTable != null) {
                long nextUid = tableCache.values().stream()
                        .filter(t -> t.uid != droppedTableUid)
                        .filter(t -> t.uid > droppedTableUid)  // 再次使用filter方法过滤出UID大于droppedTableUid的表
                        .map(t -> t.uid)
                        .findFirst()
                        .orElse(0L);
                previousTable.nextUid = nextUid;
                previousTable.persistSelf(TransactionManagerImpl.SUPER_XID); // 保存更改
            }
        }
    }

}
