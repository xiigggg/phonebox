package top.xingzhenxi.phonebox.data.parse

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 样例全部虚构；规则用随包的 assets，单元测试工作目录是 app/ */
class MessageParserTest {
    private val parser = MessageParser(MessageParser.ASSETS.map { File("src/main/assets/$it").readText() })

    @Test fun bundledRuleVersions() {
        assertEquals(listOf(3, 1), parser.versions)
    }

    @Test fun verificationCode() {
        val r = parser.parse("1069000000003", "【某某出行】您的验证码为 4821，请在页面中输入完成验证。")
        assertEquals(ParsedCard.VerificationCode("4821", "某某出行"), r)
    }

    @Test fun expressGeneric() {
        val r = parser.parse("1069000000001", "【某某快递】您的中通包裹已到阳光小区驿站，请今日20:00前取件，取件码3-2-1108。") as ParsedCard.ExpressDelivery
        assertEquals("3-2-1108", r.code)
        assertEquals("中通", r.company)
        assertEquals("阳光小区驿站", r.location)
    }

    @Test fun bankCmbExpense() {
        val r = parser.parse("95555", "【招商银行】您账户1234于10月10日09:12消费人民币128.00元（某某超市）") as ParsedCard.BankTransaction
        assertEquals("128.00", r.amount)
        assertEquals(false, r.income)
        assertEquals("1234", r.account)
    }

    @Test fun bankGenericWithBalance() {
        val r = parser.parse("95500", "【某某银行】您尾号0001的账户10月10日09:12收入人民币2,000.00元，余额3,234.56元。") as ParsedCard.BankTransaction
        assertEquals("2,000.00", r.amount)
        assertEquals(true, r.income)
        assertEquals("0001", r.account)
        assertEquals("3,234.56", r.balance)
    }

    @Test fun train12306() {
        val r = parser.parse("12306", "【12306】张三购票成功，10月12日G1次，北京南08:00开，8车05F号。") as ParsedCard.TrainTicket
        assertEquals("G1", r.trainNumber)
        assertEquals("北京南", r.departureStation)
        assertEquals("08:00", r.departureTime)
        assertEquals("张三", r.details["乘车人"])
    }

    @Test fun trainWithSeatAndGate() {
        val r = parser.parse("1069000000009", "【汇联易甄选】购票成功，2026-10-12 08:00:00出发，北京南—上海虹桥，G1，检票口：A12，张三 二等座 08车厢05F号") as ParsedCard.TrainTicket
        assertEquals("上海虹桥", r.arrivalStation)
        assertEquals("A12", r.details["检票口"])
        assertEquals("08车厢05F号", r.details["座位"])
    }

    @Test fun flightEastern() {
        val r = parser.parse("95530", "【东方航空】您已购买2026年10月20日08:30 上海虹桥机场（T2）至北京首都机场（T3），MU5101航班，请提前到达。") as ParsedCard.Flight
        assertEquals("MU5101", r.flightNumber)
        assertEquals("上海虹桥", r.departureCity)
        assertEquals("北京首都", r.arrivalCity)
        assertEquals("T2", r.terminal)
    }

    @Test fun dataUsage() {
        val r = parser.parse("10086", "【剩余流量查询】尊敬的客户，本月套餐内流量共20GB，已使用18.5GB。目前国内通用流量剩余1.5GB。") as ParsedCard.DataUsage
        assertEquals("1.5GB", r.primaryData)
        assertEquals("20GB", r.details["套餐总量"])
    }

    @Test fun signatureFallsBackToServerSig() {
        val r = parser.parse("1069000000003", "您的验证码为 4821。", fallbackSignature = "某某出行")
        assertEquals(ParsedCard.VerificationCode("4821", "某某出行"), r)
    }

    @Test fun personalIsNone() {
        assertTrue(parser.parse("13800000001", "晚上一起吃饭吗？七点老地方见。") is ParsedCard.None)
    }

    /** 发件人可控的超长正文：只看前 MAX_CONTENT 字，最坏情况也要很快返回（未截断时 2 万字约 1.8 秒） */
    @Test fun longAdversarialBodyIsBounded() {
        for (piece in listOf("尾号1234支出", "您的x包裹已到y，", "【剩余流量查询】")) {
            val body = piece.repeat(20_000 / piece.length)
            val t0 = System.nanoTime()
            assertTrue(parser.parse("1069", body) is ParsedCard.None)
            val ms = (System.nanoTime() - t0) / 1_000_000
            assertTrue("$piece 耗时 ${ms}ms", ms < 200)
        }
    }

    /** 截断不影响正常卡片：前面是卡片内容，后面拖一大段广告 */
    @Test fun longBodyStillParsesHead() {
        val r = parser.parse("1069000000003", "【某某出行】您的验证码为 4821。" + "广告".repeat(5000))
        assertEquals(ParsedCard.VerificationCode("4821", "某某出行"), r)
    }
}
