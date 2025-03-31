package com.dyx.simpledb.backend.dm.page;

import java.util.Arrays;

import com.dyx.simpledb.backend.dm.pageCache.PageCache;
import com.dyx.simpledb.backend.utils.RandomUtil;

/**
 * 特殊管理第一页
 * ValidCheck
 * db启动时给100~107字节处填入一个随机字节，db关闭时将其拷贝到108~115字节
 * 用于判断上一次数据库是否正常关闭
 */
public class PageOne {
    private static final int OF_VC = 100;
    private static final int LEN_VC = 8;

    public static byte[] InitRaw() {
        byte[] raw = new byte[PageCache.PAGE_SIZE];
        setVcOpen(raw);
        return raw;
    }

    // 设置启动时的校验字节
    public static void setVcOpen(Page pg) {
        pg.setDirty(true);
        // raw 代表页面的原始数据
        setVcOpen(pg.getData());
    }

    private static void setVcOpen(byte[] raw) {
        // 生成并设置随机校验字节，并将这些字节复制到原始数据的指定偏移位置
        System.arraycopy(RandomUtil.randomBytes(LEN_VC), 0, raw, OF_VC, LEN_VC);
    }

    // 设置关闭时的校验字节
    public static void setVcClose(Page pg) {
        pg.setDirty(true);
        setVcClose(pg.getData());
    }

    private static void setVcClose(byte[] raw) {
        // 将启动时的校验字节复制到关闭时的存储位置
        System.arraycopy(raw, OF_VC, raw, OF_VC + LEN_VC, LEN_VC);
    }

    // 校验字节是否一致
    public static boolean checkVc(Page pg) {
        return checkVc(pg.getData());
    }

    private static boolean checkVc(byte[] raw) {
        // 比较启动和关闭时的校验字节
        return Arrays.equals(Arrays.copyOfRange(raw, OF_VC, OF_VC + LEN_VC),
                Arrays.copyOfRange(raw, OF_VC + LEN_VC, OF_VC + 2 * LEN_VC));
    }
}
