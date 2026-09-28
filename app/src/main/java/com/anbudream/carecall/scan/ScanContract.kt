package com.anbudream.carecall.scan

/**
 * 스캔 기능의 진입·통신 규약.
 *
 * 진입 경로: 서버 → CareCall FCM(type=open_scan) → CareCall 이 알림을 띄움
 *          → 어르신이 탭 → ScanActivity.
 *
 * 포그라운드 서비스도, full-screen intent 도 쓰지 않습니다. 알림 탭 1회를
 * 감수하는 대신 새 권한을 한 개도 늘리지 않습니다(POST_NOTIFICATIONS 는
 * CareCall 이 이미 보유).
 *
 * cid/nonce 가 없으면 '단독 모드'로 동작합니다. 촬영·판독은 정상 수행하되
 * 서버로 아무것도 보내지 않습니다.
 */
object ScanContract {

    /** FCM data 페이로드의 type 값 */
    const val PUSH_TYPE_OPEN_SCAN = "open_scan"

    /** FCM data 키 */
    const val KEY_CID = "cid"
    const val KEY_NONCE = "nonce"
    const val KEY_TITLE = "title"
    const val KEY_BODY = "body"

    /**
     * 촬영이 끝난 뒤 되돌아갈 통화 화면 주소.
     *
     * 촬영 화면이 MealTime 통화 웹뷰를 덮으면 웹뷰가 파괴되고, [완료] 후에는
     * 별도 태스크에서 열린 이 화면이 닫히며 홈이나 다른 화면으로 떨어집니다.
     * 어르신은 스스로 통화 화면으로 돌아가지 못하고, 그러면 서버가 준비해 둔
     * 낭독을 영영 듣지 못합니다(실측에서 확인).
     * 서버가 이 주소를 함께 보내 주면 촬영 후 통화 화면을 되살립니다.
     */
    const val KEY_CALL_URL = "callUrl"

    /** 알림 탭 → ScanActivity 로 넘기는 extra */
    const val EXTRA_CID = "com.anbudream.carecall.scan.extra.CID"
    const val EXTRA_NONCE = "com.anbudream.carecall.scan.extra.NONCE"
    const val EXTRA_CALL_URL = "com.anbudream.carecall.scan.extra.CALL_URL"

    /**
     * 스캔 전용 알림 채널. 기존 채널(default_channel, care_call_pending_v1)과 분리합니다.
     *
     * ⚠ v1 → v2 로 올린 이유: 안드로이드는 이미 만들어진 채널의 설정
     * (중요도, lockscreenVisibility 등)을 코드로 바꿔도 무시합니다. 잠금화면
     * 내용 표시(VISIBILITY_PUBLIC)를 적용하려면 새 ID 로 다시 만들어야 합니다.
     * 앞으로도 채널 설정을 바꿀 때는 반드시 번호를 올리세요.
     */
    const val CHANNEL_ID = "care_scan_v2"

    /** 정리 대상인 옛 채널 ID. 설정 화면에 유령 채널이 남지 않게 지웁니다. */
    val LEGACY_CHANNEL_IDS = listOf("care_scan_v1")
    const val NOTIFICATION_ID = 8301
    const val PENDING_REQUEST_CODE = 8302

    /**
     * 서버 라우트.
     * /api 아래에 두는 이유: server.js 의 X-API-Key 인증 미들웨어가
     * '/api' 경로에만 걸려 있어, 여기 두면 인증이 자동으로 적용됩니다.
     */
    const val PATH_RESULT = "/api/scan/result"

    /** cid/nonce 형식 완충 검증 (UUID 계열 문자만, 최대 64자) */
    fun sanitizeId(raw: String?): String {
        val v = raw?.trim().orEmpty()
        if (v.isEmpty() || v.length > 64) return ""
        return if (v.all { it.isLetterOrDigit() || it == '-' || it == '_' }) v else ""
    }
}
