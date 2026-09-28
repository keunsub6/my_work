package com.anbudream.carecall.health

import android.content.Context

/**
 * 실패를 호출자에게 전파하지 않는 독립 전송기. 다음 주기 작업에서 자동 재시도합니다.
 *
 * [carecall 이식 변경점]
 *  - 전송 주소를 Config 상수 대신 HealthPushConfig(푸시로 받은 검증된 URL)에서 읽습니다.
 *  - HTTP 처리를 HealthHttp로 분리했습니다(기존 Retrofit/ApiClient는 건드리지 않습니다).
 *  - 서버 푸시로 특정 날짜를 지정해 올릴 수 있는 uploadNow()를 추가했습니다.
 */
internal object HealthMetricsUploader {

    /** 마지막으로 업로드가 막힌 이유. 실패 보고에 실어 서버 로그에서 보이게 합니다. */
    @Volatile
    var lastBlockReason: String? = null
        private set


    /** 30분 주기 작업이 끝날 때마다 호출되는 기존 경로. 미전송분을 순서대로 올립니다. */
    fun uploadDue(context: Context) {
        val appCtx = context.applicationContext
        if (!HealthFeature.isSupported) return
        if (!RegistrationClient.isRegistrationEnabled(appCtx)) return
        val token = CareTokenVerifier.savedFcmToken(appCtx)?.takeIf { it.isNotBlank() } ?: return
        val url = HealthPushConfig.uploadUrl(appCtx)

        for (date in HealthMetricsStore.datesDue(appCtx)) {
            if (send(appCtx, token, url, date)) continue
            /* [패치 #6b] 원래는 한 건 실패면 무조건 중단이었습니다(다음 주기 재시도, 순서 보존).
               그런데 자료 결함(payload_exception 등 하루 단위 문제)은 큐가 오래된 순이라
               선두에서 뒤 날짜 전부를 볼모로 잡았습니다 — 오염된 8/6이 8/7 이후를 막은 사고.
               네트워크·서버 실패만 순서 보존을 위해 중단하고, 하루 단위 결함은 건너뜁니다. */
            val block = lastBlockReason
            if (block == null || block == "network_error" || block.startsWith("http_")) break
        }
    }

    /**
     * 서버 수집 트리거 푸시 전용. 전송 간격/dirty 조건을 무시하고 지정 날짜를 즉시 올립니다.
     * @return 실제로 서버가 받아준 경우에만 true.
     */
    fun uploadNow(context: Context, date: String?): Boolean {
        val appCtx = context.applicationContext
        if (!HealthFeature.isSupported) return false
        if (!RegistrationClient.isRegistrationEnabled(appCtx)) return false
        if (date.isNullOrBlank()) return false
        val token = CareTokenVerifier.savedFcmToken(appCtx)?.takeIf { it.isNotBlank() } ?: return false
        return send(appCtx, token, HealthPushConfig.uploadUrl(appCtx), date)
    }

    private fun send(appCtx: Context, token: String, url: String, date: String): Boolean {
        lastBlockReason = null
        /* [추가] 오늘 표본이 하나도 없으면 빈 하루 기록을 만들어 둡니다.
           그래야 '앱이 죽음' 과 '표본 0개' 가 서버에서 구분됩니다. 과거 날짜는 만들지 않습니다. */
        runCatching { HealthMetricsStore.ensureDayForToday(appCtx, date) }
        /* [패치 #1] buildPayload 가 던지면(예: 저장값 NaN 직렬화 → JSONException) 예외가
           그대로 새어 lastBlockReason 이 null 로 남았고, 호출부는 그 null 을
           no_metrics_for_date 로 뭉뚱그렸습니다. 진짜 원인이 로그 어디에도 없었던
           이유입니다. 여기서 잡아 사유 문자열로 만들어 보고에 싣습니다. */
        val payload = runCatching { HealthMetricsStore.buildPayload(appCtx, date) }
            .onFailure { e ->
                lastBlockReason = "payload_exception." + e.javaClass.simpleName
                DebugLog.event(appCtx, "건강지표 payload 예외 date=$date ${e.javaClass.simpleName}: ${e.message}")
            }
            .getOrNull()
        if (payload == null) {
            if (lastBlockReason != null) return false   // 예외 경로 — 사유는 위에서 확정됨
            // [수정] 예전에는 여기서 조용히 false 를 돌려줘, 업로드가 통째로 멈춰도
            // 아무 흔적이 남지 않았습니다. 침묵이 진단을 막은 지점입니다.
            val why = runCatching { HealthMetricsStore.payloadBlockReason(appCtx, date) }
                .getOrDefault("unknown") ?: "unknown"
            DebugLog.event(appCtx, "건강지표 전송 불가 date=$date why=$why")
            lastBlockReason = why
            return false
        }
        val code = HealthHttp.postJson(url, token, payload.toString())
        return if (code in 200..299) {
            HealthMetricsStore.markUploaded(appCtx, date)
            DebugLog.event(appCtx, "건강지표 전송 완료 date=$date")
            true
        } else {
            lastBlockReason = if (code < 0) "network_error" else "http_$code"
            DebugLog.event(appCtx, "건강지표 전송 보류 date=$date code=$code")
            false
        }
    }
}
