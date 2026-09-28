package com.anbudream.carecall.health

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.google.android.gms.location.ActivityRecognition

/**
 * 센서 건강지표 기능의 유일한 진입점입니다.
 * 기존 통화/FCM/WebView 흐름과 분리하며 모든 실패는 내부에서 흡수합니다.
 */
object HealthSensorCoordinator {
    private const val PREFS = "health_sensor_control"
    private const val KEY_PERMISSION_ASKED = "activity_permission_asked"
    private const val ACTIVITY_INTERVAL_MS = 30L * 60L * 1000L

    fun initialize(ctx: Context) {
        val appCtx = ctx.applicationContext
        // [carecall] minSdk 24 대응: 집계 로직이 java.time(API 26+)에 의존합니다.
        if (!HealthFeature.isSupported) return
        if (!RegistrationClient.isRegistrationEnabled(appCtx)) return
        if (!hasActivityPermission(appCtx)) {
            HealthMetricsStore.notePermissionDenied(appCtx)
            runCatching { HealthSamplingJobService.cancel(appCtx) }
            runCatching {
                ActivityRecognition.getClient(appCtx).removeActivityUpdates(activityPendingIntent(appCtx))
            }
            return
        }

        runCatching { HealthSamplingJobService.schedule(appCtx) }
            .onFailure { DebugLog.event(appCtx, "건강지표 작업 예약 실패: ${it.javaClass.simpleName}") }
        registerActivityUpdates(appCtx)
    }

    fun disable(ctx: Context) {
        val appCtx = ctx.applicationContext
        runCatching { HealthSamplingJobService.cancel(appCtx) }
        runCatching {
            ActivityRecognition.getClient(appCtx).removeActivityUpdates(activityPendingIntent(appCtx))
        }
        // [carecall] 등록 해제는 "데이터 삭제"이므로 동의 상태와 수집분을 함께 되돌립니다.
        //   - 동의 플래그를 지워야 다시 "등록하기" 했을 때 권한을 재요청합니다.
        //   - 집계분을 지워야 기기를 넘겨받은 다른 사람의 번호로 이전 데이터가 올라가지 않습니다.
        runCatching {
            appCtx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().remove(KEY_PERMISSION_ASKED).apply()
        }
        runCatching { HealthMetricsStore.clear(appCtx) }
        DebugLog.event(appCtx, "건강지표 수집 중지")
    }

    fun hasActivityPermission(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACTIVITY_RECOGNITION) ==
            PackageManager.PERMISSION_GRANTED

    fun shouldAskActivityPermission(ctx: Context): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            !hasActivityPermission(ctx) &&
            !ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_PERMISSION_ASKED, false)

    fun markActivityPermissionAsked(ctx: Context) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_PERMISSION_ASKED, true).apply()
    }

    private fun registerActivityUpdates(ctx: Context) {
        runCatching {
            ActivityRecognition.getClient(ctx)
                .requestActivityUpdates(ACTIVITY_INTERVAL_MS, activityPendingIntent(ctx))
                .addOnSuccessListener {
                    DebugLog.event(ctx, "건강지표 활동인식 등록 완료")
                }
                .addOnFailureListener {
                    HealthMetricsStore.noteActivityRecognitionUnavailable(ctx)
                    DebugLog.event(ctx, "건강지표 활동인식 등록 실패: ${it.javaClass.simpleName}")
                }
        }.onFailure {
            HealthMetricsStore.noteActivityRecognitionUnavailable(ctx)
            DebugLog.event(ctx, "건강지표 활동인식 예외: ${it.javaClass.simpleName}")
        }
    }

    internal fun activityPendingIntent(ctx: Context): PendingIntent {
        val mutabilityFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_MUTABLE
        } else 0
        return PendingIntent.getBroadcast(
            ctx,
            41027,
            Intent(ctx, HealthActivityReceiver::class.java)
                .setAction(HealthActivityReceiver.ACTION_ACTIVITY_UPDATE),
            PendingIntent.FLAG_UPDATE_CURRENT or mutabilityFlag,
        )
    }
}
