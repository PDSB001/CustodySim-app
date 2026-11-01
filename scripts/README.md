# Android 脚本导航

从仓库根目录运行。构建命令见[开发与发布](../docs/android-development.md)。

| 脚本                      | 用途                               | 是否修改文件         |
| ------------------------- | ---------------------------------- | -------------------- |
| verify-episteme.mjs       | 核对导入阅读核心的 SHA-256         | 否                   |
| verify-source-archive.mjs | 核对准确对应源码 ZIP，拒绝私有文件 | 否                   |
| check-workflows.sh        | workflow 静态检查                  | 否                   |
| vendor-episteme-core.mjs  | 从指定上游导入并生成清单           | 是，按来源审查后执行 |
| sync-legal-documents.mjs | 同步 Web 已发布协议到 App 离线正文；`--check` 只校验 | 默认写资源，`--check` 不写 |

```bash
node scripts/verify-episteme.mjs
node scripts/verify-source-archive.mjs
```

归档检查需先生成源码 ZIP；校验通过不代表已完成签名或真机验收。不要手改清单哈希掩盖来源变化。

协议同步默认读取同级 `CustodySim` Web 仓库，需其已安装的 TypeScript 解析器；可用 `--web-root=<路径>` 指定。生成的 JSON 随 App 源码保存，普通 Android 构建及离线阅读不依赖 Web 仓库或 Node。发布前运行 `node scripts/sync-legal-documents.mjs --check`，确保标题、版本、生效日期和全文与已发布页面一致；协议更新后同步资源并重新打包。
