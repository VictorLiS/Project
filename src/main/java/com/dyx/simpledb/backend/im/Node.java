package com.dyx.simpledb.backend.im;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import com.dyx.simpledb.backend.common.SubArray;
import com.dyx.simpledb.backend.dm.dataItem.DataItem;
import com.dyx.simpledb.backend.tm.TransactionManagerImpl;
import com.dyx.simpledb.backend.utils.Parser;

/**
 * Node结构如下：
 * [LeafFlag][KeyNumber][SiblingUid]
 * [Son0][Key0][Son1][Key1]...[SonN][KeyN]
 */
public class Node {
    static final int IS_LEAF_OFFSET = 0;
    static final int NO_KEYS_OFFSET = IS_LEAF_OFFSET+1;
    static final int SIBLING_OFFSET = NO_KEYS_OFFSET+2;
    static final int NODE_HEADER_SIZE = SIBLING_OFFSET+8;

    static final int BALANCE_NUMBER = 32;
    static final int NODE_SIZE = NODE_HEADER_SIZE + (2*8)*(BALANCE_NUMBER*2+2);

    BPlusTree tree; // B+树
    DataItem dataItem; // 包装后的数据
    SubArray raw; // 原始数据
    long uid; // 唯一表示一个节点

    /**
     * 给定的字节数组中设置某个位置的值，标识当前节点是否为叶子节点
     * @param raw
     * @param isLeaf
     */
    static void setRawIsLeaf(SubArray raw, boolean isLeaf) {
        if(isLeaf) {
            raw.raw[raw.start + IS_LEAF_OFFSET] = (byte)1;
        } else {
            raw.raw[raw.start + IS_LEAF_OFFSET] = (byte)0;
        }
    }

    static boolean getRawIfLeaf(SubArray raw) {
        return raw.raw[raw.start + IS_LEAF_OFFSET] == (byte)1;
    }

    static void setRawNoKeys(SubArray raw, int noKeys) {
        System.arraycopy(Parser.short2Byte((short)noKeys), 0, raw.raw, raw.start+NO_KEYS_OFFSET, 2);
    }

    /**
     * 获取当前节点键的数量
     * @param raw
     * @return
     */
    static int getRawNoKeys(SubArray raw) {
        return (int)Parser.parseShort(Arrays.copyOfRange(raw.raw, raw.start+NO_KEYS_OFFSET, raw.start+NO_KEYS_OFFSET+2));
    }

    static void setRawSibling(SubArray raw, long sibling) {
        System.arraycopy(Parser.long2Byte(sibling), 0, raw.raw, raw.start+SIBLING_OFFSET, 8);
    }

    static long getRawSibling(SubArray raw) {
        return Parser.parseLong(Arrays.copyOfRange(raw.raw, raw.start+SIBLING_OFFSET, raw.start+SIBLING_OFFSET+8));
    }

    static void setRawKthSon(SubArray raw, long uid, int kth) {
        int offset = raw.start+NODE_HEADER_SIZE+kth*(8*2);
        System.arraycopy(Parser.long2Byte(uid), 0, raw.raw, offset, 8);
    }

    static long getRawKthSon(SubArray raw, int kth) {
        int offset = raw.start+NODE_HEADER_SIZE+kth*(8*2);
        return Parser.parseLong(Arrays.copyOfRange(raw.raw, offset, offset+8));
    }

    static void setRawKthKey(SubArray raw, long key, int kth) {
        int offset = raw.start+NODE_HEADER_SIZE+kth*(8*2)+8;
        System.arraycopy(Parser.long2Byte(key), 0, raw.raw, offset, 8);
    }

    static long getRawKthKey(SubArray raw, int kth) {
        int offset = raw.start+NODE_HEADER_SIZE+kth*(8*2)+8;
        return Parser.parseLong(Arrays.copyOfRange(raw.raw, offset, offset+8));
    }

    static void copyRawFromKth(SubArray from, SubArray to, int kth) {
        int offset = from.start+NODE_HEADER_SIZE+kth*(8*2);
        System.arraycopy(from.raw, offset, to.raw, to.start+NODE_HEADER_SIZE, from.end-offset);
    }

    static void shiftRawKth(SubArray raw, int kth) {
        int begin = raw.start+NODE_HEADER_SIZE+(kth+1)*(8*2);
        int end = raw.start+NODE_SIZE-1;
        for(int i = end; i >= begin; i --) {
            raw.raw[i] = raw.raw[i-(8*2)];
        }
    }

    /**
     * 创建一个新的根节点，并以原始字节数组的形式返回
     * @param left 左子节点的 ID
     * @param right 右子节点的 ID
     * @param key 用于分割左、右子节点的关键字
     * @return
     */
    static byte[] newRootRaw(long left, long right, long key)  {
        SubArray raw = new SubArray(new byte[NODE_SIZE], 0, NODE_SIZE);

        // 设置节点类型为非叶子节点
        setRawIsLeaf(raw, false);
        // 设置节点的键数量
        setRawNoKeys(raw, 2);
        // 设置节点的兄弟节点指针
        setRawSibling(raw, 0);
        // 设置左子节点
        setRawKthSon(raw, left, 0);
        // 设置第一个关键字
        setRawKthKey(raw, key, 0);
        // 设置右子节点
        setRawKthSon(raw, right, 1);
        // 设置第二个关键字
        setRawKthKey(raw, Long.MAX_VALUE, 1);

        return raw.raw;
    }

    /**
     * 创建一个新的叶子节点
     * @return
     */
    static byte[] newNilRootRaw()  {
        SubArray raw = new SubArray(new byte[NODE_SIZE], 0, NODE_SIZE);

        // 是叶子节点
        setRawIsLeaf(raw, true);
        // 节点的键为0
        setRawNoKeys(raw, 0);
        // 设置节点的兄弟节点指针
        setRawSibling(raw, 0);

        return raw.raw;
    }

    static Node loadNode(BPlusTree bTree, long uid) throws Exception {
        DataItem di = bTree.dm.read(uid);
        assert di != null;
        Node n = new Node();
        n.tree = bTree;
        n.dataItem = di;
        n.raw = di.data();
        n.uid = uid;
        return n;
    }

    public void release() {
        dataItem.release();
    }

    public boolean isLeaf() {
        dataItem.rLock();
        try {
            return getRawIfLeaf(raw);
        } finally {
            dataItem.rUnLock();
        }
    }

    class SearchNextRes {
        long uid;
        long siblingUid;
    }

    /**
     * 在当前节点中查找给定 key 对应的UID
     * @param key
     * @return
     */
    public SearchNextRes searchNext(long key) {
        dataItem.rLock();  // 获取读锁，确保并发安全
        try {
            SearchNextRes res = new SearchNextRes();  // 创建一个结果对象
            int noKeys = getRawNoKeys(raw);  // 获取当前节点的键的数量
            for(int i = 0; i < noKeys; i ++) {  // 遍历所有的键
                long ik = getRawKthKey(raw, i);  // 获取第 i 个键
                if(key < ik) {  // 如果传入的 key 小于当前键
                    res.uid = getRawKthSon(raw, i);  // 返回与第 i 个键对应的子节点 uid
                    res.siblingUid = 0;  // 没有兄弟节点
                    return res;  // 返回结果
                }
            }
            res.uid = 0;  // 如果 key 大于所有键，说明已经到达该节点的最右侧
            res.siblingUid = getRawSibling(raw);  // 设置当前节点的兄弟节点 UID
            return res;  // 返回结果
        } finally {
            dataItem.rUnLock();  // 释放读锁
        }
    }

    class LeafSearchRangeRes {
        List<Long> uids;
        long siblingUid;
    }

    /**
     * 获取符合范围条件的叶子节点
     * @param leftKey
     * @param rightKey
     * @return
     */
    public LeafSearchRangeRes leafSearchRange(long leftKey, long rightKey) {
        dataItem.rLock();
        try {
            // 获取当前节点中的键的数量
            int noKeys = getRawNoKeys(raw);
            // 左端点
            int kth = 0;
            while(kth < noKeys) {
                // 获取第i个键
                long ik = getRawKthKey(raw, kth);
                // 当 kth 对应的键 ik 大于等于 leftKey 时，停止循环，找到范围的左端点。
                if(ik >= leftKey) {
                    break;
                }
                kth ++;
            }
            List<Long> uids = new ArrayList<>();
            // 继续遍历当前节点的键，当键 ik 小于等于 rightKey 时，
            // 将对应的子节点 uid（即 getRawKthSon(raw, kth)）添加到 uids 列表中
            while(kth < noKeys) {
                long ik = getRawKthKey(raw, kth);
                if(ik <= rightKey) {
                    uids.add(getRawKthSon(raw, kth));
                    kth ++;
                } else {
                    // 一旦找到一个 ik 大于 rightKey，退出循环，表示已经查找完指定范围的所有数据项
                    break;
                }
            }
            long siblingUid = 0;
            // 如果已经遍历完所有的键（kth == noKeys），
            // 则说明当前节点是叶子节点的最后一个节点，需要获取其兄弟节点的 UID
            if(kth == noKeys) {
                siblingUid = getRawSibling(raw);
            }
            // 创建一个 LeafSearchRangeRes 对象，将查询结果 uids 和 siblingUid 设置到该对象中
            LeafSearchRangeRes res = new LeafSearchRangeRes();
            res.uids = uids;
            res.siblingUid = siblingUid;
            return res;
        } finally {
            dataItem.rUnLock();
        }
    }

    class InsertAndSplitRes {
        long siblingUid, newSon, newKey;
    }

    public InsertAndSplitRes insertAndSplit(long uid, long key) throws Exception {
        boolean success = false;
        Exception err = null;
        InsertAndSplitRes res = new InsertAndSplitRes();

        dataItem.before();
        try {
            success = insert(uid, key);
            if(!success) {
                res.siblingUid = getRawSibling(raw);
                return res;
            }
            if(needSplit()) {
                try {
                    SplitRes r = split();
                    res.newSon = r.newSon;
                    res.newKey = r.newKey;
                    return res;
                } catch(Exception e) {
                    err = e;
                    throw e;
                }
            } else {
                return res;
            }
        } finally {
            if(err == null && success) {
                dataItem.after(TransactionManagerImpl.SUPER_XID);
            } else {
                dataItem.unBefore();
            }
        }
    }

    public boolean insert(long uid, long key) {
        int noKeys = getRawNoKeys(raw);
        int kth = 0;
        while(kth < noKeys) {
            long ik = getRawKthKey(raw, kth);
            if(ik < key) {
                kth ++;
            } else {
                break;
            }
        }
        if(kth == noKeys && getRawSibling(raw) != 0) return false;

        if(getRawIfLeaf(raw)) {
            shiftRawKth(raw, kth);
            setRawKthKey(raw, key, kth);
            setRawKthSon(raw, uid, kth);
            setRawNoKeys(raw, noKeys+1);
        } else {
            long kk = getRawKthKey(raw, kth);
            setRawKthKey(raw, key, kth);
            shiftRawKth(raw, kth+1);
            setRawKthKey(raw, kk, kth+1);
            setRawKthSon(raw, uid, kth+1);
            setRawNoKeys(raw, noKeys+1);
        }
        return true;
    }

    private boolean needSplit() {
        return BALANCE_NUMBER*2 == getRawNoKeys(raw);
    }

    class SplitRes {
        long newSon, newKey;
    }

    private SplitRes split() throws Exception {
        SubArray nodeRaw = new SubArray(new byte[NODE_SIZE], 0, NODE_SIZE);
        setRawIsLeaf(nodeRaw, getRawIfLeaf(raw));
        setRawNoKeys(nodeRaw, BALANCE_NUMBER);
        setRawSibling(nodeRaw, getRawSibling(raw));
        copyRawFromKth(raw, nodeRaw, BALANCE_NUMBER);
        long son = tree.dm.insert(TransactionManagerImpl.SUPER_XID, nodeRaw.raw);
        setRawNoKeys(raw, BALANCE_NUMBER);
        setRawSibling(raw, son);

        SplitRes res = new SplitRes();
        res.newSon = son;
        res.newKey = getRawKthKey(nodeRaw, 0);
        return res;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("Is leaf: ").append(getRawIfLeaf(raw)).append("\n");
        int KeyNumber = getRawNoKeys(raw);
        sb.append("KeyNumber: ").append(KeyNumber).append("\n");
        sb.append("sibling: ").append(getRawSibling(raw)).append("\n");
        for(int i = 0; i < KeyNumber; i ++) {
            sb.append("son: ").append(getRawKthSon(raw, i)).append(", key: ").append(getRawKthKey(raw, i)).append("\n");
        }
        return sb.toString();
    }

}
