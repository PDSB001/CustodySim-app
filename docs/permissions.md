# Android 权限与定位

核对日期：2026-11-01。本页区分 **Manifest 声明**、**运行时授权** 与 **商店政策** 三件事；实现以 [AndroidManifest.xml](../app/src/main/AndroidManifest.xml) 和 `app/src/main/java/com/custodysim/app/location/` 为准。

## 权限一览

| 权限                              | 用途                             | 申请时机                       |
| --------------------------------- | -------------------------------- | ------------------------------ |
| `INTERNET`                        | 全部网络请求                     | 安装即授予                     |
| `POST_NOTIFICATIONS`              | 通知展示（Android 13+）          | 开启 App 消息通知时           |
| `ACCESS_COARSE_LOCATION`          | 打卡定位、后台位置上报           | 用户主动使用定位时             |
| `ACCESS_FINE_LOCATION`            | 精确打卡                         | 同上，与粗略定位一并申请       |
| `ACCESS_BACKGROUND_LOCATION`      | 后台周期上报                     | 前台授权成功后**单独**申请     |
| `FOREGROUND_SERVICE`              | 运行定位前台服务                 | 系统自动，无需用户操作         |
| `FOREGROUND_SERVICE_LOCATION`     | Android 14+ 前台服务类型对应权限 | 同上                           |
| `RECEIVE_BOOT_COMPLETED`          | 重启后恢复后台调度               | 安装即授予                     |

`android:allowBackup="false"`，并配置了 `data_extraction_rules` 与 `backup_rules`：令牌与定位队列不进系统备份。

## 前台定位服务声明

```xml
<service
    android:name=".location.LocationIntervalService"
    android:exported="false"
    android:foregroundServiceType="location" />
```

Android 14+ 要求**同时**声明 `foregroundServiceType` 与 `FOREGROUND_SERVICE_LOCATION`，并在启动前满足定位服务与运行时授权条件。只有 `FOREGROUND_SERVICE` 一行无法完整表达该能力；反过来，Studio 的红色提示也不能替代对 service 声明和实际 lint issue 的核对。

`LocationBootReceiver` 监听 `BOOT_COMPLETED` 与 `MY_PACKAGE_REPLACED`，用于覆盖安装或重启后按系统允许恢复调度；这不保证定时精确。

## 分阶段授权

1. 先解释用途，再申请前台定位（粗略 + 精确）。
2. 前台授权成功后，若用户另行开启“后台位置上报”，再按系统版本申请后台定位；Android 11+ 通常需要跳到系统设置页选择“始终允许”。
3. 用户拒绝时保留明确状态与设置入口，不伪造授权成功，也不把拒绝当成已开启继续上报。

一次性打卡定位与后台周期上报是两个独立开关，打开一个不会自动启用另一个。App 不承诺拒绝定位后仍能取得精确坐标。

## 调度与用户可见性

| 场景                     | 实现与边界                                                   |
| ------------------------ | ------------------------------------------------------------ |
| 间隔 5 / 10 分钟         | 应用可见且条件满足时启动 location 前台服务，带常驻通知        |
| 间隔 15–360 分钟         | WorkManager 周期任务兜底（可选档位 15 起每 15 分钟一档）      |
| 默认值                   | 15 分钟；允许范围 5–360 分钟                                 |
| 断网                     | 采集点写入有界队列，联网后按序上传，超出上限丢弃最旧         |
| 关闭上报、退出登录、切服务器 | 停止旧服务与旧队列，按切换流程清理                       |
| 重启、强制停止、省电限制 | 按系统允许恢复；不承诺精确周期，常驻通知也不是保活保证        |

`POST_NOTIFICATIONS` 只控制通知是否可见，与定位授权分别判断：拒绝通知权限不会让定位失效，但用户看不到常驻提示。

## 后台运行引导

开启后台位置上报时会出现“允许后台运行”弹窗，也可从「我的 → 后台运行与自启动」重新打开。弹窗跳到系统应用信息页，只解释需要打开的项；具体自启动、电池优化由用户在系统中选择，**跳转不代表设置已开启**。

## 如何验证

先跑静态与单元检查，再在真实设备逐项确认：

```powershell
.\gradlew.bat :app:lintDebug :app:testDebugUnitTest
```

| 项                          | 期望                                                   |
| --------------------------- | ------------------------------------------------------ |
| 首次开启定位                | 先前台后后台，两次系统弹窗，拒绝后状态明确             |
| 仅前台授权                  | 后台上报入口提示补授权，不静默上报                     |
| 5 / 10 分钟档               | 常驻通知存在，锁屏后仍按系统节奏上报                   |
| ≥15 分钟档                  | 无常驻前台服务，WorkManager 按系统窗口执行             |
| 断网 → 联网                 | 队列保留并在恢复后上传，不重复、不丢新点               |
| 关闭上报 / 登出 / 切服务器  | 服务停止、队列清理，不再产生上报                       |
| 重启 / 覆盖安装             | 按系统允许恢复调度                                     |
| 拒绝通知权限                | 定位功能仍可用，只是看不到常驻与推送通知               |

记录设备型号、系统版本、构建变体、时间与脱敏后的状态；不收集与问题无关的坐标，也不为了复现而卸载或用清数据代替升级验证。

## 商店政策边界

Manifest 与 lint 通过不代表完成应用商店的后台定位申报与说明。上架材料需单独准备“定位用途、频率、数据去向”的说明；不要为了消除提示删掉核心功能仍在使用的权限或服务类型。相关官方说明：[前台服务类型](https://developer.android.com/develop/background-work/services/fgs/service-types#location)、[后台定位授权](https://developer.android.com/develop/sensors-and-location/location/permissions/background)。
