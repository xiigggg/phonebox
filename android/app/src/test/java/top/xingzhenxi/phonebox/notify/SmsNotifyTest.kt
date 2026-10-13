package top.xingzhenxi.phonebox.notify

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import top.xingzhenxi.phonebox.net.SmsItem

class SmsNotifyTest {
    private val code = SmsItem(ts = 1760000000, from = "10690000001", body = "【测试银行】您的验证码是 123456，5 分钟内有效",
        kind = "验证码", code = "123456", codeLabel = "验证码", sig = "测试银行")
    private val normal = SmsItem(ts = 1760000100, from = "13800000001", body = "晚上一起吃饭", kind = "个人")
    private val unreplyable = SmsItem(ts = 1760000200, from = "测试服务号", body = "通知")
    private val spam = SmsItem(ts = 1760000300, from = "10690000009", body = "中奖了", kind = "骚扰", cat = "骚扰")

    private val known = listOf(code, normal, unreplyable).associateBy { SmsNotifyRules.key(it) }
    private fun check(action: String?, sms: SmsItem?, key: String? = sms?.let(SmsNotifyRules::key), id: Int? = null) =
        ActionValidator.check(action, id ?: key?.let(SmsNotifyRules::notificationId) ?: -1, key) { known[it] }

    @Test fun codeSmsOnlyHasCopyCodeAndRead() {
        assertEquals(listOf(SmsAction.CopyCode("123456"), SmsAction.MarkRead), SmsNotifyRules.actions(code))
    }

    @Test fun normalSmsHasCopyReplyRead() {
        assertEquals(listOf(SmsAction.CopyAll, SmsAction.Reply, SmsAction.MarkRead), SmsNotifyRules.actions(normal))
        assertEquals(listOf(SmsAction.CopyAll, SmsAction.MarkRead), SmsNotifyRules.actions(unreplyable))
    }

    @Test fun channels() {
        assertEquals(SmsChannel.Code, SmsNotifyRules.channel(code))
        assertEquals(SmsChannel.Normal, SmsNotifyRules.channel(normal))
        assertEquals(SmsChannel.Spam, SmsNotifyRules.channel(spam))
        // 服务端只判成"通知"、正文带退订字样的营销，客户端补判后也要静默
        val optOut = SmsItem(ts = 1760000400, from = "10690000008", body = "【测试健身】年卡特惠，拒收请回复R", kind = "通知")
        assertEquals(SmsChannel.Spam, SmsNotifyRules.channel(optOut))
        assertNull(SmsNotifyRules.spec(spam, SpamNotifyMode.Off))
        // 来电被 NAS 拦截过的号码发来的短信：信息页归骚扰拦截，通知也静默
        val fromBlocked = SmsItem(ts = 1760000500, from = "4000000001", body = "您有一份专属福利待领取", kind = "通知")
        assertEquals(SmsChannel.Normal, SmsNotifyRules.channel(fromBlocked))
        assertEquals(SmsChannel.Spam, SmsNotifyRules.channel(fromBlocked, setOf("4000000001")))
        assertNull(SmsNotifyRules.spec(fromBlocked, SpamNotifyMode.Off, setOf("4000000001")))
        // 个人短信带"拒收"字样照常响
        assertEquals(SmsChannel.Normal, SmsNotifyRules.channel(SmsItem(ts = 1760000600, from = "13800000001", body = "快递被我拒收了", kind = "个人")))
    }

    @Test fun publicVersionHasNoCodeOrBody() {
        val s = SmsNotifyRules.spec(code, SpamNotifyMode.Silent)!!
        assertTrue(s.title.contains("123456"))
        assertFalse(s.publicTitle.contains("123456") || s.publicText.contains("123456"))
        assertFalse(s.publicText.contains("测试银行"))
    }

    @Test fun validatorAcceptsKnown() {
        val r = check(NotifyActions.COPY_CODE, code)
        assertTrue(r is ActionCheck.Ok)
        assertEquals("123456", (r as ActionCheck.Ok).req.sms.code)
    }

    @Test fun validatorRejects() {
        assertEquals(ActionCheck.Rejected("unknown_action"), check("evil.ACTION", code))
        assertEquals(ActionCheck.Rejected("unknown_action"), check(null, code))
        assertEquals(ActionCheck.Rejected("bad_key"), check(NotifyActions.COPY_ALL, null, key = null, id = 1))
        assertEquals(ActionCheck.Rejected("unknown_sms"), check(NotifyActions.COPY_ALL, spam))
        assertEquals(ActionCheck.Rejected("bad_notif_id"), check(NotifyActions.MARK_READ, code, id = 42))
        assertEquals(ActionCheck.Rejected("not_code"), check(NotifyActions.COPY_CODE, normal))
        assertEquals(ActionCheck.Rejected("not_replyable"), check(NotifyActions.REPLY, unreplyable))
    }

    @Test fun boundedSetEvictsOldest() {
        val s = BoundedKeySet(cap = 3)
        listOf("a", "b", "c", "d").forEach { s.add(it) }
        assertEquals(3, s.size)
        assertFalse("a" in s)
        assertTrue("d" in s)
        assertFalse(s.add("d"))
        val restored = BoundedKeySet((1..5000).map { "k$it" })
        assertEquals(BoundedKeySet.DEFAULT_CAP, restored.size)
        assertTrue("k5000" in restored)
    }
}
