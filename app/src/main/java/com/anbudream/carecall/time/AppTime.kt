package com.anbudream.carecall.time

import java.text.ParsePosition
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * 서버와 앱이 공유하는 시간 정책.
 *
 * - 사람이 읽거나 서버에 전송하는 날짜/시각은 항상 Asia/Seoul(+09:00)입니다.
 * - epoch millisecond는 절대시각이므로 그대로 유지합니다.
 * - 시간대가 없는 레거시 문자열은 KST로 해석합니다.
 */
object AppTime {
    const val ZONE_ID = "Asia/Seoul"
    const val KST_OFFSET_MINUTES = 9 * 60

    private val kst: TimeZone
        get() = TimeZone.getTimeZone(ZONE_ID)

    fun nowEpochMs(): Long = System.currentTimeMillis()

    fun kstDate(epochMs: Long = nowEpochMs()): String =
        format(epochMs, "yyyy-MM-dd", kst)

    fun kstIso(epochMs: Long = nowEpochMs()): String =
        format(epochMs, "yyyy-MM-dd'T'HH:mm:ss.SSSXXX", kst)

    fun kstLogTimestamp(epochMs: Long = nowEpochMs()): String =
        format(epochMs, "yyyy-MM-dd HH:mm:ss.SSS 'KST'", kst)

    fun deviceOffsetMinutes(epochMs: Long = nowEpochMs()): Int =
        TimeZone.getDefault().getOffset(epochMs) / 60_000

    fun isValidDate(value: String?): Boolean {
        val text = value?.trim()?.takeIf { it.matches(Regex("^\\d{4}-\\d{2}-\\d{2}$")) } ?: return false
        val formatter = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
            timeZone = kst
            isLenient = false
        }
        val position = ParsePosition(0)
        return formatter.parse(text, position) != null && position.index == text.length
    }

    /**
     * FCM 시간 필드는 기존 epoch(ms)와 신규 ISO(+09:00/Z)를 모두 허용합니다.
     * 10자리 안팎의 숫자는 epoch second로, 그보다 큰 값은 epoch millisecond로 봅니다.
     */
    fun parseEpochMs(raw: String?): Long? {
        val value = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        value.toLongOrNull()?.let { number ->
            if (number <= 0L) return null
            return if (number < 100_000_000_000L) number * 1_000L else number
        }

        val patterns = arrayOf(
            ParsePattern("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", null),
            ParsePattern("yyyy-MM-dd'T'HH:mm:ssXXX", null),
            ParsePattern("yyyy-MM-dd'T'HH:mm:ss.SSSX", null),
            ParsePattern("yyyy-MM-dd'T'HH:mm:ssX", null),
            ParsePattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", TimeZone.getTimeZone("UTC")),
            ParsePattern("yyyy-MM-dd'T'HH:mm:ss'Z'", TimeZone.getTimeZone("UTC")),
            // 레거시 무시간대 문자열은 서버 운영 기준인 KST로 해석합니다.
            ParsePattern("yyyy-MM-dd'T'HH:mm:ss.SSS", kst),
            ParsePattern("yyyy-MM-dd'T'HH:mm:ss", kst),
            ParsePattern("yyyy-MM-dd HH:mm:ss.SSS", kst),
            ParsePattern("yyyy-MM-dd HH:mm:ss", kst),
        )
        for (candidate in patterns) {
            parseExact(value, candidate)?.let { return it }
        }
        return null
    }

    private fun format(epochMs: Long, pattern: String, zone: TimeZone): String =
        SimpleDateFormat(pattern, Locale.US).apply {
            timeZone = zone
            isLenient = false
        }.format(Date(epochMs))

    private fun parseExact(value: String, candidate: ParsePattern): Long? {
        val formatter = SimpleDateFormat(candidate.pattern, Locale.US).apply {
            isLenient = false
            candidate.zone?.let { timeZone = it }
        }
        val position = ParsePosition(0)
        val parsed = formatter.parse(value, position) ?: return null
        if (position.index != value.length) return null
        return parsed.time
    }

    private data class ParsePattern(val pattern: String, val zone: TimeZone?)
}
