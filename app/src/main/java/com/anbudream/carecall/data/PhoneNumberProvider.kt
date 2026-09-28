package com.anbudream.carecall.data

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import androidx.annotation.RequiresPermission
import com.google.android.gms.auth.api.identity.GetPhoneNumberHintIntentRequest
import com.google.android.gms.auth.api.identity.Identity
import kotlinx.coroutines.tasks.await

/**
 * Helpers for the three-tier phone-number acquisition strategy.
 *
 *   1. Phone Number Hint API   — preferred. User picks a number from a Google sheet.
 *                                 (Just a *hint*: user may cancel; needs Play Services.)
 *   2. Permission + read       — fallback. Often returns blank on many carriers/devices
 *                                 because the MSISDN is not stored on the SIM.
 *   3. Manual entry            — final fallback, handled in the UI layer.
 *
 * Because 1 and 2 can BOTH silently fail, the UI must always be ready to fall through
 * to manual entry. The caller decides; this object only exposes the building blocks.
 */
object PhoneNumberProvider {

    /** Builds the request and returns a PendingIntent to launch the Hint UI, or null on failure. */
    suspend fun getHintPendingIntent(context: Context): PendingIntent? = runCatching {
        val request = GetPhoneNumberHintIntentRequest.builder().build()
        Identity.getSignInClient(context)
            .getPhoneNumberHintIntent(request)
            .await()
    }.getOrNull()

    /** Extracts the chosen phone number from the Hint result intent, or null. */
    fun parseHintResult(context: Context, data: Intent?): String? {
        if (data == null) return null
        return runCatching {
            Identity.getSignInClient(context).getPhoneNumberFromIntent(data)
        }.getOrNull()?.takeIf { it.isNotBlank() }
    }

    /**
     * Reads the device line number using the non-deprecated path on API 33+.
     * Returns null if unavailable (very common) instead of an empty string.
     *
     * Requires READ_PHONE_NUMBERS (declared in the manifest, granted at runtime).
     */
    @RequiresPermission(anyOf = [Manifest.permission.READ_PHONE_NUMBERS, Manifest.permission.READ_PHONE_STATE])
    fun readFromTelephony(context: Context): String? = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val subscriptionManager = context.getSystemService(SubscriptionManager::class.java)
            val subId = SubscriptionManager.getDefaultSubscriptionId()
            subscriptionManager?.getPhoneNumber(subId)
        } else {
            @Suppress("DEPRECATION", "HardwareIds")
            context.getSystemService(TelephonyManager::class.java)?.line1Number
        }
    }.getOrNull()?.takeIf { it.isNotBlank() }
}
