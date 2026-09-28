package com.anbudream.carecall.scan

/**
 * 현장 검증(약봉투 20~50장) 때 조정할 값들을 한 곳에 모았습니다.
 * debug 빌드에서는 화면 좌하단 HUD 에 실측값이 표시되므로,
 * 실제 기기에서 값을 보고 이 파일만 고치면 됩니다.
 */
object ScanTuning {

    /* ── 가이드 사각형 ──
       화면·분석버퍼·촬영본 모두 '같은 시야(ViewPort cropRect)'를 기준 사각형으로 받아
       그 안에서 이 비율을 적용합니다. 세 좌표계가 일치합니다. */
    const val GUIDE_WIDTH_FRACTION = 0.86f
    const val GUIDE_HEIGHT_FRACTION = 0.44f
    /** 가이드 세로 중심 (0=상단, 1=하단). 하단 버튼 공간 확보를 위해 중앙보다 약간 위 */
    const val GUIDE_CENTER_Y_FRACTION = 0.44f

    /* ── 자동 촬영 게이트 ── */
    /**
     * 라플라시안 분산 선명도 문턱.
     * 주의: LAP_SPACING 을 1로 바꾼 뒤 값의 스케일이 완전히 달라졌습니다.
     * 반드시 실기기 HUD 로 재측정해서 다시 잡으세요(이전 4px 커널 기준값 무의미).
     */
    var SHARPNESS_MIN = 100.0
    /** 평균 밝기(0~255) 이 값 미만이면 "어두움"으로 보고 자동 촬영 보류 */
    const val DARK_LUMA = 55
    /** 가이드 안 포화(≥250) 화소 비율이 이 값을 넘으면 "반사"로 보고 각도 변경 안내 */
    const val GLARE_RATIO_MAX = 0.08f
    /** 가이드 안에서 이 글자 수 이상 잡혀야 "약봉투가 들어왔다"고 판단 */
    const val MIN_CHARS_IN_GUIDE = 10
    /** 위 조건이 연속으로 이 횟수 유지되면 자동 촬영 발사 */
    const val STABLE_TICKS = 3
    /** 분석 프레임에 ML Kit 를 돌리는 최소 간격(ms) */
    const val OCR_INTERVAL_MS = 260L
    /** 자동 촬영이 이 시간(ms) 동안 안 되면 수동 버튼 안내로 전환 */
    const val MANUAL_HINT_AFTER_MS = 8_000L

    /* ── 선명도 계산 방식 ── */
    /** 샘플 위치 간격(px). 크면 빠르고 거칠어집니다. 연산량은 여기에만 비례합니다. */
    const val SAMPLE_STEP = 4
    /**
     * 라플라시안 커널 이웃 간격(px). 작은 한글 획(1~2px)에 반응하려면 1이어야 합니다.
     * SAMPLE_STEP 과 분리되어 있으므로 1로 둬도 연산량은 늘지 않습니다.
     */
    const val LAP_SPACING = 1

    /**
     * 라플라시안 노이즈 바닥. |lap| 이 이 값 미만인 샘플은 선명도 집계에서 뺍니다.
     *
     * 1px 커널은 작은 한글 획에 반응하는 대신 센서 노이즈에도 민감합니다.
     * 저조도에서 ISO 노이즈가 분산을 부풀리면 "흐린데 시끄러운" 프레임이
     * sharp 로 오판되고, 동시에 노출시간이 길어져 손떨림 블러까지 겹칩니다.
     * ISO 노이즈는 대체로 ±4~6 에 몰려 있고 실제 글자 획은 그보다 훨씬 큽니다.
     * 0 으로 두면 이 필터가 꺼집니다(이전 동작).
     */
    const val LAP_NOISE_FLOOR = 6

    /* ── A/B 실험 스위치 (README_TEST.md 5장 참고) ── */
    /** true = MAXIMIZE_QUALITY, false = ZERO_SHUTTER_LAG */
    const val CAPTURE_QUALITY_MODE = true
    /** 촬영 직전 AF/AE 를 가이드 중앙에 고정할지 */
    const val FOCUS_LOCK_BEFORE_CAPTURE = true
    /** 포커스 수렴을 기다리는 최대 시간(ms). 넘으면 그냥 촬영합니다. */
    const val FOCUS_LOCK_TIMEOUT_MS = 1_200L
    /** 원본 crop 과 전처리본을 모두 판독해 좋은 쪽을 채택할지(비용 +200~400ms) */
    const val DUAL_PASS_OCR = true

    /**
     * 전처리본이 원본을 이 점수만큼 넘어야 채택합니다.
     * 0 이면 근소한 차이로도 갈아타 유령 글자에 끌려갈 수 있습니다.
     */
    const val DUAL_PASS_ADOPT_MARGIN = 15

    /** 채택 점수에서 글자수 기여 상한. 유령 글자의 무제한 가점을 막습니다. */
    const val SCORE_CHARS_CAP = 250

    /* ── 촬영 후 판독 판정 ── */
    const val VERDICT_MIN_CHARS = 20
    const val VERDICT_MIN_LINES = 3
    const val VERDICT_MIN_HANGUL_RATIO = 0.15f
    /** 연속 실패가 이 횟수에 도달하면 "다음에 하기" 출구 제안 */
    const val FAIL_STREAK_EXIT = 3

    /* ── 이미지 처리 ── */
    /** 촬영 원본 디코딩 시 이 값 밑으로는 축소하지 않습니다(축소 상한이 아니라 하한). */
    const val DECODE_MIN_LONG_EDGE = 2200
    /**
     * 가이드 크롭 시 사방 여유 비율.
     * ViewPort 로 좌표가 정합된 뒤로는 가이드에 꽉 채워 찍으면 줄 끝 글자가
     * 실제로 잘려 오독됩니다. 여유를 늘리는 비용은 거의 없습니다.
     */
    const val CROP_MARGIN_FRACTION = 0.08f
    /** 대비 스트레치 최대 배율 */
    const val CONTRAST_MAX_SCALE = 2.2f
    /** p98 - p2 가 이 값 미만이면(거의 균일) 스트레치 생략 — 노이즈 증폭 방지 */
    const val CONTRAST_MIN_RANGE = 30
}
