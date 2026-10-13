package top.xingzhenxi.phonebox.roles

import org.junit.Assert.assertEquals
import org.junit.Test
import top.xingzhenxi.phonebox.sip.DialPolicy
import top.xingzhenxi.phonebox.sip.EmergencyChecker

class RedirectPolicyTest {
    private val checker = EmergencyChecker(DialPolicy::isFallbackEmergency)

    @Test fun emergencyAlwaysUnmodified() {
        assertEquals(RedirectDecision.Emergency, RedirectPolicy.decide("112", true, true, checker))
        assertEquals(RedirectDecision.Emergency, RedirectPolicy.decide("110", false, false, checker))
    }

    @Test fun disabled() = assertEquals(RedirectDecision.Disabled, RedirectPolicy.decide("600", false, true, checker))

    @Test fun unregistered() = assertEquals(RedirectDecision.Unregistered, RedirectPolicy.decide("600", true, false, checker))

    @Test fun registered() = assertEquals(RedirectDecision.ViaSip, RedirectPolicy.decide("600", true, true, checker))

    @Test fun emptyGoesToSystem() = assertEquals(RedirectDecision.Emergency, RedirectPolicy.decide("", true, true, checker))
}
