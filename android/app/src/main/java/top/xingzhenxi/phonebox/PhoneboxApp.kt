package top.xingzhenxi.phonebox

import android.app.Application
import android.content.Context
import androidx.datastore.preferences.preferencesDataStore
import top.xingzhenxi.phonebox.config.AesGcmCredentialCipher
import top.xingzhenxi.phonebox.config.ConfigRepository

private val Context.settingsStore by preferencesDataStore(name = "settings")

class PhoneboxApp : Application() {
    // 手工依赖注入：对象少，不值得引入 Hilt
    val graph: AppGraph by lazy {
        AppGraph(this, ConfigRepository(settingsStore, AesGcmCredentialCipher.keystore()))
    }

    val configRepository: ConfigRepository get() = graph.configRepository
}

val Context.appGraph: AppGraph get() = (applicationContext as PhoneboxApp).graph
