package com.custodysim.app.data.portal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileFieldValuesTest {
    private val first = "data:image/jpeg;base64,YQ=="
    private val second = "data:image/png;base64,Yg=="

    @Test fun normalizesImageArrayAndLegacySingleImage() {
        assertEquals(listOf(first, second), normalizeProfileImages(listOf(first, second)))
        assertEquals(listOf(first), normalizeProfileImages(first))
    }

    @Test fun ignoresEmptyAndNonImageArrayEntries() {
        assertEquals(listOf(second), normalizeProfileImages(listOf("", "not an image", null, 12, second)))
        assertTrue(normalizeProfileImages("plain text").isEmpty())
        assertTrue(normalizeProfileImages(null).isEmpty())
    }

    @Test fun onlyDeclaredImageFieldsBecomeArraysInPayload() {
        val fields = listOf(
            ProfileField("附件", "IMAGE", false, emptyList()),
            ProfileField("备注", "TEXT", false, emptyList()),
            ProfileField("原文", "COPYWRITE", false, emptyList()),
        )
        val arrayLikeText = "[\"$first\",\"$second\"]"
        val payload = assembleProfileFieldValues(fields,
            mapOf("附件" to arrayLikeText, "备注" to arrayLikeText, "原文" to first),
            mapOf("附件" to listOf(first, second), "备注" to listOf(first)),
        )
        assertEquals(listOf(first, second), payload["附件"])
        assertEquals(arrayLikeText, payload["备注"])
        assertEquals(first, payload["原文"])
    }

    @Test fun removingAllImagesWritesAnEmptyArrayNotText() {
        val fields = listOf(ProfileField("附件", "IMAGE", false, emptyList()))
        val payload = assembleProfileFieldValues(fields, mapOf("附件" to "[]"), mapOf("附件" to emptyList()))
        assertEquals(emptyList<String>(), payload["附件"])
    }
}
