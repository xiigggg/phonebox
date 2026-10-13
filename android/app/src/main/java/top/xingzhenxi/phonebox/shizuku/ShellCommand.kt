package top.xingzhenxi.phonebox.shizuku

/**
 * 经 Shizuku 以 shell 身份执行的全部命令模板。
 * 参数只有本包名和本 uid，argv 逐项给出、不经 shell 解释，防止任何拼接注入。
 */
enum class ShellTemplate {
    DeviceIdleWhitelist,
    RestrictBackgroundWhitelist,
    RunAnyInBackground,
    FullScreenIntent,
    SystemAlertWindow,
    RoleDialer,
    RoleSms,
    RoleCallRedirection,
    GrantReadLogs,
    ;

    fun argv(pkg: String, uid: Int): List<String> = when (this) {
        DeviceIdleWhitelist -> listOf("cmd", "deviceidle", "whitelist", "+$pkg")
        RestrictBackgroundWhitelist -> listOf("cmd", "netpolicy", "add", "restrict-background-whitelist", uid.toString())
        RunAnyInBackground -> listOf("cmd", "appops", "set", pkg, "RUN_ANY_IN_BACKGROUND", "allow")
        FullScreenIntent -> listOf("cmd", "appops", "set", pkg, "USE_FULL_SCREEN_INTENT", "allow")
        SystemAlertWindow -> listOf("cmd", "appops", "set", pkg, "SYSTEM_ALERT_WINDOW", "allow")
        RoleDialer -> role("android.app.role.DIALER", pkg)
        RoleSms -> role("android.app.role.SMS", pkg)
        RoleCallRedirection -> role("android.app.role.CALL_REDIRECTION", pkg)
        GrantReadLogs -> listOf("pm", "grant", pkg, "android.permission.READ_LOGS")
    }

    private fun role(role: String, pkg: String) = listOf("cmd", "role", "add-role-holder", "--user", "0", role, pkg)
}

object ShellCommand {
    private val PKG = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+$")

    /**
     * 校验后产出 argv：包名必须正是本包（[selfPkg]），uid 必须是普通应用 uid。
     * 任何一项不符返回 null，调用方据此拒绝执行。
     */
    fun build(template: ShellTemplate, pkg: String, uid: Int, selfPkg: String): List<String>? {
        if (pkg != selfPkg || !PKG.matches(pkg)) return null
        if (!isAppUid(uid)) return null
        return template.argv(pkg, uid)
    }

    fun isAppUid(uid: Int): Boolean = uid >= 0 && uid % 100_000 in 10_000..19_999

    /** shell 侧校验：传入的 uid 必须正是 Binder 调用方（即本应用）的 uid，不能借它给别的应用放行 */
    fun isCaller(uid: Int, callingUid: Int): Boolean = isAppUid(uid) && uid == callingUid

    /** 更新包上限，与 update 模块的下载上限一致 */
    const val MAX_APK_BYTES = 300L * 1024 * 1024

    /**
     * 安装更新包：APK 从 stdin 读入（`-S` 给出字节数），所以 argv 里没有任何路径。
     * 内容由 App 侧 update 模块校验过 sha256 与签名后以 fd 传入。
     */
    fun installUpdate(size: Long): List<String>? =
        if (size in 1..MAX_APK_BYTES) listOf("pm", "install", "-r", "-S", size.toString()) else null
}
