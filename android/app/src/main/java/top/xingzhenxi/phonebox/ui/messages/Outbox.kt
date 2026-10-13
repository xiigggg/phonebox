package top.xingzhenxi.phonebox.ui.messages

import androidx.compose.runtime.mutableStateListOf
import top.xingzhenxi.phonebox.net.PhoneboxClient
import top.xingzhenxi.phonebox.net.SendResult

enum class SendStatus { Sending, Sent, Failed }

data class OutgoingSms(val id: Long, val to: String, val text: String, val ts: Long, val status: SendStatus, val error: String? = null)

/**
 * App 内发出的短信。服务端 feed 只有收件，没有已发记录，所以已发气泡只在本进程内存里，
 * 重启后不再显示（服务端补上发件记录前的折中）。
 */
object Outbox {
    val items = mutableStateListOf<OutgoingSms>()
    private var seq = 0L

    suspend fun send(client: PhoneboxClient?, to: String, text: String) {
        val id = ++seq
        items.add(OutgoingSms(id, to.trim(), text.trim(), System.currentTimeMillis() / 1000, SendStatus.Sending))
        val r = client?.send(to, text) ?: SendResult.Failed("phonebox 未配置")
        val idx = items.indexOfFirst { it.id == id }
        if (idx < 0) return
        items[idx] = when (r) {
            SendResult.Ok -> items[idx].copy(status = SendStatus.Sent)
            SendResult.SessionExpired -> items[idx].copy(status = SendStatus.Failed, error = "登录已失效")
            is SendResult.Rejected -> items[idx].copy(status = SendStatus.Failed, error = if (r.reason == "invalid_number") "号码不支持" else "内容不合规")
            is SendResult.Failed -> items[idx].copy(status = SendStatus.Failed, error = "发送失败")
        }
    }
}
