# App 通知

首页的通知弹层支持合并公示、详情展开、未读/重要筛选、搜索、分页、批量已读；批量提交携带通知版本，遇到更新会要求刷新。

“开启 App 消息通知”是主动启用友盟 SDK 的入口，默认关闭。Android 13+ 需要授予系统通知权限。关闭时解绑服务端设备，本地也过滤迟到的旧推送。

前台使用全局通知 WebSocket，后台使用友盟通用通道；同一投递在 SQLite 中持久去重，账号/服务器切换清除绑定。聊天只显示发送者与新消息提示，点击后由 API 再核实会话权限。

## 构建参数

私有 `local.properties`：

```properties
custodysim.umengAppKey=<AppKey>
custodysim.umengMessageSecret=<Umeng Message Secret>
```

或环境变量 `CUSTODYSIM_UMENG_APP_KEY`、`CUSTODYSIM_UMENG_MESSAGE_SECRET`。仅 production/release 注入，debug/debugR8 保持空值。Master Secret 只能放 Web 服务器。

## FCM 通道

已安装 Firebase Messaging（BoM 35.1.0）、友盟 FCM 适配器 2.3.0 和 Google Services 插件 4.5.0。配置放在本地 `app/google-services.json`，也支持标准构建变体目录；配置及生成资源不提交，源码归档也明确排除配置文件。服务账号私钥不要放进 App。

Google Services 只处理含有当前构建包名的配置。没有私有配置的 CI 或只有正式包配置的 debug 构建会跳过 Firebase 配置，清除该任务的旧生成资源，避免生产参数残留。

用户开启通知并完成友盟注册后，在具备 Google Play 服务和匹配配置的设备注册 FCM。自动初始化默认关闭；关闭通知时关闭 FCM 自动初始化。未安装 Analytics SDK。FCM 通过友盟现有通知处理链接收，沿用账号绑定过滤和去重。

友盟控制台还需要配置 FCM HTTP V1 服务账号；该私钥只在可信的控制台中配置，不上传 GitHub，也不嵌入 APK。真机送达测试应从友盟通道发起。

官方 SDK 通过 Maven 固定版本引入，仓库不用包含私有配置或 SDK 二进制。发布时仍须保留第三方许可证和 APK 对应源码，遵循本仓库发布规则。

按用户决定，安装包限定 ARM 手机：arm64-v8a、armeabi-v7a，放弃 x86/x86_64；友盟现有 x86_64 tnet 原生库未适配 16 KB 页大小。

## 第三方 SDK 的限制

`umdid:1.1.0` 的空客户端证书检查用于出站 HTTPS，不参与 App 的服务器端 TLS；其服务器链校验会调用系统 TrustManager，lint 例外仅限这个固定构件。但 SDK 另有接受服务器证书有效期异常的实现，升级时需复查，不应将这项例外扩大到其他代码。App 自身的 API、图片和 WebSocket 保持原有严格校验。

`common:10.0.1` 在当前 R8 有缺少 stack map table 的字节码警告。需要在真机验证初始化与注册，不能只凭 R8 成功保证运行无误。

当前集成友盟通用通道、FCM 与华为厂商通道适配。华为使用 U-Push 官方 `huawei-umengaccs:2.3.0` + HMS Push `6.13.0.301`；只有 release/production 构建配置了华为 App ID 时才会调用 `HuaWeiRegister`，并通过厂商点击 Activity 进入现有通知路由。生产激活与完整操作步骤见 Web 仓库 `docs/app-push.md`。优先验证前台、后台、点击、重复投递、退出账号和切换服务器；不可把编译通过当成离线必达证明。

## 华为厂商通道

发布构建通过本地 Git 忽略文件 `push.local.properties` 或构建环境变量注入华为 App ID，不提交 AppGallery 配置或密钥：

```properties
custodysim.huaweiAppId=<Huawei App ID>
```

或设置 `CUSTODYSIM_HUAWEI_APP_ID`。debug/debugR8 固定不启用，避免 `.dev` 包名误注册。AppGallery Connect 中的应用包名必须为 `com.custodysim.app`，并登记最终 APK 使用的签名证书 SHA-256。友盟厂商配置需要华为 App ID 与 Client Secret；Client Secret 只填友盟控制台，不能放 App、服务器环境变量或 GitHub。华为服务回执地址按友盟要求配置为 `https://ack.upush.umeng.com/hw/`。

开启通知并完成友盟注册后，客户端才触发华为注册。应用的通知设置分别显示友盟、FCM 和华为通道状态；华为状态“已请求注册”不是离线送达证明。U-Push 请求会带 `channel_activity`，由 `.notifications.ProviderNotificationClickActivity` 处理托管通知点击并复用账号绑定校验。真机验证需使用已配置签名、已开通 HMS Push 的华为设备；设备需安装可用的 HMS Core。华为注册日志 `HuaWeiRegister` 可能含 Push Token，不要公开原始日志。

官方文档：[手动集成](https://devs.umeng.com/docs/67966/detail/98585)、[SDK 当前版本](https://devs.umeng.com/sdk)。
