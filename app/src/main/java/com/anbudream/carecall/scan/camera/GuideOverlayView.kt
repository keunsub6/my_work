package com.anbudream.carecall.scan.camera

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.view.View
import com.anbudream.carecall.scan.ScanTuning

/**
 * 프리뷰 위에 가이드 사각형을 그립니다.
 * 바깥은 어둡게, 테두리는 게이트 상태에 따라 색이 바뀝니다.
 *
 * 좌표계 정합의 핵심:
 * 예전에는 화면 크기 / 분석버퍼 크기 / 촬영본 크기에 각각 같은 비율을 곱했는데,
 * PreviewView 가 FILL_CENTER 로 좌우를 잘라 보여주기 때문에 셋이 서로 다른
 * 영역을 가리켰습니다(20:9 화면 + 4:3 센서에서 촬영 crop 이 화면 가이드보다 1.8배 넓음).
 *
 * 이제 computeGuideRect 는 '기준 사각형' 안에서 비율을 적용합니다.
 * 기준 사각형으로 화면은 뷰 경계를, 분석·촬영은 ViewPort 가 준 cropRect 를 넘기면
 * 세 좌표계가 같은 실제 영역을 가리킵니다.
 */
class GuideOverlayView(context: Context) : View(context) {

    enum class State { IDLE, ALIGNING, READY }

    var state: State = State.IDLE
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    private val dimPaint = Paint().apply {
        color = Color.argb(150, 0, 0, 0)
        style = Paint.Style.FILL
    }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 6f
    }
    private val path = Path()
    private val guide = RectF()
    private val viewBounds = RectF()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        viewBounds.set(0f, 0f, width.toFloat(), height.toFloat())
        computeGuideRect(viewBounds, guide)

        path.reset()
        path.fillType = Path.FillType.EVEN_ODD
        path.addRect(viewBounds, Path.Direction.CW)
        path.addRoundRect(guide, CORNER, CORNER, Path.Direction.CW)
        canvas.drawPath(path, dimPaint)

        borderPaint.color = when (state) {
            State.IDLE -> Color.argb(230, 200, 200, 200)
            State.ALIGNING -> Color.argb(240, 255, 170, 40)
            State.READY -> Color.argb(255, 60, 220, 120)
        }
        canvas.drawRoundRect(guide, CORNER, CORNER, borderPaint)
    }

    /** 화면 좌표 기준 가이드 중심 — AF/AE 측광점 계산에 씁니다. */
    fun guideCenter(): FloatArray {
        viewBounds.set(0f, 0f, width.toFloat(), height.toFloat())
        computeGuideRect(viewBounds, guide)
        return floatArrayOf(guide.centerX(), guide.centerY())
    }

    companion object {
        private const val CORNER = 28f

        /** 기준 사각형 안에서 가이드 사각형을 계산합니다. 모든 좌표계가 이 함수를 씁니다. */
        fun computeGuideRect(base: RectF, out: RectF) {
            val gw = base.width() * ScanTuning.GUIDE_WIDTH_FRACTION
            val gh = base.height() * ScanTuning.GUIDE_HEIGHT_FRACTION
            val cx = base.centerX()
            val cy = base.top + base.height() * ScanTuning.GUIDE_CENTER_Y_FRACTION
            out.set(cx - gw / 2f, cy - gh / 2f, cx + gw / 2f, cy + gh / 2f)
        }

        /**
         * 버퍼 좌표계 → upright(회전 적용) 좌표계로 옮기는 행렬.
         * 결과 좌표는 (0,0)-(W',H') 범위로 정규화됩니다.
         * ML Kit 의 boundingBox 가 이 upright 좌표계를 씁니다.
         */
        fun uprightMatrix(rotationDegrees: Int, bufferW: Int, bufferH: Int): Matrix {
            val m = Matrix()
            m.postRotate(rotationDegrees.toFloat())
            val bounds = RectF(0f, 0f, bufferW.toFloat(), bufferH.toFloat())
            m.mapRect(bounds)
            m.postTranslate(-bounds.left, -bounds.top)
            return m
        }

        /**
         * cropRect(버퍼 좌표) 안의 가이드를 upright 좌표계로 계산합니다.
         * ML Kit 결과 필터링용.
         */
        fun guideInUpright(
            cropRect: Rect,
            rotationDegrees: Int,
            bufferW: Int,
            bufferH: Int,
            out: RectF
        ) {
            val m = uprightMatrix(rotationDegrees, bufferW, bufferH)
            val cropUp = RectF(cropRect)
            m.mapRect(cropUp)
            computeGuideRect(cropUp, out)
        }

        /**
         * 같은 가이드를 버퍼 좌표계로 되돌립니다.
         * 선명도·밝기 샘플링은 회전 전 버퍼를 직접 읽으므로 이쪽이 필요합니다.
         */
        fun guideInBuffer(
            cropRect: Rect,
            rotationDegrees: Int,
            bufferW: Int,
            bufferH: Int,
            out: RectF
        ) {
            guideInUpright(cropRect, rotationDegrees, bufferW, bufferH, out)
            val inverse = Matrix()
            if (uprightMatrix(rotationDegrees, bufferW, bufferH).invert(inverse)) {
                inverse.mapRect(out)
            }
        }
    }
}
