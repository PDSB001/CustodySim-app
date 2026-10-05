package com.custodysim.app.data.portal

import org.json.JSONArray
import org.json.JSONObject

internal const val PROFILE_IMAGE_MAX_COUNT = 3

private val profileImagePrefix = Regex("^data:image/(jpeg|png|webp);base64,")

/** Array values and legacy single images share one representation. Never accept plain text as an image. */
internal fun normalizeProfileImages(value: Any?): List<String> {
    val candidates = when (value) {
        is String -> listOf(value)
        is List<*> -> value.filterIsInstance<String>()
        else -> emptyList()
    }
    return candidates.filter { profileImagePrefix.containsMatchIn(it) }
}

/** Only callers handling a declared IMAGE field should decode the JSON cache. */
internal fun decodeProfileImages(value: Any?): List<String> {
    val array = when (value) {
        is JSONArray -> value
        is String -> if (value.trimStart().startsWith("[")) runCatching { JSONArray(value) }.getOrNull() else null
        else -> null
    }
    return normalizeProfileImages(if (array != null) {
        (0 until array.length()).map { array.opt(it) }
    } else value)
}

/** String-based drafts store IMAGE arrays as JSON; scalar field contents remain verbatim. */
internal fun cacheProfileFieldValue(fieldType: String, value: Any?): String = when {
    fieldType == "IMAGE" -> JSONArray(decodeProfileImages(value)).toString()
    value == null || value == JSONObject.NULL -> ""
    else -> value.toString()
}

/** Build typed values using the actual form definition, not the appearance of a text value. */
internal fun assembleProfileFieldValues(
    fields: List<ProfileField>, values: Map<String, String>, images: Map<String, List<String>>,
): Map<String, Any> {
    val imageNames = fields.filter { it.type == "IMAGE" }.map { it.name }.toSet()
    return values.mapValues { (name, value) ->
        if (name in imageNames) images[name].orEmpty() else value
    }
}

internal fun profileFieldsPayload(fields: List<ProfileField>, values: Map<String, String>): JSONObject {
    val images = fields.filter { it.type == "IMAGE" }.associate { it.name to decodeProfileImages(values[it.name]) }
    return JSONObject().apply {
        assembleProfileFieldValues(fields, values, images).forEach { (name, value) ->
            put(name, if (value is List<*>) JSONArray(value) else value)
        }
    }
}
