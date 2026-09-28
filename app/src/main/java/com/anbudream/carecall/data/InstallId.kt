package com.anbudream.carecall.data

import android.content.Context
import java.util.UUID

/**
 * A stable identifier generated once per app installation.
 *
 * This is the key design decision that resolves the "don't store the phone number
 * locally, but re-send the token on refresh" tension:
 *
 *   - The phone number is sent to the server ONLY during registration and is never
 *     persisted on the device.
 *   - The server stores the mapping  installId -> { phoneNumber, fcmToken }.
 *   - When FirebaseMessagingService.onNewToken() fires, the app only needs to send
 *     { installId, newToken }. It does not need the phone number at all.
 *
 * The installId is a random UUID. It is not personally identifiable and is safe to
 * keep in plain SharedPreferences. It is cleared automatically if the user clears app
 * data or reinstalls (which also invalidates the old FCM token on the server side).
 */
object InstallId {
    private const val PREFS = "install_prefs"
    private const val KEY = "install_id"

    fun get(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.getString(KEY, null)?.let { return it }

        val generated = UUID.randomUUID().toString()
        prefs.edit().putString(KEY, generated).apply()
        return generated
    }
}
