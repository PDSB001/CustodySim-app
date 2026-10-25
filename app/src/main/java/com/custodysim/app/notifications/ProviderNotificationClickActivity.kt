package com.custodysim.app.notifications

import android.content.Intent
import com.umeng.message.UmengNotifyClickActivity
import org.android.agoo.common.AgooConstants
import org.json.JSONObject

/** Receives notification taps from Huawei's system-owned offline notification. */
class ProviderNotificationClickActivity : UmengNotifyClickActivity() {
    override fun onMessage(intent: Intent?) {
        super.onMessage(intent)
        val payload = runCatching {
            val body = intent?.getStringExtra(AgooConstants.MESSAGE_BODY)
                ?: return@runCatching null
            val envelope = JSONObject(body)
            val extra = envelope.opt("extra")
            when (extra) {
                null -> null
                is JSONObject -> extra
                is String -> JSONObject(extra)
                else -> null
            }
        }.getOrNull()
        if (payload != null) AppNotifications.open(this, payload)
        finish()
    }
}
