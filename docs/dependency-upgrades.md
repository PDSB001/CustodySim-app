# Android 依赖升级记录

核对日期：2026-11-01。Web 侧记录见 [主仓库依赖升级](https://github.com/PDSB001/CustodySim/blob/master/docs/dependency-upgrades.md)。本轮保持源码版本 **1.7.0 / versionCode 5**、API 37、minSdk 26、应用 ID、四个构建变体与服务端协议不变。

## 当前基线

| 分组     | 依赖                                                        | 版本                                    |
| -------- | ----------------------------------------------------------- | --------------------------------------- |
| 构建     | Gradle wrapper / AGP / Kotlin                               | 9.8.2 / 9.4.1 / 2.4.21                  |
| 构建     | Google Services 插件 / Foojay resolver                      | 4.5.0 / 1.0.0                           |
| UI       | Compose BOM / Miuix / Backdrop / Navigation Event           | 2026.09.00 / 0.9.4 / 2.0.1 / 1.2.0      |
| 核心     | Core KTX / Lifecycle / Activity Compose                     | 1.19.1 / 2.11.0 / 1.13.0                |
| 运行时   | WorkManager / DataStore / OkHttp                            | 2.12.0 / 1.2.1 / 5.5.0                  |
| 资源     | AndroidSVG / ExifInterface / ProfileInstaller               | 1.4 / 1.4.2 / 1.4.1                     |
| 推送     | Firebase BoM / 友盟 common / push / asms                    | 35.1.0 / 10.0.1 / 6.7.6 / 1.8.7.2       |
| 推送     | 友盟 FCM 适配器 / 华为适配器 / HMS Push                     | 2.3.0 / 2.3.0 / 6.13.0.301              |
| 阅读内核 | Jsoup / Timber / Coroutines / Serialization                 | 1.23.2 / 5.0.1 / 1.11.0 / 1.11.0        |
| 测试     | JUnit / org.json / androidx.test:runner                      | 4.13.2 / 20260814 / 1.7.0               |

以 [版本目录](../gradle/libs.versions.toml) 为唯一事实来源；上表只是便于阅读的快照。全部为稳定版，未引入 alpha、beta、RC 或快照。

## 上一轮升级（2026-10-18 记录）

| 依赖                                  | 升级前   | 升级后   |
| ------------------------------------- | -------- | -------- |
| Gradle wrapper                        | 9.8.0    | 9.8.1    |
| Kotlin / Compose 编译插件 / Serialization | 2.4.0 | 2.4.21   |
| Core KTX                              | 1.16.0   | 1.19.1   |
| Lifecycle                             | 2.9.4    | 2.11.0   |
| Activity Compose                      | 1.9.3    | 1.13.0   |
| WorkManager                           | 2.9.1    | 2.12.0   |
| DataStore                             | 1.1.1    | 1.2.1    |
| OkHttp                                | 4.12.0   | 5.5.0    |
| Miuix                                 | 0.9.3    | 0.9.4    |
| Navigation Event                      | 1.1.2    | 1.2.0    |
| Jsoup                                 | 1.22.1   | 1.23.2   |
| Coroutines                            | 1.10.2   | 1.11.0   |
| Serialization JSON / Protobuf         | 1.9.0    | 1.11.0   |
| 测试 org.json                         | 20260719 | 20260814 |

此后 wrapper 由 9.8.1 升到 **9.8.2**（补丁版本，无 API 变更）；其余依赖未再变动。历史表格中的“升级后”不代表当前值，当前值以上面的基线表为准。

## 兼容性注意

- **OkHttp 5**：拆分为 Android / JVM 构件，由 Gradle 模块元数据选择平台；需要验证请求、HTTP 缓存（25 MiB）与 WebSocket（聊天实时通道）三条路径，不能只替换 JAR。本轮不启用 DNS / ECH 等新特性。
- **WorkManager 2.12**：把 minSdk 提到 24，仍低于 App 的 26；长周期定位仍用它兜底。
- **Activity 1.13**：包含系统栏与媒体选择修复；返回手势、权限与弹层行为仍需真机验收。
- **Kotlin 2.4.21 + AGP 9.4.1 + Gradle 9.8.2**：上游兼容表中 2.4.20–2.4.21 的 Gradle / AGP 完整测试上限低于本仓库组合，本轮只能以实际构建与 CI 结果为准，不宣称获得上游完整认证。
- **阅读内核**：Episteme 源码未修改，22 个文件哈希仍按 `UPSTREAM.json` 校验；阅读依赖升级需同步 APK 内 `READER-NOTICES.txt` 的版本与来源链接，保留原许可。
- **推送 SDK**：友盟 FCM 适配器仍使用旧 token 协议，因此保留兼容 API，不切换到 Firebase FID 注册协议；升级前先确认友盟已支持新协议。
- **AGP 9 内置 Kotlin**：不再应用 `kotlin-android` 插件；版本目录里的 Kotlin 与 Compose/Serialization 插件必须同版本，避免内置 KGP 读到更新的元数据失败。

## 验证方式

```bash
node scripts/verify-episteme.mjs
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleRelease :app:assembleProduction androidCorrespondingSource --console=plain
node scripts/verify-source-archive.mjs
```

- 单测数量随代码变化，以 [CI](ci.md) 的实际运行结果为准，不在文档里固定条数。
- Release / Production 必须确认实际执行了 R8 并生成非空 `mapping.txt`；`assembleDebug` 成功不能替代。
- wrapper 升级单独提交后再生成本地源码 ZIP，避免同时修改 ZIP 输入触发依赖校验失败。
- 升级完成后仍需真机复验：登录与刷新、覆盖安装、聊天重连、封面与正文缓存、四种图书阅读、定位授权与后台调度。

## 维护规则

1. 每个依赖改动单独说明动机（安全、修复、功能必需），不接受“顺手升级一批”。
2. 只升稳定版；需要 alpha/beta/RC 时在提交说明里写清回退方案。
3. Dependabot 每周检查 Gradle 与 Actions，兼容的 minor/patch 合为一组 PR，合并前仍要 CI 通过并人工审查变更日志。
4. Dependabot 不安装签名材料、不发布 APK、不部署生产；这些步骤始终由维护者本机完成。
