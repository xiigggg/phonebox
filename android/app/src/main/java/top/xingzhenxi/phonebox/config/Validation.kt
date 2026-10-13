package top.xingzhenxi.phonebox.config

import java.net.URI

/** 输入校验：返回 null 表示通过，否则返回给用户看的中文提示 */
object Validation {
    private val ipv4 = Regex("""^((25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)\.){3}(25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)$""")
    private val label = Regex("""^[A-Za-z0-9]([A-Za-z0-9-]{0,61}[A-Za-z0-9])?$""")
    private val hex64 = Regex("""^[0-9A-Fa-f]{64}$""")
    private val hexColon = Regex("""^[0-9A-Fa-f]{2}(:[0-9A-Fa-f]{2}){31}$""")

    fun host(input: String): String? {
        val s = input.trim()
        if (s.isEmpty()) return "请填写服务器地址"
        if (ipv4.matches(s) || isIpv6(s)) return null
        // 纯数字加点但不是合法 IPv4，多半是手误，不当作主机名放过
        if (s.all { it.isDigit() || it == '.' }) return "IP 地址格式不对"
        if (s.length > 253) return "地址太长"
        val labels = s.removeSuffix(".").split('.')
        return if (labels.all { label.matches(it) }) null else "地址只能包含字母、数字、点和连字符"
    }

    private fun isIpv6(s: String): Boolean {
        val v = s.removePrefix("[").removeSuffix("]")
        if (!v.contains(':') || v.any { !(it.isLetterOrDigit() || it == ':' || it == '.') }) return false
        return runCatching { java.net.InetAddress.getByName("[$v]") is java.net.Inet6Address }.getOrDefault(false)
    }

    fun port(input: String): String? {
        val n = input.trim().toIntOrNull() ?: return "端口要填 1–65535 的数字"
        return if (n in 1..65535) null else "端口要填 1–65535 的数字"
    }

    fun extension(input: String): String? {
        val s = input.trim()
        if (s.isEmpty()) return "请填写分机号"
        return if (s.length <= 32 && s.all { it in '0'..'9' }) null else "分机号只能是数字"
    }

    fun httpsUrl(input: String): String? {
        val s = input.trim()
        if (s.isEmpty()) return "请填写地址"
        val uri = runCatching { URI(s) }.getOrNull() ?: return "地址格式不对"
        if (!uri.scheme.equals("https", ignoreCase = true)) return "地址必须以 https:// 开头"
        if (uri.host.isNullOrEmpty()) return "地址里缺少主机名"
        if (uri.rawUserInfo != null) return "地址里不要带用户名或口令"
        return host(uri.host)
    }

    /** 空串视为“不固定证书”，合法 */
    fun fingerprint(input: String): String? {
        val s = input.trim()
        if (s.isEmpty()) return null
        return if (hex64.matches(s) || hexColon.matches(s)) null else "指纹应为 64 位十六进制（可用冒号分隔）"
    }

    /** 统一存成小写、无冒号的形式，后续比对不用再处理格式 */
    fun normalizeFingerprint(input: String): String = input.trim().replace(":", "").lowercase()

    fun normalizeUrl(input: String): String = input.trim().trimEnd('/')
}
