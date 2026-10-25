package com.custodysim.app.notifications

import androidx.core.database.sqlite.transaction
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONObject

/** SQLite keeps callback processes and the Activity on the same binding/dedup state. */
class NotificationStore(context: Context) : SQLiteOpenHelper(context, "notifications.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE settings (key TEXT PRIMARY KEY, value TEXT NOT NULL)")
        db.execSQL("CREATE TABLE seen (id TEXT PRIMARY KEY, at INTEGER NOT NULL)")
    }
    override fun onUpgrade(db: SQLiteDatabase, old: Int, new: Int) = Unit
    fun get(key: String): String? = readableDatabase.rawQuery("SELECT value FROM settings WHERE key=?", arrayOf(key)).use { if (it.moveToFirst()) it.getString(0) else null }
    fun put(key: String, value: String) { writableDatabase.execSQL("INSERT OR REPLACE INTO settings VALUES (?,?)", arrayOf(key,value)) }
    fun clearBinding() { put("binding", "") }
    fun accepts(payload: JSONObject): Boolean {
        if (get("consent") != "yes") return false
        val binding = runCatching { JSONObject(get("binding").orEmpty()) }.getOrNull() ?: return false
        return listOf("deviceId", "bindingId", "userId").all { binding.optString(it).isNotBlank() && binding.optString(it) == payload.optString(it) }
    }
    fun deliverOnce(payload: JSONObject, action: () -> Unit): Boolean {
        val db = writableDatabase
        return db.transaction {
            if (!accepts(payload) || payload.optString("deliveryId").isBlank()) return@transaction false
            val values = android.content.ContentValues().apply { put("id",payload.getString("deliveryId")); put("at",System.currentTimeMillis()) }
            val inserted = db.insertWithOnConflict("seen", null, values, SQLiteDatabase.CONFLICT_IGNORE) != -1L
            db.delete("seen", "at<?", arrayOf((System.currentTimeMillis()-8*86400_000L).toString()))
            if (inserted) action()
            true
        }
    }
}
