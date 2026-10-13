package top.xingzhenxi.phonebox.ui.dialer

import android.content.Context
import android.icu.text.Transliterator
import android.provider.ContactsContract.CommonDataKinds.Phone

/** 读系统联系人并预先算好拼音；调用方负责检查 READ_CONTACTS 并放到后台线程 */
object ContactsSource {
    fun load(context: Context): List<T9Entry> {
        val tr = Transliterator.getInstance("Han-Latin; Latin-ASCII")
        val cache = HashMap<String, String>()
        val out = ArrayList<T9Entry>()
        context.contentResolver.query(
            Phone.CONTENT_URI, arrayOf(Phone._ID, Phone.DISPLAY_NAME, Phone.NUMBER), null, null, null,
        )?.use { c ->
            while (c.moveToNext()) {
                val name = c.getString(1).orEmpty()
                val number = c.getString(2)?.filter { it.isDigit() || it == '+' }.orEmpty()
                if (number.isEmpty()) continue
                val (ini, py) = T9.pinyinOf(name) { ch -> cache.getOrPut(ch) { tr.transliterate(ch) } }
                out += T9Entry("c${c.getLong(0)}", name, number, ini, py)
            }
        }
        return out.distinctBy { it.name to it.number }
    }
}
