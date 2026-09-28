package com.anbudream.carecall.screening

import android.os.Build
import android.telecom.Call
import android.telecom.CallScreeningService

/**
 * 안부 통화가 진행 중일 때만 셀룰러 수신 전화를 거절합니다.
 *
 * 심사 관련 설계:
 *  - **발신번호를 읽지 않습니다.** callDetails.handle 에 접근하지 않으며,
 *    READ_CALL_LOG / READ_CONTACTS / READ_PHONE_STATE 를 요구하지 않습니다.
 *    판단 근거는 오직 "지금 우리 앱의 안부 통화가 진행 중인가" 하나입니다.
 *  - 거절된 전화는 **통화기록과 부재중 알림에 그대로 남습니다**(skip=false).
 *    사용자가 모르게 사라지는 전화는 없습니다.
 *  - 사용자가 앱에서 기능을 켠 경우에만 동작합니다.
 *
 * 시스템이 전화마다 이 서비스를 새로 바인딩하므로, 앱을 밀어서 날려도 판정은 항상 실행됩니다.
 */
class CareCallScreeningService : CallScreeningService() {

    override fun onScreenCall(callDetails: Call.Details) {
        // 어떤 경로로든 respondToCall 을 반드시 호출해야 합니다.
        // 응답하지 않으면 전화가 멈춘 것처럼 보입니다.
        val block = runCatching { decide(callDetails) }.getOrDefault(false)

        val response = CallResponse.Builder()
            .setDisallowCall(block)
            .setRejectCall(block)
            .setSilenceCall(false)
            .setSkipCallLog(false)        // 통화기록에 남깁니다
            .setSkipNotification(false)   // 부재중 알림을 남깁니다
            .build()

        runCatching { respondToCall(callDetails, response) }
    }

    private fun decide(callDetails: Call.Details): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        if (callDetails.callDirection != Call.Details.DIRECTION_INCOMING) return false
        return CallScreenState.shouldBlock(applicationContext)
    }
}
