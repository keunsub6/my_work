package com.anbudream.carecall.health

import android.content.Context
import com.anbudream.carecall.BuildConfig
import java.net.URL

/**
 * 서버(server.js)는 수집 트리거 푸시의 data에 uploadUrl / issueUrl을 실어 보냅니다.
 * 앱 재빌드 없이 서버를 옮기기 위한 설계이므로 그대로 존중하되, 위조 푸시로 건강데이터가
 * 외부로 새는 것을 막기 위해 반드시 검증 후에만 채택합니다.
 *
 * 검증 규칙: https 스킴 + BuildConfig.SERVER_BASE_URL과 같은 호스트(또는 그 서브도메인).
 * 검증에 실패하면 조용히 무시하고 컴파일 시점 기본값을 계속 씁니다.
 */
internal object HealthPushConfig {
    private const val PREFS = "health_push_config"
    private const val KEY_UPLOAD = "upload_url"
    private const val KEY_ISSUE = "issue_url"

    private val allowedHost: String =
        runCatching { URL(BuildConfig.SERVER_BASE_URL).host.lowercase() }.getOrNull().orEmpty()

    fun uploadUrl(ctx: Context): String = stored(ctx, KEY_UPLOAD) ?: Config.HEALTH_METRICS_URL

    fun issueUrl(ctx: Context): String = stored(ctx, KEY_ISSUE) ?: Config.HEALTH_ISSUE_URL

    fun remember(ctx: Context, uploadUrl: String?, issueUrl: String?) {
        val upload = sanitize(uploadUrl)
        val issue = sanitize(issueUrl)
        if (upload == null && issue == null) return
        runCatching {
            val editor = ctx.applicationContext
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            if (upload != null) editor.putString(KEY_UPLOAD, upload)
            if (issue != null) editor.putString(KEY_ISSUE, issue)
            editor.apply()
        }
    }

    private fun stored(ctx: Context, key: String): String? = runCatching {
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(key, null)
    }.getOrNull()?.takeIf { it.isNotBlank() }

    private fun sanitize(raw: String?): String? {
        if (raw.isNullOrBlank() || allowedHost.isBlank()) return null
        val parsed = runCatching { URL(raw) }.getOrNull() ?: return null
        if (!parsed.protocol.equals("https", ignoreCase = true)) return null
        val host = parsed.host.lowercase()
        if (host != allowedHost && !host.endsWith(".$allowedHost")) return null
        return parsed.toString()
    }
}
