# App 通知与推送

核对日期：2026-11-01。本页只描述客户端一侧；服务端投递队列、账号绑定与厂商通道配置以 Web 仓库 [App 通知与友盟推送](https://github.com/PDSB001/CustodySim/blob/master/docs/app-push.md) 为准。

## 开关与本地行为

「开启 App 消息通知」是**主动启用**第三方推送 SDK 的入口，默认关闭：

- 关闭时不注册任何厂商通道，并解绑服务端设备；本地也会过滤迟到的旧推送，避免“关了还在响”。
- 开启时才申请 `POST_NOTIFICATIONS`（Android 13+）、注册友盟，随后按设备条件注册 FCM 与华为通道。
- 前台消息走全局 WebSocket，后台才依赖厂商通道；同一投递在本地 SQLite 去重，前台与后台重复到达只展示一次。
- 退出登录或切换服务器会清除设备绑定与本地待处理记录；推送点击后仍要由 API 复核会话权限，不信任推送内容本身。

## 依赖与版本

| 组件              | 版本                                                       |
| ----------------- | ---------------------------------------------------------- |
| 友盟 common       | 10.0.1                                                     |
| 友盟 push         | 6.7.6                                                      |
| 友盟 asms         | 1.8.7.2                                                    |
| 友盟 FCM 适配器   | 2.3.0                                                      |
| 友盟华为适配器    | 2.3.0                                                      |
| HMS Push          | 6.13.0.301                                                 |
| Firebase BoM      | 35.1.0（仅 Messaging，未引入 Analytics）                   |
| Google Services   | 4.5.0                                                      |

全部通过 Maven 固定坐标引入，仓库不含 SDK 二进制或私有配置。

## 构建参数

友盟参数放在被忽略的 `local.properties`：

```properties
custodysim.umengAppKey=<AppKey>
custodysim.umengMessageSecret=<Umeng Message Secret>
```

或环境变量 `CUSTODYSIM_UMENG_APP_KEY`、`CUSTODYSIM_UMENG_MESSAGE_SECRET`。华为 App ID 放在 `push.local.properties` 或 `CUSTODYSIM_HUAWEI_APP_ID`：

```properties
custodysim.huaweiAppId=<Huawei App ID>
```

- 只有 `release` / `production` 注入这些值；`debug` / `debugR8` 固定为空，避免 `.dev` 包名误注册。
- 构建期会校验格式：友盟参数只允许字母数字，华为 App ID 只允许数字，非法值直接构建失败。
- **Master Secret 与华为 Client Secret 只能放在服务端或友盟控制台**，不进 App、不进环境变量、不进 GitHub。

## FCM 通道

- 配置文件为本地 `app/google-services.json`，也支持构建变体目录；配置与生成资源不入库，源码归档明确排除。
- `GoogleServicesTask` 会先检查配置里的 `package_name` 是否等于当前变体包名：不匹配或缺失时，清空上一次的生成资源并跳过该任务，防止生产参数残留到 `.dev` 包。
- `firebase_messaging_auto_init_enabled=false`：只有用户开启通知且完成友盟注册后，才在具备 Google Play 服务与匹配配置的设备上注册 FCM；关闭通知时同步关闭自动初始化。
- FCM 消息通过友盟现有通知处理链进入 App，沿用账号绑定过滤与去重。友盟控制台需配置 FCM HTTP V1 服务账号，该私钥不上传仓库、不嵌入 APK。

## 华为厂商通道

- 只有 `release` / `production` 配置了 App ID 时才调用 `HuaWeiRegister`；`debug` / `debugR8` 不启用。
- AppGallery Connect 中的应用包名必须是 `com.custodysim.app`，并登记最终 APK 使用的签名证书 SHA-256；换签名要同步更新，否则收不到托管消息。
- 友盟厂商配置需要华为 App ID 与 Client Secret；华为回执地址按友盟要求配置为 `https://ack.upush.umeng.com/hw/`。
- U-Push 请求会带 `channel_activity`，由 `.notifications.ProviderNotificationClickActivity` 处理点击（`agoo://<applicationId>/thirdpush`，`singleTask` + `noHistory`），复用现有账号绑定校验后进入通知路由。
- 通知设置页分别显示友盟、FCM 与华为通道状态；“已请求注册”不等于离线可达，`HuaWeiRegister` 日志可能含 Push Token，不要公开原始日志。

## 已知限制

- `umdid:1.1.0` 的空客户端证书检查用于出站 HTTPS，不参与 App 自身的 TLS；其服务器链校验会调用系统 TrustManager。该 lint 例外只限这个固定构件，升级时必须复查，不要扩大范围。App 自己的 API、图片与 WebSocket 保持严格校验。
- `common:10.0.1` 在当前 R8 下会报缺少 stack map table 的字节码警告；必须在真机确认初始化与注册，不能凭“R8 成功”推断运行正常。
- 安装包只打 `arm64-v8a` 与 `armeabi-v7a`：友盟现有 x86_64 tnet 原生库未适配 16 KB 页大小，因此放弃 x86 模拟器上的推送验证。
- 厂商通道的送达率取决于系统后台策略、省电与应用自启动设置，客户端无法保证“必达”。

## 真机验收

| 场景                 | 期望                                                       |
| -------------------- | ---------------------------------------------------------- |
| 未开启通知           | 不注册厂商通道，服务端无设备绑定，收不到推送               |
| 开启通知             | 弹权限框；授予后显示友盟/FCM/华为各自状态                  |
| 前台                 | 走 WebSocket，仅一条提示，不重复弹系统通知                 |
| 后台 / 杀进程        | 由厂商通道送达；点击进入对应页面且权限被复核               |
| 重复投递             | 本地去重，只展示一次                                       |
| 关闭通知             | 解绑设备，迟到推送被过滤                                   |
| 退出账号 / 切换服务器 | 绑定与本地待处理记录清空，旧推送不再出现                  |
| 华为设备             | 需已配置签名、开通 HMS Push、装有可用 HMS Core 才能验证    |

编译通过、日志里出现注册成功，都不等于离线必达；测试应从友盟或厂商控制台实际发起。
