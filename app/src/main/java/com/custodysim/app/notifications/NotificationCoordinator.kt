package com.custodysim.app.notifications

import android.content.Context
import com.custodysim.app.data.net.ApiClient
import com.custodysim.app.data.net.ApiResult
import kotlinx.coroutines.*
import okhttp3.*
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.security.SecureRandom

/** Foreground only. Background delivery belongs to U-Push rather than an App service. */
class NotificationCoordinator(private val context: Context, private val api: ApiClient, private val namespace: String, private val realtimeUrl: String) {
    private val store = NotificationStore(context)
    private val client = OkHttpClient()
    @Volatile private var socket: WebSocket? = null
    @Volatile private var closed = false
    @Volatile private var sessionActive = false
    private val generation = java.util.concurrent.atomic.AtomicLong()
    private fun credentials(): JSONObject {
        synchronized(NotificationCoordinator::class.java) {
            val key = "installation:$namespace"
            val saved = store.get(key)
            if (saved != null) return JSONObject(saved)
            val bytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
            val value = JSONObject().put("installationId",UUID.randomUUID().toString()).put("secret",bytes.joinToString("") { "%02x".format(it.toInt() and 255) })
            store.put(key,value.toString())
            return value
        }
    }
    suspend fun foreground(userId: String): Nothing = withContext(Dispatchers.IO) {
        sessionActive = true
        try {
            while (isActive && !closed && sessionActive) {
                if (store.get("consent") != "yes" || !AppNotifications.permitted(context)) { socket?.cancel(); delay(2000); continue }
                AppNotifications.initialize(context)
                val epoch = generation.get()
                val input = credentials().put("enabled",true).put("providerToken",store.get("providerToken") ?: JSONObject.NULL)
                val registered = api.post("/api/mobile/push/devices",input)
                if (registered !is ApiResult.Ok) { delay(15_000); continue }
                val device = registered.data
                synchronized(this@NotificationCoordinator) {
                    if (epoch == generation.get() && sessionActive && !closed)
                        store.put("binding",JSONObject().put("deviceId",device.getString("id")).put("bindingId",device.getString("bindingId")).put("userId",userId).toString())
                }
                if (epoch != generation.get() || !sessionActive || closed) continue
                val result = api.post("/api/mobile/push/realtime-token", JSONObject().put("deviceId",device.getString("id")).put("secret",input.getString("secret")))
                if (result !is ApiResult.Ok || realtimeUrl.isBlank()) { delay(15_000); continue }
                val done = CompletableDeferred<Unit>()
                val ws = client.newWebSocket(Request.Builder().url(realtimeUrl.trimEnd('/')+"/socket.io/?EIO=4&transport=websocket").build(),object: WebSocketListener() {
                    override fun onMessage(webSocket: WebSocket, text: String) {
                        try {
                            when {
                                text.startsWith("0") -> webSocket.send("40"+JSONObject().put("token",result.data.getString("token")))
                                text.startsWith("2") -> webSocket.send("3"+text.drop(1))
                                text.startsWith("42") -> {
                                    val jsonAt = text.indexOf('[')
                                    if (jsonAt < 0) return
                                    val event = JSONArray(text.substring(jsonAt))
                                    if (event.optString(0) != "notification:delivery") return
                                    val ok = !closed && AppNotifications.deliver(context,event.getJSONObject(1))
                                    val ack = text.substring(2,jsonAt)
                                    if (ack.isNotEmpty() && ack.all(Char::isDigit)) webSocket.send("43"+ack+JSONArray().put(JSONObject().put("ok",ok)))
                                }
                                text.startsWith("41") || text.startsWith("44") -> webSocket.cancel()
                            }
                        } catch (_: Exception) { webSocket.cancel() }
                    }
                    override fun onFailure(webSocket: WebSocket,t: Throwable,response: Response?) { done.complete(Unit) }
                    override fun onClosed(webSocket: WebSocket,code: Int,reason: String) { done.complete(Unit) }
                })
                socket = ws
                try {
                    coroutineScope {
                        val tokenWatcher = launch {
                            while (isActive) {
                                delay(2000)
                                if (store.get("providerToken") != input.optString("providerToken").takeIf { it != "null" }) { done.complete(Unit); break }
                            }
                        }
                        try { withTimeoutOrNull(240_000) { done.await() } } finally { tokenWatcher.cancel() }
                    }
                } finally { ws.cancel(); socket = null }
                delay(1500)
            }
            awaitCancellation()
        } finally { socket?.cancel(); socket = null }
    }
    @Synchronized fun clearLocal(stopSession: Boolean = true) {
        generation.incrementAndGet()
        if (stopSession) sessionActive = false
        store.clearBinding(); socket?.cancel(); AppNotifications.cancel(context)
        AppNotifications.pendingRoute.value = null
    }
    suspend fun unregister(stopSession: Boolean = true) {
        if (!stopSession) AppNotifications.pauseFcm(context)
        val wasBound = !store.get("binding").isNullOrBlank()
        clearLocal(stopSession)
        if (!wasBound) return
        withContext(NonCancellable + Dispatchers.IO) { withTimeoutOrNull(5000) { api.post("/api/mobile/push/devices",credentials().put("enabled",false)) } }
    }
    fun dispose() { closed = true; clearLocal(); client.dispatcher.cancelAll(); client.connectionPool.evictAll(); store.close() }
}
