package top.xingzhenxi.phonebox.telecom

import top.xingzhenxi.phonebox.data.Records
import top.xingzhenxi.phonebox.net.CallItem
import top.xingzhenxi.phonebox.ui.theme.RiskLevel

/** 来电方展示信息：号码、标签（可空）、风险等级、归属地/类型（来自 feed 里同号码的最近记录） */
data class CallerLabel(
    val number: String,
    val label: String?,
    val risk: RiskLevel,
    val location: String = "",
    val numberType: String = "",
) {
    companion object {
        /**
         * 生产 Asterisk 把 CALLERID(name) 设为“号码 标签”，例如 "13800000000 快递"。
         * 显示名去掉开头的号码后剩下的就是标签；显示名与号码无关时整体当作标签。
         */
        fun parse(number: String, display: String?, history: List<CallItem> = emptyList()): CallerLabel {
            val d = display?.trim().orEmpty()
            val label = when {
                d.isEmpty() || d == number -> null
                number.isNotEmpty() && d.startsWith(number) -> d.removePrefix(number).trim().ifEmpty { null }
                else -> d
            }
            val last = history.filter { it.number == number }.maxByOrNull { it.ts }
            val tag = label ?: last?.tag?.ifBlank { null }
            return CallerLabel(
                number = number,
                label = tag,
                risk = if (tag == null) RiskLevel.Unknown else Records.riskLevel(tag, last?.ntype.orEmpty(), last?.block == true),
                location = last?.loc.orEmpty(),
                numberType = last?.ntype.orEmpty(),
            )
        }
    }
}
