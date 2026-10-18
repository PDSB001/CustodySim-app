# Gradle 与构建缓存

核对日期：2026-10-18。当前 wrapper 9.8.1，配置见 [gradle.properties](../gradle.properties)。

```properties
org.gradle.parallel=true
org.gradle.caching=true
org.gradle.configuration-cache=true
org.gradle.configuration-cache.problems=fail
```

| 机制                | 作用               | 日志证据                         |
| ------------------- | ------------------ | -------------------------------- |
| 增量任务            | 输入未变时不重跑   | UP-TO-DATE                       |
| Build cache         | 恢复可复用任务输出 | FROM-CACHE                       |
| Configuration cache | 复用配置阶段任务图 | Configuration cache entry reused |

本机 Gradle 用户目录及仓库 .gradle 保存缓存，不进 Git。CI setup-gradle 单独管理 Actions 缓存，PR 只读主分支缓存；不能把“没有手工搭建远程缓存服务器”理解成“CI 不上传任何缓存”。

日常从仓库根执行：

```powershell
.\gradlew.bat :app:assembleDebug
.\gradlew.bat :app:assembleDebugR8
```

无需每次 clean。修改依赖、构建脚本、版本目录或私有端点后需要重新计算相关任务；更换端点必须重构建 APK。

排障临时使用 --no-configuration-cache 或 --no-build-cache，保留失败输出，修复后恢复严格模式。不要将 problems=fail 改成警告长期掩盖不兼容问题，也不要删除整个用户 Gradle 缓存作为常规步骤。

历史 2026-09-26 benchmark/JDK 25 测量仅代表当时机器和已移除变体；当前仅四个变体，以[开发指南](android-development.md)为准，不复用旧耗时作为现版本性能保证。

官方参考：[配置缓存](https://docs.gradle.org/current/userguide/configuration_cache_enabling.html)。
