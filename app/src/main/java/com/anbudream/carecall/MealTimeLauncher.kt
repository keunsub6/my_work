package com.anbudream.carecall

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import com.anbudream.carecall.time.AppLog
import java.util.UUID

/** Opens care-call URLs in MealTime when available, otherwise keeps the browser fallback. */
object MealTimeLauncher {
    private const val MEALTIME_PACKAGE = "com.anbudream.mealtime"
    private const val MEALTIME_REQUEST_ACTIVITY =
        "com.anbudream.mealtime.WebRtcRequestActivity"
    private const val MEALTIME_DIRECT_SERVICE =
        "com.anbudream.mealtime.WebCallIngressService"

    private const val ACTION_SHOW = "com.anbudream.mealtime.action.SHOW_WEBRTC_SCREEN"
    private const val ACTION_DIRECT_OPEN =
        "com.anbudream.mealtime.action.START_WEBRTC_FOREGROUND"
    private const val EXTRA_URL = "com.anbudream.mealtime.extra.URL"
    private const val EXTRA_NONCE = "com.anbudream.mealtime.extra.NONCE"
    private const val EXTRA_ACCEPTED_CALLBACK =
        "com.anbudream.mealtime.extra.ACCEPTED_CALLBACK"
    private const val EXTRA_ACK_CALLBACK =
        "com.anbudream.mealtime.extra.ACK_CALLBACK"
    private const val PERMISSION = "com.anbudream.mealtime.permission.SHOW_WEBRTC_SCREEN"

    private const val PREFS = "care_call_open"
    private const val KEY_PENDING_URL = "pending_url"
    private const val KEY_PENDING_AT = "pending_at"
    private const val KEY_PENDING_ELAPSED = "pending_elapsed"
    private const val KEY_PENDING_NONCES = "pending_nonces"
    private const val PENDING_MAX_AGE_MS = 10 * 60 * 1000L
    private const val TAG = "MealTimeLauncher"

    fun notificationUrl(intent: Intent?): String? =
        intent?.getStringExtra("url")
            ?: intent?.getStringExtra("sessionUrl")

    /**
     * MealTime의 exported foreground service를 명시적으로 직접 시작합니다.
     *
     * BroadcastReceiver와 AlarmManager를 중간에 두지 않습니다. MealTime 프로세스가 죽어 있어도
     * Android가 대상 프로세스를 만들고 WebCallIngressService.onStartCommand()에 요청을 전달합니다.
     * 실제 화면 표시 확인은 이 요청에 포함한 one-shot PendingIntent callback으로 받습니다.
     *
     * @return 서비스 시작 요청을 Android에 넘겼으면 nonce, 아니면 null
     */
    fun requestImmediateOpen(context: Context, rawUrl: String?): String? {
        val originalUrl = validateHttpsUrl(rawUrl) ?: return null
        val service = ComponentName(MEALTIME_PACKAGE, MEALTIME_DIRECT_SERVICE)
        if (!isServiceAvailable(context, service)) {
            AppLog.e(TAG, "MealTime direct service is not installed")
            return null
        }
        val nonce = UUID.randomUUID().toString()
        rememberNonce(context, nonce) // callback이 즉시 돌아와도 놓치지 않도록 먼저 저장

        val acceptedCallback = buildCallback(
            context = context,
            nonce = nonce,
            action = WebCallShownReceiver.ACTION_REQUEST_ACCEPTED,
            requestCode = nonce.hashCode() xor 0x13572468
        )
        val shownCallback = buildCallback(
            context = context,
            nonce = nonce,
            action = WebCallShownReceiver.ACTION_SHOWN_ACK,
            requestCode = nonce.hashCode()
        )

        val serviceIntent = Intent(ACTION_DIRECT_OPEN).apply {
            component = service
            putExtra(EXTRA_URL, toMealTimeUrl(originalUrl))
            putExtra(EXTRA_NONCE, nonce)
            putExtra(EXTRA_ACCEPTED_CALLBACK, acceptedCallback)
            putExtra(EXTRA_ACK_CALLBACK, shownCallback)
        }

        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
            AppLog.i(TAG, "MealTime direct foreground service requested nonce=$nonce")
            nonce
        }.onFailure {
            forgetNonce(context, nonce)
            acceptedCallback.cancel()
            shownCallback.cancel()
            AppLog.e(TAG, "MealTime direct service request failed", it)
        }.getOrNull()
    }

    private fun buildCallback(
        context: Context,
        nonce: String,
        action: String,
        requestCode: Int
    ): PendingIntent {
        val callbackIntent = Intent(context, WebCallShownReceiver::class.java).apply {
            this.action = action
            putExtra(EXTRA_NONCE, nonce)
        }
        return PendingIntent.getBroadcast(
            context,
            requestCode,
            callbackIntent,
            PendingIntent.FLAG_ONE_SHOT or
                PendingIntent.FLAG_CANCEL_CURRENT or
                PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun rememberNonce(context: Context, nonce: String) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val pending = prefs.getStringSet(KEY_PENDING_NONCES, emptySet()).orEmpty().toMutableSet()
        pending += nonce
        prefs.edit().putStringSet(KEY_PENDING_NONCES, pending).commit()
    }

    private fun forgetNonce(context: Context, nonce: String) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val pending = prefs.getStringSet(KEY_PENDING_NONCES, emptySet()).orEmpty().toMutableSet()
        if (!pending.remove(nonce)) return
        prefs.edit().putStringSet(KEY_PENDING_NONCES, pending).commit()
    }

    /** callback nonce가 실제로 대기 중인 요청일 때만 true 입니다. */
    fun consumeNonce(context: Context, nonce: String?): Boolean {
        if (nonce.isNullOrBlank()) return false
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val pending = prefs.getStringSet(KEY_PENDING_NONCES, emptySet()).orEmpty().toMutableSet()
        if (!pending.remove(nonce)) return false
        prefs.edit().putStringSet(KEY_PENDING_NONCES, pending).commit()
        return true
    }

    /** 신규 메시지가 즉시 실행되지 못했을 때 알림 탭 fallback으로 잠시 보관합니다. */
    fun rememberPendingUrl(context: Context, rawUrl: String?) {
        val url = validateHttpsUrl(rawUrl) ?: return
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_PENDING_URL, url)
            .putLong(KEY_PENDING_AT, System.currentTimeMillis())
            .putLong(KEY_PENDING_ELAPSED, SystemClock.elapsedRealtime())
            .commit()
    }

    /** 가장 최근의 유효한 URL을 한 번만 반환합니다. */
    fun consumePendingUrl(context: Context): String? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val url = prefs.getString(KEY_PENDING_URL, null)
        val savedAt = prefs.getLong(KEY_PENDING_AT, 0L)
        val savedElapsed = prefs.getLong(KEY_PENDING_ELAPSED, 0L)
        prefs.edit()
            .remove(KEY_PENDING_URL)
            .remove(KEY_PENDING_AT)
            .remove(KEY_PENDING_ELAPSED)
            .apply()

        if (url.isNullOrBlank()) return null
        val nowElapsed = SystemClock.elapsedRealtime()
        val age = when {
            savedElapsed > 0L && nowElapsed >= savedElapsed -> nowElapsed - savedElapsed
            savedElapsed > 0L -> Long.MAX_VALUE // 재부팅으로 elapsedRealtime 기준이 바뀜
            savedAt > 0L -> System.currentTimeMillis() - savedAt // 업데이트 전 데이터 fallback
            else -> Long.MAX_VALUE
        }
        if (age < 0L || age > PENDING_MAX_AGE_MS) return null
        return validateHttpsUrl(url)
    }

    /** 기기 시각 변경/재부팅 시 오래된 fallback 요청을 안전하게 폐기합니다. */
    fun clearPendingUrl(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .remove(KEY_PENDING_URL)
            .remove(KEY_PENDING_AT)
            .remove(KEY_PENDING_ELAPSED)
            .apply()
    }

    fun buildOpenIntent(context: Context, rawUrl: String?): Intent? {
        val originalUrl = validateHttpsUrl(rawUrl) ?: return null
        val component = ComponentName(MEALTIME_PACKAGE, MEALTIME_REQUEST_ACTIVITY)

        return if (isActivityAvailable(context, component)) {
            Intent(ACTION_SHOW).apply {
                this.component = component
                putExtra(EXTRA_URL, toMealTimeUrl(originalUrl))
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        } else {
            Intent(Intent.ACTION_VIEW, Uri.parse(originalUrl))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    fun open(context: Context, rawUrl: String?): Boolean {
        val openIntent = buildOpenIntent(context, rawUrl) ?: return false
        return runCatching { context.startActivity(openIntent) }
            .recoverCatching {
                val browserUrl = validateHttpsUrl(rawUrl) ?: throw it
                context.startActivity(
                    Intent(Intent.ACTION_VIEW, Uri.parse(browserUrl))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
            .isSuccess
    }

    private fun isActivityAvailable(context: Context, component: ComponentName): Boolean =
        runCatching {
            @Suppress("DEPRECATION")
            context.packageManager.getActivityInfo(component, 0)
        }.isSuccess

    private fun isServiceAvailable(context: Context, component: ComponentName): Boolean =
        runCatching {
            @Suppress("DEPRECATION")
            context.packageManager.getServiceInfo(component, 0)
        }.isSuccess

    private fun validateHttpsUrl(rawUrl: String?): String? {
        val value = rawUrl?.trim().orEmpty()
        if (value.isEmpty()) return null
        val uri = runCatching { Uri.parse(value) }.getOrNull() ?: return null
        if (!uri.scheme.equals("https", ignoreCase = true) || uri.host.isNullOrBlank()) return null
        return uri.toString()
    }

    /** MealTime WebView에서는 care.html 대신 c.html을 사용하고 쿼리 인자는 그대로 보존합니다. */
    internal fun toMealTimeUrl(url: String): String {
        val uri = Uri.parse(url)
        val path = uri.path.orEmpty()
        if (!path.endsWith("/care.html", ignoreCase = true)) return url
        return uri.buildUpon()
            .path(path.dropLast("care.html".length) + "c.html")
            .build()
            .toString()
    }
}
