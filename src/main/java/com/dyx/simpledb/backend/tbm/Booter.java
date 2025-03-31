package com.dyx.simpledb.backend.tbm;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

import com.dyx.simpledb.backend.utils.Panic;
import com.dyx.simpledb.common.Error;

// 记录第一个表的uid
public class Booter {
    public static final String BOOTER_SUFFIX = ".bt";
    public static final String BOOTER_TMP_SUFFIX = ".bt_tmp";

    String path;
    File file;

    // 创建一个新的Booter对象
    public static Booter create(String path) {
        removeBadTmp(path); // 删除可能存在的临时文件
        File f = new File(path + BOOTER_SUFFIX);
        try {
            if (!f.createNewFile()) {
                Panic.panic(Error.FileExistsException); // 文件已存在，抛出异常
            }
        } catch (Exception e) {
            Panic.panic(e); // 创建文件过程中出现异常，处理异常
        }
        if (!f.canRead() || !f.canWrite()) {
            Panic.panic(Error.FileCannotRWException); // 文件不可读写，抛出异常
        }
        return new Booter(path, f); // 返回新创建的Booter对象
    }

    // 打开一个已存在的Booter对象
    public static Booter open(String path) {
        removeBadTmp(path); // 删除可能存在的临时文件
        File f = new File(path + BOOTER_SUFFIX);
        if (!f.exists()) {
            Panic.panic(Error.FileNotExistsException); // 文件不存在，抛出异常
        }
        if (!f.canRead() || !f.canWrite()) {
            Panic.panic(Error.FileCannotRWException); // 文件不可读写，抛出异常
        }
        return new Booter(path, f); // 返回打开的Booter对象
    }

    // 删除可能存在的临时文件
    private static void removeBadTmp(String path) {
        new File(path + BOOTER_TMP_SUFFIX).delete(); // 删除临时文件
    }

    private Booter(String path, File file) {
        this.path = path;
        this.file = file;
    }

    /**
     * 加载启动信息
     * @return
     */
    public byte[] load() {
        try {
            return Files.readAllBytes(file.toPath()); // 读取文件的所有字节
        } catch (IOException e) {
            Panic.panic(e); // 读取文件过程中出现异常，处理异常
        }
        return null;
    }

    /**
     * 更新启动信息
     * @param data
     */
    public void update(byte[] data) {
        File tmp = new File(path + BOOTER_TMP_SUFFIX);
        try {
            tmp.createNewFile(); // 创建新的临时文件
        } catch (Exception e) {
            Panic.panic(e); // 创建临时文件过程中出现异常，处理异常
        }
        if (!tmp.canRead() || !tmp.canWrite()) {
            Panic.panic(Error.FileCannotRWException); // 临时文件不可读写，抛出异常
        }
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(data); // 将数据写入临时文件
            out.flush(); // 刷新输出流，确保数据写入文件
        } catch (IOException e) {
            Panic.panic(e); // 写入文件过程中出现异常，处理异常
        }
        try {
            Files.move(tmp.toPath(), new File(path + BOOTER_SUFFIX).toPath(), StandardCopyOption.REPLACE_EXISTING); // 将临时文件移动并替换原文件
        } catch (IOException e) {
            Panic.panic(e); // 移动文件过程中出现异常，处理异常
        }
        file = new File(path + BOOTER_SUFFIX); // 更新file字段为新的启动信息文件
        if (!file.canRead() || !file.canWrite()) {
            Panic.panic(Error.FileCannotRWException); // 新的启动信息文件不可读写，抛出异常
        }
    }

}
