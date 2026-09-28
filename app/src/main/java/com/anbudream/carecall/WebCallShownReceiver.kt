package com.anbudream.carecall

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.anbudream.carecall.time.AppLog
import com.anbudream.carecall.screening.CallScreenState

/**
 * MealTime에 전달한 one-shot PendingIntent callback의 수신점입니다.
 * exported=false여도 PendingIntent를 만든 앱의 권한으로 실행되므로 외부 공개가 필요 없습니다.
 */
class WebCallShownReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val nonce = intent?.getStringExtra(EXTRA_NONCE)
        when (intent?.action) {
            ACTION_REQUEST_ACCEPTED -> {
                AppLog.i(TAG, "MealTime service accepted request nonce=$nonce")
            }

            ACTION_SHOWN_ACK -> {
                if (!MealTimeLauncher.consumeNonce(context, nonce)) {
                    AppLog.w(TAG, "ignored shown callback with unknown nonce")
                    return
                }
                runCatching {
                    context.getSystemService(NotificationManager::class.java)
                        ?.cancel(PENDING_NOTIFICATION_ID)
                }
                // [추가] 통화 화면이 실제로 뜬 것이 확인된 이 시점에만 전화 차단을 시작합니다.
                //        기능이 꺼져 있으면 activate() 내부에서 즉시 반환합니다.
                runCatching { CallScreenState.activate(context.applicationContext) }
                AppLog.i(TAG, "MealTime callback confirmed the call screen is visible")
            }
        }
    }

    companion object {
        private const val TAG = "WebCallShownReceiver"
        const val ACTION_REQUEST_ACCEPTED =
            "com.anbudream.carecall.action.WEBCALL_REQUEST_ACCEPTED"
        const val ACTION_SHOWN_ACK =
            "com.anbudream.carecall.action.WEBCALL_SHOWN_CALLBACK"
        const val EXTRA_NONCE = "com.anbudream.mealtime.extra.NONCE"
        const val PENDING_NOTIFICATION_ID = 9101
    }
}
