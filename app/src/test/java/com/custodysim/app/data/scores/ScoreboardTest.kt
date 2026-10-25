package com.custodysim.app.data.scores

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ScoreboardTest {
    @Test fun preservesServerMaskingNegativeTotalsAndDetailPermissions() {
        val board = Scoreboard.from(JSONObject("""{
          "selectedWeek":"2026-10-19","currentWeek":"2026-10-19","weeks":["2026-10-19"],
          "people":[{"id":"other","name":"张**","currentScore":-6,"breakdown":{"CHECKIN_DAILY":-3,"TASK_OUTCOME":-3},
            "canViewDetails":false,"activeIsolation":null,"weeklyReview":{"result":"CLEAR"},
            "events":[{"id":"hidden","points":-3,"reason":"must not display","source":"TASK_OUTCOME","createdAt":"2026-10-20T00:00:00Z"}]}]}
        """))
        assertEquals("张**", board.people.single().name)
        assertEquals(-6, board.people.single().total)
        assertEquals(-3, board.people.single().breakdown["CHECKIN_DAILY"])
        assertTrue(board.people.single().events.isEmpty())
        assertEquals("CLEAR", board.people.single().weeklyResult)
        assertNull(board.people.single().isolationEnd)
    }

    @Test fun policyTextFollowsServerValuesAndReadingSettings() {
        val policy = JSONObject("""{
          "checkinCompleted":2,"checkinMakeup":-4,"checkinMissing":-5,
          "taskFirstPass":7,"taskReturnedThenPass":4,"taskExpired":-8,
          "isolationThreshold":-1,"isolationMinAttendanceRatio":0.6,"isolationDurationDays":4,
          "effectiveDay":"2026-10-25","weeklyReviewDelayMinutes":15,
          "reading":{"enabled":true,"minutesPerPoint":20,"dailyCap":3}}
        """)
        val text = scoreRules(policy).joinToString { it.second }
        assertTrue(text.contains("完成 +2"))
        assertTrue(text.contains("补卡通过 -4"))
        assertTrue(text.contains("首次按时通过 +7"))
        assertTrue(text.contains("20 分钟"))
        assertTrue(text.contains("最多 3 分"))
        assertTrue(text.contains("低于 -1"))
        assertTrue(text.contains("60%"))
        policy.getJSONObject("reading").put("enabled", false)
        assertTrue(scoreRules(policy).single { it.first == "阅读计分" }.second.contains("已关闭"))
    }

    @Test fun olderServerDoesNotInventRules() {
        assertTrue(scoreRules(null).single().second.contains("暂不可用"))
    }
}
