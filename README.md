# CustodySim App

Kotlin + Compose Android 客户端，独立仓库：https://github.com/PDSB001/CustodySim-app 。
Web 与服务端：https://github.com/PDSB001/CustodySim ，通过 HTTP API 协作。

## 构建

配置本机 Android SDK（local.properties 的 sdk.dir）和 JDK 后，在仓库根运行：

```powershell
.\gradlew.bat :app:assembleDevelopment
.\gradlew.bat :app:testDebugUnitTest --tests 'com.custodysim.app.ui.library.*'
.\gradlew.bat androidCorrespondingSource
```

服务端地址在 App 设置内配置；私有构建配置仅放 local.properties 或环境变量，不入库。
dev/development 的 EPUB/DOCX 使用 Episteme 原生阅读器；其他变体的引擎开关维持迁移前设置。
TXT/PDF 分别使用原生文本和 PDF 路径。应用 ID 与签名配置方式保持不变。

## 许可与发布

组合 Android 应用按 AGPL-3.0-only 分发。原有 MIT 和第三方许可声明完整保留。
详见 [README-LICENSE.md](README-LICENSE.md)。每个 APK 应同时提供其准确对应源码，
不得仅以本仓库最新分支或 Episteme 上游链接替代发布版本源码。

## 迁移来源

Android 提交历史从 CustodySim 的 android/ 子树导出，保留作者和提交信息。
迁移基准：44ac1611ff46e189ddf21715f45b0f9d124481ad 。当前未提交的 App 修复作为新仓库迁移提交纳入。
迁移审计与稳定性计划：[docs/android-stabilization-and-repo-split.md](docs/android-stabilization-and-repo-split.md)。
旧说明中的 android/ 前缀表示迁移前路径；本仓库对应根目录。
