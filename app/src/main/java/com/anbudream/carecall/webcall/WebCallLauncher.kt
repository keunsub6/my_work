package com.anbudream.carecall.webcall

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.anbudream.carecall.BuildConfig
import com.anbudream.carecall.MealTimeLauncher
import com.anbudream.carecall.time.AppLog

/**
 * 안부 통화를 CareCall 자체 WebView([WebCallActivity])로 띄우는 진입점.
 *
 * 기존 MealTime 경로를 대체하지 않습니다. 아래 조건을 하나라도 만족하지 못하면
 * 즉시 false 를 돌려주고, 호출부는 지금까지와 똑같이 [MealTimeLauncher] 를 씁니다.
 *
 *   · 통화 URL 이 신뢰된 운영 서버의 허용 페이지가 아님
 *   · 오버레이 권한("다른 앱 위에 표시") 없음 → 백그라운드 액티비티 실행 불가
 *   · RECORD_AUDIO 미허용 → 마이크 없는 통화 화면은 어르신께 무의미
 *   · startActivity 실패
 *
 * 실행에 성공한 뒤에 실패하는 경우(화면이 조용히 무시됨, 페이지 로드 실패,
 * renderer 사망 등)도 모두 MealTime 으로 되돌아갑니다. 되돌아가는 통로는
 * [WebCallSession] 이 세션당 한 번만 열어 주므로 중복 실행되지 않습니다.
 */
object WebCallLauncher {

    private const val TAG = "WebCallLauncher"

    const val EXTRA_URL = "com.anbudream.carecall.webcall.extra.URL"
    const val EXTRA_SESSION = "com.anbudream.carecall.webcall.extra.SESSION"

    /**
     * 액티비티가 실제로 떴는지 확인하기까지 기다리는 시간.
     *
     * 일부 제조사 롬은 startActivity 를 예외 없이 조용히 무시합니다. 그 경우를
     * "성공"으로 보고 끝내면 어르신에게 아무 일도 일어나지 않습니다. ScanNotifier
     * 가 쓰는 것과 같은 방식이며, 같은 한계도 공유합니다(FCM 수신 프로세스가
     * 이 시간 안에 정리되면 확인이 돌지 못합니다).
     */
    private const val START_VERIFY_DELAY_MS = 2_000L

    /**
     * @return 자체 WebView 경로로 요청을 넘겼으면 true. false 면 호출부가
     *         기존 MealTime 경로를 그대로 수행해야 합니다.
     */
    fun tryLaunch(context: Context, rawUrl: String?): Boolean {
        val app = context.applicationContext

        val resolved = WebCallPolicy.resolve(rawUrl)
        if (resolved == null) {
            AppLog.i(TAG, "in-app webcall skipped: url is not a trusted call page")
            return false
        }
        if (!canDrawOverlays(app)) {
            AppLog.i(TAG, "in-app webcall skipped: overlay permission is not granted")
            return false
        }
        if (!hasMicPermission(app)) {
            AppLog.i(TAG, "in-app webcall skipped: RECORD_AUDIO is not granted")
            return false
        }

        val sessionId = WebCallSession.start(resolved.originalUrl)
        val intent = Intent(app, WebCallActivity::class.java).apply {
            putExtra(EXTRA_URL, resolved.loadUrl)
            putExtra(EXTRA_SESSION, sessionId)
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_NO_USER_ACTION
            )
        }

        val launched = runCatching {
            app.startActivity(intent)
            true
        }.getOrElse {
            AppLog.e(TAG, "in-app webcall activity start failed", it)
            false
        }
        if (!launched) {
            // 이 세션에서는 fallback 을 호출부가 바로 수행합니다(중복 방지).
            WebCallSession.claimFallback(sessionId)
            return false
        }

        verifyStartedOrFallback(app, sessionId)
        AppLog.i(TAG, "in-app webcall requested session=$sessionId")
        return true
    }

    /** 화면이 실제로 뜨지 않았으면 기존 MealTime 경로로 되돌립니다. */
    private fun verifyStartedOrFallback(app: Context, sessionId: Long) {
        runCatching {
            Handler(Looper.getMainLooper()).postDelayed({
                if (WebCallSession.isStarted(sessionId)) return@postDelayed
                val rawUrl = WebCallSession.claimFallback(sessionId) ?: return@postDelayed
                AppLog.w(TAG, "in-app webcall screen never appeared, falling back to MealTime")
                fallbackToMealTime(app, rawUrl)
            }, START_VERIFY_DELAY_MS)
        }.onFailure { AppLog.e(TAG, "failed to schedule the webcall start check", it) }
    }

    /**
     * 기존 MealTime 경로입니다. AppFirebaseMessagingService 가 하던 동작
     * (직접 실행 요청 → 실패하면 알림 탭 fallback 용 URL 보관)과 동일합니다.
     */
    fun fallbackToMealTime(context: Context, rawUrl: String?) {
        val app = context.applicationContext
        runCatching {
            val nonce = MealTimeLauncher.requestImmediateOpen(app, rawUrl)
            if (nonce == null) MealTimeLauncher.rememberPendingUrl(app, rawUrl)
            AppLog.i(TAG, "MealTime fallback requested=${nonce != null}")
        }.onFailure { AppLog.e(TAG, "MealTime fallback failed", it) }
    }

    /**
     * 마이크 권한은 "이미 허용된 경우"에만 자체 WebView 를 씁니다.
     * 이 클래스는 권한을 요청하지 않습니다(백그라운드 FCM 경로라 요청할 수도 없습니다).
     */
    fun hasMicPermission(context: Context): Boolean = runCatching {
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    private fun canDrawOverlays(context: Context): Boolean =
        runCatching { Settings.canDrawOverlays(context) }.getOrDefault(false)
}

/**
 * 어떤 URL 을 자체 WebView 로 열어도 되는지 판정합니다.
 *
 * 신뢰 기준은 기존 운영 서버 설정(BuildConfig.SERVER_BASE_URL)입니다. 새 상수를
 * 만들지 않으므로 서버가 바뀌어도 이 파일을 고칠 필요가 없고, 설정이 비어 있으면
 * 신뢰 호스트가 하나도 없어 자체 WebView 가 아예 켜지지 않습니다(= 기존 동작 유지).
 */
internal object WebCallPolicy {

    /**
     * 통화 페이지를 서비스하는 호스트가 운영 API 서버와 다르면 여기에 적습니다.
     * 비어 있으면 BuildConfig.SERVER_BASE_URL 의 호스트만 신뢰합니다.
     */
    private val ADDITIONAL_TRUSTED_HOSTS = emptySet<String>()

    /** 로드를 허용하는 페이지. care.html → c.html 변환을 마친 경로로 판정합니다. */
    private val ALLOWED_PAGE_SUFFIXES = listOf("/c.html")

    private val trustedHosts: Set<String> by lazy {
        val hosts = mutableSetOf<String>()
        runCatching { Uri.parse(BuildConfig.SERVER_BASE_URL) }.getOrNull()?.let { base ->
            if (base.scheme.equals("https", ignoreCase = true)) {
                base.host?.lowercase()?.takeIf { it.isNotBlank() }?.let { hosts.add(it) }
            }
        }
        hosts.addAll(ADDITIONAL_TRUSTED_HOSTS)
        hosts
    }

    data class Resolved(
        /** 서버가 보낸 원본 URL. MealTime fallback 에 그대로 넘깁니다. */
        val originalUrl: String,
        /** 자체 WebView 가 로드할 URL(care.html → c.html 변환 결과). */
        val loadUrl: String,
    )

    /** 신뢰된 운영 서버의 허용 페이지일 때만 Resolved 를 돌려줍니다. */
    fun resolve(rawUrl: String?): Resolved? {
        val raw = rawUrl?.trim().orEmpty()
        if (raw.isEmpty()) return null
        if (!isTrustedUrl(raw)) return null

        // 변환 로직은 MealTime 경로와 완전히 같은 것을 재사용합니다(중복 구현 금지).
        val loadUrl = runCatching { MealTimeLauncher.toMealTimeUrl(raw) }.getOrNull() ?: return null
        val path = runCatching { Uri.parse(loadUrl).path }.getOrNull().orEmpty()
        if (ALLOWED_PAGE_SUFFIXES.none { path.endsWith(it, ignoreCase = true) }) return null

        return Resolved(originalUrl = raw, loadUrl = loadUrl)
    }

    /** https + 신뢰 호스트인지 확인합니다(경로는 보지 않습니다). */
    fun isTrustedUrl(url: String?): Boolean {
        val value = url?.trim().orEmpty()
        if (value.isEmpty()) return false
        val uri = runCatching { Uri.parse(value) }.getOrNull() ?: return false
        if (!uri.scheme.equals("https", ignoreCase = true)) return false
        val host = uri.host?.lowercase().orEmpty()
        if (host.isEmpty()) return false
        return host in trustedHosts
    }

    /** WebView 권한 요청의 origin("https://host[:port]") 검증용입니다. */
    fun isTrustedOrigin(origin: String?): Boolean = isTrustedUrl(origin)
}

/**
 * 한 번의 통화 요청에 대한 상태입니다.
 *
 * 존재 이유는 단 하나, "MealTime 으로 되돌아가기"가 정확히 한 번만 일어나도록
 * 하는 것입니다. 되돌아갈 계기가 여러 곳(시작 확인 타이머, 로드 실패, HTTP 오류,
 * renderer 사망)에 있어 조율이 없으면 통화 화면이 두 번 뜰 수 있습니다.
 *
 * 동시에 진행되는 통화는 하나뿐이므로 단일 슬롯으로 충분합니다.
 */
internal object WebCallSession {

    private val lock = Any()
    private var currentId = 0L
    private var started = false
    private var ready = false
    private var fallbackClaimed = false
    private var rawUrl: String? = null

    fun start(rawUrl: String): Long = synchronized(lock) {
        currentId += 1
        started = false
        ready = false
        fallbackClaimed = false
        this.rawUrl = rawUrl
        currentId
    }

    /** 액티비티 onCreate 도달. "조용히 무시됨" 판정을 해제합니다. */
    fun markStarted(id: Long) {
        synchronized(lock) { if (id == currentId) started = true }
    }

    /** 통화 페이지 로드 완료. */
    fun markReady(id: Long) {
        synchronized(lock) {
            if (id != currentId) return@synchronized
            started = true
            ready = true
        }
    }

    fun isStarted(id: Long): Boolean = synchronized(lock) { id == currentId && started }

    fun isReady(id: Long): Boolean = synchronized(lock) { id == currentId && ready }

    /** 세션당 한 번만 원본 URL 을 돌려줍니다. 두 번째 호출부터는 null 입니다. */
    fun claimFallback(id: Long): String? = synchronized(lock) {
        if (id != currentId || fallbackClaimed) {
            null
        } else {
            fallbackClaimed = true
            rawUrl
        }
    }

    /** 정상 종료. 이후 지연 콜백이 MealTime 을 띄우지 않게 막습니다. */
    fun finish(id: Long) {
        synchronized(lock) { if (id == currentId) fallbackClaimed = true }
    }
}
