package com.anbudream.carecall.scan

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings

/**
 * "글씨를 읽어드릴까요?" — 촬영 화면을 어르신께 보여주는 진입점.
 *
 * 2단계에서 2중 경로가 되었습니다.
 *
 *   1차) 직접 실행 — startActivity 를 그대로 부릅니다.
 *        평소에는 백그라운드에서 액티비티를 띄울 수 없지만,
 *        SYSTEM_ALERT_WINDOW("다른 앱 위에 표시")가 허용되어 있으면
 *        그 제한이 면제되어 바로 화면이 뜹니다. 어르신이 아무것도
 *        누르지 않아도 됩니다. MealTime 이 잠금·화면꺼짐 상태에서
 *        확실히 뜨는 것도 같은 원리입니다.
 *
 *   2차) 알림 — 1차가 막히면(권한 미허용, 기기 정책 등) 기존 알림을
 *        띄웁니다. 1단계에서 확인된 동작이므로, 어르신은 알림을 한 번
 *        누르면 잠금 해제 없이 카메라 화면으로 들어갑니다.
 *
 * 1차가 성공해도 알림을 함께 띄우지 않습니다. 화면이 이미 떠 있는데
 * 알림까지 남으면 어르신이 나중에 그 알림을 눌러 카메라가 다시 열립니다.
 *
 * full-screen intent 는 쓰지 않습니다. USE_FULL_SCREEN_INTENT 는 심사가
 * 가장 까다로운 권한인데, 자동 표시의 실제 동력은 오버레이 권한이라
 * 없어도 목표가 달성됩니다. 실측에서 안 뜨는 기기가 나오면 그때
 * 3차 경로로 추가하면 됩니다.
 */
object ScanNotifier {
    private const val SUB = "Notify"

    /** 3분이 지나면 알림이 스스로 사라집니다(대화가 이미 지나간 뒤 남지 않도록). */
    private const val TIMEOUT_MS = 3 * 60 * 1000L

    /** 직접 실행 후 화면이 떴는지 확인하기까지 기다리는 시간. */
    private const val VERIFY_DELAY_MS = 1_800L

    /**
     * 촬영 화면 표시 요청. 1차 직접 실행 → 실패 시 2차 알림.
     * 어느 경로로 떴는지 로그에 남겨 실기기에서 확인할 수 있습니다.
     */
    fun show(
        context: Context,
        cid: String,
        nonce: String,
        title: String?,
        body: String?,
        callUrl: String? = null,
    ) {
        val overlay = canDrawOverlays(context)
        ScanLog.i(SUB, "scan request cid=$cid overlayGranted=$overlay")

        val requestedAt = System.currentTimeMillis()
        if (launchDirectly(context, cid, nonce, callUrl)) {
            // 이전 요청의 알림이 남아 있을 수 있으니 정리합니다.
            cancel(context)
            verifyShownOrFallback(context, cid, nonce, title, body, callUrl, requestedAt)
            return
        }

        ScanLog.w(SUB, "→ 1차 직접 실행 불가, 2차 알림으로 전환 (overlayGranted=$overlay)")
        postNotification(context, cid, nonce, title, body, callUrl)
    }

    /**
     * startActivity 가 예외 없이 조용히 무시되는 기기가 있습니다(일부 제조사 롬).
     * 그 경우 "성공"으로 보고 알림도 안 띄우면 어르신에게 아무 일도
     * 일어나지 않습니다. 화면이 실제로 떴는지 잠시 뒤 확인해서, 안 떴으면
     * 알림으로 되돌립니다.
     *
     * 한계: 이 지연 확인은 FCM 수신 프로세스에서 돌기 때문에, 1.8초 안에
     * 프로세스가 정리되면 폴백이 뜨지 못합니다. 다만 화면이 실제로 떴다면
     * 그 액티비티가 프로세스를 붙잡아 두므로, 폴백이 필요한 상황
     * (= 화면이 안 뜬 상황)에서 프로세스가 살아 있을 가능성이 낮지는
     * 않습니다. 완전한 보장은 아니고, 실측에서 이 경로가 자주 관측되면
     * 그때 3단계(full-screen intent)로 대체하는 것이 맞습니다.
     */
    private fun verifyShownOrFallback(
        context: Context,
        cid: String,
        nonce: String,
        title: String?,
        body: String?,
        callUrl: String?,
        requestedAt: Long,
    ) {
        val app = context.applicationContext
        Handler(Looper.getMainLooper()).postDelayed({
            if (ScanActivity.wasShownSince(cid, requestedAt)) {
                ScanLog.i(SUB, "→ 1차 직접 실행 성공 (알림 없이 표시) cid=$cid")
            } else {
                ScanLog.w(SUB, "→ 직접 실행이 무시됨(화면 미표시). 알림으로 폴백 cid=$cid")
                postNotification(app, cid, nonce, title, body, callUrl)
            }
        }, VERIFY_DELAY_MS)
    }

    /* ───────────────────────── 1차: 직접 실행 ───────────────────────── */

    /**
     * 오버레이 권한이 있으면 백그라운드 액티비티 실행 제한이 면제됩니다.
     *
     * 권한이 없어도 시도는 해봅니다. 기기·상황에 따라(앱이 최근까지
     * 전면에 있었던 경우 등) 허용되는 경우가 있고, 실패해도 예외를 삼키고
     * 알림으로 넘어가므로 손해가 없습니다.
     *
     * 다만 실패가 예외로 오지 않고 시스템이 조용히 무시하는 경우도 있어,
     * 권한이 없을 때는 성공으로 치지 않고 알림도 함께 띄웁니다.
     */
    private fun launchDirectly(
        context: Context,
        cid: String,
        nonce: String,
        callUrl: String?,
    ): Boolean {
        if (!canDrawOverlays(context)) return false
        return runCatching {
            context.startActivity(buildScanIntent(context, cid, nonce, callUrl))
            true
        }.getOrElse {
            ScanLog.w(SUB, "직접 실행 실패", it)
            false
        }
    }

    private fun canDrawOverlays(context: Context) =
        runCatching { Settings.canDrawOverlays(context) }.getOrDefault(false)

    private fun buildScanIntent(context: Context, cid: String, nonce: String, callUrl: String?) =
        Intent(context, ScanActivity::class.java).apply {
            putExtra(ScanContract.EXTRA_CID, cid)
            putExtra(ScanContract.EXTRA_NONCE, nonce)
            if (!callUrl.isNullOrBlank()) putExtra(ScanContract.EXTRA_CALL_URL, callUrl)
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_NO_USER_ACTION
            )
        }

    /* ───────────────────────── 2차: 알림 ───────────────────────── */

    private fun postNotification(
        context: Context,
        cid: String,
        nonce: String,
        title: String?,
        body: String?,
        callUrl: String?,
    ) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        ensureChannel(manager)

        val pending = PendingIntent.getActivity(
            context,
            ScanContract.PENDING_REQUEST_CODE,
            buildScanIntent(context, cid, nonce, callUrl),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            android.app.Notification.Builder(context, ScanContract.CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            android.app.Notification.Builder(context)
        }

        val notification = builder
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle(title?.takeIf { it.isNotBlank() } ?: "글씨를 읽어드릴까요?")
            .setContentText(body?.takeIf { it.isNotBlank() } ?: "여기를 누르면 카메라가 열려요.")
            .setContentIntent(pending)
            .setAutoCancel(true)
            .setOngoing(false)
            // 잠금화면에서 내용을 그대로 보여줍니다. 어르신이 무엇을 누르는지
            // 알 수 있어야 하고, 제목이 가려지면("알림이 있습니다") 누를 이유를
            // 알 수 없어 그냥 지나치십니다.
            .setVisibility(android.app.Notification.VISIBILITY_PUBLIC)
            // CATEGORY_CALL 은 잠금화면·방해금지 상황에서 우선 표시되도록 돕습니다.
            .setCategory(android.app.Notification.CATEGORY_CALL)
            .apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) setTimeoutAfter(TIMEOUT_MS)
            }
            .build()

        manager.notify(ScanContract.NOTIFICATION_ID, notification)
        ScanLog.i(SUB, "scan notification posted cid=$cid")
    }

    fun cancel(context: Context) {
        runCatching {
            context.getSystemService(NotificationManager::class.java)
                ?.cancel(ScanContract.NOTIFICATION_ID)
        }
    }

    private fun ensureChannel(manager: NotificationManager) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        // 옛 채널을 지웁니다. 남겨두면 앱 설정에 쓰이지 않는 채널이 보여
        // 담당자가 잘못 끌 수 있습니다.
        for (old in ScanContract.LEGACY_CHANNEL_IDS) {
            runCatching { manager.deleteNotificationChannel(old) }
        }

        // 어르신이 대화 중 바로 알아채야 하므로 HIGH(헤드업)입니다.
        val channel = NotificationChannel(
            ScanContract.CHANNEL_ID,
            "글씨 읽어드리기",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "안부 대화 중 종이 글씨를 읽어드릴 때 표시됩니다."
            enableVibration(true)
            setShowBadge(false)
            // 채널 단위 설정이 개별 알림 설정을 이깁니다(Android 8+).
            // 여기서 PUBLIC 을 주지 않으면 잠금화면에서 내용이 숨겨집니다.
            lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
        }
        manager.createNotificationChannel(channel)
    }
}
