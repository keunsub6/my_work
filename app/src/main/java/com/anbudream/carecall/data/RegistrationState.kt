package com.anbudream.carecall.data

import android.content.Context

/**
 * Remembers whether this install has completed registration, so that on later launches
 * the app can show the "send test push" action directly instead of asking the user to
 * register again. Stores only a boolean — no personal data.
 */
object RegistrationState {
    private const val PREFS = "reg_prefs"
    private const val KEY = "registered"

    fun isRegistered(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY, false)

    fun setRegistered(context: Context, value: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY, value).apply()
    }
}
