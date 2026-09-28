package com.anbudream.carecall.data

import android.content.Context
import com.anbudream.carecall.data.api.ApiClient
import com.anbudream.carecall.data.api.RegisterRequest
import com.anbudream.carecall.data.api.TestPushRequest
import com.anbudream.carecall.data.api.TokenUpdateRequest
import com.anbudream.carecall.data.api.UnregisterRequest
import com.anbudream.carecall.health.CareTokenVerifier
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.tasks.await

/**
 * Orchestrates registration and token updates.
 *
 * Important: the phone number is used transiently (passed in, sent to the server) and
 * is NEVER written to disk. The only thing kept locally is the non-PII installId.
 */
class RegistrationRepository(private val context: Context) {

    /** Step: phone (already acquired) + fresh FCM token -> POST /api/register. */
    suspend fun register(phoneNumber: String): Result<Unit> = runCatching {
        val token = FirebaseMessaging.getInstance().token.await()
        // [추가] 건강지표 업로드는 서버가 이 토큰으로 전화번호를 역조회하므로 보관해 둡니다.
        CareTokenVerifier.save(context, token)
        val installId = InstallId.get(context)

        val response = ApiClient.api.register(
            RegisterRequest(
                installId = installId,
                phoneNumber = phoneNumber,
                fcmToken = token,
            )
        )
        if (!response.ok) {
            error(response.message ?: "서버가 등록을 거부했습니다.")
        }
    }

    /** Called from onNewToken via WorkManager. Only needs installId + the new token. */
    suspend fun pushTokenUpdate(newToken: String): Result<Unit> = runCatching {
        val installId = InstallId.get(context)
        val response = ApiClient.api.updateToken(
            TokenUpdateRequest(installId = installId, fcmToken = newToken)
        )
        if (!response.ok) {
            error(response.message ?: "서버가 토큰 갱신을 거부했습니다.")
        }
    }

    /** Ask the server to send a test push to THIS device (looked up by installId). */
    suspend fun sendTestPush(): Result<Unit> = runCatching {
        val installId = InstallId.get(context)
        val response = ApiClient.api.sendTestPush(TestPushRequest(installId = installId))
        if (!response.ok) {
            error(response.message ?: "알림 전송에 실패했습니다.")
        }
    }

    /** Delete this device's registration on the server (data deletion). */
    suspend fun unregister(): Result<Unit> = runCatching {
        val installId = InstallId.get(context)
        val response = ApiClient.api.unregister(UnregisterRequest(installId = installId))
        if (!response.ok) {
            error(response.message ?: "등록 해제에 실패했습니다.")
        }
    }
}
