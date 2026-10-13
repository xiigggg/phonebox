package top.xingzhenxi.phonebox.telecom

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import top.xingzhenxi.phonebox.net.CallItem
import top.xingzhenxi.phonebox.ui.theme.RiskLevel

class CallerLabelTest {
    @Test fun numberThenLabel() {
        val c = CallerLabel.parse("13800000000", "13800000000 快递")
        assertEquals("快递", c.label)
        assertEquals(RiskLevel.Business, c.risk)
    }

    @Test fun multiWordLabel() {
        assertEquals("银行 客服", CallerLabel.parse("13800000001", "13800000001 银行 客服").label)
    }

    @Test fun noLabel() {
        assertNull(CallerLabel.parse("600", null).label)
        assertNull(CallerLabel.parse("600", "600").label)
        assertEquals(RiskLevel.Unknown, CallerLabel.parse("600", "").risk)
    }

    @Test fun unrelatedDisplayIsLabel() {
        assertEquals("骚扰电话", CallerLabel.parse("13800000002", "骚扰电话").label)
        assertEquals(RiskLevel.Danger, CallerLabel.parse("13800000002", "骚扰电话").risk)
    }

    @Test fun historyFillsLocationAndTag() {
        val h = listOf(
            CallItem(ts = 1, number = "13800000003", tag = "旧", loc = "甲地"),
            CallItem(ts = 2, number = "13800000003", tag = "广告推销", ntype = "", loc = "乙地"),
        )
        val c = CallerLabel.parse("13800000003", "13800000003", h)
        assertEquals("广告推销", c.label)
        assertEquals("乙地", c.location)
        assertEquals(RiskLevel.Marketing, c.risk)
    }
}
