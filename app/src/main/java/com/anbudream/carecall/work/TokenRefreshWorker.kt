package com.anbudream.carecall.work

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.anbudream.carecall.data.RegistrationRepository
import java.util.concurrent.TimeUnit

/**
 * Sends a refreshed FCM token to the server with retry + exponential backoff.
 *
 * Why WorkManager instead of a direct network call in onNewToken():
 *   - onNewToken can fire when the device is offline or the process is short-lived.
 *   - WorkManager persists the work and retries when the network returns.
 */
class TokenRefreshWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val token = inputData.getString(KEY_TOKEN) ?: return Result.failure()

        return RegistrationRepository(applicationContext)
            .pushTokenUpdate(token)
            .fold(
                onSuccess = { Result.success() },
                onFailure = { Result.retry() },
            )
    }

    companion object {
        private const val KEY_TOKEN = "token"
        private const val UNIQUE_WORK = "fcm-token-refresh"

        fun enqueue(context: Context, token: String) {
            val request = OneTimeWorkRequestBuilder<TokenRefreshWorker>()
                .setInputData(workDataOf(KEY_TOKEN to token))
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
                .build()

            // REPLACE: only the latest token matters if several refreshes queue up.
            WorkManager.getInstance(context)
                .enqueueUniqueWork(UNIQUE_WORK, ExistingWorkPolicy.REPLACE, request)
        }
    }
}
