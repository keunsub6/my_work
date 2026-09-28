package com.anbudream.carecall.health

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * server.js 의 POST /health/collect/issue 대응.
 *
 * 수집이 아예 불가능한 상황(권한 거부·센서 없음·데이터 없음)에서는 업로드할 payload 자체가
 * 없으므로, 서버는 "아직 안 올라온 것"과 "못 올리는 것"을 구분할 수 없습니다.
 * 그 사유만 따로 보고해 해당 날짜 파일의 quality.missingReasons 에 병합되게 합니다.
 *
 * 실패는 삼킵니다. 이 보고가 안 돼도 기존 기능과 수집 자체에는 영향이 없습니다.
 */
internal object HealthIssueReporter {

    fun report(context: Context, date: String?, reasons: List<String>, detail: String? = null) {
        if (date.isNullOrBlank() || reasons.isEmpty()) return
        val appCtx = context.applicationContext
        if (!RegistrationClient.isRegistrationEnabled(appCtx)) return
        val token = CareTokenVerifier.savedFcmToken(appCtx)?.takeIf { it.isNotBlank() } ?: return

        val body = runCatching {
            JSONObject().apply {
                put("token", token)
                put("date", date)
                put("reasons", JSONArray().apply { reasons.forEach { put(it) } })
                if (!detail.isNullOrBlank()) put("detail", detail.take(64))
            }.toString()
        }.getOrNull() ?: return

        val code = HealthHttp.postJson(HealthPushConfig.issueUrl(appCtx), token, body)
        DebugLog.event(appCtx, "건강지표 사유 보고 date=$date code=$code reasons=$reasons")
    }
}
