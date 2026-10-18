# Android CI

核对日期：2026-10-18。入口 [.github/workflows/ci.yml](../.github/workflows/ci.yml)，PR、main 推送、手动运行及 workflow_call 触发。正式 APK 由维护者本机签名，CI 不保存发布密钥或私有服务端配置。

![构建验证与本机正式分发](assets/android-builds.svg)

## 环境与检查

Ubuntu 24.04、Temurin JDK 17、仓库 Gradle wrapper 9.8.0、Android API 37 / build-tools 37.0.0。工具链以 [setup-android](../.github/actions/setup-android/action.yml) 和构建文件为准。

1. workflow 静态检查、Gradle wrapper 校验；PR 仅读主分支缓存。
2. `node scripts/verify-episteme.mjs`，校验导入源与 UPSTREAM.json 哈希。
3. `./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleRelease androidCorrespondingSource --no-daemon --console=plain`。
4. `node scripts/verify-source-archive.mjs`，检查必要构建文件并拒绝私有材料。
5. 确认未签名 Release，收集对应源码、提交 SHA 和 SHA-256。

当前 gate 超时 40 分钟；同分支新运行取消旧运行。唯一 Job 名称为 **Android CI gate**，建议设置为必需检查。只申请 contents: read，Action 固定 SHA。

## 报告和产物

| 名称                       | 内容                                                                | 保留  |
| -------------------------- | ------------------------------------------------------------------- | ----- |
| android-reports-*          | app/build/reports 与 test-results                                   | 7 天  |
| android-build-{commit SHA} | Debug APK、未签名 Release、准确对应源码 ZIP、COMMIT.txt、SHA256SUMS | 14 天 |

报告在失败时也尽量上传；构建未完成时可能没有完整产物。未签名 Release 不能当正式分发包直接安装。

Lint 保持 warnings-as-errors，只排除构建文件列出的依赖版本提醒与传递 Timber 日志风格提示，其余检查仍阻止通过。

## 失败怎么查

- 哈希失败：用 vendor 脚本更新 Episteme 与 UPSTREAM.json，不手改清单掩盖来源。
- 单测失败：先看 app/build/test-results/testDebugUnitTest。
- Lint 失败：看 app/build/reports 中实际 issue 和对应行，不用全局 baseline 掩盖新增错误。
- R8/打包失败：核对真实报错与受影响变体，保留 mapping；不能因 Debug 成功跳过 Release。
- 源码归档失败：核对精确版本、必需脚本、许可证及是否意外包含本机文件。

本机归档校验需 unzip 在 PATH；Windows 可用 Git for Windows 的 usr/bin/unzip.exe。真实设备、正式签名连续性和后台成功率不由 CI 证明，按[本机发布流程](android-development.md)验收。
