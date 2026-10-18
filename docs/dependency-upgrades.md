# Android 依赖升级记录

核对日期：2026-10-18。Web 同步记录见 [主仓库](https://github.com/PDSB001/CustodySim/blob/master/docs/dependency-upgrades.md)。本轮保持 App 1.6.1-test / versionCode 4、API 37、minSdk 26、应用 ID、四种构建类型及服务端协议。

## 版本基线

| 依赖                                           | 升级前   | 升级后   |
| ---------------------------------------------- | -------- | -------- |
| Gradle wrapper                                 | 9.8.0    | 9.8.1    |
| Kotlin / Compose 编译插件 / Serialization 插件 | 2.4.0    | 2.4.21   |
| Core KTX                                       | 1.16.0   | 1.19.1   |
| Lifecycle                                      | 2.9.4    | 2.11.0   |
| Activity Compose                               | 1.9.3    | 1.13.0   |
| WorkManager                                    | 2.9.1    | 2.12.0   |
| DataStore                                      | 1.1.1    | 1.2.1    |
| OkHttp                                         | 4.12.0   | 5.5.0    |
| Miuix                                          | 0.9.3    | 0.9.4    |
| Navigation Event                               | 1.1.2    | 1.2.0    |
| Jsoup                                          | 1.22.1   | 1.23.2   |
| Coroutines                                     | 1.10.2   | 1.11.0   |
| Serialization JSON / Protobuf                  | 1.9.0    | 1.11.0   |
| 测试 org.json                                  | 20260719 | 20260814 |

AGP 9.4.1、Compose BOM 2026.09.00、Backdrop 2.0.1、ExifInterface 1.4.2、ProfileInstaller 1.4.1、AndroidSVG 1.4、Timber 5.0.1 与 JUnit 4.13.2 保持当前稳定版本。未引入 alpha、beta、RC 或快照。

本轮后续又核对了 Google Maven / Maven Central 的 27 项版本目录依赖，均已是最新稳定值；Foojay resolver 1.0.0 也核对 Gradle 官方插件门户，无需升级。Web 的 pnpm 12.14、ESLint 10 与 TypeScript 7 迁移不改变 Android 的 Gradle 工具链。

版本目录和根 buildscript 的 Kotlin 同步更新，避免 AGP 内置默认 KGP 读取新元数据失败。Gradle 通过 wrapper 任务生成，发行 ZIP 固定官方 SHA-256，wrapper JAR 也核对官方校验值。

## 兼容检查

- [OkHttp 5](https://raw.githubusercontent.com/square/okhttp/master/CHANGELOG.md)拆分 Android / JVM 构件，由 Gradle 模块元数据选取平台；需要验证现有请求、缓存和 WebSocket 调用，不能只替换 JAR。本轮不启用新的 DNS / ECH 功能。
- [WorkManager 2.12](https://developer.android.com/jetpack/androidx/releases/work)将 minSdk 提高到 24，仍低于 App 的 26。
- [Activity 1.13](https://developer.android.com/jetpack/androidx/releases/activity)包含系统栏与媒体选择相关修复；返回手势和权限仍需要真机验收。
- [Kotlin 完整支持表](https://kotlinlang.org/docs/gradle-configure-project.html)中 2.4.20–2.4.21 的 Gradle / AGP 完整测试上限低于本仓库现有 9.8 / 9.4 组合；本轮以实际构建验证，不宣称整个组合获得上游完整认证。
- 未修改导入的 Episteme 源码；22 个文件哈希仍按原 UPSTREAM.json 验证。阅读依赖升级同步更新 APK 内 READER-NOTICES.txt 的版本与来源链接，保留原许可。

## 验证

本轮在 Windows / JDK 17 完成：106 项 App 单测通过、lintDebug 无问题、Debug / Release / Production APK 构建通过。Release 和 Production 实际执行 R8，均生成非空 mapping.txt；22 个导入文件哈希及对应源码 ZIP 校验通过。episteme-core 当前没有独立单测（NO-SOURCE），不计入通过数量。

可重复入口（JDK 17、仓库 wrapper）：

```bash
node scripts/verify-episteme.mjs
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleRelease :app:assembleProduction androidCorrespondingSource --console=plain
node scripts/verify-source-archive.mjs
```

wrapper 升级任务单独运行，再构建源码 ZIP，避免同时修改 ZIP 输入造成 Gradle 隐式依赖校验失败。Release / Production 需确认实际执行 R8 并生成 mapping；构建结果不证明真机手势、后台任务或缓存性能。APK 分发前仍应测试登录刷新、聊天重连、封面缓存、四种图书阅读及定位授权，并分发匹配的对应源码。

Dependabot 每周检查 Gradle 与 Actions，兼容 minor/patch 合为一组 PR，仍需 CI 与人工审查。它不安装签名材料、不发布 APK、不部署生产。
