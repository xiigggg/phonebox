pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // linphone 只发布在自家仓库；限定 org.linphone 组，避免其它依赖从这里解析
        exclusiveContent {
            forRepository { maven("https://download.linphone.org/maven_repository") }
            filter { includeGroup("org.linphone") }
        }
    }
}

rootProject.name = "phonebox"
include(":app")
// 保护验证用的探针 App，不被 :app 依赖，不进发布构建
include(":testapps:guardian-probe")
