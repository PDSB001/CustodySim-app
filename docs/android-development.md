# Android 开发与发布

核对日期：2026-10-18。所有命令从 **CustodySim-app 仓库根目录**执行；不需要 Web 仓库中的旧 .app-workspace 或同步脚本。

![构建类型、CI 与本机发布](assets/android-builds.svg)

## 工具链

| 项目                | 当前仓库配置                       |
| ------------------- | ---------------------------------- |
| JDK                 | CI 为 Temurin 17；Java 编译目标 17 |
| Gradle wrapper      | 9.8.1                              |
| AGP / Kotlin        | 9.4.1 / 2.4.21                     |
| Compose BOM / Miuix | 2026.09.00 / 0.9.4                 |
| Android SDK         | min 26 / compile 37 / target 37    |
| 应用版本            | 1.6.1-test / versionCode 4         |

版本依据为 [版本目录](../gradle/libs.versions.toml)、[wrapper](../gradle/wrapper/gradle-wrapper.properties) 和 [App 构建文件](../app/build.gradle.kts)。不要额外使用另一套系统 Gradle 替代 wrapper。

## 构建变体

| 变体       | 应用 ID                | R8 / 资源收缩 | 默认服务器 / 签名                |
| ---------- | ---------------------- | ------------- | -------------------------------- |
| debug      | com.custodysim.app.dev | 关闭          | 私有联调配置；本机 debug 签名    |
| debugR8    | com.custodysim.app.dev | 开启          | 同 debug；非 debuggable 优化验证 |
| production | com.custodysim.app     | 开启          | 预置远端，可私有覆盖；默认未签名 |
| release    | com.custodysim.app     | 开启          | 不嵌入端点；默认未签名           |

四个变体的 EPUB/DOCX 都使用 Episteme，TXT/PDF 使用原生引擎；没有 development、benchmark、H5 开关或 WebView 回退。端点混淆只降低直接阅读便利性，不能保护密钥，运行时地址仍可恢复。

## 私有构建配置

在忽略的 local.properties 配置 sdk.dir。端点优先 local.properties，缺省再读对应环境变量：

| local.properties                 | 环境变量                           | 变体                       |
| -------------------------------- | ---------------------------------- | -------------------------- |
| custodysim.baseUrl               | CUSTODYSIM_BASE_URL                | debug / debugR8            |
| custodysim.realtimeUrl           | CUSTODYSIM_REALTIME_URL            | debug / debugR8            |
| custodysim.productionBaseUrl     | CUSTODYSIM_PRODUCTION_BASE_URL     | production                 |
| custodysim.productionRealtimeUrl | CUSTODYSIM_PRODUCTION_REALTIME_URL | production                 |
| custodysim.localNetwork          | 无                                 | 可选，联调本地网络能力判定 |

示例（替换为自己的开发机地址）：

```properties
custodysim.baseUrl=http://192.168.1.100:3000
custodysim.realtimeUrl=ws://192.168.1.100:3001
```

开发变体按配置的 HTTP/WS 主机生成明文放行；production/release 使用安全连接。release 忽略所有端点预置；production 未覆盖时使用构建文件的远端默认值，实时地址由 HTTPS 推导 WSS。不要为了修改默认服务器覆盖用户保存的运行时选择。

运行时服务器选择只接受 HTTPS 根地址，检测失败不切换，确认后清本机旧凭据与待上传队列，再重新登录。保存的选择优先于编译默认值。详见[服务器设置](https://github.com/PDSB001/CustodySim/blob/master/docs/server-selection.md)。

## 构建、验证与安装

```powershell
.\gradlew.bat :app:assembleDebug
.\gradlew.bat :app:assembleDebugR8
.\gradlew.bat :app:assembleProduction
.\gradlew.bat :app:assembleRelease
.\gradlew.bat :app:testDebugUnitTest
.\gradlew.bat :app:lintDebug
.\gradlew.bat :app:compileDebugAndroidTestKotlin
```

Bash 将 .\gradlew.bat 换为 ./gradlew。JVM 单测、androidTest 编译、真实设备 instrumented 测试是不同检查，不能相互替代。

APK 默认在 app/build/outputs/apk/{变体}/；R8 mapping 在 app/build/outputs/mapping/{变体}/，保留与发布包对应的 mapping 以便还原崩溃。

使用 `adb install -r <实际APK路径>` 覆盖安装。先核对 ID 与签名；debug 与 debugR8 同 .dev ID、本机 debug 签名可互相覆盖，正式包必须保持签名连续性。不要为排查问题卸载或清空用户数据。

## 发布与准确对应源码

1. 在本仓库确认改动与版本，记录提交，等待 [Android CI](ci.md)。
2. 本机使用维护者自己的签名流程生成正式 production 或 release；当前仓库不默认提供发布签名配置。
3. 从同一源码版本运行：

```powershell
.\gradlew.bat androidCorrespondingSource
node scripts/verify-source-archive.mjs
```

源码为 build/distributions/CustodySim-Android-corresponding-source.zip。本机校验脚本需要 unzip 在 PATH，Windows 可用 Git for Windows 的 usr/bin/unzip.exe。

4. 核对 APK、源码 ZIP 与提交的对应关系，记录 SHA-256；按[许可说明](../README-LICENSE.md)一起提供。
5. 在实际设备验证登录、升级、聊天、阅读、计时、定位与权限。CI 不证明后台成功率或帧率。

签名材料、私有端点、local.properties、APK 与构建缓存不入 Git；不上传 Android 发布密钥到 CI。
