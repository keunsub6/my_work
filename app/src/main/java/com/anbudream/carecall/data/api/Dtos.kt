package com.anbudream.carecall.data.api

// Plain data classes. Gson maps these field names directly to/from JSON keys.
// (No Kotlin serialization compiler plugin is used.)

data class RegisterRequest(
    val installId: String,
    val phoneNumber: String,
    val fcmToken: String,
    val platform: String = "android",
)

data class TokenUpdateRequest(
    val installId: String,
    val fcmToken: String,
)

data class TestPushRequest(
    val installId: String,
)

data class UnregisterRequest(
    val installId: String,
)

data class ApiResponse(
    val ok: Boolean = false,
    val message: String? = null,
)
