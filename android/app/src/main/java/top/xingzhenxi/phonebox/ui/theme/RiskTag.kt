package top.xingzhenxi.phonebox.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** 号码风险等级；颜色只是辅助，标签文字始终显示，色弱也能分辨 */
enum class RiskLevel { Danger, Marketing, Business, Unknown }

@Composable
fun riskColors(level: RiskLevel): RiskColors = when (level) {
    RiskLevel.Danger -> riskPalette.danger
    RiskLevel.Marketing -> riskPalette.marketing
    RiskLevel.Business -> riskPalette.business
    RiskLevel.Unknown -> riskPalette.unknown
}

@Composable
fun RiskTag(label: String, level: RiskLevel, modifier: Modifier = Modifier) {
    val c = riskColors(level)
    Text(
        text = label,
        style = MaterialTheme.typography.labelMedium,
        color = c.content,
        modifier = modifier
            .background(c.container, RoundedCornerShape(8.dp))
            .padding(horizontal = 8.dp, vertical = 2.dp),
    )
}
