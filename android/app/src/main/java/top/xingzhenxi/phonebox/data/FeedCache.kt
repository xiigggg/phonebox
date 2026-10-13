package top.xingzhenxi.phonebox.data

import top.xingzhenxi.phonebox.net.Feed
import top.xingzhenxi.phonebox.net.FeedJson
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** 最近一次 feed 的本地副本（filesDir/feed-cache.json），离线或刚启动时先显示它 */
class FeedCache(private val file: File) {
    fun read(): Feed? = runCatching { if (file.exists()) FeedJson.parse(file.readText()) else null }.getOrNull()

    /** 先写临时文件再 rename，进程中途被杀也不会留下半截 JSON */
    fun write(feed: Feed) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(FeedJson.encode(feed))
        Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    companion object {
        fun inDir(filesDir: File) = FeedCache(File(filesDir, "feed-cache.json"))
    }
}
