package top.xingzhenxi.phonebox.diag

/** 诊断日志的最后一道闸：任何 5 位以上的数字串（号码、验证码）只留后 4 位 */
object Redact {
    private val digits = Regex("""\+?\d[\d\- ]{3,}\d""")

    fun text(s: String): String = digits.replace(s) { m ->
        val only = m.value.filter(Char::isDigit)
        if (only.length < 5) m.value else "***" + only.takeLast(4)
    }
}
