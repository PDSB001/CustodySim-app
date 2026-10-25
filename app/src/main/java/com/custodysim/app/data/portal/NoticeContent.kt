package com.custodysim.app.data.portal

import org.json.JSONObject

data class NoticeEntry(
    val id: String, val name: String, val score: Double?, val startAt: String?, val endAt: String?,
    val status: String?, val body: String, val occurredAt: String,
)

data class NoticeGroup(val kind: String, val period: String, val entries: List<NoticeEntry>)

/** Unknown or incomplete metadata falls back to the authoritative original text. */
internal fun parseNoticeGroup(metadata: JSONObject?): NoticeGroup? {
    metadata ?: return null
    val kind = metadata.optString("kind")
    if (kind !in setOf("ISOLATION", "ISOLATION_CORRECTION", "ISOLATION_REFLECTION")) return null
    val period = metadata.optString("period").takeIf { it.isNotBlank() } ?: return null
    val values = metadata.optJSONObject("entries") ?: return null
    fun JSONObject.optional(key: String) = if (isNull(key)) null else optString(key).takeIf { it.isNotBlank() }
    val entries = values.keys().asSequence().toList().map { key ->
        val item = values.optJSONObject(key) ?: return null
        val id = item.optional("id") ?: return null
        val name = item.optional("name") ?: return null
        val occurredAt = item.optional("occurredAt") ?: return null
        NoticeEntry(id, name, if (item.isNull("score")) null else item.optDouble("score").takeIf { it.isFinite() },
            item.optional("startAt"), item.optional("endAt"), item.optional("status"), item.optional("body").orEmpty(), occurredAt)
    }.toList().sortedWith(compareBy<NoticeEntry> { it.occurredAt }.thenBy { it.id })
    if (entries.isEmpty()) return null
    return NoticeGroup(kind, period, entries)
}

internal fun noticePreview(content: String): String = content.trim().replace(Regex("\\n[\\t \\r]*\\n+"), "\n")
