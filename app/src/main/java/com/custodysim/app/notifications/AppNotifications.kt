package com.custodysim.app.notifications

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.custodysim.app.BuildConfig
import com.custodysim.app.MainActivity
import com.custodysim.app.R
import com.umeng.commonsdk.UMConfigure
import com.umeng.message.PushAgent
import com.umeng.message.UmengMessageHandler
import com.umeng.message.UmengNotificationClickHandler
import com.umeng.message.api.UPushRegisterCallback
import com.umeng.message.entity.UMessage
import org.json.JSONObject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

data class PushRegistrationState(
    val umeng: String = "等待友盟注册",
    val fcm: String = "等待检查 FCM",
    val huawei: String = "等待检查华为通道",
)

object AppNotifications {
    val pendingRoute = kotlinx.coroutines.flow.MutableStateFlow<JSONObject?>(null)
    val communityUpdate = kotlinx.coroutines.flow.MutableStateFlow<Pair<String, String>?>(null)
    @Volatile var visibleConversation: String? = null
    @Volatile var visibleCommunityPost: String? = null
    val registrationState = MutableStateFlow(PushRegistrationState())
    private var configured = false
    private var registered = false
    private var registrationAttempt = 0
    private var lastRegistrationAttempt = -30_000L
    private var registrationPendingUntil = 0L
    private var lastFcmAttempt = -60_000L
    private var lastHuaweiAttempt = -600_000L
    fun permitted(context: Context) = Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    fun initialize(context: Context) {
        if (BuildConfig.UMENG_APP_KEY.isBlank()) {
            registrationState.update { it.copy(umeng = "此安装包未配置友盟") }
            return
        }
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper() && com.umeng.commonsdk.utils.UMUtils.isMainProgress(context)) {
            Thread({ initialize(context.applicationContext) }, "push-init").start()
            return
        }
        val store = NotificationStore(context)
        if (store.get("consent") != "yes") { store.close(); return }
        store.close()
        synchronized(this) {
            if (registered) { registerFcm(context); registerHuawei(context); return }
            val now = android.os.SystemClock.elapsedRealtime()
            if (now < registrationPendingUntil || now - lastRegistrationAttempt < 30_000) return
            lastRegistrationAttempt = now
            registrationPendingUntil = now + 120_000
            val attempt = ++registrationAttempt
            registrationState.update { it.copy(umeng = "正在注册友盟…") }
            try {
                if (!configured) {
                    UMConfigure.preInit(context, BuildConfig.UMENG_APP_KEY, "custodysim")
                    val agent = PushAgent.getInstance(context)
                    // Render both paths ourselves so logout/account switches and duplicate delivery are filtered.
                    agent.setNotificationOnForeground(true)
                    agent.messageHandler = object : UmengMessageHandler() {
                        override fun dealWithNotificationMessage(context: Context, message: UMessage) {
                            deliver(context, JSONObject(message.extra ?: emptyMap<String,String>()))
                        }
                        override fun dealWithCustomMessage(context: Context, message: UMessage) {
                            deliver(context, JSONObject(message.extra ?: emptyMap<String,String>()))
                        }
                    }
                    agent.notificationClickHandler = object : UmengNotificationClickHandler() {
                        override fun launchApp(context: Context, message: UMessage) { open(context, JSONObject(message.extra ?: emptyMap<String,String>())) }
                    }
                    UMConfigure.init(context, BuildConfig.UMENG_APP_KEY, "custodysim", UMConfigure.DEVICE_TYPE_PHONE, BuildConfig.UMENG_MESSAGE_SECRET)
                    configured = true
                }
            val agent = PushAgent.getInstance(context)
            agent.register(object : UPushRegisterCallback {
                override fun onSuccess(token: String) {
                    synchronized(this@AppNotifications) {
                        if (attempt != registrationAttempt) return
                        registered = true
                        registrationPendingUntil = 0
                    }
                    NotificationStore(context).use { it.put("providerToken", token) }
                    registrationState.update { it.copy(umeng = "友盟注册成功") }
                    registerFcm(context)
                    registerHuawei(context)
                }
                override fun onFailure(code: String?, message: String?) {
                    synchronized(this@AppNotifications) {
                        if (attempt != registrationAttempt) return
                        registrationPendingUntil = 0
                    }
                    // SDK messages can contain private payloads. Show only our own fixed text.
                    registrationState.update { it.copy(umeng = "友盟注册失败，联网后自动重试") }
                }
            })
            if (com.umeng.commonsdk.utils.UMUtils.isMainProgress(context)) agent.onAppStart()
            } catch (_: Exception) {
                registrationPendingUntil = 0
                registrationState.update { it.copy(umeng = "友盟初始化失败，稍后自动重试") }
            }
        }
    }

    // U-Push's FCM 2.3.0 adapter uses token registration, not Firebase's new FID API.
    @Suppress("DEPRECATION")
    @Synchronized private fun registerFcm(context: Context) {
        if (!com.umeng.commonsdk.utils.UMUtils.isMainProgress(context)) return
        NotificationStore(context).use { if (it.get("consent") != "yes") return }
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastFcmAttempt < 60_000) return
        lastFcmAttempt = now
        try {
            // Missing private resources (CI/debug) and devices without Play services retain U-Push.
            if (com.google.firebase.FirebaseApp.getApps(context).none { it.name == com.google.firebase.FirebaseApp.DEFAULT_APP_NAME }
                && com.google.firebase.FirebaseApp.initializeApp(context) == null) {
                registrationState.update { it.copy(fcm = "此安装包未配置 Firebase") }
                return
            }
            if (com.google.android.gms.common.GoogleApiAvailabilityLight.getInstance()
                    .isGooglePlayServicesAvailable(context) != com.google.android.gms.common.ConnectionResult.SUCCESS) {
                registrationState.update { it.copy(fcm = "Google Play 服务不可用，FCM 暂不可用") }
                return
            }
            registrationState.update { it.copy(fcm = "正在检查 FCM 注册…") }
            val messaging = com.google.firebase.messaging.FirebaseMessaging.getInstance()
            messaging.isAutoInitEnabled = true
            org.android.agoo.fcm.FCMRegister.register(context)
            messaging.token.addOnCompleteListener { task ->
                NotificationStore(context).use { if (it.get("consent") != "yes") return@addOnCompleteListener }
                registrationState.update { it.copy(fcm = if (task.isSuccessful && !task.result.isNullOrBlank())
                    "已取得 FCM 令牌，离线送达待验证" else "FCM 注册失败，请检查 Google 服务连接") }
            }
        } catch (_: Exception) {
            registrationState.update { it.copy(fcm = "FCM 初始化失败，稍后自动重试") }
        }
    }

    /** U-Push reports the Huawei token to its own service through this vendor adapter. */
    @Synchronized private fun registerHuawei(context: Context) {
        if (!com.umeng.commonsdk.utils.UMUtils.isMainProgress(context)) return
        NotificationStore(context).use { if (it.get("consent") != "yes") return }
        if (BuildConfig.HUAWEI_APP_ID.isBlank()) {
            registrationState.update { it.copy(huawei = "此安装包未配置华为 App ID") }
            return
        }
        if (!Build.MANUFACTURER.equals("HUAWEI", ignoreCase = true)) {
            registrationState.update { it.copy(huawei = "当前不是华为设备，通道不适用") }
            return
        }
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastHuaweiAttempt < 600_000) return
        lastHuaweiAttempt = now
        registrationState.update { it.copy(huawei = "正在注册华为通道…") }
        try {
            org.android.agoo.huawei.HuaWeiRegister.register(context.applicationContext)
            registrationState.update { it.copy(huawei = "已请求华为注册；请用华为设备验证离线送达") }
        } catch (_: Exception) {
            registrationState.update { it.copy(huawei = "华为通道注册失败，请检查 HMS 与 App ID") }
        }
    }

    @Synchronized fun pauseFcm(context: Context) {
        if (com.google.firebase.FirebaseApp.getApps(context).any { it.name == com.google.firebase.FirebaseApp.DEFAULT_APP_NAME })
            com.google.firebase.messaging.FirebaseMessaging.getInstance().isAutoInitEnabled = false
    }

    fun deliver(context: Context, payload: JSONObject): Boolean {
        NotificationStore(context).use { store ->
            if (!store.accepts(payload)) return false
            if (!permitted(context)) return false
            val kind = payload.optString("kind")
            // ACK suppressed categories so the server does not retry them through U-Push.
            if ((kind == "NOTICE" || kind == "PROBATION") && store.get("noticesEnabled") == "no") return true
            if (kind == "TASK" && store.get("tasksEnabled") == "no") return true
            if (kind == "COMMUNITY" && store.get("communityEnabled") == "no") return true
            if (kind == "CHAT" && (store.get("chatEnabled") == "no" || store.conversationMuted(payload))) return true
            if (kind == "CHECKIN" && store.get("checkinRemindersEnabled") != "yes") return true
            if ((kind == "CHECKIN" || kind == "PROBATION" || kind == "TASK" && payload.has("expiresAt")) && runCatching { java.time.Instant.parse(payload.getString("expiresAt")).isBefore(java.time.Instant.now()) }.getOrDefault(true)) return true
            if (kind !in listOf("CHAT", "NOTICE", "CHECKIN", "TASK", "COMMUNITY", "PROBATION")) return false
            // Hold the DB write transaction through notify: logout clears/cancels after any in-flight rendering.
            return store.deliverOnce(payload) {
            if (payload.optString("kind") == "CHAT" && payload.optString("conversationId") == visibleConversation) return@deliverOnce
            if (kind == "COMMUNITY") {
                communityUpdate.value = payload.optString("postId") to payload.optString("deliveryId")
                if (payload.optString("postId") == visibleCommunityPost) return@deliverOnce
            }
            val manager = context.getSystemService(NotificationManager::class.java)
            val chat = payload.optString("kind") == "CHAT"
            val checkin = payload.optString("kind") == "CHECKIN"
            val task = payload.optString("kind") == "TASK"
            val community = payload.optString("kind") == "COMMUNITY"
            val probation = kind == "PROBATION"
            val channel = if (chat) "chat_messages" else if (checkin) "checkin_reminders" else if (task) "task_reminders" else if (community) "community_replies" else if (probation) "account_reminders" else "system_notices"
            manager.createNotificationChannel(NotificationChannel(channel, if (chat) "聊天消息" else if (checkin) "打卡提醒" else if (task) "任务提醒" else if (community) "社区回复" else if (probation) "账号考察" else "站内信", NotificationManager.IMPORTANCE_DEFAULT))
            val intent = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra("notification",payload.toString())
            val id = (if (chat) payload.optString("conversationId") else if (community) payload.optString("postId") else payload.optString("entityId")).hashCode()
            val tap = PendingIntent.getActivity(context, id, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            manager.notify(channel, id, NotificationCompat.Builder(context,channel)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(if (chat) payload.optString("senderName").take(60) else if (checkin) "打卡提醒" else if (task) "任务提醒" else if (community) "匿名社区" else if (probation) "首周考察提醒" else "CustodySim 通知")
                .setContentText(if (chat) "发来一条消息" else if (checkin) "当前有待完成的打卡，请在截止前打开 App 完成" else if (task) when(payload.optString("taskEvent")) {
                    "OPEN" -> "有新任务，请打开 App 查看"
                    "CLOSING" -> "有任务即将截止，请及时完成"
                    "RETURNED" -> "任务已退回，请查看原因并重新提交"
                    "APPROVED" -> "任务已通过审核，请打开 App 查看"
                    else -> "任务状态已更新，请打开 App 查看"
                } else if (community) "你的帖子收到新评论，点击查看" else if (probation) "首周考察即将结束，考察积分须大于 0，请及时查看" else "你有一条新通知，请打开 App 查看")
                .apply { if ((checkin || task || probation) && payload.has("expiresAt")) setTimeoutAfter((java.time.Instant.parse(payload.getString("expiresAt")).toEpochMilli() - System.currentTimeMillis()).coerceAtLeast(1)) }
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE).setPublicVersion(NotificationCompat.Builder(context, channel)
                    .setSmallIcon(R.drawable.ic_notification).setContentTitle("CustodySim").setContentText("你有一条新通知").build())
                .setAutoCancel(true).setContentIntent(tap).build())
            }
        }
    }
    fun open(context: Context, payload: JSONObject) {
        NotificationStore(context).use { if (!it.accepts(payload)) return }
        context.startActivity(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP).putExtra("notification",payload.toString()))
    }
    fun cancel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.activeNotifications.filter { it.tag in listOf("chat_messages","system_notices","checkin_reminders","task_reminders","community_replies","account_reminders") }.forEach { manager.cancel(it.tag,it.id) }
    }
    fun cancelCategory(context: Context, channel: String) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.activeNotifications.filter { it.tag == channel }.forEach { manager.cancel(it.tag, it.id) }
    }
    fun cancelConversation(context: Context, conversationId: String) {
        context.getSystemService(NotificationManager::class.java).cancel("chat_messages", conversationId.hashCode())
    }
    fun cancelCommunityPost(context: Context, postId: String) {
        context.getSystemService(NotificationManager::class.java).cancel("community_replies", postId.hashCode())
    }
}
