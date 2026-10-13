package top.xingzhenxi.phonebox.contacts

import org.junit.Assert.assertEquals
import org.junit.Test

class ContactHashTest {
    // 期望值由 server/phonebox/server.py 的 norm_number + sha256 跑出来（号码均为虚构）
    private val cases = listOf(
        Triple("138 0000 0001", "13800000001", "b1c4769e3ad14f68ea1a96b73bbe5a83d90792f044828c5daf9d908b0738b177"),
        Triple("+86 138-0000-0002", "13800000002", "64ac4842ab2fa04b62673f8418af7648def08bb6f2d09b5452b544007f4bf1ed"),
        Triple("0086 13800000003", "13800000003", "338ecb183e97f2bd1b49116c92c4e4924102d46c1a1f87b5eff8a3841a51bc88"),
        Triple("+8613800000004", "13800000004", "59daa874ce11eff1abfcdc63e2042f80e8b84e1c713780ec1ee6a5dd52bcf85d"),
        Triple("010-12345678", "01012345678", "e60124f2fe2045215abda1ae912aa80bb66dab5fc231a758387682c9c0e70c01"),
        Triple("+1 555 0100", "+15550100", "602cd7fbbe41688e2d90224bcac362db2f1ff2e2ba7487d8585c9ce226cb6d00"),
        Triple("　+86 139 0000 0005\t", "13900000005", "7e08fc6cddd4837ba02b985e8d0bd888a1cee307af9826db4e1852ac3a3df3b6"),
        Triple("95588", "95588", "7256356638545327c25a861c65a64d6892fda13f92a6b87ae93906e1895f537d"),
        // 只去一次前缀
        Triple("+86+8613800000006", "+8613800000006", "18b66162370b5e89009ac87d3256b7b19574ad62c5f827e09d328549c09b5b10"),
    )

    @Test fun matchesServer() {
        for ((raw, norm, h) in cases) {
            assertEquals(raw, norm, ContactHash.normalize(raw))
            assertEquals(raw, h, ContactHash.hash(raw))
        }
    }

    @Test fun hashAllDedupesAndDropsEmpty() {
        val r = ContactHash.hashAll(listOf("138 0000 0001", "+8613800000001", " - ", "95588"))
        assertEquals(2, r.size)
        assertEquals(setOf(cases[0].third, cases[7].third), r.toSet())
    }

    @Test fun hashAllCapsAtServerLimit() {
        val many = (0 until ContactHash.MAX + 10).map { "1380000%04d".format(it) }
        assertEquals(ContactHash.MAX, ContactHash.hashAll(many).size)
    }
}
