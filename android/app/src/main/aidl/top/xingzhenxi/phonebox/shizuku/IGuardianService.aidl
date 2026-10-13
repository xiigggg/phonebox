package top.xingzhenxi.phonebox.shizuku;

// Guardian 守护进程：只暴露启停和状态，不接受任何命令
interface IGuardianService {
    void destroy() = 16777114;

    void start() = 1;
    void stop() = 2;
    // running(boolean)、lastLaunchAt(long)、lastReason(String)、lastCheckAt(long)
    Bundle status() = 3;
}
