package com.anbudream.carecall.health

import android.content.Context
import android.os.Build
import com.anbudream.carecall.BuildConfig
import com.anbudream.carecall.data.InstallId
import com.anbudream.carecall.data.RegistrationState
import com.anbudream.carecall.time.AppLog
import com.google.android.gms.tasks.Tasks
import com.google.firebase.messaging.FirebaseMessaging
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit

/**
 * 건강지표 모듈이 기존 앱(carecall)에 붙기 위한 얇은 접착층입니다.
 *
 * 수집 모듈(care 앱)이 참조하던 심볼 이름을 그대로 유지해, 이식한 5개 파일을
 * 사실상 무수정으로 쓸 수 있게 합니다. 기존 클래스는 읽기만 하고 바꾸지 않습니다.
 */

/** minSdk 24 대응. 수집 로직이 java.time(API 26+)을 쓰므로 그 미만에서는 기능 전체를 끕니다. */
internal object HealthFeature {
    val isSupported: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
}

/** care 앱의 RegistrationClient → carecall의 RegistrationState / InstallId 로 매핑. */
internal object RegistrationClient {
    fun isRegistrationEnabled(ctx: Context): Boolean =
        runCatching { RegistrationState.isRegistered(ctx) }.getOrDefault(false)

    /** 서버가 토큰 조회에 실패했을 때 쓰는 보조 키. installId와 동일하게 둡니다. */
    fun subjectId(ctx: Context): String? =
        runCatching { InstallId.get(ctx) }.getOrNull()
}

/**
 * 업로드에 쓸 FCM 토큰 보관소.
 *
 * 서버는 "푸시 시점의 토큰 → 전화번호"를 인덱싱하므로, 업로드 때도 같은 토큰이어야
 * 파일이 전화번호로 묶입니다. 기존 설치분은 저장된 토큰이 없으므로 Firebase에서
 * 직접 받아 캐시합니다(반드시 백그라운드 스레드에서 호출).
 */
internal object CareTokenVerifier {
    private const val PREFS = "health_fcm_token"
    private const val KEY = "fcm_token"

    fun save(ctx: Context, token: String?) {
        if (token.isNullOrBlank()) return
        runCatching {
            ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY, token).apply()
        }
    }

    fun savedFcmToken(ctx: Context): String? {
        val appCtx = ctx.applicationContext
        val cached = runCatching {
            appCtx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
        }.getOrNull()
        if (!cached.isNullOrBlank()) return cached

        // 기존 설치분(업데이트 전에 등록을 마친 사용자) 대비 1회성 백필.
        val fetched = runCatching {
            Tasks.await(FirebaseMessaging.getInstance().token, 10, TimeUnit.SECONDS)
        }.getOrNull()
        if (!fetched.isNullOrBlank()) save(appCtx, fetched)
        return fetched?.takeIf { it.isNotBlank() }
    }
}

/** care 앱의 Config → BuildConfig.SERVER_BASE_URL 에서 파생. 별도 상수 추가가 필요 없습니다. */
internal object Config {
    private val base: String = BuildConfig.SERVER_BASE_URL.trimEnd('/')
    val HEALTH_METRICS_URL: String = "$base/health/metrics"
    val HEALTH_ISSUE_URL: String = "$base/health/collect/issue"
}

/** care 앱의 DebugLog → 릴리즈 빌드에서는 아무것도 남기지 않습니다. */
internal object DebugLog {
    private const val TAG = "CareHealth"

    /**
     * [수정] 예전에는 BuildConfig.DEBUG 일 때만 남겨서, release 빌드에서는
     * '건강지표 전송 보류 code=...' 같은 결정적 단서가 통째로 사라졌습니다.
     * 현장에서 수집이 멈췄을 때 원인을 볼 방법이 없어 진단이 불가능했습니다.
     *
     * 남기는 값은 날짜·HTTP 코드·사유뿐이며 개인정보는 담지 않습니다.
     * (전화번호·토큰·좌표를 넣지 마세요. 로그캣은 같은 기기의 다른 앱이 못 읽지만
     *  버그리포트에는 포함됩니다.)
     */
    @Suppress("UNUSED_PARAMETER")
    fun event(ctx: Context, message: String) {
        AppLog.i(TAG, message)
    }
}

/**
 * 건강지표 전용 최소 HTTP 클라이언트.
 *
 * 기존 Retrofit/OkHttp(ApiClient)를 일부러 쓰지 않습니다. ApiClient는 모든 요청에
 * X-API-Key를 붙이고 baseUrl에 묶여 있어, 여기에 손대면 등록/토큰/테스트푸시 경로까지
 * 영향권에 들어갑니다. 이 모듈은 완전히 독립적으로 동작해야 합니다.
 */
internal object HealthHttp {

    /** @return HTTP 상태코드. 네트워크 예외면 -1. */
    fun postJson(url: String, bearer: String, body: String): Int {
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = 5_000
                readTimeout = 5_000
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("Authorization", "Bearer $bearer")
            }
            connection.outputStream.use { stream: OutputStream ->
                stream.write(body.toByteArray(Charsets.UTF_8))
            }
            val code = connection.responseCode
            runCatching {
                (if (code in 200..299) connection.inputStream else connection.errorStream)?.close()
            }
            code
        } catch (_: Exception) {
            -1
        } finally {
            runCatching { connection?.disconnect() }
        }
    }
}
