package top.xingzhenxi.phonebox.shizuku;

// Shizuku UserService（shell 身份）：只认模板名，argv 在服务端按固定模板重建
import android.os.ParcelFileDescriptor;

interface IShellService {
    // Shizuku 约定的销毁事务码
    void destroy() = 16777114;

    // 返回 "退出码\t输出"；模板名未知或参数校验失败时退出码为 -2
    String exec(String template, int uid) = 1;

    // 安装已校验的更新包：App 打开文件后只把 fd 传过来，服务端不接受任何路径；size 用于 pm install -S
    String installApk(in ParcelFileDescriptor apk, long size) = 2;
}
