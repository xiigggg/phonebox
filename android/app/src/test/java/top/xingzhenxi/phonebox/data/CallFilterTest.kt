package top.xingzhenxi.phonebox.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import top.xingzhenxi.phonebox.net.CallItem

class CallFilterTest {
    private val answeredIn = CallItem(ts = 10, number = "13800000001", dir = "in", disposition = "ANSWERED", billsec = 30, ntype = "手机")
    private val missedIn = CallItem(ts = 20, number = "13800000003", dir = "in", disposition = "NO ANSWER", tag = "手机", ntype = "手机")
    private val outFailed = CallItem(ts = 30, number = "01000000001", dir = "out", disposition = "NO ANSWER")
    private val blockedMissed = CallItem(ts = 40, number = "4000000001", dir = "in", disposition = "NO ANSWER", tag = "广告推销", block = true)
    // 仅提示不拦截时服务端 block=false，但标签属于 BLOCK_TAGS
    private val tagOnlySpam = CallItem(ts = 50, number = "13800000099", dir = "in", disposition = "ANSWERED", tag = "骚扰电话")
    private val business = CallItem(ts = 60, number = "95555", dir = "in", disposition = "ANSWERED", tag = "招商银行", ntype = "服务号")
    private val all = listOf(answeredIn, missedIn, outFailed, blockedMissed, tagOnlySpam, business)
    private val contacts = setOf("13800000001")
    private val isContact: (String) -> Boolean = { it in contacts }

    private fun cats(c: CallItem) = CallCategory.entries.filter { CallFilter.matches(c, it, isContact(c.number)) }.toSet()

    @Test fun eachCategoryMaps() {
        assertEquals(setOf(CallCategory.All, CallCategory.Incoming), cats(answeredIn))
        assertEquals(setOf(CallCategory.All, CallCategory.Missed, CallCategory.Stranger), cats(missedIn))
        assertEquals(setOf(CallCategory.All, CallCategory.Blocked), cats(tagOnlySpam))
        assertEquals(setOf(CallCategory.All, CallCategory.Incoming), cats(business))
    }

    @Test fun blockedAndMissedCountsAsBlocked() {
        assertEquals(setOf(CallCategory.All, CallCategory.Blocked), cats(blockedMissed))
    }

    @Test fun unansweredOutgoingIsOutgoing() {
        val c = cats(outFailed)
        assertTrue(CallCategory.Outgoing in c)
        assertFalse(CallCategory.Missed in c)
    }

    @Test fun strangerRules() {
        assertFalse(CallFilter.isStranger(answeredIn, isContact = true))
        assertTrue(CallFilter.isStranger(answeredIn, isContact = false))
        assertTrue(CallFilter.isStranger(missedIn, isContact = false))
        assertTrue(CallFilter.isStranger(answeredIn.copy(tag = "13800000001 手机"), isContact = false))
        assertTrue(CallFilter.isStranger(outFailed.copy(tag = "北京固话", ntype = "北京固话"), isContact = false))
        assertFalse(CallFilter.isStranger(business, isContact = false))
        assertFalse(CallFilter.isStranger(blockedMissed, isContact = false))
    }

    @Test fun counts() {
        val n = CallFilter.counts(all, isContact)
        assertEquals(6, n[CallCategory.All])
        assertEquals(1, n[CallCategory.Missed])
        assertEquals(2, n[CallCategory.Incoming])
        assertEquals(1, n[CallCategory.Outgoing])
        assertEquals(2, n[CallCategory.Stranger])
        assertEquals(2, n[CallCategory.Blocked])
        assertEquals(4, CallFilter.counts(all, isContact, showBlockedInAll = false)[CallCategory.All])
    }

    @Test fun unreadMissed() {
        assertEquals(1, CallFilter.unreadMissed(all, 0))
        assertEquals(20L, CallFilter.latestMissedTs(all))
        assertEquals(0, CallFilter.unreadMissed(all, CallFilter.latestMissedTs(all)))
    }

    @Test fun normalize() {
        assertEquals("13800000001", CallFilter.normalize("+86 138-0000-0001"))
        assertEquals("13800000001", CallFilter.normalize("008613800000001"))
    }
}
