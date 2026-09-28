package com.anbudream.carecall

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import com.anbudream.carecall.health.HealthFeature
import com.anbudream.carecall.health.HealthMetricsStore
import com.anbudream.carecall.time.AppLog

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        createDefaultNotificationChannel()
        if (HealthFeature.isSupported) {
            runCatching { HealthMetricsStore.ensureKstStorage(applicationContext) }
                .onSuccess { result -> if (result != null) AppLog.i("CareTime", result) }
                .onFailure { AppLog.e("CareTime", "KST health migration failed", it) }
        }
    }

    private fun createDefaultNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channelId = getString(R.string.default_notification_channel_id)
            val channel = NotificationChannel(
                channelId,
                "기본 알림",
                NotificationManager.IMPORTANCE_DEFAULT
            )
            getSystemService(NotificationManager::class.java)
                ?.createNotificationChannel(channel)
        }
    }
}
