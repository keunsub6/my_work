package com.anbudream.carecall.health

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.anbudream.carecall.time.AppTime
import java.util.concurrent.TimeUnit

/**
 * server.js 가 보내는 data-only 푸시(type=health_collect)를 처리합니다.
 *
 * FCM 콜백에서 직접 네트워크를 타지 않고 WorkManager 로 위임합니다
 * (server.js 주석 ③이 기대하는 구조이며, 기존 TokenRefreshWorker 와 같은 패턴입니다).
 *
 * 30분 주기 수집은 그대로 유지되므로, 이 워커는 "지금 올려라" 트리거 역할만 합니다.
 */
class HealthCollectWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val ctx = applicationContext
        val requestedDate = inputData.getString(KEY_DATE)
        val date = requestedDate?.takeIf(::isValidDate) ?: today()
        val requestedAt = AppTime.parseEpochMs(inputData.getString(KEY_REQUESTED_AT))
        if (requestedDate != null && requestedDate != date) {
            DebugLog.event(ctx, "건강수집 날짜 형식 오류 requested=$requestedDate fallback=$date")
        }

        // 서버가 알려준 업로드/보고 주소를 먼저 반영합니다(호스트 검증은 내부에서).
        HealthPushConfig.remember(
            ctx,
            inputData.getString(KEY_UPLOAD_URL),
            inputData.getString(KEY_ISSUE_URL),
        )

        if (!RegistrationClient.isRegistrationEnabled(ctx)) return@withContext Result.success()

        if (!HealthFeature.isSupported) {
            HealthIssueReporter.report(ctx, date, listOf("unsupported_os_version"))
            return@withContext Result.success()
        }

        // 권한이 있으면 주기 작업/활동인식 등록을 되살립니다(이미 살아있으면 no-op).
        runCatching { HealthSensorCoordinator.initialize(ctx) }

        val reasons = mutableListOf<String>()
        if (!HealthSensorCoordinator.hasActivityPermission(ctx)) {
            reasons += "activity_permission_denied"
        }

        /* [수정] 푸시를 30분 잡의 백업 경로로 씁니다.
         *
         * 예전에는 initialize() 로 잡을 되살리기만 하고 바로 업로드를 시도했습니다.
         * 그런데 잡이 '예약된 채 안 도는' 상태면 initialize() 가 아무 일도 하지 않아,
         * 오늘 표본이 하나도 없는 채로 no_metrics_for_date 만 반복 보고했습니다.
         * (recordStepCounter 는 맨 처음에 하루 기록을 만들므로, 표본이 한 번이라도
         *  있었다면 업로드는 성공합니다. 즉 실패는 곧 '샘플링이 안 돌았다'는 뜻입니다.)
         *
         * 이제 오늘 요청이면 잡을 기다리지 않고 직접 한 번 읽습니다. 잡이 죽어 있어도
         * 하루 기록이 생겨서 서버가 '앱이 죽음'과 '표본이 모자람'을 구분할 수 있습니다.
         */
        val jobScheduled = runCatching { HealthSamplingJobService.isScheduled(ctx) }.getOrDefault(false)
        val staleMs = runCatching { HealthMetricsStore.msSinceLastStepSample(ctx) }
            .getOrDefault(Long.MAX_VALUE)

        if (date == today()) {
            runCatching { HealthSamplingJobService.sampleOnce(ctx) }
        }
        // 오래 멈춰 있었다면 예약을 갈아끼웁니다(pending 이어도 강제).
        if (staleMs > STALE_SAMPLE_MS) {
            runCatching { HealthSamplingJobService.schedule(ctx, force = true) }
        }

        val uploaded = runCatching { HealthMetricsUploader.uploadNow(ctx, date) }.getOrDefault(false)
        // [패치 #6a] 푸시 날짜의 실패 사유를 먼저 확보합니다 — 아래 uploadDue 플러시가
        // lastBlockReason 을 다른 날짜의 값으로 덮기 때문입니다.
        val block = HealthMetricsUploader.lastBlockReason

        /* [2단계] 서버 결측 날짜 강제 재업로드.
           플러시(uploadDue)는 앱이 dirty 라고 아는 날만 나른다. 그런데 서버에는 없는데
           앱은 clean 이라 믿는 날이 있다 — 재설치, 서버 유실, 부분본으로 굳은 채 새 표본이
           안 들어온 날. 서버가 푸시 data.dates 에 실어 보낸 결측 목록을 dirty 와 무관하게
           다시 올린다(uploadNow 는 dirty 를 보지 않는다). 성공하면 markUploaded 로 clean
           이 되므로 뒤의 플러시가 같은 날짜를 중복 전송하지 않는다.
           실패 정책은 uploadDue(#6b)와 동일: 네트워크·서버 실패는 중단(뒤 날짜도 어차피
           실패), 하루 단위 결함(payload_exception 등)은 건너뛴다. 기기에 자료가 아예 없는
           과거 날짜는 backfill_no_data 로 날짜별 보고(최대 3건)해 서버가 그 날짜를
           '결측 확정'으로 닫고 다시 묻지 않게 한다. */
        val backfillDates = parseBackfillDates(inputData.getString(KEY_DATES), today())
        var noDataReported = 0
        for (d in backfillDates) {
            if (d == date) continue   // 푸시 본 날짜는 위에서 이미 시도함
            val okUp = runCatching { HealthMetricsUploader.uploadNow(ctx, d) }.getOrDefault(false)
            if (okUp) continue
            val why = HealthMetricsUploader.lastBlockReason
            when {
                why == null || why == "network_error" || why.startsWith("http_") -> break
                (why.startsWith("no_") || why == "bad_date") && d < today() -> {
                    if (noDataReported < 3) {
                        noDataReported++
                        runCatching {
                            HealthIssueReporter.report(
                                ctx, d, listOf("backfill_no_data"),
                                "why." + why.replace(Regex("[^0-9a-zA-Z._-]"), "."),
                            )
                        }
                    }
                }
                else -> Unit
            }
        }

        /* [패치 #6a] 전날 마감본 플러시.
           어제를 서버로 나르는 운반체는 지금까지 30분 잡의 uploadDue 하나뿐이었습니다.
           아침 통화 푸시가 하루 1회뿐인 운영에서 그 잡이 절전으로 죽으면, 푸시가 와도
           uploadNow(오늘)만 돌아 어제 마감본이 영영 올라가지 않았습니다. 밀린 날짜
           (어제 포함, 오래된 순 최대 5일)를 여기서 함께 올립니다. 오늘은 방금
           markUploaded 되어 datesDue 의 6시간 조건에 걸리므로 중복 전송되지 않습니다. */
        runCatching { HealthMetricsUploader.uploadDue(ctx) }

        if (!uploaded) {
            reasons += when {
                // [패치 #2] 예외를 no_metrics_for_date 로 뭉개지 않습니다. 이 라벨 하나가
                // '자료 결함'을 '데이터 없음'으로 보이게 해 진단이 하루를 돌았습니다.
                block != null && block.startsWith("payload_exception") -> "upload_exception"
                block == null -> "upload_not_attempted"   // send() 도달 전 조기 반환(토큰 부재 등)
                block.startsWith("no_") || block == "bad_date" || block == "unknown" -> "no_metrics_for_date"
                else -> "upload_failed"
            }
            // 왜 없는지를 함께 보냅니다. 예전에는 이유가 하나뿐이라 서버가 앱 사망·절전
            // 제한·단순 표본 부족을 구분할 수 없었습니다. 전부 무권한 조회입니다.
            if (!jobScheduled) reasons += "sampling_job_not_scheduled"
            if (staleMs > STALE_SAMPLE_MS) reasons += "sampling_stalled"
        }

        if (reasons.isNotEmpty()) {
            runCatching { HealthIssueReporter.report(ctx, date, reasons, diagnostics(ctx, date, jobScheduled, staleMs, requestedAt, block)) }
        }
        Result.success()
    }

    /**
     * [추가] 서버 로그 한 줄로 원인이 보이도록 진단을 실어 보냅니다.
     *
     * DebugLog 는 `if (BuildConfig.DEBUG)` 라 release 빌드에서 전부 사라집니다. 그래서
     * '건강지표 전송 보류 code=...' 같은 결정적 단서를 운영에서 볼 방법이 없었습니다.
     * 서버가 이미 detail(64자)을 받아 로그에 찍으므로 그 자리에 담습니다.
     *
     * 개인정보는 담지 않습니다 — 예약 여부, 경과 분, 표본 수, 기기 로컬 날짜뿐입니다.
     * 기기 로컬 날짜를 넣는 이유: 서버가 요청한 날짜와 앱이 기록하는 날짜가 어긋나면
     * 하루 기록을 영영 못 찾는데, 그 경우를 이 한 값으로 바로 확인할 수 있습니다.
     */
    private fun diagnostics(
        ctx: Context,
        date: String,
        scheduled: Boolean,
        staleMs: Long,
        requestedAt: Long?,
        why: String?,   // [패치 #6a] uploadDue 플러시가 lastBlockReason 을 덮으므로 호출부에서 캡처해 전달
    ): String {
        val stale = if (staleMs == Long.MAX_VALUE) "never" else (staleMs / 60_000L).toString() + "m"
        val samples = runCatching { HealthMetricsStore.stepSampleCountOf(ctx, date) }.getOrDefault(-1)
        // 업로드가 막힌 이유를 맨 앞에 둡니다 — 길이 제한에서 잘려도 이건 살아남아야 합니다.

        /* [수정] 이전에는 요청 날짜와 기기 날짜가 다르면 tz!= 를 붙였는데, 과거 날짜를
           요청하면 당연히 달라서 항상 떴습니다. 시간대 문제로 오해하기 딱 좋았습니다.
           기기 날짜는 표시만 하고, 어긋남 판정은 서버가 하도록 둡니다. */
        val dev = today()
        val requestAge = requestedAt?.let { ((System.currentTimeMillis() - it) / 60_000L).coerceAtLeast(0L) }

        /* 구분자를 . 과 _ 로 씁니다. 서버의 옛 정리기(healthSafe)가 = , ! 를 지워
           'job=1,last=10m' 이 'job1last10m' 으로 뭉개졌습니다. 서버를 고쳤지만,
           구버전 서버에 붙어도 읽히도록 살아남는 글자만 씁니다. */
        return (if (why != null) "why." + why.replace(Regex("[^0-9a-zA-Z._-]"), ".") + "_" else "") +
            "job." + (if (scheduled) "1" else "0") +
            "_last." + stale +
            "_n." + samples +
            "_dev." + dev +
            (requestAge?.let { "_req." + it + "m" } ?: "")
    }

    companion object {
        const val PUSH_TYPE = "health_collect"

        private const val KEY_DATE = "date"
        private const val KEY_UPLOAD_URL = "uploadUrl"
        private const val KEY_ISSUE_URL = "issueUrl"
        private const val KEY_REQUESTED_AT = "requestedAt"
        private const val KEY_CID = "cid"
        private const val KEY_DATES = "dates"   // [2단계] 서버가 계산한 결측 날짜(JSON 배열)
        private const val KEY_PUSH_SCHEMA = "schemaVersion"
        private const val UNIQUE_WORK = "health-collect"
        /** 이보다 오래 표본이 없으면 잡이 예약만 된 채 안 도는 것으로 봅니다. */
        private const val STALE_SAMPLE_MS = 2L * 60L * 60L * 1000L
        private val DATE_RE = Regex("^\\d{4}-\\d{2}-\\d{2}$")

        /** 푸시에 date가 없거나 형식이 틀린 경우의 안전한 KST 대체값. */
        private fun today(): String = AppTime.kstDate()

        private fun isValidDate(value: String): Boolean =
            DATE_RE.matches(value) && AppTime.isValidDate(value)

        /** [2단계] 서버 결측 목록 파싱. JSON 배열 우선, 못 읽으면 CSV 로 관용 처리.
         *  오늘 제외·형식 검증·중복 제거·오래된 순·최대 20(앱 보관 21일 이내). */
        private fun parseBackfillDates(raw: String?, todayYmd: String): List<String> {
            if (raw.isNullOrBlank()) return emptyList()
            val items = runCatching {
                val arr = org.json.JSONArray(raw)
                (0 until arr.length()).map { arr.optString(it) }
            }.getOrElse { raw.split(',') }
            return items.map { it.trim() }
                .filter { isValidDate(it) && it != todayYmd }
                .distinct()
                .sorted()
                .take(20)
        }

        /** FCM data 맵을 그대로 받아 워커로 넘깁니다. 호출은 가볍고 즉시 반환됩니다. */
        fun enqueue(context: Context, data: Map<String, String>) {
            runCatching {
                val request = OneTimeWorkRequestBuilder<HealthCollectWorker>()
                    .setInputData(
                        workDataOf(
                            KEY_DATE to data[KEY_DATE],
                            KEY_UPLOAD_URL to data[KEY_UPLOAD_URL],
                            KEY_ISSUE_URL to data[KEY_ISSUE_URL],
                            KEY_REQUESTED_AT to data[KEY_REQUESTED_AT],
                            KEY_CID to data[KEY_CID],
                            KEY_DATES to data[KEY_DATES],
                            KEY_PUSH_SCHEMA to data[KEY_PUSH_SCHEMA],
                        )
                    )
                    .setConstraints(
                        Constraints.Builder()
                            .setRequiredNetworkType(NetworkType.CONNECTED)
                            .build()
                    )
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                    .build()

                // 날짜별 작업으로 분리하고 같은 날짜의 새 서버 요청은 이전 대기 작업을 교체합니다.
                val dateKey = data[KEY_DATE]?.takeIf(::isValidDate) ?: today()
                WorkManager.getInstance(context)
                    .enqueueUniqueWork("$UNIQUE_WORK-$dateKey", ExistingWorkPolicy.REPLACE, request)
            }
        }
    }
}
