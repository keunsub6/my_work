package com.anbudream.carecall.time

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.anbudream.carecall.MealTimeLauncher
import com.anbudream.carecall.health.HealthFeature
import com.anbudream.carecall.health.HealthMetricsStore
import com.anbudream.carecall.health.HealthSensorCoordinator
import com.anbudream.carecall.health.RegistrationClient
import com.anbudream.carecall.screening.CallScreenState

/** 기기 시각·시간대·재부팅 변화가 통화 리스와 건강 집계 구간을 오염시키지 않게 정리합니다. */
class TimeChangeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val appCtx = context.applicationContext
        when (intent?.action) {
            Intent.ACTION_TIME_CHANGED -> {
                // 통화 리스와 대기 URL은 elapsedRealtime 기준이므로 벽시계 변경과 무관합니다.
                // 건강 표본의 벽시계 구간만 끊어 다음 표본부터 안전하게 다시 연결합니다.
                runCatching { HealthMetricsStore.handleWallClockChanged(appCtx, intent.action.orEmpty()) }
                AppLog.w(TAG, "device wall clock changed; health sampling anchors reset")
            }

            Intent.ACTION_TIMEZONE_CHANGED -> {
                // 집계 기준은 KST 고정이므로 데이터 재분할은 필요하지 않습니다.
                AppLog.i(
                    TAG,
                    "device timezone changed; aggregation remains ${AppTime.ZONE_ID}, " +
                        "deviceOffset=${AppTime.deviceOffsetMinutes()}"
                )
            }

            Intent.ACTION_BOOT_COMPLETED -> {
                // elapsedRealtime 기반 리스는 재부팅을 넘길 수 없으므로 fail-open으로 해제합니다.
                runCatching { CallScreenState.release(appCtx, "기기 재부팅") }
                runCatching { MealTimeLauncher.clearPendingUrl(appCtx) }
                if (
                    HealthFeature.isSupported &&
                    runCatching { RegistrationClient.isRegistrationEnabled(appCtx) }.getOrDefault(false)
                ) {
                    runCatching { HealthSensorCoordinator.initialize(appCtx) }
                }
                AppLog.i(TAG, "boot completed; transient time state reset")
            }
        }
    }

    companion object {
        private const val TAG = "CareTime"
    }
}
