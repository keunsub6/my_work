package com.anbudream.carecall.scan.camera

import android.graphics.Rect
import android.graphics.RectF
import androidx.annotation.OptIn
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.anbudream.carecall.scan.ScanLog
import com.anbudream.carecall.scan.ScanTuning
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognizer
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 자동 촬영 게이트.
 *
 * 매 프레임(값싼 연산): 가이드 영역의 평균 밝기, 라플라시안 분산, 포화(반사) 비율
 * 260ms 간격: ML Kit 로 가이드 안 글자 수
 * "글자 충분 + 선명 + 밝음 + 반사 적음" 이 3연속이면 onTrigger 를 1회 발사
 *
 * 이전 버전 대비 고친 것 두 가지:
 *  1) 라플라시안 이웃 간격을 4px → 1px 로 분리(SAMPLE_STEP 과 LAP_SPACING).
 *     4px 커널은 표 선·로고·테두리에만 반응하고 정작 OCR 대상인 1~2px 획의
 *     흐림을 놓쳤습니다. 샘플 '위치' 간격은 4px 그대로라 연산량은 동일합니다.
 *  2) 샘플 영역을 버퍼 중앙 고정(20~80%)에서 실제 가이드 영역으로 교체.
 */
class FrameAnalyzer(
    /** 매 프레임 조회합니다. 인식기가 닫혔다 다시 열려도 stale 참조가 남지 않습니다. */
    private val recognizer: () -> TextRecognizer,
    private val onSignal: (Signal) -> Unit,
    private val onTrigger: () -> Unit
) : ImageAnalysis.Analyzer {

    data class Signal(
        val sharpness: Double,
        val meanLuma: Int,
        val glareRatio: Float,
        val charsInGuide: Int,
        val state: GuideOverlayView.State,
        val hint: Hint
    )

    enum class Hint { NONE, DARK, GLARE, SHAKY, READY }

    private val inflight = AtomicBoolean(false)
    private val armed = AtomicBoolean(true)
    @Volatile private var lastOcrAt = 0L
    @Volatile private var stable = 0
    @Volatile private var lastChars = 0

    private val guideBuffer = RectF()
    private val guideUpright = RectF()

    fun rearm() {
        stable = 0
        lastChars = 0
        armed.set(true)
    }

    fun disarm() {
        armed.set(false)
    }

    @OptIn(ExperimentalGetImage::class)
    override fun analyze(proxy: ImageProxy) {
        if (!armed.get()) {
            proxy.close()
            return
        }

        val rotation = proxy.imageInfo.rotationDegrees
        val crop = effectiveCropRect(proxy)

        GuideOverlayView.guideInBuffer(crop, rotation, proxy.width, proxy.height, guideBuffer)
        val stats = sampleGuide(proxy, guideBuffer)

        val now = System.currentTimeMillis()
        val runOcr = now - lastOcrAt >= ScanTuning.OCR_INTERVAL_MS &&
            inflight.compareAndSet(false, true)

        if (!runOcr) {
            emit(stats, lastChars)
            proxy.close()
            return
        }

        lastOcrAt = now
        val mediaImage = proxy.image
        if (mediaImage == null) {
            inflight.set(false)
            proxy.close()
            return
        }

        GuideOverlayView.guideInUpright(crop, rotation, proxy.width, proxy.height, guideUpright)
        val guideForOcr = RectF(guideUpright)
        val input = InputImage.fromMediaImage(mediaImage, rotation)

        recognizer().process(input)
            .addOnSuccessListener { text ->
                var inside = 0
                for (block in text.textBlocks) {
                    for (line in block.lines) {
                        val b = line.boundingBox ?: continue
                        if (guideForOcr.contains(b.exactCenterX(), b.exactCenterY())) {
                            inside += line.text.count { !it.isWhitespace() }
                        }
                    }
                }
                lastChars = inside

                val ok = inside >= ScanTuning.MIN_CHARS_IN_GUIDE &&
                    stats.sharpness >= ScanTuning.SHARPNESS_MIN &&
                    stats.meanLuma >= ScanTuning.DARK_LUMA &&
                    stats.glareRatio <= ScanTuning.GLARE_RATIO_MAX

                stable = if (ok) stable + 1 else 0
                emit(stats, inside)

                if (stable >= ScanTuning.STABLE_TICKS && armed.compareAndSet(true, false)) {
                    ScanLog.i(
                        SUB,
                        "auto trigger sharp=%.1f luma=%d glare=%.3f chars=%d"
                            .format(stats.sharpness, stats.meanLuma, stats.glareRatio, inside)
                    )
                    onTrigger()
                }
            }
            .addOnFailureListener { ScanLog.w(SUB, "frame ocr failed", it) }
            .addOnCompleteListener {
                inflight.set(false)
                proxy.close()
            }
    }

    /**
     * ViewPort 를 붙이면 cropRect 가 '화면에 실제로 보이는 영역'으로 채워집니다.
     * 붙지 않았거나 값이 비정상이면 버퍼 전체로 폴백합니다(동작은 하되 예전처럼
     * 화면과 어긋나므로, 로그로 확인할 수 있게 남깁니다).
     */
    private fun effectiveCropRect(proxy: ImageProxy): Rect {
        val c = proxy.cropRect
        val valid = c.width() > 0 && c.height() > 0 &&
            c.width() <= proxy.width && c.height() <= proxy.height
        if (!valid) {
            if (!warnedCrop) {
                warnedCrop = true
                ScanLog.w(SUB, "cropRect 비정상 → 버퍼 전체 사용 (ViewPort 미적용?)")
            }
            return Rect(0, 0, proxy.width, proxy.height)
        }
        return c
    }

    private fun emit(stats: Stats, chars: Int) {
        val hint = when {
            chars < ScanTuning.MIN_CHARS_IN_GUIDE -> Hint.NONE
            stats.glareRatio > ScanTuning.GLARE_RATIO_MAX -> Hint.GLARE
            stats.meanLuma < ScanTuning.DARK_LUMA -> Hint.DARK
            stats.sharpness < ScanTuning.SHARPNESS_MIN -> Hint.SHAKY
            else -> Hint.READY
        }
        val state = when (hint) {
            Hint.NONE -> GuideOverlayView.State.IDLE
            Hint.READY -> GuideOverlayView.State.READY
            else -> GuideOverlayView.State.ALIGNING
        }
        onSignal(
            Signal(stats.sharpness, stats.meanLuma, stats.glareRatio, chars, state, hint)
        )
    }

    private class Stats(val meanLuma: Int, val sharpness: Double, val glareRatio: Float)

    /**
     * 가이드 영역만 순회하며 밝기·선명도·포화비율을 한 번에 구합니다.
     * 위치는 SAMPLE_STEP(4px) 간격, 라플라시안 이웃은 LAP_SPACING(1px) 간격.
     * 샘플당 읽기 5회로 이전과 동일합니다.
     */
    private fun sampleGuide(proxy: ImageProxy, guide: RectF): Stats {
        val plane = proxy.planes[0]
        val buf = plane.buffer
        val rowStride = plane.rowStride
        val pixStride = plane.pixelStride
        val step = ScanTuning.SAMPLE_STEP
        val gap = ScanTuning.LAP_SPACING

        val x0 = guide.left.toInt().coerceAtLeast(gap)
        val x1 = guide.right.toInt().coerceAtMost(proxy.width - gap - 1)
        val y0 = guide.top.toInt().coerceAtLeast(gap)
        val y1 = guide.bottom.toInt().coerceAtMost(proxy.height - gap - 1)
        if (x1 <= x0 || y1 <= y0) return Stats(0, 0.0, 0f)

        val floor = ScanTuning.LAP_NOISE_FLOOR

        var lumaSum = 0L
        var lapSum = 0.0
        var lapSqSum = 0.0
        var saturated = 0
        var n = 0
        var lapN = 0

        var y = y0
        while (y <= y1) {
            val row = y * rowStride
            val rowUp = (y - gap) * rowStride
            val rowDown = (y + gap) * rowStride
            var x = x0
            while (x <= x1) {
                val xi = x * pixStride
                val c = buf.get(row + xi).toInt() and 0xFF
                val l = buf.get(row + (x - gap) * pixStride).toInt() and 0xFF
                val r = buf.get(row + (x + gap) * pixStride).toInt() and 0xFF
                val u = buf.get(rowUp + xi).toInt() and 0xFF
                val d = buf.get(rowDown + xi).toInt() and 0xFF
                val lapI = 4 * c - l - r - u - d
                lumaSum += c
                if (c >= SATURATION_LEVEL) saturated++
                n++
                // 노이즈 바닥 미만은 선명도 집계에서 제외합니다.
                // 밝기·포화 비율은 전체 샘플 기준을 유지해야 하므로 카운터를 분리합니다.
                if (lapI >= floor || lapI <= -floor) {
                    val lap = lapI.toDouble()
                    lapSum += lap
                    lapSqSum += lap * lap
                    lapN++
                }
                x += step
            }
            y += step
        }

        if (n == 0) return Stats(0, 0.0, 0f)
        val mean = (lumaSum / n).toInt()
        // 분모는 lapN 이 아니라 n 입니다. lapN 으로 나누면 "엣지가 거의 없는
        // 흐린 프레임"에서 살아남은 소수 샘플만으로 평균이 커져, 흐릴수록
        // 선명도가 높아지는 역전이 생깁니다. 전체 샘플 대비 엣지 에너지로 봅니다.
        val variance = if (lapN == 0) 0.0 else {
            val lapMean = lapSum / n
            (lapSqSum / n - lapMean * lapMean).coerceAtLeast(0.0)
        }
        return Stats(mean, variance, saturated.toFloat() / n)
    }

    companion object {
        private const val SUB = "Gate"
        private const val SATURATION_LEVEL = 250
        @Volatile private var warnedCrop = false
    }
}
