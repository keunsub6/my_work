package com.anbudream.carecall.health

import android.content.Context
import android.os.SystemClock
import com.anbudream.carecall.time.AppTime
import com.google.android.gms.location.DetectedActivity
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 원시 센서 이벤트를 저장하지 않고 일 단위 집계만 보관합니다.
 * SharedPreferences 하나를 잠금으로 보호해 Receiver/JobService의 동시 갱신을 직렬화합니다.
 */
object HealthMetricsStore {
    private const val PREFS = "health_sensor_metrics"
    private const val KEY_DAYS = "days_json"
    private const val KEY_LAST_ACTIVITY_AT = "last_activity_at"
    private const val KEY_LAST_ACTIVITY_CATEGORY = "last_activity_category"
    private const val KEY_LAST_ACTIVITY_CONFIDENCE = "last_activity_confidence"
    private const val KEY_LAST_STEP_AT = "last_step_at"
    private const val KEY_LAST_STEP_VALUE = "last_step_value"
    private const val KEY_LAST_STEP_BOOT = "last_step_boot"
    private const val KEY_LAST_STEP_ELAPSED = "last_step_elapsed"
    private const val KEY_AGGREGATION_VERSION = "aggregation_version"
    private const val AGGREGATION_VERSION_KST = 1
    private const val MAX_KEEP_DAYS = 21
    private const val MAX_VALID_ACTIVITY_GAP_MS = 75L * 60L * 1000L
    private const val MIN_CONFIDENCE = 50
    private const val CURRENT_UPLOAD_INTERVAL_MS = 6L * 60L * 60L * 1000L

    // ── [추가] 시간대별 누적 ──────────────────────────────────────────────────
    // 일별 집계 안에 24칸 배열을 함께 보관합니다. 저장하는 값은 누적값이 아니라
    // "그 시각에 발생한 증분(구간값)"입니다. 0시~h시 누적은 서버가 prefix sum 으로
    // 만듭니다. 구간값 → 누적은 무손실이지만, 누적 → 구간값은 반올림 손실이 있어
    // 되돌릴 수 없기 때문입니다.
    private const val KEY_HOURLY_STEPS = "hourlySteps"
    private const val KEY_HOURLY_ACTIVE = "hourlyActiveMinutes"
    private const val HOURS_PER_DAY = 24

    // 시 단위 배분을 허용할 최대 구간 길이. 이보다 긴 공백은 시각별로 흩뿌리면 없는
    // 정밀도를 지어내는 셈이라, 일 단위 배분만 하고 시 배분은 건너뜁니다.
    //
    // 12시간인 이유: 야간 도즈로 23시~06시(7시간)가 통으로 비는 건 정상입니다. 6시간으로
    // 잡으면 멀쩡한 날이 매일 제외됩니다. 12시간을 넘는 단일 공백은 기기 장기 전원 꺼짐
    // 같은 진짜 결측입니다.
    private const val MAX_HOURLY_SPAN_MS = 12L * 60L * 60L * 1000L

    private val lock = Any()
    private val KST_ZONE: ZoneId = ZoneId.of(AppTime.ZONE_ID)

    fun recordActivitySample(
        context: Context,
        timestampMs: Long,
        detectedType: Int,
        confidence: Int,
    ) = synchronized(lock) {
        val prefs = prefs(context)
        val days = loadDays(prefs.getString(KEY_DAYS, null))
        val timestamp = timestampMs.coerceAtMost(System.currentTimeMillis() + 60_000L)
        val category = activityCategory(detectedType)
        val previousAt = prefs.getLong(KEY_LAST_ACTIVITY_AT, 0L)
        val previousCategory = prefs.getString(KEY_LAST_ACTIVITY_CATEGORY, CATEGORY_UNKNOWN) ?: CATEGORY_UNKNOWN
        val previousConfidence = prefs.getInt(KEY_LAST_ACTIVITY_CONFIDENCE, 0)

        if (previousAt > 0L && timestamp > previousAt) {
            val gap = timestamp - previousAt
            if (gap <= MAX_VALID_ACTIVITY_GAP_MS && previousConfidence >= MIN_CONFIDENCE) {
                for (segment in splitByDate(previousAt, timestamp)) {
                    val day = getOrCreateDay(days, segment.date, segment.startMs)
                    addActivityDuration(day, previousCategory, segment.durationMinutes)
                    // [추가] activeMinutes 와 같은 조건에서만 시간 버킷에 적립합니다.
                    if (previousCategory == CATEGORY_ACTIVE) allocateHourlyActive(day, segment)
                    touch(day, segment.endMs)
                }
            } else {
                for (segment in splitByDate(previousAt, timestamp)) {
                    val day = getOrCreateDay(days, segment.date, segment.startMs)
                    day.put(
                        "activityMissingMinutes",
                        day.finiteDouble("activityMissingMinutes") + segment.durationMinutes,
                    )
                    day.put("currentSedentaryMinutes", 0.0)
                    touch(day, segment.endMs)
                }
            }
        }

        val sampleDate = localDate(timestamp)
        val sampleDay = getOrCreateDay(days, sampleDate, timestamp)
        val outOfOrderActivity = previousAt > 0L && timestamp <= previousAt
        if (outOfOrderActivity) {
            // 지연 전달된 오래된 이벤트나 기기 벽시계 롤백 표본은 품질 표지만 남기고
            // 마지막 앵커를 과거로 되돌리지 않습니다. 앵커를 되돌리면 다음 정상 표본에서
            // 거대한 가짜 구간이 만들어질 수 있습니다.
            sampleDay.put("activityClockRollbackObserved", true)
            sampleDay.put("outOfOrderActivityIgnored", true)
        }
        sampleDay.put("activitySampleCount", sampleDay.optInt("activitySampleCount", 0) + 1)
        if (confidence < MIN_CONFIDENCE) {
            sampleDay.put("lowConfidenceActivitySamples", sampleDay.optInt("lowConfidenceActivitySamples", 0) + 1)
        }
        if (category == CATEGORY_ACTIVE && confidence >= MIN_CONFIDENCE) {
            markActiveMoment(sampleDay, timestamp)
        }
        touch(sampleDay, timestamp)
        prune(days)

        if (outOfOrderActivity) {
            prefs.edit().putString(KEY_DAYS, days.toString()).apply()
            return@synchronized
        }

        prefs.edit()
            .putString(KEY_DAYS, days.toString())
            .putLong(KEY_LAST_ACTIVITY_AT, timestamp)
            .putString(KEY_LAST_ACTIVITY_CATEGORY, category)
            .putInt(KEY_LAST_ACTIVITY_CONFIDENCE, confidence.coerceIn(0, 100))
            .apply()
    }

    fun recordStepCounter(
        context: Context,
        timestampMs: Long,
        cumulativeSteps: Long,
        bootCount: Int,
    ) = synchronized(lock) {
        val prefs = prefs(context)
        val days = loadDays(prefs.getString(KEY_DAYS, null))
        val timestamp = timestampMs.coerceAtMost(System.currentTimeMillis() + 60_000L)
        val today = getOrCreateDay(days, localDate(timestamp), timestamp)
        today.put("stepCounterSupported", true)
        today.put("stepSampleCount", today.optInt("stepSampleCount", 0) + 1)
        touch(today, timestamp)

        val previousAt = prefs.getLong(KEY_LAST_STEP_AT, 0L)
        val previousValue = prefs.getLong(KEY_LAST_STEP_VALUE, -1L)
        val previousBoot = prefs.getInt(KEY_LAST_STEP_BOOT, Int.MIN_VALUE)

        if (
            previousAt > 0L && timestamp > previousAt && previousValue >= 0L &&
            previousBoot == bootCount && cumulativeSteps >= previousValue
        ) {
            val delta = cumulativeSteps - previousValue
            val gapMinutes = (timestamp - previousAt) / 60_000.0
            val segments = splitByDate(previousAt, timestamp)
            var allocated = 0L
            segments.forEachIndexed { index, segment ->
                val share = if (index == segments.lastIndex) {
                    delta - allocated
                } else {
                    ((delta.toDouble() * segment.durationMs.toDouble()) / (timestamp - previousAt).toDouble())
                        .toLong().coerceAtLeast(0L)
                }
                allocated += share
                val day = getOrCreateDay(days, segment.date, segment.startMs)
                day.put("steps", day.optLong("steps", 0L) + share)
                allocateHourlySteps(day, segment, share)   // [추가]
                day.put("stepDeltaObserved", true)
                day.put("stepMaxGapMinutes", max(day.optDouble("stepMaxGapMinutes", 0.0), gapMinutes))
                if (segments.size > 1 && gapMinutes > 120.0) day.put("stepAllocationApproximate", true)
                if (share > 0L) markActiveMoment(day, segment.endMs - 1L)
                touch(day, segment.endMs)
            }
        } else if (
            previousAt <= 0L && previousValue >= 0L && previousBoot == bootCount &&
            cumulativeSteps >= previousValue
        ) {
            // 시각 변경 브로드캐스트에서 벽시계 앵커만 제거한 뒤 들어온 첫 표본입니다.
            // 누적 카운터 증분은 일 합계에 보존하되 정확한 발생 시간대는 알 수 없으므로
            // 시간 버킷에는 배치하지 않습니다.
            val delta = cumulativeSteps - previousValue
            if (delta > 0L) {
                today.put("steps", today.optLong("steps", 0L) + delta)
                today.put("stepDeltaObserved", true)
                today.put("hourlyAllocationSkipped", true)
                today.put("hourlyUnplacedSteps", today.optLong("hourlyUnplacedSteps", 0L) + delta)
                today.put("stepAllocationApproximate", true)
                today.put("stepReanchoredAfterClockChange", true)
                markActiveMoment(today, timestamp)
            }
        } else if (
            previousAt > 0L && previousValue >= 0L && previousBoot == bootCount &&
            cumulativeSteps >= previousValue && timestamp <= previousAt
        ) {
            // 벽시계가 뒤로 바뀌어도 누적 걸음 증분 자체는 신뢰할 수 있습니다.
            // 일 합계에는 보존하되 어느 시간대에 속하는지는 알 수 없으므로 미배치로 표시합니다.
            val delta = cumulativeSteps - previousValue
            if (delta > 0L) {
                today.put("steps", today.optLong("steps", 0L) + delta)
                today.put("stepDeltaObserved", true)
                today.put("hourlyAllocationSkipped", true)
                today.put("hourlyUnplacedSteps", today.optLong("hourlyUnplacedSteps", 0L) + delta)
                today.put("stepAllocationApproximate", true)
                markActiveMoment(today, timestamp)
            }
            today.put("stepClockRollbackObserved", true)
        } else if (previousAt > 0L && previousBoot != bootCount) {
            today.put("stepCounterResetObserved", true)
        } else if (previousAt > 0L && timestamp <= previousAt) {
            // 같은/이전 벽시각인데 누적값도 이어지지 않는 표본은 지연된 오래된 이벤트로
            // 봅니다. 마지막 앵커와 누적값을 과거로 되돌리면 이후 걸음 계산이 오염됩니다.
            today.put("outOfOrderStepIgnored", true)
            prune(days)
            prefs.edit().putString(KEY_DAYS, days.toString()).apply()
            return@synchronized
        }

        prune(days)
        prefs.edit()
            .putString(KEY_DAYS, days.toString())
            .putLong(KEY_LAST_STEP_AT, timestamp)
            .putLong(KEY_LAST_STEP_VALUE, cumulativeSteps)
            .putInt(KEY_LAST_STEP_BOOT, bootCount)
            .putLong(KEY_LAST_STEP_ELAPSED, SystemClock.elapsedRealtime())
            .apply()
    }

    fun notePermissionDenied(context: Context) = updateToday(context) {
        it.put("activityPermissionDenied", true)
    }

    fun noteActivityRecognitionUnavailable(context: Context) = updateToday(context) {
        it.put("activityRecognitionUnavailable", true)
    }

    fun noteStepCounterUnsupported(context: Context) = updateToday(context) {
        it.put("stepCounterSupported", false)
        it.put("stepSensorReason", "unsupported")
    }

    fun noteStepSampleFailure(context: Context, reason: String) = updateToday(context) {
        it.put("stepSensorReason", reason.take(64))
        it.put("stepSampleFailureCount", it.optInt("stepSampleFailureCount", 0) + 1)
    }

    /**
     * 앱 업데이트 전에는 기기 기본 시간대로 일자/시간 버킷을 만들었습니다.
     * 최초 1회 KST 집계 정책으로 전환합니다. 당시 기기 오프셋이 KST가 아니면 기존 집계는
     * 안전하게 재해석할 원시 데이터가 없으므로 삭제해 서버 기준선을 오염시키지 않습니다.
     * @return 운영 로그에 남길 마이그레이션 결과, 이미 완료했으면 null
     */
    fun ensureKstStorage(context: Context): String? = synchronized(lock) {
        val p = prefs(context)
        if (p.getInt(KEY_AGGREGATION_VERSION, 0) >= AGGREGATION_VERSION_KST) return null

        val now = System.currentTimeMillis()
        val deviceOffset = AppTime.deviceOffsetMinutes(now)
        val editor = p.edit().putInt(KEY_AGGREGATION_VERSION, AGGREGATION_VERSION_KST)
        val result: String
        if (deviceOffset != AppTime.KST_OFFSET_MINUTES) {
            editor.remove(KEY_DAYS)
                .remove(KEY_LAST_ACTIVITY_AT)
                .remove(KEY_LAST_ACTIVITY_CATEGORY)
                .remove(KEY_LAST_ACTIVITY_CONFIDENCE)
                .remove(KEY_LAST_STEP_AT)
                .remove(KEY_LAST_STEP_VALUE)
                .remove(KEY_LAST_STEP_BOOT)
                .remove(KEY_LAST_STEP_ELAPSED)
            result = "legacy health aggregates cleared; oldDeviceOffset=$deviceOffset"
        } else {
            val days = loadDays(p.getString(KEY_DAYS, null))
            for (key in days.keys().asSequence().toList()) {
                days.optJSONObject(key)?.put("aggregationTimezone", AppTime.ZONE_ID)
            }
            editor.putString(KEY_DAYS, days.toString())
            result = "legacy health aggregates retained as KST"
        }
        editor.commit()
        result
    }

    /** 수동 시각 변경 뒤 이전/신규 표본 사이에 잘못된 구간을 만들지 않도록 앵커만 초기화합니다. */
    fun handleWallClockChanged(context: Context, reason: String) = synchronized(lock) {
        val p = prefs(context)
        val days = loadDays(p.getString(KEY_DAYS, null))
        val now = System.currentTimeMillis()
        val day = getOrCreateDay(days, localDate(now), now)
        day.put("wallClockChangeObserved", true)
        day.put("wallClockChangeReason", reason.take(48))
        day.put("wallClockChangedAt", AppTime.kstIso(now))
        touch(day, now)
        p.edit()
            .putString(KEY_DAYS, days.toString())
            .remove(KEY_LAST_ACTIVITY_AT)
            .remove(KEY_LAST_ACTIVITY_CATEGORY)
            .remove(KEY_LAST_ACTIVITY_CONFIDENCE)
            // 누적 step counter 값과 boot count는 유지합니다. 다음 표본에서 증분을
            // 일 합계에는 보존하고, 시간대 미배치로 명시합니다.
            .remove(KEY_LAST_STEP_AT)
            .remove(KEY_LAST_STEP_ELAPSED)
            .apply()
    }

    fun datesDue(context: Context, nowMs: Long = System.currentTimeMillis()): List<String> = synchronized(lock) {
        val days = loadDays(prefs(context).getString(KEY_DAYS, null))
        val today = localDate(nowMs)
        days.keys().asSequence().toList().sorted().filter { date ->
            val day = days.optJSONObject(date) ?: return@filter false
            val dirty = day.optBoolean("dirty", true)
            if (!dirty) return@filter false
            date != today || nowMs - day.optLong("lastUploadedAt", 0L) >= CURRENT_UPLOAD_INTERVAL_MS
        }.take(5)
    }

    /**
     * [추가] 마지막 걸음 표본으로부터 흐른 시간(ms). 표본이 없으면 Long.MAX_VALUE.
     * 잡이 '예약되어 있는지'가 아니라 '실제로 도는지'를 판단하는 유일한 근거입니다.
     */
    /** [추가] 그 날짜에 쌓인 걸음 표본 수. 하루 기록 자체가 없으면 -1 입니다.
     *  0 과 -1 을 구분해야 '샘플링은 돌았는데 값이 없음' 과 '아예 안 돌았음' 이 갈립니다. */
    /**
     * [추가] buildPayload 가 null 을 반환하는 이유를 밝힙니다.
     *
     * send() 는 payload 가 null 이면 로그 한 줄 없이 false 를 돌려주고, 호출부는 그걸
     * no_metrics_for_date 하나로 뭉뚱그려 보고했습니다. 그래서 '표본이 없다' 와
     * '대상자 식별자가 없다' 와 '날짜 키가 어긋난다' 가 전부 같은 증상으로 보였습니다.
     * 원인마다 처방이 완전히 다르므로 반드시 구분해야 합니다.
     */
    /**
     * [추가] 그 날짜의 하루 기록이 없으면 만들어 둡니다(오늘만).
     *
     * 지금까지는 오늘 표본이 하나도 없으면 buildPayload 가 null 을 돌려주고 업로드가
     * 아예 일어나지 않아, 서버 입장에서 '앱이 죽음' 과 '표본이 0개' 가 완전히 같은
     * 침묵으로 보였습니다. 빈 기록이라도 올라가면 서버는 stepSampleCount=0 과
     * missingReasons 를 보고 정확히 구분할 수 있습니다.
     *
     * 과거 날짜는 만들지 않습니다 — 없던 날을 지어내면 기준선이 오염됩니다.
     * @return 새로 만들었으면 true
     */
    fun ensureDayForToday(context: Context, date: String): Boolean = synchronized(lock) {
        if (date != localDate(System.currentTimeMillis())) return false
        val prefs = prefs(context)
        val days = loadDays(prefs.getString(KEY_DAYS, null))
        if (days.optJSONObject(date) != null) return false
        val now = System.currentTimeMillis()
        val day = getOrCreateDay(days, date, now)
        day.put("createdBy", "upload_fallback")   // 센서가 아니라 업로드 경로가 만든 기록
        touch(day, now)
        prune(days)
        prefs.edit().putString(KEY_DAYS, days.toString()).apply()
        return true
    }

    fun payloadBlockReason(context: Context, date: String): String? = synchronized(lock) {
        val days = loadDays(prefs(context).getString(KEY_DAYS, null))
        if (days.optJSONObject(date) == null) {
            // 기기가 기록 중인 날짜를 함께 알려야 시간대 어긋남을 구분할 수 있습니다.
            // 길이가 짧아야 서버 쪽 길이 제한에서 안 잘립니다.
            val keys = days.keys().asSequence().toList().sorted()
            val newest = keys.lastOrNull()
            return if (keys.isEmpty()) "no_days" else "no_day.have$newest"
        }
        if (runCatching { LocalDate.parse(date) }.getOrNull() == null) return "bad_date"
        return null
    }

    fun stepSampleCountOf(context: Context, date: String): Int = synchronized(lock) {
        val days = loadDays(prefs(context).getString(KEY_DAYS, null))
        val day = days.optJSONObject(date) ?: return -1
        day.optInt("stepSampleCount", 0)
    }

    fun msSinceLastStepSample(context: Context): Long = synchronized(lock) {
        val p = prefs(context)
        val lastElapsed = p.getLong(KEY_LAST_STEP_ELAPSED, 0L)
        val nowElapsed = SystemClock.elapsedRealtime()
        if (lastElapsed > 0L && nowElapsed >= lastElapsed) return nowElapsed - lastElapsed
        if (lastElapsed > 0L) return Long.MAX_VALUE // 재부팅 후에는 즉시 재샘플링/재예약

        // 업데이트 전 데이터의 안전한 fallback입니다.
        val lastWall = p.getLong(KEY_LAST_STEP_AT, 0L)
        if (lastWall <= 0L) return Long.MAX_VALUE
        val wallAge = System.currentTimeMillis() - lastWall
        if (wallAge < 0L) Long.MAX_VALUE else wallAge
    }

    fun buildPayload(context: Context, date: String, nowMs: Long = System.currentTimeMillis()): JSONObject? = synchronized(lock) {
        /* [수정] subjectId 가 없다고 업로드를 포기하지 않습니다.
           서버는 토큰으로 먼저 전화번호를 찾고(healthPhoneRefByToken), subjectId 는
           그게 실패했을 때만 쓰는 보조 키입니다. 즉 없어도 저장에 지장이 없는데,
           앱이 여기서 null 을 돌려주는 바람에 업로드가 통째로 멈추고 서버에는
           아무 흔적도 남지 않았습니다. 없으면 필드만 비우고 진행합니다. */
        val subjectId = RegistrationClient.subjectId(context)
        val days = loadDays(prefs(context).getString(KEY_DAYS, null))
        val day = days.optJSONObject(date) ?: return null
        val dateValue = runCatching { LocalDate.parse(date) }.getOrNull() ?: return null
        val nowKst = Instant.ofEpochMilli(nowMs).atZone(KST_ZONE)
        val today = nowKst.toLocalDate()
        val partialDay = dateValue == today
        val expectedMinutes = if (partialDay) {
            ChronoUnit.MINUTES.between(dateValue.atStartOfDay(KST_ZONE), nowKst)
                .toDouble().coerceIn(1.0, 1440.0)
        } else 1440.0
        val activityObserved = day.finiteDouble("activityObservedMinutes")   // [패치 #4] NaN이면 coverage→JSON put에서 즉사했음
        val activityCoverage = ((activityObserved / expectedMinutes) * 100.0).coerceIn(0.0, 100.0)
        val stepSamples = day.optInt("stepSampleCount", 0)
        val stepSupported = if (day.has("stepCounterSupported")) day.optBoolean("stepCounterSupported") else null
        val activityUsable = activityObserved >= min(60.0, expectedMinutes * 0.10)
        val stepsUsable = stepSamples >= 2 && day.optBoolean("stepDeltaObserved", false)

        val metrics = JSONObject().apply {
            putNullable("steps", if (stepsUsable) day.optLong("steps", 0L) else null)
            putNullable("activeMinutes", if (activityUsable) day.finiteDouble("activeMinutes").roundToInt() else null)
            putNullable("sedentaryMinutes", if (activityUsable) day.finiteDouble("sedentaryMinutes").roundToInt() else null)
            putNullable("vehicleMinutes", if (activityUsable) day.finiteDouble("vehicleMinutes").roundToInt() else null)
            putNullable(
                "longestSedentaryMinutes",
                if (activityUsable) day.finiteDouble("longestSedentaryMinutes").roundToInt() else null,
            )
            put("activeHours", Integer.bitCount(day.optInt("activeHoursMask", 0)))
            // [추가] 시간대별 구간값 24칸. 일별 지표가 못 쓸 값이면 배열도 null 로 보내
            // 서버가 "믿을 수 있는 날"의 기준을 하나만 보게 합니다.
            putNullable("hourlySteps", hourlyArrayOrNull(day, KEY_HOURLY_STEPS, stepsUsable, asInt = true))
            putNullable("hourlyActiveMinutes", hourlyArrayOrNull(day, KEY_HOURLY_ACTIVE, activityUsable, asInt = false))
            putNullable("firstActiveAt", day.optLong("firstActiveAt", 0L).takeIf { it > 0L }?.let(::toIso))
            putNullable("lastActiveAt", day.optLong("lastActiveAt", 0L).takeIf { it > 0L }?.let(::toIso))
        }

        val baseline = buildBaseline(days, dateValue)
        val changes = JSONObject().apply {
            val baselineSteps = baseline.optDoubleOrNull("medianSteps")
            val baselineActive = baseline.optDoubleOrNull("medianActiveMinutes")
            val baselineSedentary = baseline.optDoubleOrNull("medianSedentaryMinutes")
            putNullable("stepsPercent", percentChange(metrics.optDoubleOrNull("steps"), baselineSteps))
            putNullable("activeMinutesPercent", percentChange(metrics.optDoubleOrNull("activeMinutes"), baselineActive))
            putNullable("sedentaryMinutesPercent", percentChange(metrics.optDoubleOrNull("sedentaryMinutes"), baselineSedentary))
            val firstAt = day.optLong("firstActiveAt", 0L).takeIf { it > 0L }
            val firstMinute = firstAt?.let(::minuteOfDay)
            /* [패치 #7 — 진범] org.json 의 has() 는 값이 JSONObject.NULL 이어도 true 다.
               기준선 이력이 부족해 medianFirstActiveMinute 가 NULL 로 저장된 상태에서
               오늘 첫 활동시각(firstMinute)이 잡히는 순간 getDouble 이
               "Value null ... cannot be converted to double" JSONException 으로 즉사했고,
               이것이 업로드 전면 중단(8/6~)의 실제 원인이었다 — NaN 이 아니라 NULL.
               이 파일의 다른 기준선 읽기는 전부 optDoubleOrNull 을 쓰는데 이 한 줄만
               예외였다. 같은 헬퍼로 통일한다(getDouble 은 이 파일에서 소멸). */
            val baselineFirst = baseline.optDoubleOrNull("medianFirstActiveMinute")
            putNullable(
                "firstActivityShiftMinutes",
                if (firstMinute != null && baselineFirst != null)
                    firstMinute - baselineFirst.roundToInt() else null,
            )
        }

        val missing = JSONArray().apply {
            if (!HealthSensorCoordinator.hasActivityPermission(context)) put("activity_permission_denied")
            if (day.optBoolean("activityRecognitionUnavailable", false)) put("activity_recognition_unavailable")
            if (stepSupported == false) put("step_counter_unsupported")
            if (!stepsUsable && stepSupported != false) put(day.optString("stepSensorReason", "insufficient_step_samples"))
            if (!activityUsable) put("insufficient_activity_coverage")
            if (baseline.optInt("days", 0) < 3) put("insufficient_baseline")
        }
        val reliability = when {
            stepsUsable && activityCoverage >= 60.0 && !day.optBoolean("stepAllocationApproximate", false) -> "high"
            stepsUsable || activityCoverage >= 30.0 -> "medium"
            else -> "low"
        }

        JSONObject().apply {
            // [변경] schemaVersion 3. 시간대별 배열과 KST 집계 정책을 서버가 구분할 수 있어야 합니다.
            // 서버는 schemaVersion >= 2 인 날만 시각 컷오프 비교에 씁니다.
            put("schemaVersion", 3)
            putNullable("subjectId", subjectId)
            put("measurementDate", date)
            put("measuredAt", toIso(nowMs))
            put("timezoneOffsetMinutes", AppTime.KST_OFFSET_MINUTES)
            put("aggregationTimezone", AppTime.ZONE_ID)
            put("deviceTimezoneOffsetMinutes", AppTime.deviceOffsetMinutes(nowMs))
            put("partialDay", partialDay)
            // [추가] 마지막으로 "온전히" 관측된 시각(0~23). 지난 날이면 23,
            // 오늘이면 현재 시각의 직전 시(진행 중인 시각은 반쪽이라 제외).
            // 오늘 0시대라 온전한 시각이 아직 없으면 -1.
            // 서버는 0..observedThroughHour 구간만 기준선과 비교해야 합니다.
            put("observedThroughHour", if (partialDay) nowKst.hour - 1 else 23)
            // [추가] 긴 공백 때문에 시 단위 배분을 건너뛴 날인지, 그리고 그때 시간
            // 버킷에 담지 못한 걸음 수. 이 값이 0 이면 버킷 합계 == steps 가 보장됩니다.
            // 서버는 unplaced / steps 비율을 보고 시각 비교 사용 여부를 정하면 됩니다.
            put("hourlyAllocationSkipped", day.optBoolean("hourlyAllocationSkipped", false))
            put("hourlyUnplacedSteps", day.optLong("hourlyUnplacedSteps", 0L))
            put("metrics", metrics)
            put("changesFrom7DayBaseline", changes)
            put("baseline", baseline)
            put("quality", JSONObject().apply {
                put("reliability", reliability)
                put("activityCoveragePercent", oneDecimal(activityCoverage))
                put("activitySampleCount", day.optInt("activitySampleCount", 0))
                put("lowConfidenceActivitySamples", day.optInt("lowConfidenceActivitySamples", 0))
                put("stepSampleCount", stepSamples)
                putNullable("stepCounterSupported", stepSupported)
                put("stepAllocationApproximate", day.optBoolean("stepAllocationApproximate", false))
                put("maxStepGapMinutes", oneDecimal(day.optDouble("stepMaxGapMinutes", 0.0)))
                put("missingReasons", missing)
            })
            put("sensorSources", JSONArray().apply {
                put("android_step_counter")
                put("google_activity_recognition")
            })
        }
    }

    /** [carecall] 등록 해제 시 호출. 보관 중인 일 단위 집계와 마지막 샘플 상태를 전부 지웁니다. */
    fun clear(context: Context) = synchronized(lock) {
        runCatching { prefs(context).edit().clear().apply() }
        Unit
    }

    fun markUploaded(context: Context, date: String, uploadedAt: Long = System.currentTimeMillis()) = synchronized(lock) {
        val prefs = prefs(context)
        val days = loadDays(prefs.getString(KEY_DAYS, null))
        val day = days.optJSONObject(date) ?: return
        day.put("lastUploadedAt", uploadedAt)
        day.put("dirty", false)
        prefs.edit().putString(KEY_DAYS, days.toString()).apply()
    }

    private fun updateToday(context: Context, block: (JSONObject) -> Unit) = synchronized(lock) {
        val prefs = prefs(context)
        val days = loadDays(prefs.getString(KEY_DAYS, null))
        val now = System.currentTimeMillis()
        val day = getOrCreateDay(days, localDate(now), now)
        block(day)
        touch(day, now)
        prune(days)
        prefs.edit().putString(KEY_DAYS, days.toString()).apply()
    }

    private fun addActivityDuration(day: JSONObject, category: String, minutes: Double) {
        // [패치 #4] NaN은 (NaN <= 0.0)이 false 라 아래 하한 검사를 그대로 통과해 분 단위
        // 누적 전체를 오염시켰습니다(오염 후에는 모든 합이 NaN → 업로드 직렬화 즉사).
        // 유입 차단과 함께, 기존 저장값이 이미 오염됐어도 finiteDouble 이 0으로 되살립니다.
        if (!minutes.isFinite() || minutes <= 0.0) return
        day.put("activityObservedMinutes", day.finiteDouble("activityObservedMinutes") + minutes)
        when (category) {
            CATEGORY_ACTIVE -> {
                day.put("activeMinutes", day.finiteDouble("activeMinutes") + minutes)
                day.put("currentSedentaryMinutes", 0.0)
            }
            CATEGORY_STILL -> {
                val streak = day.finiteDouble("currentSedentaryMinutes") + minutes
                day.put("sedentaryMinutes", day.finiteDouble("sedentaryMinutes") + minutes)
                day.put("currentSedentaryMinutes", streak)
                day.put("longestSedentaryMinutes", max(day.finiteDouble("longestSedentaryMinutes"), streak))
            }
            CATEGORY_VEHICLE -> {
                day.put("vehicleMinutes", day.finiteDouble("vehicleMinutes") + minutes)
                day.put("currentSedentaryMinutes", 0.0)
            }
            else -> {
                day.put("unknownMinutes", day.finiteDouble("unknownMinutes") + minutes)
                day.put("currentSedentaryMinutes", 0.0)
            }
        }
    }

    private fun markActiveMoment(day: JSONObject, timestampMs: Long) {
        if (day.optLong("firstActiveAt", 0L) == 0L) day.put("firstActiveAt", timestampMs)
        day.put("lastActiveAt", max(day.optLong("lastActiveAt", 0L), timestampMs))
        val hour = Instant.ofEpochMilli(timestampMs).atZone(KST_ZONE).hour
        day.put("activeHoursMask", day.optInt("activeHoursMask", 0) or (1 shl hour))
    }

    // ── [추가] 시간대별 누적 적립 ─────────────────────────────────────────────
    // activeHoursMask 는 그대로 둡니다. 값이 생기면 마스크는 파생 가능해 중복이지만,
    // 이미 activeHours 로 서버에 나가고 대시보드가 쓰고 있어 제거하면 깨집니다.

    /** 24칸 구간값 배열을 지연 생성해 돌려줍니다. */
    private fun hourlyBucket(day: JSONObject, key: String): JSONArray =
        day.optJSONArray(key) ?: JSONArray().also { fresh ->
            repeat(HOURS_PER_DAY) { fresh.put(0) }
            day.put(key, fresh)
        }

    /** 걸음은 정수로 누적합니다. 합계가 steps 와 정확히 일치해야 하기 때문입니다. */
    private fun addHourlySteps(day: JSONObject, hour: Int, value: Long) {
        if (value <= 0L || hour !in 0 until HOURS_PER_DAY) return
        val bucket = hourlyBucket(day, KEY_HOURLY_STEPS)
        bucket.put(hour, bucket.optLong(hour, 0L) + value)
    }

    /** 활동 분은 실수로 누적합니다. 페이로드에서만 소수 1자리로 줄입니다. */
    private fun addHourlyActive(day: JSONObject, hour: Int, minutes: Double) {
        if (minutes <= 0.0 || hour !in 0 until HOURS_PER_DAY) return
        val bucket = hourlyBucket(day, KEY_HOURLY_ACTIVE)
        bucket.put(hour, bucket.optDouble(hour, 0.0) + minutes)
    }

    /**
     * 날짜 세그먼트에 배분된 걸음 share 를 다시 시 경계로 나눠 적립합니다.
     * 날짜 단위 배분과 같은 방식(마지막 칸에 나머지를 몰아주기)이라 합계가 정확히
     * share 로 보존됩니다.
     */
    private fun allocateHourlySteps(day: JSONObject, segment: DateSegment, share: Long) {
        if (share <= 0L) return
        if (segment.durationMs > MAX_HOURLY_SPAN_MS) {
            // 건너뛰면 시간 버킷 합계 < steps 가 됩니다. 얼마나 어긋났는지를 남겨야
            // 서버가 "이 날을 시각 비교에 써도 되는지"를 추측 없이 판단할 수 있습니다.
            day.put("hourlyAllocationSkipped", true)
            day.put("hourlyUnplacedSteps", day.optLong("hourlyUnplacedSteps", 0L) + share)
            return
        }
        val slots = splitByHour(segment.startMs, segment.endMs)
        if (slots.isEmpty()) return
        var allocated = 0L
        slots.forEachIndexed { index, slot ->
            val part = if (index == slots.lastIndex) {
                share - allocated
            } else {
                ((share.toDouble() * slot.durationMs.toDouble()) / segment.durationMs.toDouble())
                    .toLong().coerceAtLeast(0L)
            }
            allocated += part
            addHourlySteps(day, slot.hour, part)
        }
    }

    /**
     * 활동 구간을 시 경계로 나눠 적립합니다. 호출부에서 CATEGORY_ACTIVE 인 구간만
     * 넘기므로, 합계는 activeMinutes 와 (반올림 오차 범위에서) 일치합니다.
     */
    private fun allocateHourlyActive(day: JSONObject, segment: DateSegment) {
        if (segment.durationMs > MAX_HOURLY_SPAN_MS) {
            day.put("hourlyAllocationSkipped", true)
            return
        }
        for (slot in splitByHour(segment.startMs, segment.endMs)) {
            addHourlyActive(day, slot.hour, slot.durationMinutes)
        }
    }

    private fun buildBaseline(days: JSONObject, currentDate: LocalDate): JSONObject {
        val candidates = (1L..7L).mapNotNull { offset ->
            val day = days.optJSONObject(currentDate.minusDays(offset).toString()) ?: return@mapNotNull null
            // [추가] 서버 사본이 현재 로컬 상태와 일치하는 날만 기준선에 넣습니다.
            //
            //  - lastUploadedAt == 0 : 한 번도 안 올라간 날. 서버엔 아예 없습니다.
            //  - dirty == true       : 올라간 뒤 값이 더 쌓인 날. 오늘은 6시간마다
            //                          올라가므로 자정 직후의 '어제'가 여기 해당합니다.
            //                          서버엔 partialDay 사본만 있어 truncated 로 제외되는데,
            //                          앱만 전체값으로 세면 유효일수가 다시 어긋납니다.
            //
            // markUploaded() 가 두 값을 함께 기록하므로 새 상태가 필요 없습니다.
            if (day.optLong("lastUploadedAt", 0L) <= 0L) return@mapNotNull null
            if (day.optBoolean("dirty", true)) return@mapNotNull null
            val activityObserved = day.finiteDouble("activityObservedMinutes")
            BaselineDay(
                steps = day.optLong("steps", 0L).takeIf {
                    day.optInt("stepSampleCount", 0) >= 2 && day.optBoolean("stepDeltaObserved", false)
                }?.toDouble(),
                activeMinutes = day.finiteDouble("activeMinutes").takeIf { activityObserved >= 180.0 },
                sedentaryMinutes = day.finiteDouble("sedentaryMinutes").takeIf { activityObserved >= 180.0 },
                firstActiveMinute = day.optLong("firstActiveAt", 0L).takeIf { it > 0L }?.let(::minuteOfDay)?.toDouble(),
            )
        }
        return JSONObject().apply {
            put("days", candidates.size)
            putNullable("medianSteps", median(candidates.mapNotNull { it.steps })?.roundToInt())
            putNullable("medianActiveMinutes", median(candidates.mapNotNull { it.activeMinutes })?.roundToInt())
            putNullable("medianSedentaryMinutes", median(candidates.mapNotNull { it.sedentaryMinutes })?.roundToInt())
            putNullable("medianFirstActiveMinute", median(candidates.mapNotNull { it.firstActiveMinute })?.let(::oneDecimal))
        }
    }

    private fun percentChange(current: Double?, baseline: Double?): Double? {
        if (current == null || baseline == null || !current.isFinite() || !baseline.isFinite() || abs(baseline) < 1.0) return null
        return oneDecimal(((current - baseline) / baseline) * 100.0)
    }

    private fun median(values: List<Double>): Double? {
        if (values.isEmpty()) return null
        val sorted = values.sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[middle] else (sorted[middle - 1] + sorted[middle]) / 2.0
    }

    private fun minuteOfDay(timestampMs: Long): Int {
        val zdt = Instant.ofEpochMilli(timestampMs).atZone(KST_ZONE)
        return zdt.hour * 60 + zdt.minute
    }

    private fun getOrCreateDay(days: JSONObject, date: String, timestampMs: Long): JSONObject {
        val existing = days.optJSONObject(date)
        if (existing != null) {
            if (!existing.has("aggregationTimezone")) existing.put("aggregationTimezone", AppTime.ZONE_ID)
            return existing
        }
        return JSONObject().apply {
            put("date", date)
            put("aggregationTimezone", AppTime.ZONE_ID)
            put("createdAt", timestampMs)
            put("updatedAt", timestampMs)
            put("dirty", true)
            days.put(date, this)
        }
    }

    private fun touch(day: JSONObject, timestampMs: Long) {
        day.put("updatedAt", max(day.optLong("updatedAt", 0L), timestampMs))
        day.put("dirty", true)
    }

    private fun prune(days: JSONObject) {
        val keys = days.keys().asSequence().toList().sorted()
        keys.dropLast(MAX_KEEP_DAYS).forEach { days.remove(it) }
    }

    private fun splitByDate(startMs: Long, endMs: Long): List<DateSegment> {
        if (endMs <= startMs) return emptyList()
        val zone = KST_ZONE
        val out = mutableListOf<DateSegment>()
        var cursor = Instant.ofEpochMilli(startMs).atZone(zone)
        val end = Instant.ofEpochMilli(endMs).atZone(zone)
        while (cursor.isBefore(end)) {
            val nextDay = cursor.toLocalDate().plusDays(1).atStartOfDay(zone)
            val segmentEnd = if (nextDay.isBefore(end)) nextDay else end
            val start = cursor.toInstant().toEpochMilli()
            val finish = segmentEnd.toInstant().toEpochMilli()
            out += DateSegment(cursor.toLocalDate().toString(), start, finish)
            cursor = segmentEnd
        }
        return out
    }

    /**
     * [추가] 구간을 로컬 시각의 시(hour) 경계로 쪼갭니다.
     *
     * splitByDate 를 고쳐서 시 단위로 만들지 않고 별도 함수를 둔 이유:
     *  - recordStepCounter 의 `segments.size > 1` 은 "자정을 넘어 배분했다"는 뜻이라,
     *    시 단위로 바꾸면 거의 항상 참이 되어 stepAllocationApproximate 판정이 깨집니다.
     *  - addActivityDuration 의 currentSedentaryMinutes 스트릭도 호출 단위에 영향을
     *    받습니다.
     * 기존 4개 호출부의 의미를 그대로 두기 위해 시 단위 분할은 여기서만 씁니다.
     */
    private fun splitByHour(startMs: Long, endMs: Long): List<HourSegment> {
        if (endMs <= startMs) return emptyList()
        val zone = KST_ZONE
        val out = mutableListOf<HourSegment>()
        var cursor = Instant.ofEpochMilli(startMs).atZone(zone)
        val end = Instant.ofEpochMilli(endMs).atZone(zone)
        while (cursor.isBefore(end)) {
            val nextHour = cursor.truncatedTo(ChronoUnit.HOURS).plusHours(1)
            val segmentEnd = if (nextHour.isBefore(end)) nextHour else end
            val start = cursor.toInstant().toEpochMilli()
            val finish = segmentEnd.toInstant().toEpochMilli()
            if (finish <= start) break   // 시간대 변경 등으로 진행이 멈추면 탈출
            out += HourSegment(cursor.hour, start, finish)
            cursor = segmentEnd
        }
        return out
    }

    private fun localDate(timestampMs: Long): String =
        Instant.ofEpochMilli(timestampMs).atZone(KST_ZONE).toLocalDate().toString()

    private fun activityCategory(type: Int): String = when (type) {
        DetectedActivity.WALKING,
        DetectedActivity.RUNNING,
        DetectedActivity.ON_FOOT,
        DetectedActivity.ON_BICYCLE -> CATEGORY_ACTIVE
        DetectedActivity.STILL -> CATEGORY_STILL
        DetectedActivity.IN_VEHICLE -> CATEGORY_VEHICLE
        else -> CATEGORY_UNKNOWN
    }

    private fun loadDays(raw: String?): JSONObject =
        runCatching { if (raw.isNullOrBlank()) JSONObject() else JSONObject(raw) }.getOrElse { JSONObject() }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun toIso(timestampMs: Long): String = AppTime.kstIso(timestampMs)

    /** [패치 #4] 저장돼 있던 비유한값(NaN/∞)을 읽는 시점에 무해화합니다. 이미 오염된
     *  하루(예: 8/6)도 이 경로로 업로드가 되살아나고, 다음 적립 때 저장값도 교체됩니다. */
    private fun JSONObject.finiteDouble(key: String, fallback: Double = 0.0): Double {
        val v = optDouble(key, fallback)
        return if (v.isFinite()) v else fallback
    }

    /** [패치 #3] NaN/∞가 org.json put(String, double)의 checkDouble에서 JSONException으로
     *  즉사하던 지점의 1차 차단막. 이 가드가 없어 업로드 경로가 통째로 죽었습니다. */
    private fun oneDecimal(value: Double): Double {
        if (!value.isFinite()) return 0.0
        return String.format(Locale.US, "%.1f", value).toDouble()
    }

    /**
     * [추가] 저장된 24칸을 페이로드용 배열로 변환합니다.
     *
     * 걸음(asInt=true)은 정수로 누적해 왔으므로 합계가 steps 와 정확히 일치합니다.
     * 활동 분(asInt=false)은 칸마다 소수 1자리로 줄이므로, 합계가 activeMinutes 와
     * 최대 24 × 0.05 ≈ 1.2분까지 어긋날 수 있습니다. 서버는 일별 값을 정본으로 두고
     * 시간대별 값은 비율 계산에만 써야 합니다.
     */
    private fun hourlyArrayOrNull(
        day: JSONObject,
        key: String,
        usable: Boolean,
        asInt: Boolean,
    ): JSONArray? {
        if (!usable) return null
        val stored = day.optJSONArray(key) ?: return null
        return JSONArray().apply {
            for (hour in 0 until HOURS_PER_DAY) {
                val value = stored.optDouble(hour, 0.0).takeIf { it.isFinite() } ?: 0.0
                // put(Int) 와 put(Double) 을 명시적으로 갈라 호출합니다. 삼항식으로
                // 합치면 공통 상위타입 때문에 put(Any) 로 풀려 1 이 1.0 으로 나갑니다.
                if (asInt) put(value.roundToInt()) else put(oneDecimal(value))
            }
        }
    }

    private fun JSONObject.putNullable(key: String, value: Any?) {
        // [패치 #3] put(String, Any)는 org.json의 NaN/∞ 검사를 우회해 toString() 단계에서
        // 터집니다. Double/Float 비유한값은 여기서 걸러 null 로 보냅니다(2차 차단막).
        val safe = when (value) {
            is Double -> value.takeIf { it.isFinite() }
            is Float -> value.takeIf { it.isFinite() }?.toDouble()
            else -> value
        }
        put(key, safe ?: JSONObject.NULL)
    }

    private fun JSONObject.optDoubleOrNull(key: String): Double? =
        if (!has(key) || isNull(key)) null else optDouble(key).takeIf { it.isFinite() }

    private data class DateSegment(val date: String, val startMs: Long, val endMs: Long) {
        val durationMs: Long get() = endMs - startMs
        val durationMinutes: Double get() = durationMs / 60_000.0
    }

    /** [추가] 시 단위 구간. date 는 호출부가 이미 알고 있어 담지 않습니다. */
    private data class HourSegment(val hour: Int, val startMs: Long, val endMs: Long) {
        val durationMs: Long get() = endMs - startMs
        val durationMinutes: Double get() = durationMs / 60_000.0
    }

    private data class BaselineDay(
        val steps: Double?,
        val activeMinutes: Double?,
        val sedentaryMinutes: Double?,
        val firstActiveMinute: Double?,
    )

    private const val CATEGORY_ACTIVE = "active"
    private const val CATEGORY_STILL = "still"
    private const val CATEGORY_VEHICLE = "vehicle"
    private const val CATEGORY_UNKNOWN = "unknown"
}
