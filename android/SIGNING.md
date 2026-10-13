# 自签 release 包

release 签名配置读仓库外的文件，仓库里不放任何密钥。查找顺序：

1. 环境变量 `PB_SIGNING_PROPERTIES` 指向的文件；
2. 默认 `~/.config/phonebox/signing.properties`。

两者都找不到时，`assembleRelease` 照样能跑，产出未签名的包（`app-release-unsigned.apk`）。

## 步骤

1. 用 keytool 生成 PKCS#12 密钥库（有效期按需调整，口令自己记牢，丢了就无法再发同签名的升级包）：

   ```bash
   mkdir -p ~/.config/phonebox && chmod 700 ~/.config/phonebox
   keytool -genkeypair -storetype PKCS12 -keystore ~/.config/phonebox/release.p12 \
     -alias phonebox -keyalg RSA -keysize 4096 -validity 10000 \
     -dname "CN=phonebox"
   ```

2. 写 `~/.config/phonebox/signing.properties`，四个字段：

   ```properties
   storeFile=release.p12
   storePassword=你的密钥库口令
   keyAlias=phonebox
   keyPassword=你的密钥口令（PKCS12 下通常与 storePassword 相同）
   ```

   `storeFile` 可写绝对路径；写相对路径时按 signing.properties 所在目录解析。建议 `chmod 600` 这个文件。

3. 构建：

   ```bash
   cd android
   ./gradlew assembleRelease
   # 或者用别处的配置：
   PB_SIGNING_PROPERTIES=/path/to/signing.properties ./gradlew assembleRelease
   ```

   产物在 `app/build/outputs/apk/release/app-release.apk`，只含 arm64-v8a。

4. 用 Android SDK build-tools 里的 apksigner 校验：

   ```bash
   apksigner verify --print-certs app/build/outputs/apk/release/app-release.apk
   ```

   记下输出里的 `certificate SHA-256 digest`，以后每次发版都应一致。

## fork 后改包名

包名 `top.xingzhenxi.phonebox` 是原作者的。fork 后想换成自己的，要改三处：

- `app/build.gradle.kts` 里的 `applicationId`；
- 同文件里的 `namespace`；
- 源码包路径 `app/src/main/java/top/xingzhenxi/phonebox/`（以及 `src/test`、`src/androidTest` 下对应目录）和每个文件的 `package` / `import` 行。

只改 `applicationId` 也能装，但三处一致更不容易出错。

## 关于覆盖安装

Android 只允许"包名相同且签名相同"的包覆盖升级：

- 保留原包名、但用你自己的密钥签名：无法覆盖安装原作者发布的版本，必须先卸载再装（App 内的配置和缓存会丢失）。
- 改了包名：会作为另一个独立 App 安装，与原版并存。
