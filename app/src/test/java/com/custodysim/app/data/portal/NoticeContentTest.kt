package com.custodysim.app.data.portal

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class NoticeContentTest {
    private fun entry(id: String, occurredAt: String) = JSONObject().put("id", id).put("name", "测试人员")
        .put("occurredAt", occurredAt).put("score", -5).put("status", "COMPLETED")
    private fun group(kind: String, entries: JSONObject) = JSONObject().put("kind", kind)
        .put("period", "2026-10-12").put("entries", entries)

    @Test fun groupedEntriesRetainContentAndServerOrdering() {
        val values = JSONObject().put("b", entry("b", "2026-10-13T00:00:00Z").put("body", "完整正文\n第二段"))
            .put("a", entry("a", "2026-10-12T00:00:00Z").put("startAt", "2026-10-12T00:00:00Z").put("endAt", "2026-10-14T00:00:00Z"))
        val parsed = parseNoticeGroup(group("ISOLATION", values))!!
        assertEquals(listOf("a", "b"), parsed.entries.map { it.id })
        assertEquals(-5.0, parsed.entries[0].score!!, .001)
        assertEquals("COMPLETED", parsed.entries[0].status)
        assertEquals("完整正文\n第二段", parsed.entries[1].body)
        assertNotNull(parsed.entries[0].endAt)
    }
    @Test fun reflectionAndCorrectionUseTheSameCompleteContract() {
        for (kind in listOf("ISOLATION_REFLECTION", "ISOLATION_CORRECTION")) {
            val parsed = parseNoticeGroup(group(kind, JSONObject().put("a", entry("a", "2026-10-12T00:00:00Z").put("body", "原因与正文"))))!!
            assertEquals(kind, parsed.kind)
            assertEquals("原因与正文", parsed.entries.single().body)
        }
    }
    @Test fun unknownOrPartialMetadataFallsBackInsteadOfShowingAnIncompleteGroup() {
        assertNull(parseNoticeGroup(null))
        assertNull(parseNoticeGroup(group("UNKNOWN", JSONObject())))
        assertNull(parseNoticeGroup(group("ISOLATION", JSONObject())))
        assertNull(parseNoticeGroup(group("ISOLATION", JSONObject().put("a", entry("a", "2026-10-12T00:00:00Z")).put("b", JSONObject()))))
    }
    @Test fun absentScoresAreNotPresentedAsZero() {
        val item = entry("a", "2026-10-12T00:00:00Z").apply { remove("score") }
        assertNull(parseNoticeGroup(group("ISOLATION", JSONObject().put("a", item)))!!.entries.single().score)
    }
    @Test fun previewDoesNotWasteItsLinesOnEmptyParagraphs() {
        assertEquals("标题\n第一位人员\n执行期限", noticePreview("标题\n\n第一位人员\n \n执行期限\n"))
    }
}
