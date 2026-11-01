# Gradle 与构建缓存

核对日期：2026-11-01。Gradle wrapper **9.8.2**，配置见 [gradle.properties](../gradle.properties) 与 [wrapper](../gradle/wrapper/gradle-wrapper.properties)。所有命令从仓库根目录执行。

```properties
org.gradle.jvmargs=-Xmx2048m -Dfile.encoding=UTF-8
org.gradle.parallel=true
org.gradle.caching=true
org.gradle.configuration-cache=true
org.gradle.configuration-cache.problems=fail
```

## 三层复用机制

| 机制                | 作用                       | 日志证据                         |
| ------------------- | -------------------------- | -------------------------------- |
| 增量任务            | 输入未变时不重跑           | `UP-TO-DATE`                     |
| Build cache         | 恢复可复用的任务输出       | `FROM-CACHE`                     |
| Configuration cache | 复用配置阶段算出的任务图   | `Configuration cache entry reused` |

`problems=fail` 是刻意的严格模式：构建脚本出现不可序列化状态、读取 `Task.project` 之类问题时，构建直接失败，而不是静默丢弃缓存后继续。修复脚本本身，不要把这一行改成警告。

## 什么情况下必须重新构建

| 改动                                           | 结果                                       |
| ---------------------------------------------- | ------------------------------------------ |
| Kotlin / 资源源码                              | 只重跑受影响的任务                         |
| `libs.versions.toml`、`build.gradle.kts`       | 版本目录与配置缓存失效，重新计算任务图     |
| `local.properties` 中的端点（`custodysim.*`）  | BuildConfig 与网络安全配置变化，必须重构建 |
| `push.local.properties`、`CUSTODYSIM_*` 环境变量 | 推送/华为参数注入变化，必须重构建         |
| `app/google-services.json`                     | Firebase 生成资源变化，相关变体重跑        |
| `proguard-rules.pro`、`AndroidManifest.xml`    | 打包与收缩任务重跑，需要复核 mapping       |

`debug` 与 `debugR8` 的网络安全配置由 `GenerateNetworkSecurityConfig` 任务按 `local.properties` 生成到 `app/build/generated/res/network-security/<variant>/`，属于可再生成的构建产物，不入库。

## 缓存位置

| 位置                                  | 内容                             | 是否入库 |
| ------------------------------------- | -------------------------------- | -------- |
| `~/.gradle/caches`                    | 依赖与 Build cache 条目          | 否       |
| `<repo>/.gradle`                      | 配置缓存与本机构建状态           | 否       |
| `app/build`、`build`                  | APK、报告、mapping、源码 ZIP     | 否       |
| CI `gradle/actions/setup-gradle`      | Actions 缓存，PR 只读主分支缓存  | 不适用   |

CI 由 `gradle/actions/setup-gradle` 管理缓存，PR 使用只读缓存、主分支可写；这不等于“CI 不上传任何缓存”，也不等于本机已有远程缓存服务器。

## 日常命令

```powershell
.\gradlew.bat :app:assembleDebug
.\gradlew.bat :app:assembleDebugR8
.\gradlew.bat :app:testDebugUnitTest
.\gradlew.bat :app:lintDebug
```

Bash 用 `./gradlew`。无需每次 `clean`；`clean` 只在怀疑产物污染时使用，之后要重新付出完整构建时间。

## 排障

1. 先看失败任务名与 `--stacktrace`，确认是代码问题还是缓存不兼容。
2. 配置缓存问题用 `--no-configuration-cache` 复现一次，保留完整输出；修好后立刻恢复严格模式。
3. 输出可疑时用 `--rerun-tasks` 只重跑当前任务，或用 `--no-build-cache` 排除缓存干扰。
4. 不要删除整个用户 Gradle 目录作为常规步骤；那会同时丢掉依赖与 CI 之外的全部可用输出。

## 边界

- 构建缓存只证明“同样输入复用同样输出”，不证明功能正确；功能验证见[开发与发布](android-development.md)与 [CI](ci.md)。
- 历史 benchmark / JDK 25 测量对应已移除的变体与当时的机器，不能当作当前版本的耗时或内存承诺；当前只有 debug、debugR8、production、release 四个变体。

官方参考：[配置缓存](https://docs.gradle.org/current/userguide/configuration_cache_enabling.html)。
