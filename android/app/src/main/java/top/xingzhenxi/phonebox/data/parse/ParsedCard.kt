/*
 * 移植自 PixelText（https://github.com/Pixel-Tailor-CN/PixelText）domain/model/MessageModel.kt 中的 ParsedResult，
 * Apache License 2.0。改动：改名 ParsedCard，去掉本项目用不到的 Dynamic；动账补了尾号/余额字段。
 */
package top.xingzhenxi.phonebox.data.parse

/** 客户端解析出的短信卡片；解析不出来是 [None] */
sealed interface ParsedCard {
    data class VerificationCode(val code: String, val signature: String? = null) : ParsedCard

    data class TrainTicket(
        val date: String,
        val trainNumber: String,
        val departureTime: String,
        val departureStation: String,
        val arrivalStation: String,
        /** 乘车人 / 席别 / 座位 / 检票口 / 订单号，按出现顺序 */
        val details: Map<String, String>,
    ) : ParsedCard

    data class Flight(
        val date: String,
        val flightNumber: String,
        val departureCity: String,
        val departureTime: String,
        val arrivalCity: String,
        val arrivalTime: String,
        val terminal: String,
    ) : ParsedCard

    data class BankTransaction(
        val type: String,
        /** 不带符号的金额，如 "128.00" */
        val amount: String,
        /** true 收入、false 支出、null 看不出方向 */
        val income: Boolean?,
        val account: String?,
        val balance: String?,
        val isSuccess: Boolean = true,
        val errorMessage: String? = null,
        val details: Map<String, String> = emptyMap(),
    ) : ParsedCard

    data class ExpressDelivery(val company: String, val code: String, val location: String, val time: String? = null) : ParsedCard

    data class PhoneRecharge(val amount: String, val details: Map<String, String>) : ParsedCard

    data class MissedCall(val phoneNumber: String, val time: String? = null, val location: String? = null) : ParsedCard

    data class DataUsage(
        val status: DataUsageStatus,
        val primaryData: String?,
        val dataLabel: String,
        val cutoffTime: String? = null,
        val details: Map<String, String> = emptyMap(),
    ) : ParsedCard

    data object None : ParsedCard
}

enum class DataUsageStatus { NORMAL, LOW, EXHAUSTED }
