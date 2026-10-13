package top.xingzhenxi.phonebox.shizuku

import android.net.ConnectivityManager
import org.junit.Assert.assertEquals
import org.junit.Test

class HealthMappingTest {
    @Test fun okMapping() {
        assertEquals(HealthStatus.Ok, HealthMapping.fromOk(true))
        assertEquals(HealthStatus.NeedsFix, HealthMapping.fromOk(false))
        assertEquals(HealthStatus.Unknown, HealthMapping.fromOk(null))
    }

    @Test fun restrictBackground() {
        assertEquals(HealthStatus.Ok, HealthMapping.fromRestrictBackground(ConnectivityManager.RESTRICT_BACKGROUND_STATUS_DISABLED))
        assertEquals(HealthStatus.Ok, HealthMapping.fromRestrictBackground(ConnectivityManager.RESTRICT_BACKGROUND_STATUS_WHITELISTED))
        assertEquals(HealthStatus.NeedsFix, HealthMapping.fromRestrictBackground(ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED))
        assertEquals(HealthStatus.Unknown, HealthMapping.fromRestrictBackground(99))
    }

    @Test fun roles() {
        assertEquals(HealthStatus.Unknown, HealthMapping.fromRole(available = false, held = false))
        assertEquals(HealthStatus.Ok, HealthMapping.fromRole(available = true, held = true))
        assertEquals(HealthStatus.NeedsFix, HealthMapping.fromRole(available = true, held = false))
    }

    @Test fun onlyNeedsFixWithTemplateIsFixed() {
        val r = listOf(
            HealthResult(HealthItem.BatteryOptimization, HealthStatus.NeedsFix),
            HealthResult(HealthItem.Overlay, HealthStatus.Ok),
            HealthResult(HealthItem.RoleSms, HealthStatus.Unknown),
            HealthResult(HealthItem.Notifications, HealthStatus.NeedsFix),
            HealthResult(HealthItem.RoleDialer, HealthStatus.NeedsFix),
        )
        assertEquals(listOf(ShellTemplate.DeviceIdleWhitelist, ShellTemplate.RoleDialer), HealthMapping.fixesFor(r))
    }
}
