package top.xingzhenxi.phonebox.update

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.ParcelFileDescriptor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import top.xingzhenxi.phonebox.BuildConfig
import top.xingzhenxi.phonebox.config.ConfigRepository
import top.xingzhenxi.phonebox.diag.DiagLog
import top.xingzhenxi.phonebox.diag.DiagType
import top.xingzhenxi.phonebox.net.PinnedTls
import top.xingzhenxi.phonebox.shizuku.ShizukuShell
import top.xingzhenxi.phonebox.shizuku.ShizukuState
import top.xingzhenxi.phonebox.shizuku.ShizukuStatus
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data class UpToDate(val remote: Long?) : UpdateState
    data class Available(val manifest: UpdateManifest) : UpdateState
    data class Downloading(val manifest: UpdateManifest, val percent: Int) : UpdateState
    data class Installing(val manifest: UpdateManifest, val silent: Boolean) : UpdateState
    data class Error(val message: String) : UpdateState
}

/**
 * 应用内更新：拉清单 → 下载到私有缓存 → 校验 sha256 与签名证书 → 交给系统安装。
 * 校验规则见 [UpdateRules]，任一不符就删文件并记诊断日志。
 */
class UpdateManager(
    context: Context,
    private val config: ConfigRepository,
    private val diag: DiagLog,
) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("update", Context.MODE_PRIVATE)
    private val mutex = Mutex()
    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    private val _customUrl = MutableStateFlow(prefs.getString(K_URL, "").orEmpty())
    /** 空字符串表示用默认地址 */
    val customUrl: StateFlow<String> = _customUrl.asStateFlow()

    fun setCustomUrl(url: String) {
        val v = url.trim()
        prefs.edit().putString(K_URL, v).apply()
        _customUrl.value = v
    }

    private val currentVersion: Long get() = BuildConfig.VERSION_CODE.toLong()

    suspend fun manifestUrl(): String? {
        _customUrl.value.takeIf { it.isNotEmpty() }?.let { return it }
        val base = config.config.first().phonebox.baseUrl.trimEnd('/')
        return if (base.isEmpty()) null else "$base/$DEFAULT_PATH"
    }

    /** 后台检查用：距上次检查不足一天就跳过 */
    fun dueForBackgroundCheck(now: Long = System.currentTimeMillis()): Boolean = now - prefs.getLong(K_LAST, 0) >= DAY_MS

    suspend fun check(): UpdateState = mutex.withLock {
        _state.value = UpdateState.Checking
        val r = runCatching { fetchManifest() }.fold(
            onSuccess = { (m, reason) -> if (reason == null) UpdateState.Available(m) else UpdateState.UpToDate(m.versionCode) },
            onFailure = {
                diag.log(DiagType.Update, "检查更新失败：${it.message ?: it.javaClass.simpleName}")
                UpdateState.Error("检查失败：${it.message ?: it.javaClass.simpleName}")
            },
        )
        prefs.edit().putLong(K_LAST, System.currentTimeMillis()).apply()
        _state.value = r
        r
    }

    /** 下载、校验并安装；Shizuku 可用时静默安装，否则弹系统确认 */
    suspend fun downloadAndInstall(m: UpdateManifest) = mutex.withLock {
        // 版本不升的清单根本走不到这里；再判一次防止状态过期
        UpdateRules.checkManifest(m, app.packageName, currentVersion)?.let { return@withLock reject(it) }
        val file = File(File(app.cacheDir, "update").apply { mkdirs() }, "update.apk")
        try {
            download(m, file)
            val facts = withContext(Dispatchers.IO) { apkFacts(file) }
            UpdateRules.checkApk(m, facts, installedSigners(), app.packageName, currentVersion)?.let {
                file.delete()
                return@withLock reject(it)
            }
            diag.log(DiagType.Update, "更新包校验通过：${m.versionCode}")
            val silent = ShizukuStatus.current() == ShizukuState.Ready
            _state.value = UpdateState.Installing(m, silent)
            if (silent && installViaShizuku(file)) return@withLock
            installViaSession(file)
        } catch (e: Exception) {
            file.delete()
            diag.log(DiagType.Update, "更新失败：${e.message ?: e.javaClass.simpleName}")
            _state.value = UpdateState.Error("更新失败：${e.message ?: e.javaClass.simpleName}")
        }
    }

    private fun reject(reason: String) {
        diag.log(DiagType.Update, "拒绝更新包：$reason")
        _state.value = UpdateState.Error("已拒绝：$reason")
    }

    private suspend fun http(url: HttpUrl): OkHttpClient {
        // 清单与 phonebox 同主机时沿用它的证书指纹；用户改到别的主机就走系统 CA
        val pb = config.config.first().phonebox
        val pbHost = pb.baseUrl.toHttpUrlOrNull()?.host
        val b = OkHttpClient.Builder().followSslRedirects(false).followRedirects(false)
            .connectTimeout(15, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS)
        return (if (pbHost == url.host) PinnedTls.configure(b, pb.certFingerprint) else b).build()
    }

    private suspend fun fetchManifest(): Pair<UpdateManifest, String?> = withContext(Dispatchers.IO) {
        val url = manifestUrl()?.toHttpUrlOrNull() ?: throw IOException("清单地址无效")
        if (!url.isHttps) throw IOException("清单只允许 https")
        val text = http(url).newCall(Request.Builder().url(url).header("Cache-Control", "no-cache").build()).execute().use { r ->
            if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
            if ((r.body.contentLength()) > 64 * 1024) throw IOException("清单过大")
            r.body.string()
        }
        val m = try { UpdateRules.parse(text) } catch (e: Exception) { throw IOException("清单格式不对") }
        m to UpdateRules.checkManifest(m, app.packageName, currentVersion)
    }

    private suspend fun download(m: UpdateManifest, file: File) = withContext(Dispatchers.IO) {
        val manifest = manifestUrl()?.toHttpUrlOrNull() ?: throw IOException("清单地址无效")
        val url = manifest.resolve(m.apk) ?: throw IOException("APK 地址无效")
        _state.value = UpdateState.Downloading(m, 0)
        http(url).newCall(Request.Builder().url(url).build()).execute().use { r ->
            if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
            val total = r.body.contentLength()
            if (total > MAX_BYTES) throw IOException("APK 过大")
            var done = 0L
            r.body.byteStream().use { input ->
                file.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        done += n
                        if (done > MAX_BYTES) throw IOException("APK 过大")
                        out.write(buf, 0, n)
                        if (total > 0) _state.value = UpdateState.Downloading(m, (done * 100 / total).toInt())
                    }
                }
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun apkFacts(file: File): ApkFacts {
        val info = app.packageManager.getPackageArchiveInfo(file.path, PackageManager.GET_SIGNING_CERTIFICATES)
        return ApkFacts(
            sha256 = UpdateRules.sha256Hex(file),
            packageName = info?.packageName,
            versionCode = info?.longVersionCode,
            signers = info?.let(::signersOf).orEmpty(),
        )
    }

    private fun installedSigners(): List<String> =
        signersOf(app.packageManager.getPackageInfo(app.packageName, PackageManager.GET_SIGNING_CERTIFICATES))

    private fun signersOf(info: PackageInfo): List<String> {
        val si = info.signingInfo ?: return emptyList()
        // 单签名者时 apkContentsSigners 就是当前证书；多签名者原样返回，由规则拒绝
        return si.apkContentsSigners.map { sig ->
            MessageDigest.getInstance("SHA-256").digest(sig.toByteArray()).joinToString("") { "%02x".format(it) }
        }
    }

    private suspend fun installViaShizuku(file: File): Boolean {
        val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        val r = runCatching { ShizukuShell(app).installApk(pfd, file.length()) }.getOrNull()
        if (r?.ok == true) {
            diag.log(DiagType.Update, "Shizuku 静默安装成功")
            return true
        }
        diag.log(DiagType.Update, "Shizuku 安装失败，改用系统安装：${r?.output?.take(80) ?: "绑定失败"}")
        return false
    }

    private suspend fun installViaSession(file: File) = withContext(Dispatchers.IO) {
        val installer = app.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(app.packageName)
            setSize(file.length())
        }
        val id = installer.createSession(params)
        installer.openSession(id).use { s ->
            file.inputStream().use { input ->
                s.openWrite("base.apk", 0, file.length()).use { out ->
                    input.copyTo(out)
                    s.fsync(out)
                }
            }
            // 系统回填状态和确认界面 Intent，所以必须是 MUTABLE；目标组件写死为本应用的接收器
            val pi = PendingIntent.getBroadcast(
                app, id, Intent(app, UpdateInstallReceiver::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
            )
            s.commit(pi.intentSender)
        }
        file.delete()
    }

    internal fun onInstallStatus(status: Int, message: String?) {
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> diag.log(DiagType.Update, "等待用户确认安装")
            PackageInstaller.STATUS_SUCCESS -> diag.log(DiagType.Update, "安装成功")
            else -> {
                diag.log(DiagType.Update, "安装失败：$status ${message.orEmpty().take(80)}")
                _state.value = UpdateState.Error("安装失败（$status）")
            }
        }
    }

    companion object {
        const val DEFAULT_PATH = "app-update/version.json"
        private const val MAX_BYTES = 300L * 1024 * 1024
        private const val K_URL = "manifest_url"
        private const val K_LAST = "last_check"
        private const val DAY_MS = 24 * 3600_000L
    }
}

/** PackageInstaller 的回调：需要用户确认时拉起系统确认页 */
class UpdateInstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val graph = (context.applicationContext as top.xingzhenxi.phonebox.PhoneboxApp).graph
        graph.updateManager.onInstallStatus(status, intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE))
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            val confirm = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
            } else {
                @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_INTENT)
            } ?: return
            context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}
