package top.xingzhenxi.phonebox.ui.dialer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class T9Test {
    // 测试里用固定表代替 ICU 转写
    private val fake = mapOf("张" to "zhāng", "三" to "sān", "李" to "lǐ", "四" to "sì", "王" to "wáng")
    private fun tr(s: String) = fake[s] ?: ""

    private fun entry(name: String, number: String): T9Entry {
        val (ini, py) = T9.pinyinOf(name, ::tr)
        return T9Entry(name + number, name, number, ini, py)
    }

    @Test fun digitsMapsLetters() {
        assertEquals("94264", T9.digits("zhang"))
        assertEquals("2223", T9.digits("ab2d"))
    }

    @Test fun pinyinInitialsPerCharacter() {
        assertEquals("zs" to "zhangsan", T9.pinyinOf("张三", ::tr))
        assertEquals("lsa" to "lisia", T9.pinyinOf("李四A", ::tr))
        assertEquals("" to "", T9.pinyinOf("123", ::tr))
    }

    @Test fun matchesInitialsPinyinAndNumber() {
        val zs = entry("张三", "13800000001")
        val ls = entry("李四", "13900000097")
        val list = listOf(zs, ls)
        // zs -> 97
        assertEquals(listOf(zs, ls), T9.match("97", list).map { it.entry })
        assertEquals(T9Hit.Initials, T9.match("97", list)[0].hit)
        assertEquals(T9Hit.Number, T9.match("97", list)[1].hit)
        // 全拼前缀 zhang -> 94264
        assertEquals(T9Hit.Pinyin, T9.match("94264", list).single().hit)
        // 号码子串
        assertEquals(zs, T9.match("0001", list).single().entry)
        assertTrue(T9.match("", list).isEmpty())
        assertTrue(T9.match("5555", list).isEmpty())
    }
}
