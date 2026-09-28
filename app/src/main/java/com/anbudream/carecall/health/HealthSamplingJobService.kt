package com.anbudream.carecall.health

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import kotlin.concurrent.thread

/**
 * 30분 단위로 OS가 배치 실행하는 짧은 작업입니다.
 * 하드웨어 누적 걸음수의 스냅샷만 읽고 즉시 센서를 해제합니다.
 */
class HealthSamplingJobService : JobService(), SensorEventListener {
    private var params: JobParameters? = null
    private var sensorManager: SensorManager? = null
    private var completed = false
    private val handler = Handler(Looper.getMainLooper())
    private val timeout = Runnable { finishSample("step_sample_timeout") }

    override fun onStartJob(jobParams: JobParameters): Boolean {
        params = jobParams
        // [carecall] minSdk 24 대응: 집계 로직이 java.time(API 26+)에 의존합니다.
        if (!HealthFeature.isSupported) {
            cancel(this)
            jobFinished(jobParams, false)
            return false
        }
        if (!RegistrationClient.isRegistrationEnabled(this)) {
            jobFinished(jobParams, false)
            return false
        }

        HealthSensorCoordinator.initialize(this)
        sensorManager = getSystemService(SensorManager::class.java)
        val stepCounter = sensorManager?.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)
        if (stepCounter == null) {
            HealthMetricsStore.noteStepCounterUnsupported(this)
            finishSample("step_counter_unsupported")
            return true
        }

        val registered = runCatching {
            sensorManager?.registerListener(this, stepCounter, SensorManager.SENSOR_DELAY_NORMAL) == true
        }.getOrDefault(false)
        if (!registered) {
            HealthMetricsStore.noteStepSampleFailure(this, "step_listener_unavailable")
            finishSample("step_listener_unavailable")
            return true
        }

        handler.postDelayed(timeout, SAMPLE_TIMEOUT_MS)
        return true
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (completed || event?.sensor?.type != Sensor.TYPE_STEP_COUNTER) return
        val value = event.values.firstOrNull()?.toDouble() ?: return
        if (!value.isFinite() || value < 0.0) return
        val bootCount = runCatching {
            Settings.Global.getInt(contentResolver, Settings.Global.BOOT_COUNT)
        }.getOrDefault(-1)
        HealthMetricsStore.recordStepCounter(
            context = this,
            timestampMs = System.currentTimeMillis(),
            cumulativeSteps = value.toLong(),
            bootCount = bootCount,
        )
        finishSample(null)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    override fun onStopJob(jobParams: JobParameters?): Boolean {
        cleanupSensor()
        return true
    }

    private fun finishSample(reason: String?) {
        if (completed) return
        completed = true
        handler.removeCallbacks(timeout)
        cleanupSensor()
        if (reason != null) HealthMetricsStore.noteStepSampleFailure(this, reason)

        val p = params
        thread(name = "health-metrics-upload") {
            runCatching { HealthMetricsUploader.uploadDue(this) }
                .onFailure {
                    // [패치 #5] 클래스명만으로는 'JSONException' 한 단어가 전부였습니다.
                    // 메시지에 필드 단서(Forbidden numeric value 등)가 함께 남게 합니다.
                    DebugLog.event(this, "건강지표 전송 작업 실패: ${it.javaClass.simpleName}: ${it.message}")
                }
            if (p != null) jobFinished(p, false)
        }
    }

    private fun cleanupSensor() {
        runCatching { sensorManager?.unregisterListener(this) }
        sensorManager = null
    }

    companion object {
        private const val JOB_ID = 41028
        private const val INTERVAL_MS = 30L * 60L * 1000L
        private const val FLEX_MS = 10L * 60L * 1000L
        private const val SAMPLE_TIMEOUT_MS = 8_000L

        /** 예약만 되어 있고 실제로는 오래 안 돈 상태로 볼 기준(주기 30분 + 여유 10분의 3배). */
        private const val STALE_MS = 2L * 60L * 60L * 1000L

        fun schedule(ctx: Context) = schedule(ctx, force = false)

        /**
         * [수정] 예약 여부만 보면 되살릴 수 없습니다.
         *
         * allPendingJobs 에 있다는 건 '예약됨'이지 '돈다'는 뜻이 아닙니다. 절전 제한이나
         * 앱 대기 버킷 강등으로 잡이 예약된 채 영영 안 뜨면, 예전 코드는 "이미 있네" 하고
         * 그냥 돌아와서 아무리 푸시를 보내도 복구되지 않았습니다.
         * 마지막 표본이 오래됐으면 취소 후 다시 예약합니다.
         */
        fun schedule(ctx: Context, force: Boolean) {
            val scheduler = ctx.getSystemService(JobScheduler::class.java) ?: return
            val pending = scheduler.allPendingJobs.any { it.id == JOB_ID }
            val stale = HealthMetricsStore.msSinceLastStepSample(ctx) > STALE_MS
            if (pending && !force && !stale) return
            if (pending) runCatching { scheduler.cancel(JOB_ID) }
            val info = JobInfo.Builder(JOB_ID, ComponentName(ctx, HealthSamplingJobService::class.java))
                .setPeriodic(INTERVAL_MS, FLEX_MS)
                .setPersisted(true)
                .build()
            runCatching { scheduler.schedule(info) }
        }

        /** 잡이 예약되어 있는지. 진단 보고용이며 권한이 필요 없습니다. */
        fun isScheduled(ctx: Context): Boolean =
            ctx.getSystemService(JobScheduler::class.java)
                ?.allPendingJobs?.any { it.id == JOB_ID } == true

        /**
         * [추가] 30분 잡을 기다리지 않고 지금 즉시 걸음 스냅샷을 한 번 읽습니다.
         *
         * 푸시(HealthCollectWorker)가 백업 경로로 씁니다. 잡이 죽어 있어도 최소한 하루
         * 기록은 생겨서, 서버가 '앱이 죽음'과 '표본이 아직 모자람'을 구분할 수 있습니다.
         * 잡과 같은 8초 제한을 씁니다.
         */
        suspend fun sampleOnce(ctx: Context): Boolean = suspendCancellableCoroutine { cont ->
            val sm = ctx.getSystemService(SensorManager::class.java)
            val sensor = sm?.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)
            if (sm == null || sensor == null) {
                HealthMetricsStore.noteStepCounterUnsupported(ctx)
                if (cont.isActive) cont.resume(false)
                return@suspendCancellableCoroutine
            }
            val handler = Handler(Looper.getMainLooper())
            val done = AtomicBoolean(false)
            var listener: SensorEventListener? = null

            fun finish(result: Boolean) {
                if (!done.compareAndSet(false, true)) return
                listener?.let { l -> runCatching { sm.unregisterListener(l) } }
                if (cont.isActive) cont.resume(result)
            }

            listener = object : SensorEventListener {
                override fun onSensorChanged(event: SensorEvent?) {
                    if (event?.sensor?.type != Sensor.TYPE_STEP_COUNTER) return
                    val v = event.values.firstOrNull()?.toDouble() ?: return
                    if (!v.isFinite() || v < 0.0) return
                    val boot = runCatching {
                        Settings.Global.getInt(ctx.contentResolver, Settings.Global.BOOT_COUNT)
                    }.getOrDefault(-1)
                    runCatching {
                        HealthMetricsStore.recordStepCounter(ctx, System.currentTimeMillis(), v.toLong(), boot)
                    }
                    finish(true)
                }
                override fun onAccuracyChanged(s: Sensor?, a: Int) = Unit
            }

            val registered = runCatching {
                sm.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_NORMAL)
            }.getOrDefault(false)
            if (!registered) {
                HealthMetricsStore.noteStepSampleFailure(ctx, "step_listener_unavailable")
                finish(false)
                return@suspendCancellableCoroutine
            }
            handler.postDelayed({ finish(false) }, SAMPLE_TIMEOUT_MS)
            cont.invokeOnCancellation { finish(false) }
        }

        fun cancel(ctx: Context) {
            ctx.getSystemService(JobScheduler::class.java)?.cancel(JOB_ID)
        }
    }
}
