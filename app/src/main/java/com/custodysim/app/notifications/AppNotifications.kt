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

object AppNotifications {
    val pendingRoute = kotlinx.coroutines.flow.MutableStateFlow<JSONObject?>(null)
    @Volatile var visibleConversation: String? = null
    @Volatile private var initialized = false
    fun permitted(context: Context) = Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    fun initialize(context: Context) {
        if (initialized || BuildConfig.UMENG_APP_KEY.isBlank()) return
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper() && com.umeng.commonsdk.utils.UMUtils.isMainProgress(context)) {
            Thread({ initialize(context.applicationContext) }, "push-init").start()
            return
        }
        val store = NotificationStore(context)
        if (store.get("consent") != "yes") { store.close(); return }
        store.close()
        synchronized(this) {
            if (initialized) return
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
            agent.register(object : UPushRegisterCallback {
                override fun onSuccess(token: String) {
                    NotificationStore(context).use { it.put("providerToken", token) }
                    registerFcm(context)
                }
                override fun onFailure(code: String?, message: String?) { /* Never log credentials or SDK payloads. */ }
            })
            if (com.umeng.commonsdk.utils.UMUtils.isMainProgress(context)) agent.onAppStart()
            initialized = true
        }
    }

    @Synchronized private fun registerFcm(context: Context) {
        if (!com.umeng.commonsdk.utils.UMUtils.isMainProgress(context)) return
        NotificationStore(context).use { if (it.get("consent") != "yes") return }
        // Missing private resources (CI/debug) and devices without Play services retain U-Push.
        if (com.google.firebase.FirebaseApp.getApps(context).none { it.name == com.google.firebase.FirebaseApp.DEFAULT_APP_NAME }
            && com.google.firebase.FirebaseApp.initializeApp(context) == null) return
        if (com.google.android.gms.common.GoogleApiAvailabilityLight.getInstance()
                .isGooglePlayServicesAvailable(context) != com.google.android.gms.common.ConnectionResult.SUCCESS) return
        com.google.firebase.messaging.FirebaseMessaging.getInstance().isAutoInitEnabled = true
        org.android.agoo.fcm.FCMRegister.register(context)
    }

    @Synchronized fun pauseFcm(context: Context) {
        if (com.google.firebase.FirebaseApp.getApps(context).any { it.name == com.google.firebase.FirebaseApp.DEFAULT_APP_NAME })
            com.google.firebase.messaging.FirebaseMessaging.getInstance().isAutoInitEnabled = false
    }

    fun deliver(context: Context, payload: JSONObject): Boolean {
        NotificationStore(context).use { store ->
            if (!store.accepts(payload)) return false
            if (!permitted(context)) return false
            // Hold the DB write transaction through notify: logout clears/cancels after any in-flight rendering.
            return store.deliverOnce(payload) {
            if (payload.optString("kind") == "CHAT" && payload.optString("conversationId") == visibleConversation) return@deliverOnce
            val manager = context.getSystemService(NotificationManager::class.java)
            val chat = payload.optString("kind") == "CHAT"
            val channel = if (chat) "chat_messages" else "system_notices"
            manager.createNotificationChannel(NotificationChannel(channel, if (chat) "聊天消息" else "系统通知", NotificationManager.IMPORTANCE_DEFAULT))
            val intent = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra("notification",payload.toString())
            val id = (if (chat) payload.optString("conversationId") else payload.optString("entityId")).hashCode()
            val tap = PendingIntent.getActivity(context, id, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            manager.notify(channel, id, NotificationCompat.Builder(context,channel)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(if (chat) payload.optString("senderName").take(60) else "CustodySim 通知")
                .setContentText(if (chat) "发来一条消息" else "你有一条新通知，请打开 App 查看")
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
        manager.activeNotifications.filter { it.tag in listOf("chat_messages","system_notices") }.forEach { manager.cancel(it.tag,it.id) }
    }
}
