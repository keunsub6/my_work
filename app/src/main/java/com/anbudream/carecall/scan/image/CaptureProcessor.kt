package com.anbudream.carecall.scan.image

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.os.Build
import androidx.camera.core.ImageProxy
import com.anbudream.carecall.scan.ScanLog
import com.anbudream.carecall.scan.ScanTuning
import com.anbudream.carecall.scan.camera.GuideOverlayView
import kotlin.math.ceil
import kotlin.math.floor

/**
 * 촬영 JPEG → OCR 투입용 비트맵.
 *
 * 수행하는 전처리는 3가지뿐입니다: 회전 보정, 가이드 크롭, 선형 대비 스트레치.
 * 원근 보정·이진화·샤프닝은 의도적으로 넣지 않았습니다 — 보간이 작은 한글 획을
 * 뭉개고, 사각형 검출이 실패하는 상황(구겨진 봉투)이 정확히 그게 필요한 상황입니다.
 *
 * 좌표: ImageProxy.cropRect(ViewPort 가 채움)를 upright 좌표로 옮겨 화면
 * 가이드와 동일한 실제 영역을 구한 뒤, 그 사각형만 부분 디코딩합니다.
 *
 * 부분 디코딩(BitmapRegionDecoder)을 쓰는 이유:
 * 전체 12MP 를 디코딩할 필요가 없어져 ARGB_8888 을 쓸 수 있습니다.
 * RGB_565(채널당 5~6비트)는 감열지의 옅은 회색 글자에서 획 경계 계조를
 * 8단계 단위로 양자화해 OCR 입력 품질을 깎았습니다. 8888 가이드 크롭
 * (~1750x2100 ≈ 15MB)은 예전 565 전체 디코딩(4032x3024 ≈ 24MB)보다도
 * 작아, 화질과 메모리를 동시에 잡습니다. 부분 디코딩이 실패하는 예외
 * 상황에서만 예전 경로(565 전체 디코딩 → 회전 → 크롭)로 폴백합니다.
 */
object CaptureProcessor {
    private const val SUB = "Process"

    /**
     * 촬영·크롭된 비트맵의 선명도를 게이트와 같은 척도(1px 라플라시안 분산,
     * 노이즈 바닥 적용)로 잽니다. 게이트는 1280x960 분석 버퍼에서 판정하므로
     * 작은 한글 획의 블러를 놓칠 수 있는데, 이 값과 게이트 값을 함께 로그로
     * 남겨야 "게이트가 통과시킨 프레임이 실제로 선명했는가"를 데이터로
     * 확인할 수 있습니다. 튜닝에만 쓰이고 판정에는 관여하지 않습니다.
     */
    fun measureSharpness(src: Bitmap): Double {
        val w = src.width
        val h = src.height
        if (w < 8 || h < 8) return 0.0
        val step = ScanTuning.SAMPLE_STEP
        val floor = ScanTuning.LAP_NOISE_FLOOR
        val rows = arrayOf(IntArray(w), IntArray(w), IntArray(w))

        fun luma(p: Int) = (Color.red(p) * 77 + Color.green(p) * 151 + Color.blue(p) * 28) shr 8

        var lapSum = 0.0
        var lapSqSum = 0.0
        var n = 0
        var lapN = 0

        var y = 1
        while (y < h - 1) {
            src.getPixels(rows[0], 0, w, 0, y - 1, w, 1)
            src.getPixels(rows[1], 0, w, 0, y, w, 1)
            src.getPixels(rows[2], 0, w, 0, y + 1, w, 1)
            var x = 1
            while (x < w - 1) {
                val c = luma(rows[1][x])
                val lapI = 4 * c - luma(rows[1][x - 1]) - luma(rows[1][x + 1]) -
                    luma(rows[0][x]) - luma(rows[2][x])
                n++
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
        if (n == 0 || lapN == 0) return 0.0
        val mean = lapSum / n
        return (lapSqSum / n - mean * mean).coerceAtLeast(0.0)
    }

    /** OCR 입력 2종. raw = 크롭만, enhanced = 크롭 + 대비 보정 */
    class Output(val raw: Bitmap, val enhanced: Bitmap?) {
        /** 확인 화면에 보여줄 대표 이미지 */
        val display: Bitmap get() = enhanced ?: raw

        fun recycleExcept(keep: Bitmap?) {
            if (raw !== keep && !raw.isRecycled) raw.recycle()
            enhanced?.let { if (it !== keep && !it.isRecycled) it.recycle() }
        }
    }

    fun process(proxy: ImageProxy): Output {
        val buffer = proxy.planes[0].buffer
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)
        val rotation = proxy.imageInfo.rotationDegrees
        val cropRect = Rect(proxy.cropRect)

        val cropped = decodeGuideRegion(bytes, cropRect, rotation)
            ?: decodeFullThenCrop(bytes, cropRect, rotation)

        val enhanced = if (ScanTuning.DUAL_PASS_OCR) stretchContrast(cropped, keepSource = true)
        else stretchContrast(cropped, keepSource = false)

        return if (ScanTuning.DUAL_PASS_OCR) {
            Output(cropped, if (enhanced === cropped) null else enhanced)
        } else {
            Output(enhanced, null)
        }
    }

    /**
     * 가이드 영역만 원본 해상도·ARGB_8888 로 부분 디코딩합니다.
     *
     * 화면 오버레이와 같은 식(computeGuideRect)으로 upright 좌표에서 가이드를
     * 구한 뒤, 역행렬로 원본 JPEG 좌표의 부분 디코딩 사각형을 얻습니다.
     * 크롭 후 회전과 회전 후 크롭은 90도 배수에서 동일하므로, 작은 크롭만
     * 회전해 예전 파이프라인과 같은 결과를 만듭니다.
     *
     * 실패(형식 미지원, 비정상 좌표 등) 시 null 을 돌려 폴백을 태웁니다.
     */
    private fun decodeGuideRegion(
        bytes: ByteArray,
        cropRect: Rect,
        rotationDegrees: Int
    ): Bitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val w = bounds.outWidth
        val h = bounds.outHeight
        if (w <= 0 || h <= 0) return@runCatching null

        val base = RectF(cropRect)
        val valid = base.width() > 1f && base.height() > 1f &&
            base.left >= -1f && base.top >= -1f &&
            base.right <= w + 1f && base.bottom <= h + 1f
        if (!valid) {
            ScanLog.w(SUB, "cropRect 비정상 → 전체 프레임 기준 (ViewPort 미적용?)")
            base.set(0f, 0f, w.toFloat(), h.toFloat())
        }

        val upright = GuideOverlayView.uprightMatrix(rotationDegrees, w, h)
        val baseUp = RectF(base)
        upright.mapRect(baseUp)
        val guide = RectF()
        GuideOverlayView.computeGuideRect(baseUp, guide)
        guide.inset(
            -baseUp.width() * ScanTuning.CROP_MARGIN_FRACTION,
            -baseUp.height() * ScanTuning.CROP_MARGIN_FRACTION
        )
        val inverse = Matrix()
        if (!upright.invert(inverse)) return@runCatching null
        inverse.mapRect(guide)

        val region = Rect(
            floor(guide.left).toInt().coerceIn(0, w - 1),
            floor(guide.top).toInt().coerceIn(0, h - 1),
            ceil(guide.right).toInt().coerceIn(1, w),
            ceil(guide.bottom).toInt().coerceIn(1, h)
        )
        if (region.width() < 16 || region.height() < 16) return@runCatching null

        // 폴백 경로와 같은 하한 논리를 '크롭 크기'에 적용합니다.
        // 통상 크롭 긴 변(~2100)은 2·DECODE_MIN_LONG_EDGE 미만이라 sample=1,
        // 즉 원본 해상도 그대로 디코딩됩니다.
        var sample = 1
        var longEdge = maxOf(region.width(), region.height())
        while (longEdge / 2 >= ScanTuning.DECODE_MIN_LONG_EDGE) {
            sample *= 2
            longEdge /= 2
        }

        @Suppress("DEPRECATION") // minSdk 26 — 4-인자 newInstance 는 31 미만 유일 경로
        val decoder = BitmapRegionDecoder.newInstance(bytes, 0, bytes.size, false)
            ?: return@runCatching null
        val out = try {
            decoder.decodeRegion(
                region,
                BitmapFactory.Options().apply {
                    inSampleSize = sample
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                }
            )
        } finally {
            decoder.recycle()
        }
        out?.let { decoded ->
            ScanLog.i(
                SUB,
                "capture ${w}x$h sample=$sample decoded=${decoded.width}x${decoded.height} " +
                    "crop=${decoded.width}x${decoded.height} config=8888 mode=region"
            )
            rotate(decoded, rotationDegrees)
        }
    }.getOrElse {
        ScanLog.w(SUB, "부분 디코딩 실패 → 전체 디코딩 폴백", it)
        null
    }

    /** 폴백: 예전 경로(전체 디코딩 → 회전 → 크롭). 메모리 때문에 RGB_565 유지. */
    private fun decodeFullThenCrop(bytes: ByteArray, cropRect: Rect, rotationDegrees: Int): Bitmap {
        val (decoded, sample) = decodeBounded(bytes)
        val decodedW = decoded.width
        val decodedH = decoded.height
        val rotated = rotate(decoded, rotationDegrees)
        val cropped = cropToGuide(rotated, cropRect, sample, rotationDegrees, decodedW, decodedH)
        ScanLog.i(
            SUB,
            "capture sample=$sample decoded=${decodedW}x$decodedH " +
                "crop=${cropped.width}x${cropped.height} config=565 mode=full"
        )
        return cropped
    }

    /**
     * (폴백 전용) 긴 변이 DECODE_MIN_LONG_EDGE 밑으로 내려가지 않는 선에서만 축소합니다.
     * (상한이 아니라 하한입니다 — 12MP 촬영본은 축소 없이 그대로 디코딩됩니다.)
     * 전체 프레임을 디코딩해야 하는 이 경로에서만 RGB_565 를 유지합니다:
     * 4032x3024 기준 ARGB_8888 은 48.8MB, 565 는 24.4MB 로 OOM 여유가 커집니다.
     * 정상 경로(부분 디코딩)는 크롭만 디코딩하므로 8888 을 씁니다.
     */
    private fun decodeBounded(bytes: ByteArray): Pair<Bitmap, Int> {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        var sample = 1
        var longEdge = maxOf(opts.outWidth, opts.outHeight)
        while (longEdge / 2 >= ScanTuning.DECODE_MIN_LONG_EDGE) {
            sample *= 2
            longEdge /= 2
        }
        val real = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.RGB_565
        }
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, real)
            ?: throw IllegalStateException("decode failed")
        return bmp to sample
    }

    private fun rotate(src: Bitmap, rotationDegrees: Int): Bitmap {
        if (rotationDegrees == 0) return src
        val m = Matrix().apply { postRotate(rotationDegrees.toFloat()) }
        val out = Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
        if (out !== src) src.recycle()
        return out
    }

    /**
     * cropRect(원본 JPEG 좌표) → 디코딩 축소 → 회전 → 그 안에서 가이드 비율 적용.
     * 화면 오버레이와 같은 computeGuideRect 식을 쓰므로 어르신이 네모에 맞춘
     * 영역이 그대로 잘립니다.
     */
    private fun cropToGuide(
        rotated: Bitmap,
        cropRect: Rect,
        sample: Int,
        rotationDegrees: Int,
        decodedW: Int,
        decodedH: Int
    ): Bitmap {
        val base = RectF(
            cropRect.left / sample.toFloat(),
            cropRect.top / sample.toFloat(),
            cropRect.right / sample.toFloat(),
            cropRect.bottom / sample.toFloat()
        )
        val valid = base.width() > 1f && base.height() > 1f &&
            base.width() <= decodedW + 1f && base.height() <= decodedH + 1f
        if (!valid) {
            ScanLog.w(SUB, "cropRect 비정상 → 전체 프레임 사용 (ViewPort 미적용?)")
            base.set(0f, 0f, decodedW.toFloat(), decodedH.toFloat())
        }

        GuideOverlayView.uprightMatrix(rotationDegrees, decodedW, decodedH).mapRect(base)

        val guide = RectF()
        GuideOverlayView.computeGuideRect(base, guide)
        val mx = base.width() * ScanTuning.CROP_MARGIN_FRACTION
        val my = base.height() * ScanTuning.CROP_MARGIN_FRACTION

        val r = Rect(
            (guide.left - mx).toInt().coerceAtLeast(0),
            (guide.top - my).toInt().coerceAtLeast(0),
            (guide.right + mx).toInt().coerceAtMost(rotated.width),
            (guide.bottom + my).toInt().coerceAtMost(rotated.height)
        )
        if (r.width() <= 0 || r.height() <= 0) return rotated
        val out = Bitmap.createBitmap(rotated, r.left, r.top, r.width(), r.height())
        if (out !== rotated) rotated.recycle()
        return out
    }

    /**
     * 휘도 p2~p98 을 0~255 로 펴는 선형 스트레치.
     *
     * range < 30 이면 생략합니다. 이건 "저대비 인쇄를 놓치는 조건"이 아닙니다 —
     * 흐린 감열 인쇄도 흰 바탕(220↑)과 회색 글자(150↓)가 공존해 range 는 보통 60~90 입니다.
     * range < 30 은 거의 균일한 화면(빈 종이, 반사로 날아간 사진, 렌즈 가림)이고,
     * 이걸 2.2배 증폭하면 노이즈만 커집니다.
     */
    private fun stretchContrast(src: Bitmap, keepSource: Boolean): Bitmap {
        val w = src.width
        val h = src.height
        val stepX = (w / 160).coerceAtLeast(1)
        val stepY = (h / 160).coerceAtLeast(1)
        val cols = ((w + stepX - 1) / stepX)
        val rowBuf = IntArray(w)

        val hist = IntArray(256)
        var total = 0
        var y = 0
        while (y < h) {
            src.getPixels(rowBuf, 0, w, 0, y, w, 1)
            var x = 0
            while (x < w) {
                val p = rowBuf[x]
                val luma =
                    (Color.red(p) * 77 + Color.green(p) * 151 + Color.blue(p) * 28) shr 8
                hist[luma.coerceIn(0, 255)]++
                total++
                x += stepX
            }
            y += stepY
        }
        if (total == 0 || cols == 0) return src

        var acc = 0
        var p2 = 0
        var p98 = 255
        val lowTarget = total * 2 / 100
        val highTarget = total * 98 / 100
        for (i in 0..255) {
            acc += hist[i]
            if (acc >= lowTarget) { p2 = i; break }
        }
        acc = 0
        for (i in 0..255) {
            acc += hist[i]
            if (acc >= highTarget) { p98 = i; break }
        }

        val range = p98 - p2
        if (range < ScanTuning.CONTRAST_MIN_RANGE || range >= 250) {
            ScanLog.i(SUB, "contrast skip p2=$p2 p98=$p98")
            return src
        }
        val scale = (255f / range).coerceAtMost(ScanTuning.CONTRAST_MAX_SCALE)
        val offset = -p2 * scale

        val cm = ColorMatrix(
            floatArrayOf(
                scale, 0f, 0f, 0f, offset,
                0f, scale, 0f, 0f, offset,
                0f, 0f, scale, 0f, offset,
                0f, 0f, 0f, 1f, 0f
            )
        )
        // 입력 계조를 그대로 유지합니다(정상 경로 8888 → 8888).
        // 예전처럼 565 로 고정하면 부분 디코딩으로 살린 계조를 여기서 다시 버립니다.
        // HARDWARE 는 createBitmap 이 거부하는 유일한 config 라 따로 걸러냅니다.
        // HARDWARE 는 createBitmap 이 거부하는 유일한 config 입니다.
        // 상수 자체가 API 26 에 추가되어, minSdk 가 26 미만인 모듈에서도
        // 안전하도록 버전 가드를 함께 둡니다.
        val srcConfig = src.config
        val isHardware = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            srcConfig == Bitmap.Config.HARDWARE
        val outConfig = if (srcConfig == null || isHardware) {
            Bitmap.Config.ARGB_8888
        } else {
            srcConfig
        }
        val out = Bitmap.createBitmap(w, h, outConfig)
        val paint = Paint().apply { colorFilter = ColorMatrixColorFilter(cm) }
        Canvas(out).drawBitmap(src, 0f, 0f, paint)
        if (!keepSource) src.recycle()
        ScanLog.i(SUB, "contrast p2=$p2 p98=$p98 scale=%.2f".format(scale))
        return out
    }
}
