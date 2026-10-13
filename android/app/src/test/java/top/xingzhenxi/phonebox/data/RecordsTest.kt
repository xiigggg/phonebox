package top.xingzhenxi.phonebox.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import top.xingzhenxi.phonebox.net.CallItem
import top.xingzhenxi.phonebox.net.FeedJson
import top.xingzhenxi.phonebox.ui.theme.RiskLevel

class RecordsTest {
    private val feed = FeedJson.parse(javaClass.getResource("/feed-fixture.json")!!.readText())

    @Test fun conversationsGroupBySignatureThenNumber() {
        val c = Records.conversations(feed.sms)
        assertEquals(listOf("【测试银行】", "13800000001", "【某快递】", "【某商城】"), c.map { it.title })
        assertEquals(2, c[0].messages.size)
        assertEquals(1760000300, c[0].latest.ts)
        assertEquals(2, c[1].messages.size)
        assertTrue(c[0].bySignature)
    }

    @Test fun codeCardsOnlyRecentVerificationCodes() {
        val now = 1760000300L + 60
        val cards = Records.codeCards(feed.sms, now)
        assertEquals(1, cards.size)
        assertEquals("123456", cards[0].code)
        assertEquals("测试银行", cards[0].source)
        assertTrue(Records.codeCards(feed.sms, 1760000300L + 601).isEmpty())
    }

    @Test fun callTypes() {
        assertEquals(
            listOf(CallType.Blocked, CallType.Incoming, CallType.Missed, CallType.Outgoing),
            feed.calls.map(Records::callType),
        )
    }

    @Test fun riskMapping() {
        assertEquals(RiskLevel.Danger, Records.riskLevel("诈骗电话"))
        assertEquals(RiskLevel.Danger, Records.riskLevel("响一声"))
        assertEquals(RiskLevel.Marketing, Records.riskLevel("房产中介"))
        assertEquals(RiskLevel.Marketing, Records.riskLevel("广告推销"))
        assertEquals(RiskLevel.Business, Records.riskLevel("快递外卖"))
        assertEquals(RiskLevel.Business, Records.riskLevel("", ntype = "企业热线"))
        assertEquals(RiskLevel.Unknown, Records.riskLevel("", ntype = "手机"))
        assertEquals(RiskLevel.Danger, Records.riskLevel("某未知标签", block = true))
        assertEquals(RiskLevel.Danger, Records.riskLevel(feed.sms[4]))
        assertEquals(RiskLevel.Danger, Records.riskLevel(CallItem(tag = "骚扰电话")))
    }

    @Test fun blockedListMergesCallsAndSpamSms() {
        val b = Records.blocked(feed.calls, feed.sms)
        assertEquals(2, b.size)
        assertTrue(b[0] is BlockedEntry.Call)
        assertTrue(b[1] is BlockedEntry.Sms)
    }
}
