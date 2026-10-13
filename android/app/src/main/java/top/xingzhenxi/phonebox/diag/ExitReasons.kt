package top.xingzhenxi.phonebox.diag

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context

data class ExitRecord(val ts: Long, val reason: String, val importance: Int)

/** 系统记录的本进程历次退出原因（Android 11+），用来回答“是不是被系统杀了” */
object ExitReasons {
    fun read(context: Context, max: Int = 20): List<ExitRecord> {
        val am = context.getSystemService(ActivityManager::class.java) ?: return emptyList()
        return runCatching { am.getHistoricalProcessExitReasons(context.packageName, 0, max) }
            .getOrDefault(emptyList())
            .map { ExitRecord(it.timestamp, label(it.reason), it.importance) }
    }

    fun label(reason: Int): String = when (reason) {
        ApplicationExitInfo.REASON_LOW_MEMORY -> "LOW_MEMORY 内存不足"
        ApplicationExitInfo.REASON_USER_REQUESTED -> "USER_REQUESTED 用户强行停止"
        ApplicationExitInfo.REASON_USER_STOPPED -> "USER_STOPPED 用户停止"
        ApplicationExitInfo.REASON_SIGNALED -> "SIGNALED 被信号杀死"
        ApplicationExitInfo.REASON_EXIT_SELF -> "EXIT_SELF 自行退出"
        ApplicationExitInfo.REASON_CRASH -> "CRASH 崩溃"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "CRASH_NATIVE 原生崩溃"
        ApplicationExitInfo.REASON_ANR -> "ANR 无响应"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "EXCESSIVE_RESOURCE_USAGE 资源占用过高"
        ApplicationExitInfo.REASON_FREEZER -> "FREEZER 冻结后被杀"
        ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "DEPENDENCY_DIED 依赖进程退出"
        ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "PERMISSION_CHANGE 权限变更"
        ApplicationExitInfo.REASON_PACKAGE_UPDATED -> "PACKAGE_UPDATED 应用更新"
        ApplicationExitInfo.REASON_PACKAGE_STATE_CHANGE -> "PACKAGE_STATE_CHANGE 应用状态变更"
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "INITIALIZATION_FAILURE 初始化失败"
        ApplicationExitInfo.REASON_OTHER -> "OTHER 其他"
        else -> "UNKNOWN($reason)"
    }
}

/**
 * 扩展点：有 READ_LOGS 权限（P6 经 Shizuku 授予）时，从 logcat 抓本包被杀/被冻结的系统日志行。
 * 本阶段不实现，诊断页对 null 实现显示“未授权”。
 */
fun interface KillLogSource {
    fun recentLines(max: Int): List<String>
}
