package top.xingzhenxi.phonebox.service

import kotlin.random.Random

/**
 * 注册失败后的重试间隔：2s、4s、8s…封顶 5 分钟，再乘 0.5–1.0 的抖动系数，
 * 避免 NAS 重启后多台设备同一时刻一起涌上来。成功后 [reset]。
 */
class Backoff(
    private val baseMs: Long = 2_000L,
    private val maxMs: Long = 5 * 60_000L,
    private val random: Random = Random.Default,
) {
    var attempt: Int = 0
        private set

    fun next(): Long {
        val exp = (baseMs shl attempt.coerceIn(0, 20)).coerceAtMost(maxMs)
        attempt++
        return (exp * (0.5 + random.nextDouble() * 0.5)).toLong().coerceAtLeast(1)
    }

    fun reset() {
        attempt = 0
    }
}
