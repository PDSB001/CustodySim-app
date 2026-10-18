# CustodySim Android

Kotlin + Compose + Miuix 原生 Android 客户端，与独立的 [Web / API](https://github.com/PDSB001/CustodySim) 协作。日常开发、构建和提交均在本仓库进行。

![Android 构建与分发方式](docs/assets/android-builds.svg)

> 文档核对日期：2026-10-18。当前版本 **1.6.1-test / versionCode 4**，minSdk 26，compileSdk / targetSdk 37。版本以 [app/build.gradle.kts](app/build.gradle.kts) 为准。

## 文档入口

| 事项                         | 文档                                      |
| ---------------------------- | ----------------------------------------- |
| 工具链、构建变体、端点、安装 | [开发与发布](docs/android-development.md) |
| 持续集成、失败报告、源码产物 | [CI](docs/ci.md)                          |
| 网络鉴权、聊天与服务端契约   | [接口与行为](docs/android-client.md)      |
| 正文、PDF、插图与本地缓存    | [阅读与缓存](docs/reader-cache.md)        |
| 后台定位与前台服务提示       | [权限与定位](docs/permissions.md)         |
| Gradle 增量构建              | [构建缓存](docs/android-build-cache.md)   |
| 许可与对应源码               | [README-LICENSE.md](README-LICENSE.md)    |
| 历史迁移、性能与设计         | [文档中心](docs/README.md)                |

## 快速构建

准备 JDK 17、Android SDK 37，在忽略的 local.properties 设置 sdk.dir，使用仓库 wrapper：

```powershell
.\gradlew.bat :app:assembleDebug
.\gradlew.bat :app:testDebugUnitTest
.\gradlew.bat :app:lintDebug
```

Bash 用 ./gradlew。只有 debug、debugR8、production、release 四个构建类型；production/release/debugR8 开启 R8。未配置发布签名的 production/release 默认未签名。正式 APK 由维护者本机签名，CI 不保存签名材料。

release 不嵌入默认服务器；production 预置远端端点，可通过本机配置覆盖；debug/debugR8 使用私有联调配置。运行时从服务器设置检测并选择 HTTPS 根地址，保存的选择优先，不通过改 APK 静默覆盖用户设置。

## 阅读与缓存

EPUB/DOCX 使用 Episteme，TXT 使用 StaticLayout，PDF 使用 PdfRenderer，不使用 WebView 回退。每次开书先在线校验 metadata，再复用按服务器、readerKey 与 revision 隔离的 128 MiB 阅读磁盘缓存。

首次正文支持 shared-v1 共享 CDN 字节和 gzip；5xx、传输或共享身份异常仅恢复一次 document-origin，权限失败与取消不重试。25 MiB OkHttp HTTP 缓存另行遵循缓存头，不等于阅读磁盘缓存。详见[阅读链路](docs/reader-cache.md)。

## 发布与边界

组合 Android 应用按 **AGPL-3.0-only** 分发，保留原 MIT 和第三方声明。分发 APK 时提供同一版本的准确对应源码：

```powershell
.\gradlew.bat androidCorrespondingSource
```

源码 ZIP 不含签名、local.properties 或用户数据。编译、lint 和 JVM 单测不证明真实设备的手势、帧率、后台定位或签名升级已验收。

迁移历史来自原 Web 仓库 android 子树，见[迁移记录](docs/repository-migration.md)。旧 .app-workspace、development/benchmark/H5 阅读器说明仅在历史归档中保留。
