# Android CI

`.github/workflows/ci.yml` 在 PR、`main` 推送及手动运行时执行检查。
流水线不读取私有密钥和服务配置，也不发布正式 APK。正式 Release APK 由维护者
在本机签名构建。

环境固定为 Ubuntu 24.04、JDK 17、仓库 Gradle wrapper、Android API 37。
Gradle setup 自动校验 wrapper，PR 使用只读缓存。Action 固定提交 SHA，
Dependabot 每周提出更新；同分支新运行会取消旧运行。

Lint 保留 warnings-as-errors。只排除依赖新版本提醒与传递引入 Timber 的日志
风格提示：依赖版本由维护者审查升级，App 继续使用 Android Log。其余正确性、
安全、资源及 API 兼容检查仍作为失败门槛，不使用整体 baseline 隐藏问题。

检查内容：

1. `node scripts/verify-episteme.mjs`：校验 vendored 文件与 `UPSTREAM.json`。
2. `./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleRelease androidCorrespondingSource`。
3. `node scripts/verify-source-archive.mjs`：检查源码包必要文件，拒绝密钥、私有配置及 APK 混入源码。
4. 确认 Release APK 未签名，打包同次构建 APK、对应源码、提交 SHA 和 SHA-256 清单。

`android-reports-*` 报告保留 7 天；`android-build-<commit SHA>` 产物保留 14 天。
建议在主分支规则中要求 **Android CI gate** 成功。

本机发布时选择 CI 通过的提交，使用自己的签名材料构建正式 APK，并从相同
源码重新运行 `androidCorrespondingSource`。分发时 APK 与准确对应源码一起
提供；签名身份、真机功能、手势和帧率由本机流程验收。

本机执行 `verify-source-archive.mjs` 时需把 `unzip` 加入 PATH；Windows 可使用
Git for Windows 附带的 `usr/bin/unzip.exe`。CI 的 Ubuntu runner 已包含此工具。
