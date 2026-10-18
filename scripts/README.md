# Android 脚本导航

从仓库根目录运行。构建命令见[开发与发布](../docs/android-development.md)。

| 脚本                      | 用途                               | 是否修改文件         |
| ------------------------- | ---------------------------------- | -------------------- |
| verify-episteme.mjs       | 核对导入阅读核心的 SHA-256         | 否                   |
| verify-source-archive.mjs | 核对准确对应源码 ZIP，拒绝私有文件 | 否                   |
| check-workflows.sh        | workflow 静态检查                  | 否                   |
| vendor-episteme-core.mjs  | 从指定上游导入并生成清单           | 是，按来源审查后执行 |

```bash
node scripts/verify-episteme.mjs
node scripts/verify-source-archive.mjs
```

归档检查需先生成源码 ZIP；校验通过不代表已完成签名或真机验收。不要手改清单哈希掩盖来源变化。
