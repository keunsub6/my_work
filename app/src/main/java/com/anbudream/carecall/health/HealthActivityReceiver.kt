package com.anbudream.carecall.health

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.google.android.gms.location.ActivityRecognitionResult

/** Google Play services의 저전력 활동 분류 결과만 받아 일 단위 집계로 변환합니다. */
class HealthActivityReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != ACTION_ACTIVITY_UPDATE) return
        // [carecall] minSdk 24 대응: 집계 로직이 java.time(API 26+)에 의존합니다.
        if (!HealthFeature.isSupported) return
        runCatching {
            if (!ActivityRecognitionResult.hasResult(intent)) return
            val result = ActivityRecognitionResult.extractResult(intent) ?: return
            val probable = result.probableActivities.maxByOrNull { it.confidence } ?: return
            val eventWall = result.time.takeIf { it > 0L } ?: System.currentTimeMillis()
            HealthMetricsStore.recordActivitySample(
                context = context.applicationContext,
                timestampMs = eventWall,
                detectedType = probable.type,
                confidence = probable.confidence,
            )
        }.onFailure {
            DebugLog.event(context, "건강지표 활동 샘플 처리 실패: ${it.javaClass.simpleName}")
        }
    }

    companion object {
        const val ACTION_ACTIVITY_UPDATE = "com.anbudream.carecall.health.ACTIVITY_UPDATE"
    }
}
