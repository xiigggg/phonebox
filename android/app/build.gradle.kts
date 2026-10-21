import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

/**
 * 发布签名：密钥和口令都在仓库外。优先读环境变量 PB_SIGNING_PROPERTIES 指向的文件，
 * 默认 ~/.config/phonebox/signing.properties（storeFile/storePassword/keyAlias/keyPassword）。
 * storeFile 写相对路径时按 signing.properties 所在目录解析，自签步骤见 android/SIGNING.md。
 * 找不到时 release 不签名，assembleRelease 产出 unsigned 包。
 */
val releaseSigning: Properties? = run {
    val path = System.getenv("PB_SIGNING_PROPERTIES")
        ?: "${System.getProperty("user.home")}/.config/phonebox/signing.properties"
    val f = file(path)
    if (!f.isFile) null else Properties().apply {
        f.inputStream().use { load(it) }
        setProperty("storeFile", f.absoluteFile.parentFile.resolve(getProperty("storeFile")).path)
    }
}

android {
    namespace = "top.xingzhenxi.phonebox"
    compileSdk = 36

    defaultConfig {
        applicationId = "top.xingzhenxi.phonebox"
        minSdk = 31
        targetSdk = 36
        // 发布/测试应用内更新时用 -PpbVersionCode=N 出一个更高版本的包
        versionCode = (project.findProperty("pbVersionCode") as String?)?.toInt() ?: 4
        versionName = "0.2.2"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        releaseSigning?.let { p ->
            create("release") {
                storeFile = file(p.getProperty("storeFile"))
                storePassword = p.getProperty("storePassword")
                keyAlias = p.getProperty("keyAlias")
                keyPassword = p.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            // 调试版额外带 x86_64，供模拟器使用
            ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
        }
        release {
            ndk { abiFilters += listOf("arm64-v8a") }
            if (releaseSigning != null) signingConfig = signingConfigs.getByName("release")
            // 收尾阶段决定暂不开 R8：liblinphone 经 JNI 按名字回调 Java，release 只有 arm64，
            // 本机模拟器是 x86_64，无法在真包上把各条回调路径跑一遍；体积换风险不划算
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
        // Shizuku UserService 的 Binder 接口
        aidl = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.telecom)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.work.runtime.ktx)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material3.adaptive)
    implementation(libs.compose.material3.adaptive.layout)
    implementation(libs.compose.material3.adaptive.navigation)
    implementation(libs.compose.material3.adaptive.navigation.suite)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.okhttp)
    implementation(libs.okhttp.sse)
    implementation(libs.kotlinx.serialization.json)

    implementation(libs.shizuku.api)
    implementation(libs.shizuku.provider)

    implementation(libs.linphone.sdk)

    testImplementation(libs.junit)
    testImplementation(libs.okhttp.mockwebserver3)
    testImplementation(libs.okhttp.tls)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.androidx.test.ext.junit)
}
