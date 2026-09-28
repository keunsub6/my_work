package com.anbudream.carecall.data.api

import retrofit2.http.Body
import retrofit2.http.POST

interface RegisterApi {

    @POST("api/register")
    suspend fun register(@Body body: RegisterRequest): ApiResponse

    @POST("api/token")
    suspend fun updateToken(@Body body: TokenUpdateRequest): ApiResponse

    @POST("api/test-push")
    suspend fun sendTestPush(@Body body: TestPushRequest): ApiResponse

    @POST("api/unregister")
    suspend fun unregister(@Body body: UnregisterRequest): ApiResponse
}
