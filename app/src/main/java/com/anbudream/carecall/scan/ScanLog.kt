package com.anbudream.carecall.scan

import android.util.Log

/**
 * 스캔 기능 전용 로그. CareCall 의 기존 time.AppLog 와 이름이 겹치지 않도록
 * ScanLog 로 둡니다. 태그가 분리돼 있어 기존 로그와 섞이지 않습니다:
 *   adb logcat -s CareScan
 */
object ScanLog {
    private const val TAG = "CareScan"
    fun i(sub: String, msg: String) = Log.i(TAG, "[$sub] $msg")
    fun w(sub: String, msg: String, t: Throwable? = null) = Log.w(TAG, "[$sub] $msg", t)
    fun e(sub: String, msg: String, t: Throwable? = null) = Log.e(TAG, "[$sub] $msg", t)
}
