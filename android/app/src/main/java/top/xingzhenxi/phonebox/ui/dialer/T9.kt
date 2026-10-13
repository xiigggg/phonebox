package top.xingzhenxi.phonebox.ui.dialer

/** 可被 T9 检索的条目；initials/pinyin 由调用方预先算好（拼音转换依赖 Android ICU，单测里注入） */
data class T9Entry(
    val key: String,
    val name: String,
    val number: String,
    /** 姓名每个字的首字母，如“张三” -> "zs" */
    val initials: String,
    /** 姓名全拼，如“张三” -> "zhangsan" */
    val pinyin: String,
)

enum class T9Hit { Initials, Pinyin, Number }

data class T9Match(val entry: T9Entry, val hit: T9Hit, val position: Int)

object T9 {
    private val keyOf: Map<Char, Char> = buildMap {
        listOf("2" to "abc", "3" to "def", "4" to "ghi", "5" to "jkl", "6" to "mno", "7" to "pqrs", "8" to "tuv", "9" to "wxyz")
            .forEach { (d, letters) -> letters.forEach { put(it, d[0]) } }
    }

    /** 字母转 T9 数字串；数字原样保留，其余字符丢弃 */
    fun digits(text: String): String = buildString {
        text.lowercase().forEach { c ->
            when {
                c in '0'..'9' -> append(c)
                else -> keyOf[c]?.let(::append)
            }
        }
    }

    /**
     * 把单字转写函数（Han-Latin）应用到每个字上，得到首字母串和全拼。
     * 逐字转写，避免多音字/词组合并后拿不到每个字的首字母。
     */
    fun pinyinOf(name: String, transliterate: (String) -> String): Pair<String, String> {
        val initials = StringBuilder()
        val full = StringBuilder()
        name.forEach { ch ->
            val latin = when {
                ch.isLetter() && ch.code < 128 -> ch.toString()
                Character.UnicodeScript.of(ch.code) == Character.UnicodeScript.HAN -> transliterate(ch.toString())
                else -> ""
            }.let { java.text.Normalizer.normalize(it, java.text.Normalizer.Form.NFD) } // 兜底去掉声调
                .lowercase().filter { it in 'a'..'z' }
            if (latin.isNotEmpty()) {
                initials.append(latin[0])
                full.append(latin)
            }
        }
        return initials.toString() to full.toString()
    }

    /**
     * 首字母 T9 前缀/子串 > 全拼前缀 > 号码子串；号码只认数字，便于“输入部分号码”找人。
     * 同一条目只取最好的命中。
     */
    fun match(query: String, entries: List<T9Entry>): List<T9Match> {
        val q = query.filter { it in '0'..'9' }
        if (q.isEmpty()) return emptyList()
        return entries.mapNotNull { e ->
            val ini = digits(e.initials).indexOf(q)
            val py = digits(e.pinyin).indexOf(q)
            val num = e.number.filter { it.isDigit() }.indexOf(q)
            when {
                ini >= 0 -> T9Match(e, T9Hit.Initials, ini)
                py == 0 -> T9Match(e, T9Hit.Pinyin, 0)
                num >= 0 -> T9Match(e, T9Hit.Number, num)
                else -> null
            }
        }.sortedWith(compareBy({ it.hit.ordinal }, { it.position }, { it.entry.name.ifEmpty { it.entry.number } }))
    }
}
