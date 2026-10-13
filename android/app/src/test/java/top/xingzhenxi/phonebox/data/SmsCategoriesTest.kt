package top.xingzhenxi.phonebox.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import top.xingzhenxi.phonebox.data.parse.ParsedCard
import top.xingzhenxi.phonebox.net.CallItem
import top.xingzhenxi.phonebox.net.SmsItem
import top.xingzhenxi.phonebox.notify.SmsChannel
import top.xingzhenxi.phonebox.notify.SmsNotifyRules

class SmsCategoriesTest {
    private fun sms(kind: String, cat: String = "", from: String = "1069000000001", body: String = "正文", ts: Long = 100) =
        SmsItem(ts = ts, from = from, body = body, kind = kind, cat = cat)

    private val code = sms("验证码", "验证码")
    private val notice = sms("通知")
    private val carrier = sms("运营商", "运营商", from = "10086")
    private val finance = sms("金融", "金融", from = "95555")
    private val express = sms("快递", "快递")
    private val travel = sms("出行", "出行", from = "12306")
    private val marketing = sms("骚扰", "骚扰", body = "满300减50，回T退订")
    private val marketingClient = sms("通知", "营销", body = "双十一预售开启，回T退订")
    private val modelSpam = sms("骚扰", "骚扰（模型）", body = "高额贷款秒到账")
    private val personal = sms("个人", from = "13800000001")
    private val fromBlocked = sms("通知", from = "4000000001")
    private val blocked = SmsCategories.blockedNumbers(listOf(
        CallItem(number = "+864000000001", block = true),
        CallItem(number = "13800000002", block = false),
    ))
    private val all = listOf(code, notice, carrier, finance, express, travel, marketing, marketingClient, modelSpam, personal, fromBlocked)

    @Test fun mapping() {
        val c = { s: SmsItem -> SmsCategories.classify(s, blocked) }
        assertEquals(SmsCategory.Code, c(code))
        assertEquals(SmsCategory.Notice, c(notice))
        assertEquals(SmsCategory.Carrier, c(carrier))
        assertEquals(SmsCategory.Finance, c(finance))
        assertEquals(SmsCategory.Express, c(express))
        assertEquals(SmsCategory.Travel, c(travel))
        assertEquals(SmsCategory.Marketing, c(marketing))
        assertEquals(SmsCategory.Marketing, c(marketingClient))
        assertEquals(SmsCategory.Spam, c(modelSpam))
        assertEquals(SmsCategory.Spam, c(fromBlocked))
        assertNull(c(personal))
        // 已被归到具体类的不受退订字样影响
        assertEquals(SmsCategory.Finance, c(sms("金融", "金融", body = "理财到期提醒，拒收请回复R")))
        // 卡片规则没认出、但 NAS 关键词判为快递的，归快递
        assertEquals(SmsCategory.Express, c(sms("通知", "快递", body = "【某某快递】您的快递已到达小区驿站，取件码 8-3-2041")))
        // 个人短信里出现"拒收/退订"字样不算营销，否则会被静音
        assertNull(c(sms("个人", from = "13800000001", body = "快递被我拒收了，明天再寄一次")))
    }

    @Test fun allExcludesSpamByDefault() {
        val off = SmsCategories.filter(all, SmsCategory.All, blocked, showSpamInAll = false)
        assertFalse(modelSpam in off)
        assertFalse(fromBlocked in off)
        assertTrue(marketing in off && personal in off)
        assertEquals(all.size - 2, off.size)
        val on = SmsCategories.filter(all, SmsCategory.All, blocked, showSpamInAll = true)
        assertEquals(all.size, on.size)
        assertEquals(listOf(modelSpam, fromBlocked), SmsCategories.filter(all, SmsCategory.Spam, blocked, false))
        assertEquals(listOf(code), SmsCategories.filter(all, SmsCategory.Code, blocked, false))
    }

    @Test fun unreadCounts() {
        val read = setOf(notice, marketing)
        val counts = SmsCategories.unreadCounts(all, blocked, showSpamInAll = false) { it !in read }
        assertEquals(all.size - 2 - 2, counts[SmsCategory.All])
        assertEquals(1, counts[SmsCategory.Code])
        assertEquals(0, counts[SmsCategory.Notice])
        assertEquals(1, counts[SmsCategory.Marketing])
        assertEquals(2, counts[SmsCategory.Spam])
        val withSpam = SmsCategories.unreadCounts(all, blocked, showSpamInAll = true) { it !in read }
        assertEquals(all.size - 2, withSpam[SmsCategory.All])
    }

    @Test fun codeCardsOnlyInAllAndCode() {
        assertEquals(listOf(SmsCategory.All, SmsCategory.Code), SmsCategory.entries.filter(SmsCategories::showsCodeCards))
    }

    @Test fun summaryLine() {
        assertEquals("取件码 3-2-1108", SmsCategories.summaryLine(express, ParsedCard.ExpressDelivery("中通", "3-2-1108", "驿站")))
        assertEquals("支出 ¥128.00", SmsCategories.summaryLine(finance, ParsedCard.BankTransaction("消费", "128.00", false, "1234", null)))
        assertEquals("收入 ¥5.00", SmsCategories.summaryLine(finance, ParsedCard.BankTransaction("入账", "5.00", true, null, null)))
        assertEquals("验证码 4821", SmsCategories.summaryLine(code, ParsedCard.VerificationCode("4821")))
        // 解析失败退回服务端
        assertEquals("G1 北京南 08:00", SmsCategories.summaryLine(travel.copy(summary = "G1 北京南 08:00"), ParsedCard.None))
        assertEquals("取件码 8-3-2041", SmsCategories.summaryLine(express.copy(code = "8-3-2041", codeLabel = "取件码"), ParsedCard.None))
        assertEquals("正文", SmsCategories.summaryLine(personal, ParsedCard.None))
    }

    @Test fun marketingNotifiesSilently() {
        assertEquals(SmsChannel.Spam, SmsNotifyRules.channel(marketing))
        assertEquals(SmsChannel.Spam, SmsNotifyRules.channel(marketingClient))
        assertEquals(SmsChannel.Normal, SmsNotifyRules.channel(personal))
    }
}
