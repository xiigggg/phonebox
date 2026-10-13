// 仅用于 P6 保护验证：以普通第三方 App 身份尝试启动 GuardianTargetService，应被拒
plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.example.guardianprobe"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.example.guardianprobe"
        minSdk = 31
        targetSdk = 36
        versionCode = 1
        versionName = "1"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
