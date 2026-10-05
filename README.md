# CustodySim App

Kotlin + Compose Android 客户端，独立仓库：https://github.com/PDSB001/CustodySim-app 。
Web 与服务端：https://github.com/PDSB001/CustodySim ，通过 HTTP API 协作。

## 构建

配置本机 Android SDK（local.properties 的 sdk.dir）和 JDK 后，在仓库根运行：

```powershell
.\gradlew.bat :app:assembleDebug
.\gradlew.bat :app:testDebugUnitTest --tests 'com.custodysim.app.ui.library.*'
.\gradlew.bat androidCorrespondingSource
```

服务端地址在 App 设置内配置；私有构建配置仅放 local.properties 或环境变量，不入库。
仅保留 debug、debugR8、production 和 release。EPUB/DOCX 统一使用 Episteme 原生阅读器，TXT/PDF 分别使用
原生文本和 PDF 路径；旧 WebView 阅读器已移除。debug 保留联调应用 ID `.dev`，
debugR8 使用相同联调地址并开启 R8。production 预置远端服务器，release 无预置地址；
两者保留正式应用 ID 并开启 R8，未配置发布签名时产物为未签名 APK。

日常开发在主项目目录内独立的 `.app-workspace` Git worktree 中进行，App 源码不提交到
Web／服务端仓库。发布前提交 App 工作区，再通过主项目的 `scripts/sync-app-release.ps1`
同步到独立发布目录；默认只构建，显式 `-Push` 才推送远程。

## 许可与发布

组合 Android 应用按 AGPL-3.0-only 分发。原有 MIT 和第三方许可声明完整保留。
详见 [README-LICENSE.md](README-LICENSE.md)。每个 APK 应同时提供其准确对应源码，
不得仅以本仓库最新分支或 Episteme 上游链接替代发布版本源码。

## 迁移来源

Android 提交历史从 CustodySim 的 android/ 子树导出，保留作者和提交信息。
迁移基准：44ac1611ff46e189ddf21715f45b0f9d124481ad 。当前未提交的 App 修复作为新仓库迁移提交纳入。
迁移审计与稳定性计划：[docs/android-stabilization-and-repo-split.md](docs/android-stabilization-and-repo-split.md)。
旧说明中的 android/ 前缀表示迁移前路径；本仓库对应根目录。
