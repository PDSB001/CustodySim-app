# Android 接口与行为

核对日期：2026-10-18。完整服务端协议统一维护在 [CustodySim 原生接入规格](https://github.com/PDSB001/CustodySim/blob/master/docs/android-client.md)。本页只记录当前 App 的实现入口，避免重复一份容易漂移的 API 文档。

## 当前关键约定

| 能力       | App 实现                                                     |
| ---------- | ------------------------------------------------------------ |
| 客户端代际 | X-CustodySim-Client: android-app/2                           |
| 业务鉴权   | Bearer，不依赖浏览器 Cookie                                  |
| 刷新       | 401 后单飞刷新，更新轮换后的 refreshToken；仅重试一次        |
| 聊天图片   | 代际 2 使用 imageUrl 认证取字节，兼容旧服务端内联数据        |
| 实时       | OkHttp WebSocket 处理 Engine.IO / Socket.IO，文本 2 / 3 心跳 |
| 正文共享   | shared-v1，按当次响应头校验阅读身份，兼容 gzip               |
| 原生阅读   | EPUB/DOCX Episteme，TXT StaticLayout，PDF PdfRenderer        |

服务器发现接口的 nativeProtocol 区间属于检测契约，与聊天图片的客户端代际不是同一个字段，不要混用。运行时服务器只在明确确认切换后保存，旧容器与位置队列停止，需重新登录。

## 身份与安全边界

AppConfig 添加原生头；ApiClient 统一请求和认证；TokenStore 使用应用私有的加密存储。403 不应直接清登录，5xx 保留凭据；写失败先核对服务端是否已成功。令牌、可信设备凭据和异常正文不能直接输出日志。

退出接口使该账号所有设备的会话失效；切服务器仅清本机旧凭据，不主动退出账号其他设备。App 暂无修改密码页面，首次改密与安全设置使用同一服务的 Web。

## 功能入口

- 考核计分板：首页与“我的”均可进入，支持周次、排名、来源分项、获准查看的积分流水及监管账号人工调整。直接使用 `/api/scores` 的姓名脱敏和明细权限，不自行放宽。规则展示读取响应 `policy`，与服务端实际计分和图书馆阅读设置同源；旧服务端未返回规则时展示暂不可用，不编造分值。
- 任务分类使用固定等宽三栏，底轨不横向滚动；首次加载空列表时禁止拖动占位内容。首页概览使用单行摘要，避免为说明文案保留两行空白。
- “我的 → 版权、开源许可与源码”按项目归属、分发许可、源码和第三方组件展示，许可证原文独立阅读并可选择复制。完整原文仍随安装包提供。

- [AppConfig](../app/src/main/java/com/custodysim/app/config/AppConfig.kt)：协议头和路径常量。
- [ApiClient](../app/src/main/java/com/custodysim/app/data/net/ApiClient.kt)：认证、单飞刷新、取消与阅读传输。
- [ServerSettings](../app/src/main/java/com/custodysim/app/config/ServerSettings.kt)：保存端点、旧会话绑定和显式选择优先。
- [阅读与缓存](reader-cache.md)：metadata、磁盘内容、版本及受控恢复。
- [权限与定位](permissions.md)：后台调度、前台服务与通知边界。
- [开发与发布](android-development.md)：四个构建类型、R8、签名与准确对应源码。

## 协议变更流程

先修改服务端契约与兼容回归，再修改 App 解析；同时核对旧客户端仍需的字段、图片形态、定位/计时和权限约束。不要只递增 android-app/N 而不改实际解析，也不要随意改变阅读位置字段。

[历史接入规格与事故记录](archive/android-client-legacy.md)包含旧 worktree、benchmark 与 H5 路径，仅供追溯。构建、JVM 测试通过不等于真实设备网络、后台定位与手势验收通过。
