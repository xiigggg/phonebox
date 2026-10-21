package top.xingzhenxi.phonebox.push

import android.app.Activity
import android.content.Context

/**
 * 一种推送通道。拿到 token 后交给 [PushRegistrar] 上报 phonebox；收到消息一律交给 [PushWake]，
 * 消息内容不被信任，只当“该醒了”的信号。
 */
interface PushChannel {
    /** 与服务端 push.py 的 provider 名一致 */
    val id: String
    val label: String

    /** 本机能不能用：系统服务在不在、账号配没配；不弹任何界面 */
    fun available(context: Context): Boolean

    /** 发起注册；token 可能同步拿到，也可能稍后经回调到达 */
    fun register(activity: Activity)

    fun unregister(context: Context)
}

object PushChannels {
    /** 目前只有 UnifiedPush；要接别的通道，实现 PushChannel 加进来，服务端 push.py 加对应发送器 */
    fun all(): List<PushChannel> = listOf(UnifiedPushChannel)

    fun byId(id: String?): PushChannel? = all().firstOrNull { it.id == id }

    fun best(context: Context): PushChannel? = pick(all()) { it.available(context) }

    internal fun <T> pick(ordered: List<T>, available: (T) -> Boolean): T? = ordered.firstOrNull(available)
}
