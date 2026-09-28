package com.anbudream.carecall.screening

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** 차단 알림의 "지금 전화 받기" 버튼. 어떤 상황에서도 즉시 정상 수신으로 되돌립니다. */
class ScreeningControlReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != ACTION_RELEASE) return
        runCatching { CallScreenState.release(context.applicationContext, "사용자가 알림에서 해제") }
    }

    companion object {
        const val ACTION_RELEASE = "com.anbudream.carecall.action.SCREENING_RELEASE"
    }
}
