# Android 权限与定位

核对日期：2026-10-18。本页区分 Manifest 声明、运行时授权和商店政策提醒。具体实现以 [AndroidManifest.xml](../app/src/main/AndroidManifest.xml) 与 location 模块为准。

## 前台定位服务声明

当前 Manifest 已包含：

```xml
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_LOCATION" />
<uses-permission android:name="android.permission.ACCESS_COARSE_LOCATION" />
<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />
<uses-permission android:name="android.permission.ACCESS_BACKGROUND_LOCATION" />

<service
    android:name=".location.LocationIntervalService"
    android:exported="false"
    android:foregroundServiceType="location" />
```

[Android 14+ 官方要求](https://developer.android.com/develop/background-work/services/fgs/service-types#location)同时声明服务类型及对应权限，并在启动前满足定位服务和运行时授权。只有 FOREGROUND_SERVICE 一行不能完整表达该能力，但 Studio 红线也不能仅凭这一行断定缺了类型，必须查看 service 声明与实际 issue。

“requires declaration and justification”可能涉及商店申报说明；Manifest、lint 通过不代表自动完成商店政策审核。不要为隐藏提示删除仍被核心定位功能使用的权限。

## 分阶段授权

先解释用途并申请前台定位，再按系统版本单独申请后台能力；拒绝后保留明确状态和设置入口，不伪造授权成功。[官方后台定位说明](https://developer.android.com/develop/sensors-and-location/location/permissions/background)描述了系统差异。

打卡 GPS 是一次性操作开关，后台位置上传是独立设置。打开一个不自动启用另一个。App 不承诺拒绝定位后仍能获得精确位置。

## 调度与用户可见性

| 场景                 | 实现与边界                                             |
| -------------------- | ------------------------------------------------------ |
| 5 / 10 分钟档        | 在应用可见且条件满足时启动 location 前台服务，常驻通知 |
| 15 分钟及以上        | WorkManager 周期兜底                                   |
| 断网                 | 已采集点留在有界队列，联网后上传                       |
| 关闭、登出或切服务器 | 停止旧资格/服务，旧队列按切换流程清理                  |
| 重启或系统限制       | 按系统允许恢复；不承诺精确周期                         |

POST_NOTIFICATIONS 控制通知展示；通知权限与定位权限分别判断。权限、Doze、省电、强制停止、厂商后台限制、服务启动限制都可能延迟任务，常驻通知不是保活成功保证。

## 如何验证

先运行 :app:lintDebug 与定位单元测试，再在真实设备分别验证前台/后台授权、拒绝权限、关闭定位、5/10 分钟、长周期、断网恢复、登出、重启。不要卸载或清用户数据来掩盖升级问题。记录设备、系统、构建变体、时间与脱敏状态，不收集无关坐标。
