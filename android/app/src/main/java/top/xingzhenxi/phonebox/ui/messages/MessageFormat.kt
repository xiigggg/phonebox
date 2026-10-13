package top.xingzhenxi.phonebox.ui.messages

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp
import top.xingzhenxi.phonebox.data.Records
import top.xingzhenxi.phonebox.net.SmsItem
import top.xingzhenxi.phonebox.ui.theme.RiskLevel
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

object MessageFormat {
    private val hm = DateTimeFormatter.ofPattern("HH:mm")
    private val md = DateTimeFormatter.ofPattern("M/d")
    private val ymd = DateTimeFormatter.ofPattern("yyyy/M/d")

    /** 今天显示时分，昨天显示“昨天”，今年显示月/日，更早带年份 */
    fun short(ts: Long, now: Long = System.currentTimeMillis() / 1000, zone: ZoneId = ZoneId.systemDefault()): String {
        val t = Instant.ofEpochSecond(ts).atZone(zone)
        val today = Instant.ofEpochSecond(now).atZone(zone).toLocalDate()
        val d: LocalDate = t.toLocalDate()
        return when {
            d == today -> hm.format(t)
            d == today.minusDays(1) -> "昨天 ${hm.format(t)}"
            d.year == today.year -> md.format(t)
            else -> ymd.format(t)
        }
    }

    fun full(ts: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        DateTimeFormatter.ofPattern("yyyy/M/d HH:mm").format(Instant.ofEpochSecond(ts).atZone(zone))

    /** 头像调色板下标：同一签名永远同一个色 */
    fun avatarIndex(key: String, size: Int): Int = (key.hashCode() and 0x7fffffff) % size

    /** 头像文字：签名取首字，号码取末两位 */
    fun avatarText(sig: String, number: String): String =
        sig.firstOrNull()?.toString() ?: number.filter(Char::isDigit).takeLast(2).ifEmpty { "#" }

    /** 风险标签：颜色 + 文字；没有可说的就不显示 */
    fun riskTag(s: SmsItem): Pair<String, RiskLevel>? {
        val level = Records.riskLevel(s)
        return when {
            Records.isSpam(s) -> "骚扰" to level
            s.kind.isNotEmpty() && s.kind != "个人" && s.kind != "通知" -> s.kind to level
            else -> null
        }
    }
}

/** 这几个界面专用的图标（Material Symbols 路径），与 AppIcons 同样做法 */
internal object MsgIcons {
    val Edit = icon("M3,17.25V21h3.75L17.81,9.94l-3.75,-3.75L3,17.25zM20.71,7.04c0.39,-0.39 0.39,-1.02 0,-1.41l-2.34,-2.34c-0.39,-0.39 -1.02,-0.39 -1.41,0l-1.83,1.83 3.75,3.75 1.83,-1.83z")
    val Send = icon("M2.01,21L23,12 2.01,3 2,10l15,2 -15,2z")
    val Copy = icon("M16,1H4c-1.1,0 -2,0.9 -2,2v14h2V3h12V1zM19,5H8c-1.1,0 -2,0.9 -2,2v14c0,1.1 0.9,2 2,2h11c1.1,0 2,-0.9 2,-2V7c0,-1.1 -0.9,-2 -2,-2zM19,21H8V7h11v14z")

    private fun icon(path: String) = ImageVector.Builder(
        defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f,
    ).addPath(pathData = addPathNodes(path), fill = SolidColor(Color.Black)).build()
}
