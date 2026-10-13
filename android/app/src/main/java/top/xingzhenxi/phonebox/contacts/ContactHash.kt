package top.xingzhenxi.phonebox.contacts

import java.security.MessageDigest

/** 通讯录号码的规范化与哈希，必须与 server.py 的 norm_number/contact_hash 逐字节一致 */
object ContactHash {
    const val MAX = 5000

    // Python 的 \s 对 str 匹配 str.isspace() 为真的全部字符；Android 的 Pattern 不支持 UNICODE_CHARACTER_CLASS，只好逐个列出
    private val strip = Regex("[\t\n\u000b\u000c\r\u001c-\u001f \u0085\u00a0\u1680\u2000-\u200a\u2028\u2029\u202f\u205f\u3000-]")

    fun normalize(n: String): String {
        val s = strip.replace(n, "")
        for (p in listOf("+86", "0086")) if (s.startsWith(p)) return s.removePrefix(p)
        return s
    }

    fun hash(n: String): String =
        MessageDigest.getInstance("SHA-256").digest(normalize(n).toByteArray()).joinToString("") { "%02x".format(it) }

    /** 去空、去重、截到服务端上限；顺序无关，排序后上传便于比对 */
    fun hashAll(numbers: Collection<String>): List<String> =
        numbers.map(::normalize).filter { it.isNotEmpty() }.toSortedSet().take(MAX)
            .map { MessageDigest.getInstance("SHA-256").digest(it.toByteArray()).joinToString("") { b -> "%02x".format(b) } }
}
