package com.anbudream.carecall.time

import android.util.Log

/** Logcat 자체 시간대와 무관하게 메시지 안에 KST 시각을 남깁니다. */
object AppLog {
    fun i(tag: String, message: String) = Log.i(tag, prefix(message))
    fun w(tag: String, message: String) = Log.w(tag, prefix(message))
    fun e(tag: String, message: String) = Log.e(tag, prefix(message))
    fun e(tag: String, message: String, error: Throwable) = Log.e(tag, prefix(message), error)

    private fun prefix(message: String): String = "[${AppTime.kstLogTimestamp()}] $message"
}
