package top.xingzhenxi.phonebox.ui.messages

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import top.xingzhenxi.phonebox.appGraph
import top.xingzhenxi.phonebox.data.SmsCategories
import top.xingzhenxi.phonebox.data.SmsCategory
import top.xingzhenxi.phonebox.data.parse.DataUsageStatus
import top.xingzhenxi.phonebox.data.parse.ParsedCard
import top.xingzhenxi.phonebox.net.SmsItem
import top.xingzhenxi.phonebox.notify.NotificationActionReceiver
import top.xingzhenxi.phonebox.notify.SmsNotifyRules
import top.xingzhenxi.phonebox.ui.theme.RiskLevel
import top.xingzhenxi.phonebox.ui.theme.riskColors

// 卡片样式参照 PixelText ui/message/cards/*（Apache-2.0），颜色、圆角、字体改用本项目设计系统

@Composable
internal fun CategoryBar(selected: SmsCategory, unread: Map<SmsCategory, Int>, onSelect: (SmsCategory) -> Unit) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(horizontal = 4.dp)) {
        items(SmsCategory.entries, key = { it.name }) { c ->
            val n = unread[c] ?: 0
            FilterChip(
                selected = c == selected,
                onClick = { onSelect(c) },
                label = { Text(if (n > 0) "${c.label} $n" else c.label) },
            )
        }
    }
}

/** 头像底色/字色：取设计系统里已有的几组容器色，按签名哈希稳定挑一组 */
@Composable
private fun avatarColors(): List<Pair<Color, Color>> {
    val cs = MaterialTheme.colorScheme
    return listOf(
        cs.primaryContainer to cs.onPrimaryContainer,
        riskColors(RiskLevel.Business).let { it.container to it.content },
        riskColors(RiskLevel.Marketing).let { it.container to it.content },
        cs.secondaryContainer to cs.onSecondaryContainer,
        cs.tertiary to cs.onTertiary,
        riskColors(RiskLevel.Unknown).let { it.container to it.content },
    )
}

@Composable
internal fun Avatar(key: String, text: String) {
    val palette = avatarColors()
    val (bg, fg) = palette[MessageFormat.avatarIndex(key, palette.size)]
    Box(Modifier.size(44.dp).background(bg, CircleShape), contentAlignment = Alignment.Center) {
        Text(text, color = fg, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

@Composable
internal fun CategoryTag(c: SmsCategory?) {
    val level = when (c) {
        null, SmsCategory.All, SmsCategory.Notice -> return
        SmsCategory.Spam -> RiskLevel.Danger
        SmsCategory.Marketing -> RiskLevel.Marketing
        SmsCategory.Express, SmsCategory.Finance, SmsCategory.Travel, SmsCategory.Carrier -> RiskLevel.Business
        SmsCategory.Code -> RiskLevel.Unknown
    }
    top.xingzhenxi.phonebox.ui.theme.RiskTag(c.label, level)
}

/** 会话时间线里的一条收到的短信：能解析成卡片就显示卡片 + 原文，否则普通气泡 */
@Composable
internal fun IncomingSms(sms: SmsItem, parsed: ParsedCard, time: String) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.Start) {
        if (parsed is ParsedCard.None || parsed is ParsedCard.MissedCall || parsed is ParsedCard.PhoneRecharge) {
            BubbleText(sms.body)
        } else {
            Column(
                Modifier.widthIn(max = 360.dp).fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh, MaterialTheme.shapes.extraLarge)
                    .padding(18.dp),
            ) {
                when (parsed) {
                    is ParsedCard.VerificationCode -> CodeBody(sms, parsed)
                    is ParsedCard.ExpressDelivery -> ExpressBody(sms, parsed)
                    is ParsedCard.BankTransaction -> BankBody(parsed)
                    is ParsedCard.TrainTicket -> TrainBody(parsed)
                    is ParsedCard.Flight -> FlightBody(parsed)
                    is ParsedCard.DataUsage -> DataBody(parsed)
                    else -> Unit
                }
                HorizontalDivider(Modifier.padding(vertical = 12.dp), thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
                Text(sms.body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Text(time, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
    }
}

@Composable
private fun BubbleText(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.widthIn(max = 320.dp)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh, androidx.compose.foundation.shape.RoundedCornerShape(20.dp))
            .padding(horizontal = 14.dp, vertical = 10.dp),
    )
}

@Composable
private fun CardLabel(text: String) {
    Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
}

/** 复制：复用通知里的实现（剪贴板标记敏感），顺带标已读、撤通知 */
@Composable
private fun CopyButton(sms: SmsItem, text: String) {
    val ctx = LocalContext.current
    FilledTonalButton(onClick = {
        NotificationActionReceiver.copy(ctx, text)
        val key = SmsNotifyRules.key(sms)
        ctx.appGraph.readStore.markRead(listOf(key))
        ctx.appGraph.smsNotifier.cancelForKeys(listOf(key))
    }) {
        Icon(MsgIcons.Copy, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text("复制")
    }
}

@Composable
private fun BigValueRow(sms: SmsItem, value: String, copyable: Boolean, color: Color = MaterialTheme.colorScheme.onSurface) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
        Text(
            value, fontSize = 32.sp, fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.Monospace, letterSpacing = 2.sp,
            color = color, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
        )
        if (copyable) CopyButton(sms, value)
    }
}

@Composable
private fun Field(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(72.dp))
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun CodeBody(sms: SmsItem, c: ParsedCard.VerificationCode) {
    CardLabel("验证码" + (c.signature?.let { " · $it" } ?: ""))
    BigValueRow(sms, c.code, copyable = true)
}

@Composable
private fun ExpressBody(sms: SmsItem, c: ParsedCard.ExpressDelivery) {
    CardLabel("取件码 · ${c.company}")
    BigValueRow(sms, c.code, copyable = true)
    if (c.location != "--") Field("驿站", c.location)
    c.time?.let { Field("取件期限", it) }
}

@Composable
private fun BankBody(c: ParsedCard.BankTransaction) {
    // 收入用设计系统的橄榄绿、支出用强调色；颜色只是辅助，前面始终有"收入/支出"文字
    val color = when (c.income) {
        true -> MaterialTheme.colorScheme.tertiary
        false -> MaterialTheme.colorScheme.primary
        null -> MaterialTheme.colorScheme.onSurface
    }
    CardLabel(if (c.isSuccess) SmsCategories.direction(c) else "${c.type}失败")
    Text(
        (if (c.income == true) "+" else if (c.income == false) "-" else "") + "¥" + c.amount,
        fontSize = 34.sp, fontWeight = FontWeight.SemiBold, color = color, modifier = Modifier.padding(top = 4.dp),
    )
    c.account?.let { Field("尾号", it) }
    c.balance?.let { Field("余额", "¥$it") }
    c.errorMessage?.let { Field("原因", it) }
    c.details.forEach { (k, v) -> Field(k, v) }
}

// 票面大字只要时:分，短信里带的秒会把字号挤得很难看
internal fun hourMinute(t: String): String = Regex("""^(\d{1,2}:\d{2}):\d{2}$""").replace(t.trim(), "$1")

/** 票面：左右两端是站/城市和时间，中间是车次/航班号 */
@Composable
private fun TicketFace(kind: String, number: String, date: String, from: String, fromTime: String, to: String, toTime: String) {
    CardLabel(kind + if (date.isNotEmpty()) " · $date" else "")
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Column(Modifier.weight(1f)) {
            Text(hourMinute(fromTime), fontSize = 26.sp, fontWeight = FontWeight.SemiBold)
            Text(from, style = MaterialTheme.typography.bodyMedium)
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(number, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            Box(Modifier.width(64.dp).height(1.dp).background(MaterialTheme.colorScheme.outline))
        }
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.End) {
            Text(hourMinute(toTime), fontSize = 26.sp, fontWeight = FontWeight.SemiBold)
            Text(to, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun TrainBody(c: ParsedCard.TrainTicket) {
    TicketFace("火车票", c.trainNumber, c.date, c.departureStation, c.departureTime, c.arrivalStation, "--")
    c.details.forEach { (k, v) -> Field(k, v) }
}

@Composable
private fun FlightBody(c: ParsedCard.Flight) {
    TicketFace("航班", c.flightNumber, c.date, c.departureCity, c.departureTime, c.arrivalCity, c.arrivalTime)
    if (c.terminal != "--") Field("航站楼", c.terminal)
}

@Composable
private fun DataBody(c: ParsedCard.DataUsage) {
    CardLabel(c.dataLabel)
    Text(
        c.primaryData ?: "已用尽", fontSize = 32.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 4.dp),
        color = if (c.status == DataUsageStatus.NORMAL) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error,
    )
    c.cutoffTime?.let { Field("截至", it) }
    c.details.forEach { (k, v) -> Field(k, v) }
}

/** 设置页"拦截设置"区里的开关；存储在 ReadStore，信息页实时生效 */
@Composable
fun SpamInAllSwitch() {
    val store = LocalContext.current.appGraph.readStore
    val on by store.showSpamInAllFlow.collectAsStateWithLifecycle()
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.weight(1f)) {
            Text("在全部中显示骚扰拦截短信", style = MaterialTheme.typography.bodyLarge)
            Text("关闭时只在信息页的“骚扰拦截”里看到", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = on, onCheckedChange = { store.showSpamInAll = it })
    }
}
