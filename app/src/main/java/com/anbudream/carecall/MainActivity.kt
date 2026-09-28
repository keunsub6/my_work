package com.anbudream.carecall

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.core.content.ContextCompat
import com.anbudream.carecall.ui.RegisterScreen

class MainActivity : ComponentActivity() {

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* no-op */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestNotificationPermissionIfNeeded()

        // Only on a fresh launch (not on rotation), so the URL isn't reopened on recreation.
        if (savedInstanceState == null) {
            openUrlFromNotification(intent)
        }

        setContent {
            MaterialTheme {
                Surface { RegisterScreen() }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        openUrlFromNotification(intent)
    }

    /**
     * 알림 URL은 MealTime이 설치되어 있으면 MealTime WebView로 보내고,
     * 설치되어 있지 않으면 기존처럼 기본 브라우저로 엽니다.
     */
    private fun openUrlFromNotification(intent: Intent?) {
        val url = MealTimeLauncher.notificationUrl(intent)
            ?: MealTimeLauncher.consumePendingUrl(this)
        MealTimeLauncher.open(this, url)
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                this, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }
}
