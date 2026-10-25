package com.custodysim.app.data.scores

import com.custodysim.app.data.net.ApiClient
import com.custodysim.app.data.net.ApiResult
import org.json.JSONObject

data class ScoreEvent(val id: String, val points: Int, val reason: String, val source: String, val createdAt: String)
data class PersonScore(val id: String, val name: String, val total: Int, val breakdown: Map<String, Int>,
    val isolationEnd: String?, val weeklyResult: String?, val canViewDetails: Boolean, val events: List<ScoreEvent>)
data class Scoreboard(val selectedWeek: String, val currentWeek: String, val weeks: List<String>,
    val people: List<PersonScore>, val rules: List<Pair<String, String>>) {
    companion object {
        fun from(json: JSONObject): Scoreboard {
            val people = json.getJSONArray("people")
            val weeks = json.getJSONArray("weeks")
            return Scoreboard(json.getString("selectedWeek"), json.getString("currentWeek"),
                (0 until weeks.length()).map { weeks.getString(it) },
                (0 until people.length()).map { index ->
                    val person = people.getJSONObject(index)
                    val breakdown = person.optJSONObject("breakdown") ?: JSONObject()
                    val canView = person.optBoolean("canViewDetails")
                    val events = person.getJSONArray("events")
                    PersonScore(person.getString("id"), person.getString("name"), person.getInt("currentScore"),
                        breakdown.keys().asSequence().associateWith { breakdown.getInt(it) },
                        person.optJSONObject("activeIsolation")?.getString("endAt"),
                        person.optJSONObject("weeklyReview")?.getString("result"), canView,
                        if (!canView) emptyList() else (0 until events.length()).map { i ->
                            val event = events.getJSONObject(i)
                            ScoreEvent(event.getString("id"), event.getInt("points"), event.getString("reason"), event.getString("source"), event.getString("createdAt"))
                        })
                }, scoreRules(json.optJSONObject("policy")))
        }
    }
}

fun signedPoints(points: Int): String = if (points > 0) "+$points" else points.toString()
fun scoreSource(source: String) = when(source) {
    "CHECKIN_DAILY" -> "打卡日结"
    "TASK_OUTCOME" -> "任务结果"
    "READING_DURATION" -> "图书馆阅读"
    "MANUAL" -> "人工调整"
    else -> source
}

/** All numeric rules come from the same server policy used for settlement. */
fun scoreRules(policy: JSONObject?): List<Pair<String, String>> {
    if (policy == null) return listOf("积分规则" to "规则暂不可用，请稍后刷新。")
    val reading = policy.getJSONObject("reading")
    val ratio = (policy.getDouble("isolationMinAttendanceRatio") * 100).toInt()
    val threshold = policy.getInt("isolationThreshold")
    return listOf(
        "打卡计分" to "每时段完成 ${signedPoints(policy.getInt("checkinCompleted"))}，补卡通过 ${signedPoints(policy.getInt("checkinMakeup"))}，缺卡 ${signedPoints(policy.getInt("checkinMissing"))}。日结可为负，系统补卡不计分。按时段计分自 ${policy.getString("effectiveDay")} 生效，之前日结保留原口径。",
        "任务计分" to "首次按时通过 ${signedPoints(policy.getInt("taskFirstPass"))}；退回后按时通过 ${signedPoints(policy.getInt("taskReturnedThenPass"))}；截止仍未通过 ${signedPoints(policy.getInt("taskExpired"))}。任务评分不影响积分。",
        "阅读计分" to if (reading.getBoolean("enabled")) "有效阅读每 ${reading.getInt("minutesPerPoint")} 分钟 1 分，每日最多 ${reading.getInt("dailyCap")} 分，按上海自然日结算；心跳间隔超过 25 秒不计入有效时长。" else "当前已关闭阅读积分。",
        "周结与禁闭" to "周一 00:00 截止，等待 ${policy.getInt("weeklyReviewDelayMinutes")} 分钟后周结。周积分低于 $threshold 且出勤率（完成与补卡通过）低于 $ratio% 时，进入 ${policy.getInt("isolationDurationDays")} 天禁闭；该周没有打卡排班时仅按积分判定。",
        "请假" to "请假期间系统补卡与自动通过任务不参与行为积分。"
    )
}

class ScoreRepository(private val api: ApiClient) {
    suspend fun load(week: String?): ApiResult<Scoreboard> = when(val result = api.get("/api/scores" + (week?.let { "?week=$it" } ?: ""))) {
        is ApiResult.Err -> result
        is ApiResult.Ok -> ApiResult.Ok(Scoreboard.from(result.data))
    }
    suspend fun adjust(userId: String, points: Int, reason: String): ApiResult<JSONObject> =
        api.post("/api/scores", JSONObject().put("supervisedId",userId).put("points",points).put("reason",reason))
}
