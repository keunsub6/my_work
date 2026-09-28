package com.anbudream.carecall.screening

import android.content.Context
import android.media.AudioManager
import android.os.Build
import android.os.SystemClock
import com.anbudream.carecall.time.AppLog

/**
 * 안부 통화 중 셀룰러 수신 전화 차단 상태.
 *
 * 리스 경과시간은 벽시계가 아니라 elapsedRealtime으로 판단합니다. 사용자가 시각/시간대를
 * 바꾸거나 통신사 시간이 보정돼도 10분 리스가 늘어나거나 즉시 만료되지 않습니다.
 */
object CallScreenState {

    private const val PREFS = "call_screen_state"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_ACTIVE = "active"
    private const val KEY_CID = "cid"
    private const val KEY_ACTIVATED_AT = "activated_at"                 // 서버 epoch 비교/진단용 벽시계
    private const val KEY_SERVER_STARTED_AT = "server_started_at"       // 서버가 보낸 epoch
    private const val KEY_ACTIVATED_ELAPSED = "activated_elapsed"       // 경과시간 판정용
    private const val KEY_ACTIVE_UNTIL_ELAPSED = "active_until_elapsed" // 경과시간 판정용
    private const val KEY_AUDIO_SEEN = "audio_seen"

    /** 하드 상한. 서버 푸시가 유실돼도 이 시간이면 반드시 정상 수신으로 돌아옵니다. */
    const val LEASE_MS = 10L * 60L * 1000L

    private const val SERVER_TIME_TOLERANCE_MS = 2L * 60L * 1000L
    private val lock = Any()

    data class Snapshot(
        val active: Boolean,
        val cid: String?,
        val activatedAt: Long,
        val serverStartedAt: Long,
        val activeUntil: Long,
    )

    val isSupported: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

    fun isEnabled(ctx: Context): Boolean =
        isSupported && runCatching { prefs(ctx).getBoolean(KEY_ENABLED, false) }.getOrDefault(false)

    fun setEnabled(ctx: Context, enabled: Boolean) {
        runCatching { prefs(ctx).edit().putBoolean(KEY_ENABLED, enabled).apply() }
        if (!enabled) release(ctx, "사용자가 기능을 껐습니다")
    }

    /** care_call 푸시 수신 시점에 cid만 미리 적어 둡니다(아직 차단하지 않습니다). */
    fun rememberCid(ctx: Context, cid: String?) {
        if (cid.isNullOrBlank()) return
        runCatching { prefs(ctx).edit().putString(KEY_CID, cid).apply() }
    }

    /**
     * @param cid null이면 기억된 cid를 유지합니다. 빈 문자열이면 이전 cid를 지웁니다.
     * @param serverStartedAt 서버 call_start의 epoch. 화면 ACK 경로에서는 null입니다.
     */
    fun activate(ctx: Context, cid: String? = null, serverStartedAt: Long? = null) {
        if (!isEnabled(ctx)) return
        val wallNow = System.currentTimeMillis()
        val elapsedNow = SystemClock.elapsedRealtime()
        synchronized(lock) {
            runCatching {
                val editor = prefs(ctx).edit()
                    .putBoolean(KEY_ACTIVE, true)
                    .putLong(KEY_ACTIVATED_AT, wallNow)
                    .putLong(KEY_ACTIVATED_ELAPSED, elapsedNow)
                    .putLong(KEY_ACTIVE_UNTIL_ELAPSED, elapsedNow + LEASE_MS)
                    .putBoolean(KEY_AUDIO_SEEN, false)
                if (cid != null) {
                    if (cid.isBlank()) editor.remove(KEY_CID) else editor.putString(KEY_CID, cid)
                }
                if (serverStartedAt != null && serverStartedAt > 0L) {
                    editor.putLong(KEY_SERVER_STARTED_AT, serverStartedAt)
                }
                editor.commit()
            }
        }
        ScreeningNotifier.show(ctx, wallNow + LEASE_MS)
        AppLog.i(TAG, "call screening activated cid=${cid?.take(12)}")
    }

    fun release(ctx: Context, reason: String) {
        synchronized(lock) {
            runCatching {
                prefs(ctx).edit()
                    .putBoolean(KEY_ACTIVE, false)
                    .remove(KEY_ACTIVATED_AT)
                    .remove(KEY_SERVER_STARTED_AT)
                    .remove(KEY_ACTIVATED_ELAPSED)
                    .remove(KEY_ACTIVE_UNTIL_ELAPSED)
                    .remove(KEY_AUDIO_SEEN)
                    .remove(KEY_CID)
                    .commit()
            }
        }
        ScreeningNotifier.cancel(ctx)
        AppLog.i(TAG, "call screening released reason=$reason")
    }

    /**
     * 서버 종료 신호를 처리합니다.
     *
     * cid가 양쪽에 있으면 cid를 최우선으로 사용하므로 서버/기기 벽시계 차이와 무관합니다.
     * cid가 없는 레거시 경로만 서버 startedAt/endedAt 순서를 보조 검증에 사용합니다.
     */
    fun releaseFromServer(ctx: Context, cid: String?, endedAt: Long?) {
        val snap = snapshot(ctx)
        if (!snap.active) return

        val remoteCid = cid?.takeIf { it.isNotBlank() }
        val localCid = snap.cid?.takeIf { it.isNotBlank() }
        if (remoteCid != null && localCid != null && remoteCid != localCid) {
            AppLog.w(TAG, "ignored call_end because cid mismatched")
            return
        }

        val ended = endedAt ?: 0L
        if (
            ended > 0L && snap.serverStartedAt > 0L &&
            ended + SERVER_TIME_TOLERANCE_MS < snap.serverStartedAt
        ) {
            AppLog.w(TAG, "ignored call_end older than server call_start")
            return
        }

        // 명시적 불일치가 없으면 fail-open: 전화를 계속 막는 것보다 즉시 해제가 안전합니다.
        release(ctx, "서버 통화 종료 신호")
    }

    fun snapshot(ctx: Context): Snapshot {
        val p = runCatching { prefs(ctx) }.getOrNull()
            ?: return inactiveSnapshot()
        val active = p.getBoolean(KEY_ACTIVE, false)
        if (!active) return inactiveSnapshot()

        val activatedElapsed = p.getLong(KEY_ACTIVATED_ELAPSED, 0L)
        val untilElapsed = p.getLong(KEY_ACTIVE_UNTIL_ELAPSED, 0L)
        val nowElapsed = SystemClock.elapsedRealtime()

        // 업데이트 전 상태, 재부팅, 손상된 상태는 모두 fail-open으로 즉시 해제합니다.
        if (
            activatedElapsed <= 0L || untilElapsed <= activatedElapsed ||
            nowElapsed < activatedElapsed || nowElapsed >= untilElapsed
        ) {
            release(ctx, if (nowElapsed < activatedElapsed) "재부팅 감지" else "리스 만료 또는 상태 마이그레이션")
            return inactiveSnapshot()
        }

        val remaining = untilElapsed - nowElapsed
        return Snapshot(
            active = true,
            cid = p.getString(KEY_CID, null),
            activatedAt = p.getLong(KEY_ACTIVATED_AT, 0L),
            serverStartedAt = p.getLong(KEY_SERVER_STARTED_AT, 0L),
            activeUntil = System.currentTimeMillis() + remaining,
        )
    }

    fun shouldBlock(ctx: Context): Boolean {
        if (!isSupported || !isEnabled(ctx)) return false
        val snap = snapshot(ctx)
        if (!snap.active) return false

        if (!isCallAudioLive(ctx)) {
            release(ctx, "통화 오디오 종료 감지")
            return false
        }
        return true
    }

    private fun isCallAudioLive(ctx: Context): Boolean {
        val am = runCatching { ctx.getSystemService(AudioManager::class.java) }.getOrNull()
            ?: return true
        val mode = runCatching { am.mode }.getOrDefault(AudioManager.MODE_NORMAL)

        if (mode == AudioManager.MODE_IN_COMMUNICATION || mode == AudioManager.MODE_IN_CALL) {
            runCatching { prefs(ctx).edit().putBoolean(KEY_AUDIO_SEEN, true).commit() }
            return true
        }
        if (mode == AudioManager.MODE_RINGTONE) return true

        val audioSeen = runCatching { prefs(ctx).getBoolean(KEY_AUDIO_SEEN, false) }
            .getOrDefault(false)
        return !audioSeen
    }

    private fun inactiveSnapshot() = Snapshot(false, null, 0L, 0L, 0L)

    private fun prefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private const val TAG = "CallScreenState"
}
