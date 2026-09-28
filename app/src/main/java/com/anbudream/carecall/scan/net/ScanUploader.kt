package com.anbudream.carecall.scan.net

import com.anbudream.carecall.BuildConfig
import com.anbudream.carecall.scan.ScanContract
import com.anbudream.carecall.scan.ScanLog
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * 판독 결과 전송.
 *
 * CareCall 의 기존 서버 설정(BuildConfig.SERVER_BASE_URL / API_KEY)을 그대로 씁니다.
 * Retrofit(ApiClient)을 쓰지 않는 이유는 기존 RegisterApi 인터페이스를 건드리지 않기
 * 위해서입니다. 라우트 하나뿐이라 HttpURLConnection 으로 충분하고, 기존 통신 계층에
 * 아무 변경도 남기지 않습니다.
 *
 * 전송하는 것: 판독 원문 텍스트, cid, nonce.
 * 전송하지 않는 것: 이미지. 어떤 경우에도 사진은 기기를 떠나지 않습니다.
 */
object ScanUploader {
    private const val SUB = "Net"

    val enabled: Boolean get() = BuildConfig.SERVER_BASE_URL.isNotBlank()

    /** 판독 결과 전송. nonce 대조는 서버가 합니다. */
    suspend fun sendResult(cid: String, nonce: String, rawText: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val url = URL(BuildConfig.SERVER_BASE_URL.trimEnd('/') + ScanContract.PATH_RESULT)
                val payload = JSONObject()
                    .put("cid", cid)
                    .put("nonce", nonce)
                    .put("rawText", rawText)
                    .put("capturedAt", System.currentTimeMillis())
                    .toString()
                    .toByteArray(Charsets.UTF_8)

                val conn = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 8_000
                    readTimeout = 12_000
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    // 기존 ApiClient 인터셉터와 동일한 인증 헤더입니다.
                    setRequestProperty("X-API-Key", BuildConfig.API_KEY)
                    setFixedLengthStreamingMode(payload.size)
                }
                try {
                    conn.outputStream.use { it.write(payload) }
                    val code = conn.responseCode
                    if (code !in 200..299) {
                        val body = runCatching {
                            conn.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                        }.getOrDefault("")
                        ScanLog.w(SUB, "POST ${ScanContract.PATH_RESULT} -> $code $body")
                        error("server responded $code")
                    }
                    ScanLog.i(SUB, "result sent cid=$cid chars=${rawText.length}")
                    Unit
                } finally {
                    conn.disconnect()
                }
            }
        }
}
