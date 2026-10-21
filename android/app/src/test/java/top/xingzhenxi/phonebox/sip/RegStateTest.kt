package top.xingzhenxi.phonebox.sip

import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RegStateTest {
    @Test fun sameReasonFailuresAreDistinct() {
        assertNotEquals(RegState.Failed("TLS 连接失败：UnknownHostException"), RegState.Failed("TLS 连接失败：UnknownHostException"))
    }

    @Test fun stateFlowDoesNotSwallowRepeatedFailure() {
        val flow = MutableStateFlow<RegState>(RegState.Failed("x"))
        val first = flow.value
        flow.value = RegState.Failed("x")
        assertTrue(flow.value !== first && flow.value != first)
    }
}
