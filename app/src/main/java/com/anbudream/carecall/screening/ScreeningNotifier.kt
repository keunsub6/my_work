package com.anbudream.carecall.screening

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat

/**
 * 차단이 걸려 있는 동안 항상 보이는 알림입니다.
 *
 * 두 가지 역할을 겸합니다.
 *  1) 투명성 — 지금 전화가 차단 중이라는 사실을 사용자가 늘 알 수 있어야 합니다(심사 필수 요소).
 *  2) 안전장치 — 앱을 밀어서 날려도 알림은 남으므로, 여기서 즉시 해제할 수 있습니다.
 */
internal object ScreeningNotifier {

    private const val CHANNEL_ID = "care_call_screening_v1"
    private const val NOTIFICATION_ID = 9102   // 기존 9101(통화 대기)과 겹치지 않습니다

    fun show(ctx: Context, activeUntil: Long) {
        val appCtx = ctx.applicationContext
        val manager = appCtx.getSystemService(NotificationManager::class.java) ?: return
        runCatching {
            createChannel(manager)

            val release = PendingIntent.getBroadcast(
                appCtx,
                NOTIFICATION_ID,
                Intent(appCtx, ScreeningControlReceiver::class.java)
                    .setAction(ScreeningControlReceiver.ACTION_RELEASE),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val notification = NotificationCompat.Builder(appCtx, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("안부 통화 중")
                .setContentText("통화가 끊기지 않도록 걸려오는 전화를 잠시 받지 않습니다.")
                .setStyle(
                    NotificationCompat.BigTextStyle().bigText(
                        "안부 통화가 끊기지 않도록 걸려오는 전화를 잠시 받지 않습니다. " +
                            "통화가 끝나면 자동으로 원래대로 돌아갑니다. " +
                            "지금 바로 전화를 받으시려면 아래를 눌러 주세요."
                    )
                )
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setSilent(true)
                .setOngoing(true)          // 실수로 쓸어 없애지 않도록
                .setShowWhen(false)
                .setTimeoutAfter(CallScreenState.LEASE_MS)   // 리스와 함께 사라집니다
                .addAction(0, "지금 전화 받기", release)
                .build()

            manager.notify(NOTIFICATION_ID, notification)
        }
    }

    fun cancel(ctx: Context) {
        runCatching {
            ctx.applicationContext.getSystemService(NotificationManager::class.java)
                ?.cancel(NOTIFICATION_ID)
        }
    }

    private fun createChannel(manager: NotificationManager) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "통화 중 전화 차단", NotificationManager.IMPORTANCE_LOW)
                .apply {
                    description = "안부 통화 중 전화를 잠시 받지 않는 동안 표시됩니다."
                    setSound(null, null)
                    enableVibration(false)
                    setShowBadge(false)
                }
        )
    }
}
