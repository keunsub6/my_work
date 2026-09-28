package com.anbudream.carecall.webcall

import android.annotation.SuppressLint
import android.app.KeyguardManager
import android.app.NotificationManager
import android.content.Intent
import android.graphics.Color
import android.net.http.SslError
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.annotation.Keep
import androidx.annotation.RequiresApi
import com.anbudream.carecall.BuildConfig
import com.anbudream.carecall.WebCallShownReceiver
import com.anbudream.carecall.screening.CallScreenState
import com.anbudream.carecall.time.AppLog

/**
 * 안부 통화 전체화면 WebView.
 *
 * MealTime 의 통화 화면을 CareCall 안에서 그대로 재현하는 것이 목적이며,
 * 기존 MealTime 경로를 대체하지 않습니다. 이 화면에서 무엇이 잘못되든
 * ([failToMealTime]) 기존 경로로 되돌아갑니다.
 *
 * 잠금화면 위 표시 / 화면 켜기는 ScanActivity 와 같은 방식입니다(매니페스트 속성 +
 * 코드 플래그 이중). 어르신은 잠금 해제를 못 하시는 경우가 많아, 이게 없으면
 * 통화 화면이 "잠금을 푸세요" 뒤에 가려 사실상 못 씁니다.
 *
 * 보안 경계:
 *   · 신뢰된 운영 서버(https)의 허용 페이지만 로드합니다([WebCallPolicy]).
 *   · WebView 권한은 마이크 하나만, 그것도 앱이 이미 RECORD_AUDIO 를 받아 두었고
 *     요청 origin 이 신뢰 대상일 때만 승인합니다. 카메라는 어떤 경우에도 주지 않습니다.
 *   · JS 종료 브리지는 호출 시점의 페이지 URL 을 다시 확인합니다.
 */
class WebCallActivity : ComponentActivity() {

    private var webView: WebView? = null
    private var sessionId = 0L
    private var ackDone = false
    private var closing = false

    private val mainHandler = Handler(Looper.getMainLooper())

    /** 페이지가 끝내 뜨지 않는 경우(네트워크 지연, 서버 무응답)의 마지막 방어선. */
    private val loadTimeout = Runnable {
        AppLog.w(TAG, "call page did not finish loading in time")
        failToMealTime("load timeout")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyLockScreenFlags()

        sessionId = runCatching {
            intent?.getLongExtra(WebCallLauncher.EXTRA_SESSION, 0L) ?: 0L
        }.getOrDefault(0L)

        // 인텐트가 손상된 경우까지 포함해 정책을 다시 확인합니다.
        val resolved = WebCallPolicy.resolve(
            runCatching { intent?.getStringExtra(WebCallLauncher.EXTRA_URL) }.getOrNull()
        )
        if (resolved == null) {
            AppLog.w(TAG, "webcall url rejected by policy")
            failToMealTime("untrusted url")
            return
        }

        // 런처가 이미 확인했지만, 권한이 그 사이에 회수됐을 수 있습니다.
        if (!WebCallLauncher.hasMicPermission(this)) {
            AppLog.w(TAG, "RECORD_AUDIO is not granted at screen start")
            failToMealTime("microphone permission missing")
            return
        }

        WebCallSession.markStarted(sessionId)

        val view = runCatching { buildWebView() }.getOrElse {
            AppLog.e(TAG, "WebView initialization failed", it)
            failToMealTime("webview init failed")
            return
        }
        webView = view

        val attached = runCatching { setContentView(view) }.isSuccess
        if (!attached) {
            AppLog.w(TAG, "failed to attach the WebView to the window")
            failToMealTime("webview attach failed")
            return
        }

        mainHandler.postDelayed(loadTimeout, LOAD_TIMEOUT_MS)
        runCatching { view.loadUrl(resolved.loadUrl) }.onFailure {
            AppLog.e(TAG, "loadUrl failed", it)
            failToMealTime("loadUrl failed")
        }
    }

    /**
     * 화면이 떠 있는 동안 통화 푸시가 한 번 더 오면(FCM 중복 전달 등) singleTop 때문에
     * onCreate 가 아니라 여기로 들어옵니다.
     *
     * 여기서 새 세션을 넘겨받지 않으면 런처의 시작 확인 타이머가 "화면이 안 떴다"고
     * 판정해 MealTime 을 통화 화면 위에 겹쳐 띄웁니다(마이크도 두 화면이 다툽니다).
     *
     * 넘겨받지 못하는 경우(URL 이 정책에 맞지 않거나 WebView 가 이미 정리됨)에는
     * 일부러 아무것도 하지 않습니다. 그러면 새 세션은 markStarted 가 없으므로
     * 기존 MealTime 경로로 자동 폴백됩니다.
     *
     * 이전 세션의 지연 콜백은 세션 id 가 더 이상 최신이 아니라 스스로 무효화됩니다.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (closing) return

        val view = webView
        val resolved = WebCallPolicy.resolve(
            runCatching { intent.getStringExtra(WebCallLauncher.EXTRA_URL) }.getOrNull()
        )
        if (view == null || resolved == null) {
            AppLog.w(TAG, "new webcall intent not adopted, leaving it to the MealTime fallback")
            return
        }

        sessionId = runCatching {
            intent.getLongExtra(WebCallLauncher.EXTRA_SESSION, 0L)
        }.getOrDefault(0L)
        WebCallSession.markStarted(sessionId)

        ackDone = false
        mainHandler.removeCallbacks(loadTimeout)
        mainHandler.postDelayed(loadTimeout, LOAD_TIMEOUT_MS)
        AppLog.i(TAG, "reusing the call screen for session=$sessionId")

        runCatching { view.loadUrl(resolved.loadUrl) }.onFailure {
            AppLog.e(TAG, "loadUrl failed for a new intent", it)
            failToMealTime("loadUrl failed")
        }
    }

    /* ───────────────────────── WebView 구성 ───────────────────────── */

    @SuppressLint("SetJavaScriptEnabled")
    private fun buildWebView(): WebView {
        val view = WebView(this)
        view.setBackgroundColor(Color.BLACK)
        view.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            // WebRTC 오디오가 사용자 탭 없이 시작되어야 합니다(어르신은 화면을 누르지 않습니다).
            mediaPlaybackRequiresUserGesture = false
            loadWithOverviewMode = true
            useWideViewPort = true
            // 통화 페이지에는 필요 없는 통로를 닫아 둡니다.
            allowFileAccess = false
            allowContentAccess = false
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        }
        if (BuildConfig.DEBUG) {
            runCatching { WebView.setWebContentsDebuggingEnabled(true) }
        }

        view.webViewClient = callWebViewClient()
        view.webChromeClient = callWebChromeClient()
        view.addJavascriptInterface(CloseBridge { onCloseRequestedByPage() }, BRIDGE_NAME)
        return view
    }

    private fun callWebViewClient() = object : WebViewClient() {

        override fun shouldOverrideUrlLoading(
            view: WebView?,
            request: WebResourceRequest?,
        ): Boolean {
            val target = runCatching { request?.url?.toString() }.getOrNull()
            if (WebCallPolicy.isTrustedUrl(target)) return false
            // 통화 페이지 밖으로 나가지 않습니다. 어르신이 돌아오실 수 없습니다.
            AppLog.w(TAG, "blocked a navigation outside the trusted call origin")
            return true
        }

        override fun onPageFinished(view: WebView?, url: String?) {
            // teardown 중의 about:blank 로는 준비 완료로 보지 않습니다.
            if (!WebCallPolicy.isTrustedUrl(url)) return
            mainHandler.removeCallbacks(loadTimeout)
            WebCallSession.markReady(sessionId)
            ackScreenShown()
        }

        @RequiresApi(Build.VERSION_CODES.M)
        override fun onReceivedError(
            view: WebView?,
            request: WebResourceRequest?,
            error: WebResourceError?,
        ) {
            if (request?.isForMainFrame != true) return
            val code = runCatching { error?.errorCode }.getOrNull()
            AppLog.w(TAG, "main frame load error code=$code")
            failToMealTime("load error")
        }

        @RequiresApi(Build.VERSION_CODES.M)
        override fun onReceivedHttpError(
            view: WebView?,
            request: WebResourceRequest?,
            errorResponse: WebResourceResponse?,
        ) {
            if (request?.isForMainFrame != true) return
            val status = runCatching { errorResponse?.statusCode }.getOrNull()
            AppLog.w(TAG, "main frame http error status=$status")
            failToMealTime("http error")
        }

        override fun onReceivedSslError(
            view: WebView?,
            handler: SslErrorHandler?,
            error: SslError?,
        ) {
            // 통화 내용이 오가는 화면입니다. 어떤 인증서 오류도 통과시키지 않습니다.
            runCatching { handler?.cancel() }
            AppLog.w(TAG, "ssl error on the call page")
            failToMealTime("ssl error")
        }

        @RequiresApi(Build.VERSION_CODES.O)
        override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean {
            AppLog.w(TAG, "webview renderer process is gone")
            // 죽은 WebView 를 정리하고 true 를 돌려주지 않으면 시스템이 앱 프로세스를 함께 죽입니다.
            val dead = webView
            webView = null
            runCatching {
                (dead?.parent as? ViewGroup)?.removeView(dead)
                dead?.destroy()
            }
            failToMealTime("renderer gone")
            return true
        }
    }

    private fun callWebChromeClient() = object : WebChromeClient() {

        /**
         * 통화 페이지의 getUserMedia 요청입니다. UI 스레드로 들어옵니다.
         *
         * 승인 조건 3가지를 모두 만족해야 합니다.
         *   1) 요청에 마이크가 포함되어 있을 것
         *   2) 요청 origin 이 신뢰된 운영 서버일 것
         *   3) 앱이 이미 RECORD_AUDIO 를 보유하고 있을 것 (여기서 요청하지 않습니다)
         *
         * 승인은 마이크 하나만 합니다. 요청에 카메라가 섞여 있어도 주지 않습니다.
         */
        override fun onPermissionRequest(request: PermissionRequest?) {
            if (request == null) return
            val origin = runCatching { request.origin?.toString() }.getOrNull()
            val wantsAudio = runCatching {
                request.resources?.contains(PermissionRequest.RESOURCE_AUDIO_CAPTURE) == true
            }.getOrDefault(false)

            val allowed = wantsAudio &&
                WebCallPolicy.isTrustedOrigin(origin) &&
                WebCallLauncher.hasMicPermission(this@WebCallActivity)

            runCatching {
                if (allowed) {
                    request.grant(arrayOf(PermissionRequest.RESOURCE_AUDIO_CAPTURE))
                    AppLog.i(TAG, "granted microphone to the call page")
                } else {
                    request.deny()
                    AppLog.w(TAG, "denied a webview permission request wantsAudio=$wantsAudio")
                }
            }.onFailure { AppLog.e(TAG, "failed to answer a webview permission request", it) }
        }

        override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
            if (BuildConfig.DEBUG && consoleMessage != null) {
                AppLog.i(TAG, "console: ${consoleMessage.message()}")
            }
            return true
        }
    }

    /* ───────────────────────── JS 종료 브리지 ───────────────────────── */

    /**
     * 통화 페이지가 `window.WebCallWVHandler.onCloseWebcall()` 로 화면을 닫습니다.
     *
     * @Keep 을 붙인 이유: R8 이 이 메서드를 "아무도 부르지 않는다"고 보고 지우면
     * 통화가 끝나도 화면이 닫히지 않습니다. 기본 proguard 규칙에도 같은 내용이
     * 있지만, 프로젝트가 규칙을 바꾸더라도 살아남도록 명시합니다.
     *
     * private 으로 두지 않는 이유: WebView 는 이 객체의 메서드를 리플렉션으로
     * 호출합니다. 선언 클래스가 접근 불가하면 일부 WebView 버전에서
     * IllegalAccessException 이 나 종료 브리지가 조용히 동작하지 않습니다.
     */
    @Keep
    class CloseBridge internal constructor(private val onClose: () -> Unit) {
        @JavascriptInterface
        fun onCloseWebcall() {
            onClose()
        }
    }

    /** JS 스레드에서 들어옵니다. 반드시 main thread 로 넘겨서 종료합니다. */
    private fun onCloseRequestedByPage() {
        mainHandler.post {
            val current = runCatching { webView?.url }.getOrNull()
            if (!WebCallPolicy.isTrustedUrl(current)) {
                AppLog.w(TAG, "close bridge ignored: the page is not the trusted call page")
                return@post
            }
            AppLog.i(TAG, "the call page asked to close")
            closeNormally()
        }
    }

    private fun closeNormally() {
        if (closing) return
        closing = true
        mainHandler.removeCallbacks(loadTimeout)
        // 정상 종료입니다. 지연 콜백이 MealTime 을 다시 띄우지 않게 막습니다.
        WebCallSession.finish(sessionId)
        runCatching { finish() }
    }

    /* ───────────────────────── 실패 시 기존 경로 복귀 ───────────────────────── */

    /**
     * 자체 WebView 로는 통화를 못 하는 상황입니다. 기존 MealTime 경로로 넘기고 닫습니다.
     * [WebCallSession] 이 세션당 한 번만 통과시키므로 중복으로 띄우지 않습니다.
     */
    private fun failToMealTime(reason: String) {
        if (closing) return
        closing = true
        mainHandler.removeCallbacks(loadTimeout)

        val rawUrl = WebCallSession.claimFallback(sessionId)
        if (rawUrl != null) {
            AppLog.w(TAG, "in-app webcall failed, falling back to MealTime reason=$reason")
            WebCallLauncher.fallbackToMealTime(applicationContext, rawUrl)
        } else {
            AppLog.w(TAG, "in-app webcall failed but fallback was already handled reason=$reason")
        }
        runCatching { finish() }
    }

    /* ───────────────────────── 기존 상태 처리 연결 ───────────────────────── */

    /**
     * MealTime 화면 ACK([WebCallShownReceiver.ACTION_SHOWN_ACK])가 하던 일과 같은 의미입니다.
     * 화면이 실제로 보인 것이 확인된 이 시점에만 대기 알림을 지우고 전화 차단을 켭니다.
     * (기능이 꺼져 있으면 activate() 내부에서 즉시 반환합니다.)
     */
    private fun ackScreenShown() {
        if (ackDone) return
        ackDone = true
        runCatching {
            getSystemService(NotificationManager::class.java)
                ?.cancel(WebCallShownReceiver.PENDING_NOTIFICATION_ID)
        }
        runCatching { CallScreenState.activate(applicationContext) }
        AppLog.i(TAG, "in-app call screen confirmed visible session=$sessionId")
    }

    /* ───────────────────────── 화면 표시 / 정리 ───────────────────────── */

    /**
     * ScanActivity 와 같은 이유로 매니페스트 속성과 코드 플래그를 이중으로 겁니다.
     * 잠금 해제(requestDismissKeyguard)는 부르지 않습니다. 그게 바로 어르신이
     * 넘어가지 못하시는 "잠금을 푸세요" 화면입니다.
     */
    private fun applyLockScreenFlags() {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                setShowWhenLocked(true)
                setTurnScreenOn(true)
            } else {
                @Suppress("DEPRECATION")
                window.addFlags(
                    WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                        WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                )
            }
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }.onFailure { AppLog.e(TAG, "failed to apply lock-screen flags", it) }

        val locked = runCatching {
            getSystemService(KeyguardManager::class.java)?.isKeyguardLocked
        }.getOrNull()
        AppLog.i(TAG, "webcall lock-screen flags applied keyguardLocked=$locked")
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(loadTimeout)
        // 화면이 사라진 뒤에 지연 콜백이 MealTime 을 띄우는 일이 없게 합니다.
        WebCallSession.finish(sessionId)
        teardownWebView()
        super.onDestroy()
    }

    private fun teardownWebView() {
        val view = webView ?: return
        webView = null
        runCatching {
            view.stopLoading()
            view.removeJavascriptInterface(BRIDGE_NAME)
            view.loadUrl("about:blank")
            view.clearHistory()
            (view.parent as? ViewGroup)?.removeView(view)
            view.removeAllViews()
            view.destroy()
        }.onFailure { AppLog.e(TAG, "webview teardown failed", it) }
    }

    companion object {
        private const val TAG = "CareCallWebCall"

        /** 통화 페이지가 JS 에서 부르는 이름입니다: window.WebCallWVHandler.onCloseWebcall() */
        private const val BRIDGE_NAME = "WebCallWVHandler"

        /** 이 시간 안에 페이지가 뜨지 않으면 기존 MealTime 경로로 넘깁니다. */
        private const val LOAD_TIMEOUT_MS = 20_000L
    }
}
