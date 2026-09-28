package com.anbudream.carecall.scan

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.app.KeyguardManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Rational
import android.util.Size
import android.view.Surface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.anbudream.carecall.BuildConfig
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.UseCaseGroup
import androidx.camera.core.ViewPort
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.anbudream.carecall.scan.camera.FrameAnalyzer
import com.anbudream.carecall.scan.camera.GuideOverlayView
import com.anbudream.carecall.scan.image.CaptureProcessor
import com.anbudream.carecall.scan.net.ScanUploader
import com.anbudream.carecall.scan.ocr.MedOcr
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 종이 글씨를 읽어주는 화면.
 * 단일 흐름: 권한 → 카메라(자동 촬영 게이트) → 촬영 확인 → 결과/전송.
 *
 * 진입 경로 2가지 (둘 다 같은 액티비티):
 *   · 스캔 알림 탭   → cid/nonce 있음 → 세션 모드. [이 사진 사용] 시 서버 전송
 *   · 앱 안에서 실행 → cid 없음 → 단독 모드. 촬영·판독만 하고 서버 전송 없음
 *
 * CareCall 의 기존 기능과 공유하는 것은 BuildConfig(서버 주소·API 키)뿐입니다.
 * 통화·건강지표·전화차단 어느 계층도 참조하지 않습니다.
 *
 * 포그라운드 서비스와 full-screen intent 는 쓰지 않습니다. 알림 탭 1회를
 * 감수하는 대신 새 권한을 늘리지 않는 선택입니다.
 */
class ScanActivity : ComponentActivity() {

    /* ── 세션 상태 ── */
    private var cid: String = ""
    private var nonce: String = ""

    /** 촬영을 마친 뒤 되살릴 통화 화면 주소(서버가 함께 보내 줍니다). */
    private var callUrl: String = ""
    private var callScreenRestored = false
    private val sessionMode: Boolean get() = cid.isNotBlank() && nonce.isNotBlank()

    /* ── 카메라 ── */
    private lateinit var cameraExecutor: ExecutorService
    private var cameraProvider: ProcessCameraProvider? = null
    private var camera: androidx.camera.core.Camera? = null
    private var imageCapture: ImageCapture? = null
    private var analyzer: FrameAnalyzer? = null
    private var capturing = false

    /* ── UI ── */
    private lateinit var root: FrameLayout
    private lateinit var cameraLayer: FrameLayout
    private var previewView: PreviewView? = null
    private var overlay: GuideOverlayView? = null
    private var statusText: TextView? = null
    private var hudText: TextView? = null
    private var shutterBtn: Button? = null
    private var closeBtn: Button? = null
    private var confirmLayer: FrameLayout? = null
    private var shownBitmap: Bitmap? = null

    private var failStreak = 0
    private var topInset = 0
    private var bottomInset = 0
    private var gateStartedAt = 0L

    /** 촬영 시점의 게이트 판정값. 촬영본 실측치와 대조해 게이트 신뢰도를 봅니다. */
    private var lastGateSharp = 0.0
    private var lastGateLuma = 0

    private val manualHint = Runnable {
        statusText?.text = "네모 안에 약봉투를 맞추고\n아래 큰 버튼을 눌러 주세요"
    }

    /** 초점 락 타임아웃 등 뷰 유무와 무관하게 예약해야 하는 작업용 */
    private val mainHandler = Handler(Looper.getMainLooper())
    private var focusTimeout: Runnable? = null

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startCamera() else showPermissionScreen(deniedOnce = true)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyLockScreenFlags()
        WindowCompat.setDecorFitsSystemWindows(window, false)
        readSession(intent)
        markShown(cid)

        root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        cameraLayer = FrameLayout(this)
        root.addView(cameraLayer, matchParent())
        setContentView(root)

        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            topInset = bars.top
            bottomInset = bars.bottom
            applyChromeInsets()
            insets
        }

        if (hasCameraPermission()) {
            buildCameraUi()
            startCamera()
        } else {
            showPermissionScreen(deniedOnce = false)
        }
    }

    /**
     * 촬영을 마치고 화면을 닫을 때 통화 화면을 되살립니다.
     *
     * 이 화면은 백그라운드 실행 때문에 FLAG_ACTIVITY_NEW_TASK 로 별도 태스크에서
     * 열립니다. 그래서 닫으면 MealTime 통화 화면이 아니라 홈이나 다른 화면으로
     * 떨어집니다. 게다가 촬영 중에 통화 웹뷰가 파괴되는 경우가 많아, 어르신이
     * 스스로 통화로 돌아가지 못하고 서버가 준비해 둔 낭독을 영영 못 듣습니다.
     *
     * MealTime 을 다시 띄우는 통로는 CareCall 이 이미 갖고 있습니다
     * (통화 푸시에서 쓰던 것과 같은 경로). 새 권한이 필요 없습니다.
     */
    private fun restoreCallScreen() {
        if (callScreenRestored) return
        callScreenRestored = true
        if (callUrl.isBlank()) {
            ScanLog.i(SUB, "통화 화면 복귀 생략 — callUrl 없음(단독 촬영이거나 구버전 서버)")
            return
        }
        val nonce = runCatching {
            com.anbudream.carecall.MealTimeLauncher.requestImmediateOpen(this, callUrl)
        }.getOrNull()
        ScanLog.i(SUB, "통화 화면 복귀 요청 requested=${nonce != null}")
    }

    override fun finish() {
        restoreCallScreen()
        super.finish()
    }

    /**
     * 잠금화면 위 표시 설정.
     *
     * 매니페스트의 showWhenLocked / turnScreenOn 과 중복이지만, 두 가지 이유로
     * 코드에서도 겁니다.
     *   1) 매니페스트 속성은 API 27 부터입니다. 그 아래에서는 윈도우 플래그만 듣습니다.
     *   2) 일부 제조사 롬에서 매니페스트 속성만으로는 화면이 켜지지 않는 사례가 있어,
     *      MealTime 도 같은 방식으로 이중으로 걸어 두었습니다.
     *
     * KEEP_SCREEN_ON 은 별개 목적입니다. 어르신이 종이를 가이드에 맞추는 데
     * 시간이 걸리는데(자동 촬영 게이트가 3틱을 기다립니다), 기본 화면 꺼짐이
     * 15~30초인 기기에서는 조준 중에 화면이 꺼져 버립니다.
     *
     * 잠금 해제(requestDismissKeyguard)는 부르지 않습니다. 그게 바로 어르신이
     * 못 넘어가시는 "잠금을 푸세요" 화면입니다. 카메라는 잠금 위에서 그대로 동작합니다.
     */
    private fun applyLockScreenFlags() {
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

        // 실측 확인용. 잠금 상태에서 화면이 떴는지 로그로 검증할 수 있습니다.
        val locked = runCatching {
            getSystemService(KeyguardManager::class.java)?.isKeyguardLocked
        }.getOrNull()
        ScanLog.i(SUB, "lock-screen flags applied keyguardLocked=$locked")
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        readSession(intent)
        // 화면이 이미 떠 있는 상태에서 새 요청이 오면 singleTop 때문에
        // onCreate 가 아니라 여기로 들어옵니다. 마커를 찍지 않으면
        // ScanNotifier 가 "화면이 안 떴다"고 오판해 불필요한 알림을 띄웁니다.
        markShown(cid)
    }

    private fun readSession(source: Intent?) {
        val newCid = ScanContract.sanitizeId(source?.getStringExtra(ScanContract.EXTRA_CID))
        val newNonce = ScanContract.sanitizeId(source?.getStringExtra(ScanContract.EXTRA_NONCE))
        if (newCid.isNotBlank() && newNonce.isNotBlank()) {
            cid = newCid
            nonce = newNonce
            source?.getStringExtra(ScanContract.EXTRA_CALL_URL)
                ?.takeIf { it.startsWith("https://") }
                ?.let { callUrl = it }
            // 알림으로 들어온 경우 그 알림을 지웁니다.
            // 직접 실행(2단계 1차 경로)으로 들어왔으면 알림이 없어 무해한 호출입니다.
            ScanNotifier.cancel(this)
            ScanLog.i(SUB, "session mode cid=$cid")
        } else if (cid.isBlank()) {
            ScanLog.i(SUB, "standalone mode")
        }
    }

    /* ───────────────────────── 권한 화면 ───────────────────────── */

    private fun hasCameraPermission() =
        checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    private fun showPermissionScreen(deniedOnce: Boolean) {
        cameraLayer.removeAllViews()
        previewView = null
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(28), dp(28), dp(28), dp(28))
        }
        column.addView(bigText("약봉투를 찍으려면\n카메라 사용 허락이 필요해요", 26))
        val permanentlyDenied = deniedOnce &&
            !shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)
        val action = bigButton(
            if (permanentlyDenied) "설정에서 허용하기" else "카메라 허용하기",
            Color.rgb(15, 110, 86)
        ) {
            if (permanentlyDenied) {
                runCatching {
                    startActivity(
                        Intent(
                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.parse("package:$packageName")
                        )
                    )
                }
            } else {
                permissionLauncher.launch(Manifest.permission.CAMERA)
            }
        }
        column.addView(action, buttonParams(dp(24)))
        column.addView(bigButton("닫기", Color.DKGRAY) { finish() }, buttonParams(dp(12), false))
        cameraLayer.addView(column, matchParent())
    }

    /**
     * 잠금 상태에서 화면이 내려가면 다음 세션 첫 프레임이 이전 거리의 초점으로
     * 시작합니다. resumeCamera() 와 짝을 맞춰 여기서도 반드시 풀어 줍니다.
     */
    override fun onPause() {
        super.onPause()
        runCatching { camera?.cameraControl?.cancelFocusAndMetering() }
    }

    override fun onResume() {
        super.onResume()
        if (hasCameraPermission() && previewView == null && confirmLayer == null) {
            buildCameraUi()
            startCamera()
        }
    }

    /* ───────────────────────── 카메라 화면 ───────────────────────── */

    private fun buildCameraUi() {
        cameraLayer.removeAllViews()

        previewView = PreviewView(this).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
        cameraLayer.addView(previewView, matchParent())

        overlay = GuideOverlayView(this)
        cameraLayer.addView(overlay, matchParent())

        val status = TextView(this).apply {
            text = "약봉투를 네모 안에\n맞춰 주세요"
            textSize = 24f
            setTextColor(Color.WHITE)
            setShadowLayer(8f, 0f, 2f, Color.BLACK)
            gravity = Gravity.CENTER
            typeface = Typeface.DEFAULT_BOLD
        }
        statusText = status
        cameraLayer.addView(
            status,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP
            ).apply { topMargin = topInset + dp(28) }
        )

        val hud = TextView(this).apply {
            textSize = 12f
            setTextColor(Color.YELLOW)
            visibility = if (BuildConfig.DEBUG) View.VISIBLE else View.GONE
        }
        hudText = hud
        cameraLayer.addView(
            hud,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.START
            ).apply {
                leftMargin = dp(12)
                bottomMargin = bottomInset + dp(12)
            }
        )

        val shutter = bigButton("사진 찍기", Color.rgb(15, 110, 86)) { requestCapture("manual") }
        shutterBtn = shutter
        cameraLayer.addView(
            shutter,
            FrameLayout.LayoutParams(dp(220), dp(88), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
                .apply { bottomMargin = bottomInset + dp(28) }
        )

        val close = Button(this).apply {
            text = "닫기"
            textSize = 18f
            isAllCaps = false
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.argb(190, 0, 0, 0))
            setOnClickListener { finish() }
        }
        closeBtn = close
        cameraLayer.addView(
            close,
            FrameLayout.LayoutParams(dp(88), dp(56), Gravity.TOP or Gravity.END).apply {
                topMargin = topInset + dp(16)
                rightMargin = dp(16)
            }
        )

        applyChromeInsets()
        armManualHint()
    }

    private fun applyChromeInsets() {
        statusText?.let { v ->
            (v.layoutParams as? FrameLayout.LayoutParams)?.let {
                it.topMargin = topInset + dp(28); v.layoutParams = it
            }
        }
        closeBtn?.let { v ->
            (v.layoutParams as? FrameLayout.LayoutParams)?.let {
                it.topMargin = topInset + dp(16); v.layoutParams = it
            }
        }
        shutterBtn?.let { v ->
            (v.layoutParams as? FrameLayout.LayoutParams)?.let {
                it.bottomMargin = bottomInset + dp(28); v.layoutParams = it
            }
        }
        hudText?.let { v ->
            (v.layoutParams as? FrameLayout.LayoutParams)?.let {
                it.bottomMargin = bottomInset + dp(12); v.layoutParams = it
            }
        }
    }

    private fun armManualHint() {
        statusText?.removeCallbacks(manualHint)
        statusText?.postDelayed(manualHint, ScanTuning.MANUAL_HINT_AFTER_MS)
    }

    private fun startCamera() {
        if (!::cameraExecutor.isInitialized) {
            cameraExecutor = Executors.newSingleThreadExecutor()
        }
        if (previewView == null) buildCameraUi()
        val preview = previewView ?: return

        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            val provider = runCatching { providerFuture.get() }.getOrNull() ?: run {
                ScanLog.e(SUB, "provider unavailable")
                return@addListener
            }
            cameraProvider = provider
            // 뷰 크기가 잡힌 뒤 ViewPort 를 만들어야 종횡비가 맞습니다.
            // post 만 쓰면 아직 레이아웃 전일 때 영영 바인딩되지 않으므로 레이아웃을 기다립니다.
            whenLaidOut(preview) { bindUseCases(provider, preview) }
        }, mainExecutor)
    }

    /** 뷰 크기가 확정된 뒤 실행합니다(이미 확정이면 즉시). */
    private fun whenLaidOut(view: View, action: () -> Unit) {
        if (view.width > 0 && view.height > 0) {
            action()
            return
        }
        view.addOnLayoutChangeListener(object : View.OnLayoutChangeListener {
            override fun onLayoutChange(
                v: View, l: Int, t: Int, r: Int, b: Int,
                ol: Int, ot: Int, or_: Int, ob: Int
            ) {
                if (v.width > 0 && v.height > 0) {
                    v.removeOnLayoutChangeListener(this)
                    action()
                }
            }
        })
    }

    private fun bindUseCases(provider: ProcessCameraProvider, view: PreviewView) {
        if (isFinishing || isDestroyed) return
        val viewW = view.width.takeIf { it > 0 } ?: return
        val viewH = view.height.takeIf { it > 0 } ?: return
        if (cameraExecutor.isShutdown) return

        val previewUseCase = Preview.Builder().build().also {
            it.setSurfaceProvider(view.surfaceProvider)
        }

        val analysis = ImageAnalysis.Builder()
            .setResolutionSelector(
                ResolutionSelector.Builder()
                    .setResolutionStrategy(
                        ResolutionStrategy(
                            Size(1280, 960),
                            ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                        )
                    )
                    .build()
            )
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()

        analyzer = FrameAnalyzer(
            recognizer = { MedOcr.client },
            onSignal = { sig -> runOnUiThread { onGateSignal(sig) } },
            onTrigger = { runOnUiThread { requestCapture("auto") } }
        )
        analysis.setAnalyzer(cameraExecutor, analyzer!!)

        val capture = ImageCapture.Builder()
            .setCaptureMode(
                if (ScanTuning.CAPTURE_QUALITY_MODE) ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY
                else ImageCapture.CAPTURE_MODE_ZERO_SHUTTER_LAG
            )
            .build()
        imageCapture = capture

        // ViewPort 가 Preview/Analysis/Capture 에 같은 시야를 걸어 줍니다.
        // 이게 없으면 화면 가이드와 실제 크롭 영역이 어긋납니다(20:9 화면에서 1.8배).
        // PreviewView.viewPort 가 현재 scaleType/회전을 그대로 반영해 주는 정석 경로이고,
        // null 인 경우에만 뷰 크기로 직접 만듭니다.
        val viewPort = view.viewPort ?: ViewPort.Builder(
            Rational(viewW, viewH),
            view.display?.rotation ?: Surface.ROTATION_0
        ).setScaleType(ViewPort.FILL_CENTER).build()

        val group = UseCaseGroup.Builder()
            .setViewPort(viewPort)
            .addUseCase(previewUseCase)
            .addUseCase(analysis)
            .addUseCase(capture)
            .build()

        runCatching {
            provider.unbindAll()
            camera = provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, group)
        }.onFailure {
            ScanLog.e(SUB, "camera bind failed", it)
            Toast.makeText(this, "카메라를 열 수 없어요", Toast.LENGTH_LONG).show()
            finish()
        }

        gateStartedAt = System.currentTimeMillis()
        ScanLog.i(
            SUB,
            "bound view=${viewW}x$viewH qualityMode=${ScanTuning.CAPTURE_QUALITY_MODE} " +
                "focusLock=${ScanTuning.FOCUS_LOCK_BEFORE_CAPTURE}"
        )
    }

    private fun onGateSignal(sig: FrameAnalyzer.Signal) {
        overlay?.state = sig.state
        lastGateSharp = sig.sharpness
        lastGateLuma = sig.meanLuma
        if (BuildConfig.DEBUG) {
            hudText?.text = "sharp=%.1f luma=%d glare=%.2f chars=%d"
                .format(sig.sharpness, sig.meanLuma, sig.glareRatio, sig.charsInGuide)
        }
        if (capturing) return
        statusText?.text = when (sig.hint) {
            FrameAnalyzer.Hint.NONE -> return
            FrameAnalyzer.Hint.GLARE -> "빛이 반사돼요\n각도를 조금 바꿔 주세요"
            FrameAnalyzer.Hint.DARK -> "조금 더 밝은 곳에서\n비춰 주세요"
            FrameAnalyzer.Hint.SHAKY -> "흔들리지 않게\n잠깐만 멈춰 주세요"
            FrameAnalyzer.Hint.READY -> "좋아요, 그대로 계세요"
        }
    }

    /* ───────────────────────── 촬영 ───────────────────────── */

    private fun requestCapture(via: String) {
        if (capturing) return
        val capture = imageCapture ?: return
        capturing = true
        analyzer?.disarm()
        statusText?.removeCallbacks(manualHint)
        statusText?.text = "찍었어요!\n글자를 읽는 중…"
        val requestedAt = System.currentTimeMillis()
        ScanLog.i(SUB, "capture via=$via gateElapsed=${requestedAt - gateStartedAt}ms")

        if (ScanTuning.FOCUS_LOCK_BEFORE_CAPTURE) {
            lockFocusThen { takePicture(capture, via, requestedAt) }
        } else {
            takePicture(capture, via, requestedAt)
        }
    }

    /**
     * 촬영 직전 가이드 중앙에 AF/AE 를 고정합니다.
     * 게이트가 "좋다"고 판정한 프레임과 실제 촬영 프레임 사이(200~800ms)에
     * 초점·노출이 달라지는 것을 막습니다. 수렴을 못 하면 그냥 촬영합니다.
     *
     * disableAutoCancel 로 잠근 3A 는 자동으로 풀리지 않으므로,
     * 재촬영 진입점인 resumeCamera() 가 cancelFocusAndMetering() 으로
     * 반드시 되돌립니다. 이 짝이 깨지면 연속 AF 가 죽은 채 조준하게 됩니다.
     */
    private fun lockFocusThen(next: () -> Unit) {
        val cam = camera
        val view = previewView
        val ov = overlay
        if (cam == null || view == null || ov == null) { next(); return }

        val center = ov.guideCenter()
        val point = runCatching {
            view.meteringPointFactory.createPoint(center[0], center[1])
        }.getOrNull()
        if (point == null) { next(); return }

        val action = FocusMeteringAction.Builder(
            point,
            FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE
        ).disableAutoCancel().build()

        val future = runCatching { cam.cameraControl.startFocusAndMetering(action) }.getOrNull()
        if (future == null) { next(); return }

        var resumed = false
        val proceed = {
            mainHandler.post {
                if (!resumed) {
                    resumed = true
                    focusTimeout?.let { mainHandler.removeCallbacks(it) }
                    focusTimeout = null
                    if (!isFinishing && !isDestroyed) next()
                }
            }
        }
        runCatching {
            future.addListener({
                val ok = runCatching { future.get().isFocusSuccessful }.getOrDefault(false)
                ScanLog.i(SUB, "focus lock success=$ok")
                proceed()
            }, cameraExecutor)
        }.onFailure { proceed() }
        // 수렴이 늦어도 무한정 기다리지 않습니다. 뷰가 아닌 핸들러에 걸어
        // statusText 유무와 무관하게 항상 예약되고, 진행 시 반드시 해제됩니다.
        val timeout = Runnable {
            ScanLog.w(SUB, "focus lock timeout ${ScanTuning.FOCUS_LOCK_TIMEOUT_MS}ms → 그냥 촬영")
            proceed()
        }
        focusTimeout = timeout
        mainHandler.postDelayed(timeout, ScanTuning.FOCUS_LOCK_TIMEOUT_MS)
    }

    private fun takePicture(capture: ImageCapture, via: String, requestedAt: Long) {
        capture.takePicture(cameraExecutor, object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                val shotAt = System.currentTimeMillis()
                ScanLog.i(SUB, "shutter latency=${shotAt - requestedAt}ms via=$via")
                // 화면이 이미 닫히는 중이면 판독·표시로 넘어가지 않고 버립니다.
                // 이미지를 닫지 않으면 버퍼가 반납되지 않습니다.
                if (isFinishing || isDestroyed) {
                    runCatching { image.close() }
                    ScanLog.i(SUB, "captured but activity finishing — 폐기")
                    return
                }
                lifecycleScope.launch {
                    val result = runCatching {
                        val output = withContext(Dispatchers.Default) {
                            CaptureProcessor.process(image).also { image.close() }
                        }
                        if (BuildConfig.DEBUG) {
                            val captured = withContext(Dispatchers.Default) {
                                CaptureProcessor.measureSharpness(output.raw)
                            }
                            // 이 두 값의 상관이 낮으면 게이트 문턱을 아무리 만져도
                            // 소용없다는 뜻입니다(분석 버퍼 해상도의 구조적 한계).
                            ScanLog.i(
                                SUB,
                                "sharp gate=%.1f captured=%.1f luma=%d"
                                    .format(lastGateSharp, captured, lastGateLuma)
                            )
                        }
                        val (outcome, chosen) = MedOcr.recognizeBest(output.raw, output.enhanced)
                        withContext(Dispatchers.Default) {
                            saveDebugCopy(chosen)
                            output.recycleExcept(chosen)
                        }
                        ScanLog.i(SUB, "ocr done total=${System.currentTimeMillis() - requestedAt}ms")
                        chosen to outcome
                    }
                    result
                        .onSuccess { (bmp, ocr) -> showConfirm(bmp, ocr) }
                        .onFailure {
                            ScanLog.e(SUB, "process/ocr failed", it)
                            runCatching { image.close() }
                            if (!isFinishing && !isDestroyed) {
                                resumeCamera("읽기에 실패했어요\n다시 찍어 주세요")
                            }
                        }
                }
            }

            override fun onError(exception: ImageCaptureException) {
                // 액티비티가 파괴되는 도중에 대기 중이던 촬영 요청이 취소되면
                // "Camera is closed" 로 여기에 들어옵니다(뒤로가기·화면 꺼짐 등).
                // 이미 사라진 화면에서 뷰를 만지고 카메라를 다시 무장시키면 안 됩니다.
                if (isFinishing || isDestroyed) {
                    ScanLog.i(SUB, "capture aborted (activity finishing) — 무시")
                    return
                }
                ScanLog.e(SUB, "capture failed", exception)
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    resumeCamera("촬영이 안 됐어요\n다시 시도해 주세요")
                }
            }
        })
    }

    private fun resumeCamera(message: String? = null) {
        capturing = false
        clearConfirmLayer()
        // 촬영 직전 disableAutoCancel 로 잠근 AF/AE 를 반드시 풉니다.
        // 풀지 않으면 이후 조준 단계 내내 연속 AF·AE 가 죽어, 거리·조명이
        // 조금만 바뀌어도 흐리거나 노출이 틀어진 사진이 찍힙니다
        // (재촬영부터 품질이 급락하던 원인).
        runCatching { camera?.cameraControl?.cancelFocusAndMetering() }
            .onFailure { ScanLog.w(SUB, "AF/AE 잠금 해제 실패", it) }
        message?.let { statusText?.text = it }
        analyzer?.rearm()
        gateStartedAt = System.currentTimeMillis()
        armManualHint()
    }

    private fun clearConfirmLayer() {
        confirmLayer?.let { root.removeView(it) }
        confirmLayer = null
        shownBitmap?.let { if (!it.isRecycled) it.recycle() }
        shownBitmap = null
    }

    /* ───────────────────────── 확인 화면 ───────────────────────── */

    private fun showConfirm(bitmap: Bitmap, ocr: MedOcr.Outcome) {
        if (isFinishing || isDestroyed) {
            if (!bitmap.isRecycled) bitmap.recycle()
            return
        }
        ScanLog.i(
            SUB,
            "verdict good=${ocr.good} chars=${ocr.chars} lines=${ocr.lines} " +
                "hints=${ocr.dosageHints} hangul=%.2f".format(ocr.hangulRatio)
        )
        if (!ocr.good) failStreak++ else failStreak = 0
        val exitProposal = !ocr.good && failStreak >= ScanTuning.FAIL_STREAK_EXIT

        val layer = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            isClickable = true
        }
        confirmLayer = layer
        shownBitmap = bitmap

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), topInset + dp(20), dp(20), bottomInset + dp(20))
        }

        column.addView(
            ImageView(this).apply {
                setImageBitmap(bitmap)
                scaleType = ImageView.ScaleType.FIT_CENTER
                setBackgroundColor(Color.rgb(18, 18, 18))
            },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        )

        val verdictLine = when {
            exitProposal -> "계속 잘 안 읽히네요\n다음에 다시 해볼까요?"
            ocr.good -> "글자가 잘 읽혔어요"
            else -> "잘 안 읽혔어요\n다시 찍는 게 좋겠어요"
        }
        column.addView(bigText(verdictLine, 24), textParams(dp(16)))

        val useBtn = bigButton("이 사진 사용", Color.rgb(15, 110, 86)) { onUsePhoto(ocr) }
        val retryBtn = bigButton("다시 찍기", Color.rgb(60, 60, 66)) {
            resumeCamera("약봉투를 네모 안에\n맞춰 주세요")
        }
        val exitBtn = bigButton("다음에 하기", Color.rgb(60, 60, 66)) { finish() }

        when {
            exitProposal -> {
                column.addView(exitBtn, buttonParams(dp(16), true))
                retryBtn.text = "그래도 다시 찍기"
                column.addView(retryBtn, buttonParams(dp(10), false))
            }
            ocr.good -> {
                column.addView(useBtn, buttonParams(dp(16), true))
                column.addView(retryBtn, buttonParams(dp(10), false))
            }
            else -> {
                retryBtn.setBackgroundColor(Color.rgb(186, 117, 23))
                column.addView(retryBtn, buttonParams(dp(16), true))
                useBtn.text = "그래도 이 사진 사용"
                column.addView(useBtn, buttonParams(dp(10), false))
            }
        }

        layer.addView(column, matchParent())
        root.addView(layer, matchParent())
    }

    private fun onUsePhoto(ocr: MedOcr.Outcome) {
        if (sessionMode && ScanUploader.enabled) {
            Toast.makeText(this, "보내는 중…", Toast.LENGTH_SHORT).show()
            lifecycleScope.launch {
                ScanUploader.sendResult(cid, nonce, ocr.rawText)
                    .onSuccess { showResult(ocr, sent = true) }
                    .onFailure {
                        ScanLog.w(SUB, "upload failed", it)
                        Toast.makeText(
                            this@ScanActivity,
                            "전송이 안 됐어요. 잠시 후 다시 시도해 주세요.",
                            Toast.LENGTH_LONG
                        ).show()
                        showResult(ocr, sent = false)
                    }
            }
        } else {
            showResult(ocr, sent = false)
        }
    }

    /** 판독 원문 표시(M0 완료 조건). 전송 여부를 함께 알립니다. */
    private fun showResult(ocr: MedOcr.Outcome, sent: Boolean) {
        clearConfirmLayer()
        val layer = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            isClickable = true
        }
        confirmLayer = layer

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), topInset + dp(20), dp(20), bottomInset + dp(20))
        }
        column.addView(bigText(if (sent) "읽은 내용을 보냈어요" else "읽은 내용이에요", 26))

        val scroll = ScrollView(this)
        scroll.addView(
            TextView(this).apply {
                text = if (ocr.rawText.isBlank()) "(읽힌 글자가 없어요)" else ocr.rawText
                textSize = 20f
                setTextColor(Color.WHITE)
                setPadding(dp(8), dp(16), dp(8), dp(16))
            }
        )
        column.addView(
            scroll,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
                .apply { topMargin = dp(12) }
        )

        column.addView(
            bigButton("완료", Color.rgb(15, 110, 86)) { finish() },
            buttonParams(dp(16), true)
        )
        layer.addView(column, matchParent())
        root.addView(layer, matchParent())
    }

    /* ───────────────────────── 공통 ───────────────────────── */

    @Deprecated("Deprecated in Android but adequate for this two-screen flow")
    override fun onBackPressed() {
        if (confirmLayer != null) {
            resumeCamera("약봉투를 네모 안에\n맞춰 주세요")
        } else {
            @Suppress("DEPRECATION")
            super.onBackPressed()
        }
    }

    override fun onDestroy() {
        statusText?.removeCallbacks(manualHint)
        mainHandler.removeCallbacksAndMessages(null)
        focusTimeout = null
        runCatching { cameraProvider?.unbindAll() }
        if (::cameraExecutor.isInitialized) {
            cameraExecutor.shutdown()
            runCatching { cameraExecutor.awaitTermination(1, TimeUnit.SECONDS) }
        }
        shownBitmap?.let { if (!it.isRecycled) it.recycle() }
        shownBitmap = null
        // 구성 변경 재생성 시에는 닫지 않습니다 — 공유 인식기는 한 번 닫으면 못 되살립니다.
        if (isFinishing) MedOcr.close()
        super.onDestroy()
    }

    /** debug 빌드에서만 전처리 결과를 캐시에 저장(현장 튜닝용). release 는 저장하지 않음. */
    private fun saveDebugCopy(bitmap: Bitmap) {
        if (!BuildConfig.DEBUG) return
        runCatching {
            val name = "scan_" +
                SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".jpg"
            File(cacheDir, name).outputStream().use {
                bitmap.compress(Bitmap.CompressFormat.JPEG, 92, it)
            }
            ScanLog.i(SUB, "debug copy saved: $name (${bitmap.width}x${bitmap.height})")
        }
    }

    private fun bigText(value: String, sizeSp: Int) = TextView(this).apply {
        text = value
        textSize = sizeSp.toFloat()
        setTextColor(Color.WHITE)
        gravity = Gravity.CENTER
        typeface = Typeface.DEFAULT_BOLD
    }

    private fun bigButton(label: String, bg: Int, onClick: () -> Unit) = Button(this).apply {
        text = label
        textSize = 24f
        isAllCaps = false
        setTextColor(Color.WHITE)
        setBackgroundColor(bg)
        setOnClickListener { onClick() }
    }

    /** primary = 권장 버튼(크게), false = 대안 버튼(작게) */
    private fun buttonParams(topMargin: Int, primary: Boolean = true) =
        LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            if (primary) dp(92) else dp(68)
        ).apply { this.topMargin = topMargin }

    private fun textParams(topMargin: Int) =
        LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { this.topMargin = topMargin }

    private fun matchParent() = FrameLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.MATCH_PARENT
    )

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val SUB = "Scan"

        /* 화면이 실제로 떴는지 확인하는 마커.
           오버레이 권한이 있어도 일부 제조사 롬에서는 startActivity 가
           예외 없이 조용히 무시됩니다. 그러면 ScanNotifier 는 "성공"으로
           보고 알림을 띄우지 않아, 어르신에게 아무 일도 일어나지 않습니다.
           그 실패를 감지하려고 액티비티가 뜬 시각을 남깁니다. */
        @Volatile private var shownCid: String = ""
        @Volatile private var shownAt: Long = 0L

        fun markShown(cid: String) {
            shownCid = cid
            shownAt = System.currentTimeMillis()
        }

        /** since 이후에 이 cid 로 화면이 떴는가 */
        fun wasShownSince(cid: String, since: Long): Boolean =
            shownAt >= since && (cid.isBlank() || shownCid == cid)
    }
}
