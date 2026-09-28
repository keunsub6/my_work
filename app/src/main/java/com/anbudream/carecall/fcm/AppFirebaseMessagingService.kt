package com.anbudream.carecall.fcm

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.anbudream.carecall.MealTimeLauncher
import com.anbudream.carecall.R
import com.anbudream.carecall.WebCallShownReceiver
import com.anbudream.carecall.health.CareTokenVerifier
import com.anbudream.carecall.health.HealthCollectWorker
import com.anbudream.carecall.scan.ScanContract
import com.anbudream.carecall.scan.ScanNotifier
import com.anbudream.carecall.screening.CallScreenState
import com.anbudream.carecall.time.AppLog
import com.anbudream.carecall.time.AppTime
import com.anbudream.carecall.work.TokenRefreshWorker
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

class AppFirebaseMessagingService : FirebaseMessagingService() {

    override fun onNewToken(token: String) {
        // [추가] 서버는 "푸시 시점의 토큰 → 전화번호"로 건강지표를 묶습니다.
        //        업로드 때 같은 토큰을 써야 하므로 로컬에도 보관합니다.
        CareTokenVerifier.save(applicationContext, token)
        TokenRefreshWorker.enqueue(applicationContext, token)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        // [추가] 건강지표 수집 트리거(data-only, url/notification 없음).
        //        이 분기가 없으면 아래에서 url·notification 모두 null이라 그냥 return 되던
        //        경로입니다. 따라서 기존 통화 푸시 처리에는 영향이 없습니다.
        if (message.data["type"] == HealthCollectWorker.PUSH_TYPE) {
            HealthCollectWorker.enqueue(applicationContext, message.data)
            return
        }

        // [추가] 통화 시작 신호(data-only). 레거시 앱이 오버레이로 통화를 띄우면
        //        이 앱은 화면 ACK 를 받지 못하므로, 서버가 알려주는 이 신호로 차단을 시작합니다.
        //        이 앱이 직접 통화를 띄운 경우에는 ACK 와 중복되지만 activate() 는 멱등입니다.
        if (message.data["type"] == TYPE_CALL_START) {
            val startedAt = AppTime.parseEpochMs(message.data["startedAt"])
            runCatching {
                CallScreenState.activate(
                    applicationContext,
                    message.data["cid"].orEmpty(),
                    startedAt,
                )
            }
            if (message.data["startedAt"] != null && startedAt == null) {
                AppLog.w(TAG, "call_start has invalid startedAt")
            }
            return
        }

        // [추가] 통화 종료 신호(data-only). 차단을 즉시 해제합니다.
        //        url/notification 이 없어 기존 로직에서는 어차피 return 되던 경로입니다.
        if (message.data["type"] == TYPE_CALL_END) {
            runCatching {
                CallScreenState.releaseFromServer(
                    applicationContext,
                    message.data["cid"],
                    AppTime.parseEpochMs(message.data["endedAt"]),
                )
            }
            return
        }

        // [추가] 글씨 읽어드리기 요청(data-only, url/notification 없음).
        //
        //   ⚠ 이 분기의 위치가 중요합니다. 반드시 아래 rememberCid() 앞이어야 합니다.
        //     스캔 푸시도 cid 를 담고 오는데, rememberCid 를 먼저 통과시키면
        //     통화용 cid 가 스캔 cid 로 덮여 전화차단 해제 판정이 어긋납니다.
        //     여기서 return 하므로 통화 상태에는 아무 영향이 없습니다.
        //
        //   이 분기가 없을 때 이 메시지는 url·notification 모두 null 이라
        //   그냥 return 되던 경로입니다. 따라서 기존 처리에는 영향이 없습니다.
        if (message.data["type"] == ScanContract.PUSH_TYPE_OPEN_SCAN) {
            val cid = ScanContract.sanitizeId(message.data[ScanContract.KEY_CID])
            val nonce = ScanContract.sanitizeId(message.data[ScanContract.KEY_NONCE])
            if (cid.isBlank() || nonce.isBlank()) {
                AppLog.w(TAG, "open_scan ignored: cid/nonce missing or malformed")
                return
            }
            runCatching {
                ScanNotifier.show(
                    applicationContext,
                    cid,
                    nonce,
                    message.data[ScanContract.KEY_TITLE],
                    message.data[ScanContract.KEY_BODY],
                    message.data[ScanContract.KEY_CALL_URL],
                )
            }.onFailure { AppLog.e(TAG, "open_scan notification failed", it) }
            return
        }

        // [추가] 통화 시작 푸시의 cid 를 적어 둡니다(차단은 화면 ACK 이후에 시작).
        runCatching { CallScreenState.rememberCid(applicationContext, message.data["cid"]) }

        val notification = message.notification
        val legacyUrl = message.data["url"]
        val sessionUrl = message.data["sessionUrl"]
        val url = legacyUrl ?: sessionUrl

        if (!url.isNullOrBlank()) {
            // 1. 대기 알림을 먼저 띄웁니다.
            //    - MealTime이 화면을 띄우면 ACK가 와서 이 알림이 사라집니다.
            //    - 실패하면 남아서 사용자가 탭할 수 있는 마지막 수단이 됩니다.
            //    - high priority 메시지가 사용자에게 보이는 결과를 남겨야 FCM이 이후
            //      메시지를 normal로 강등하지 않습니다.
            postPendingNotification(url)

            // 2. 표시를 요청합니다. 블로킹하지 않고, 성공을 판정하지도 않습니다.
            val nonce = MealTimeLauncher.requestImmediateOpen(applicationContext, url)
            AppLog.i(
                TAG,
                "care call push requested=${nonce != null} priority=${message.priority} " +
                    "originalPriority=${message.originalPriority} keys=${message.data.keys}"
            )

            if (nonce == null) {
                // MealTime 미설치/구버전이면 기존 알림 탭 fallback도 함께 준비합니다.
                MealTimeLauncher.rememberPendingUrl(applicationContext, url)
            }
            return
        }

        if (notification == null) return

        val channelId = getString(R.string.default_notification_channel_id)
        val builder = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(notification.title ?: "알림")
            .setContentText(notification.body ?: "탭하여 열어주세요.")
            .setAutoCancel(true)
            .setContentIntent(buildContentIntent(null))

        getSystemService(NotificationManager::class.java)
            ?.notify(System.currentTimeMillis().toInt(), builder.build())
    }

    /**
     * 통화 요청 대기 알림입니다.
     *
     * 전용 채널을 씁니다. 기본 채널(default_channel)은 IMPORTANCE_DEFAULT라
     * 개별 PRIORITY_LOW 설정이 무시되고 소리가 납니다. Android 8 이상에서는
     * 채널 중요도가 알림 개별 우선순위를 이깁니다.
     */
    private fun postPendingNotification(url: String) {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        createPendingChannel(manager)

        val notification = NotificationCompat.Builder(this, PENDING_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("안부 통화 요청")
            .setContentText("화면이 열리지 않으면 눌러 주세요.")
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setSilent(true)
            .setAutoCancel(true)
            .setTimeoutAfter(PENDING_TIMEOUT_MS)
            .setContentIntent(buildContentIntent(url))
            .build()

        manager.notify(WebCallShownReceiver.PENDING_NOTIFICATION_ID, notification)
    }

    private fun createPendingChannel(manager: NotificationManager) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            PENDING_CHANNEL_ID,
            "안부 통화 대기",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "통화 화면이 열리는 동안 잠깐 표시됩니다."
            setSound(null, null)
            enableVibration(false)
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    /** MealTime이 있으면 그쪽으로, 없으면 기존 기본 브라우저로 여는 fallback 대상입니다. */
    private fun buildContentIntent(url: String?): PendingIntent {
        val intent = MealTimeLauncher.buildOpenIntent(this, url)
            ?: Intent(this, com.anbudream.carecall.MainActivity::class.java)
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        return PendingIntent.getActivity(this, (url ?: "app").hashCode(), intent, flags)
    }

    companion object {
        private const val TAG = "CareCallFCM"
        private const val TYPE_CALL_START = "call_start"
        private const val TYPE_CALL_END = "call_end"
        private const val PENDING_CHANNEL_ID = "care_call_pending_v1"
        private const val PENDING_TIMEOUT_MS = 3 * 60 * 1000L
    }
}
