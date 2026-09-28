package com.anbudream.carecall.scan.ocr

import android.graphics.Bitmap
import com.anbudream.carecall.scan.ScanLog
import com.anbudream.carecall.scan.ScanTuning
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * ML Kit 한글 인식기 공유 지점.
 * 분석 게이트(FrameAnalyzer)와 촬영 후 판독이 같은 인스턴스를 씁니다.
 */
object MedOcr {

    /**
     * 판정의 한계를 분명히 해둡니다.
     * good 은 "글자답게 읽혔다"는 뜻이지 "내용이 정확하다"는 뜻이 아닙니다.
     * `1일 3회` 가 `1일 8회` 로 잘못 읽혀도 글자수·줄수·한글비율은 그대로라
     * good=true 가 나옵니다. 이 판정은 어르신에게 재촬영을 권할지 정하는
     * UI 용도이고, 복약정보의 정확성 보증은 서버·대화 계층의 몫입니다.
     *
     * dosageHints 는 복약 표현이 잡혔는지 알려주는 보조 신호입니다.
     * 판정을 막는 데 쓰지 않습니다 — 약국 양식마다 표기가 달라
     * 하드 조건으로 걸면 멀쩡한 봉투를 거부하게 됩니다.
     */
    data class Outcome(
        val rawText: String,
        val chars: Int,
        val lines: Int,
        val hangulRatio: Float,
        val dosageHints: Int,
        val good: Boolean
    ) {
        /**
         * 이중 판독에서 어느 쪽을 채택할지 고르는 점수.
         *
         * chars 를 무제한 선형으로 두면 대비 증폭이 노이즈에서 만들어낸
         * 유령 글자가 그대로 가점되어 나쁜 쪽이 이깁니다. 그래서
         *  - chars 에 상한을 두고,
         *  - 한글 비율을 가점에 넣습니다(유령 글자는 대개 한글이 아닌 파편).
         * 채택 판정에는 ADOPT_MARGIN 을 함께 써서 애매하면 원본을 남깁니다.
         */
        val score: Int get() =
            minOf(chars, ScanTuning.SCORE_CHARS_CAP) +
                dosageHints * 25 +
                (if (good) 40 else 0) +
                (hangulRatio * 40).toInt()
    }

    /** 복약 표현 후보. 양식 종속을 피하려 느슨하게 잡았습니다. */
    private val DOSAGE_PATTERNS = listOf(
        Regex("""1\s*일\s*\d+\s*회"""),
        Regex("""\d+\s*일\s*분"""),
        Regex("""식\s*(전|후|간)"""),
        Regex("""취침\s*전"""),
        Regex("""\d+\s*(mg|밀리그램|ml|mL)""", RegexOption.IGNORE_CASE),
        Regex("""\d+\s*정"""),
        Regex("""아침|점심|저녁""")
    )

    @Volatile private var instance: TextRecognizer? = null

    /**
     * 재생성 가능한 싱글턴입니다.
     *
     * lazy val 로 두면 close() 후 되살릴 수 없어, "알림 탭 → 촬영 → 완료 → 다시 알림 탭"
     * 처럼 같은 프로세스에서 액티비티가 재생성될 때 닫힌 recognizer 를 써서 죽습니다.
     */
    val client: TextRecognizer
        @Synchronized get() = instance ?: TextRecognition
            .getClient(KoreanTextRecognizerOptions.Builder().build())
            .also { instance = it }

    @Synchronized
    fun close() {
        instance?.let { runCatching { it.close() } }
        instance = null
    }

    suspend fun recognize(bitmap: Bitmap): Outcome = suspendCancellableCoroutine { cont ->
        client.process(InputImage.fromBitmap(bitmap, 0))
            .addOnSuccessListener { text ->
                val raw = text.text.trim()
                val chars = raw.count { !it.isWhitespace() }
                val lineCount = text.textBlocks.sumOf { it.lines.size }
                val hangul = raw.count { it in '가'..'힣' }
                val ratio = if (chars > 0) hangul.toFloat() / chars else 0f
                val hints = DOSAGE_PATTERNS.count { it.containsMatchIn(raw) }
                val good = chars >= ScanTuning.VERDICT_MIN_CHARS &&
                    lineCount >= ScanTuning.VERDICT_MIN_LINES &&
                    ratio >= ScanTuning.VERDICT_MIN_HANGUL_RATIO
                cont.resume(Outcome(raw, chars, lineCount, ratio, hints, good))
            }
            .addOnFailureListener { cont.resumeWithException(it) }
    }

    /**
     * 원본 크롭과 대비 보정본을 모두 판독해 점수가 높은 쪽을 채택합니다.
     * 전처리가 항상 이롭다는 가정을 없애고 매 장면마다 실측으로 정합니다.
     * 동점이면 원본을 택합니다(가공이 적은 쪽이 유령 글자 위험도 적음).
     *
     * 어느 쪽이 채택됐는지 로그로 남깁니다. score 는 글자수 기반이라
     * 대비 증폭이 노이즈에서 유령 글자를 만들면 나쁜 쪽이 이길 수 있는데,
     * 이 로그와 debug 캐시 이미지를 대조해야 그 사례를 걸러낼 수 있습니다.
     */
    suspend fun recognizeBest(raw: Bitmap, enhanced: Bitmap?): Pair<Outcome, Bitmap> {
        val first = recognize(raw)
        if (enhanced == null) return first to raw
        val second = recognize(enhanced)
        // 근소한 차이로는 갈아타지 않습니다. 대비 증폭이 유령 글자를 만들어
        // 점수를 조금 올리는 경우가 실제로 있어, 확실히 이길 때만 채택합니다.
        val useEnhanced = second.score > first.score + ScanTuning.DUAL_PASS_ADOPT_MARGIN
        ScanLog.i(
            SUB,
            "dual-pass raw(score=${first.score} chars=${first.chars} hints=${first.dosageHints}) " +
                "enhanced(score=${second.score} chars=${second.chars} hints=${second.dosageHints}) " +
                "margin=${ScanTuning.DUAL_PASS_ADOPT_MARGIN} " +
                "→ ${if (useEnhanced) "enhanced" else "raw"} 채택"
        )
        return if (useEnhanced) second to enhanced else first to raw
    }

    private const val SUB = "Ocr"
}
