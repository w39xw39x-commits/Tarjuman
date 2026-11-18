package com.tarjuman.tv

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import kotlin.math.max

/** Captures the sound the TV is playing (any channel / IPTV app) and feeds the pipeline. */
class CaptureService : Service() {
    companion object {
        @Volatile var running = false
            private set
        const val EXTRA_CODE = "code"
        const val EXTRA_DATA = "data"
        const val ACTION_STOP = "com.tarjuman.tv.STOP"

        fun stop(c: Context) {
            c.stopService(Intent(c, CaptureService::class.java))
        }
    }

    private var projection: MediaProjection? = null
    private var record: AudioRecord? = null
    private var worker: Thread? = null
    private var pipeline: Pipeline? = null
    private var overlay: SubtitleOverlay? = null
    private var screenWatcher: ScreenTextWatcher? = null
    @Volatile private var stopping = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null || intent.action == ACTION_STOP) {
            stopSelf(); return START_NOT_STICKY
        }
        startInForeground()
        if (running) return START_NOT_STICKY

        val code = intent.getIntExtra(EXTRA_CODE, 0)
        val data: Intent? = if (Build.VERSION.SDK_INT >= 33)
            intent.getParcelableExtra(EXTRA_DATA, Intent::class.java)
        else @Suppress("DEPRECATION") intent.getParcelableExtra<Intent>(EXTRA_DATA)

        val mpm = getSystemService(MediaProjectionManager::class.java)
        val p = try { data?.let { mpm.getMediaProjection(code, it) } } catch (e: Exception) { null }
        if (p == null) { stopSelf(); return START_NOT_STICKY }
        projection = p
        p.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { stopSelf() }
        }, Handler(Looper.getMainLooper()))

        running = true
        overlay = OverlayHost.get(this)
        pipeline = Pipeline(this, overlay)
        if (Prefs.screenText(this)) {
            val m = resources.displayMetrics
            screenWatcher = try {
                ScreenTextWatcher(p, m.widthPixels, m.heightPixels, m.densityDpi) { pipeline?.onScreenText(it) }
                    .also { it.start() }
            } catch (_: Throwable) { null }
        }
        if (startCapture(p)) {
            overlay?.flash("✓ الترجمة مفعّلة  ←  ${Prefs.target(this).label}")
        }
        return START_NOT_STICKY
    }

    @SuppressLint("MissingPermission")
    private fun startCapture(p: MediaProjection): Boolean {
        val cfg = AudioPlaybackCaptureConfiguration.Builder(p)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()
        val fmt = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(16000)
            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
            .build()
        val minBuf = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = try {
            AudioRecord.Builder()
                .setAudioFormat(fmt)
                .setBufferSizeInBytes(max(minBuf, 32000))
                .setAudioPlaybackCaptureConfig(cfg)
                .build()
        } catch (e: Exception) { null }
        if (rec == null || rec.state != AudioRecord.STATE_INITIALIZED) {
            overlay?.flash("تعذّر التقاط صوت الجهاز", 4000)
            stopSelf(); return false
        }
        record = rec
        rec.startRecording()

        val mode = Prefs.mode(this)
        worker = Thread({
            val frame = ShortArray(320)
            val seg = Segmenter(mode) { pipeline?.submit(it) }
            var lastSound = SystemClock.elapsedRealtime()
            var warned = false
            while (!stopping) {
                val n = try { rec.read(frame, 0, frame.size) } catch (e: Exception) { -1 }
                if (n < 0) break
                if (n == 0) continue
                seg.push(frame, n)
                val now = SystemClock.elapsedRealtime()
                if (seg.lastRms > 20) { lastSound = now; warned = false }
                else if (!warned && now - lastSound > 15000) {
                    warned = true
                    overlay?.flash("لا يصل أي صوت… قد يكون مشغّل القنوات يمنع التقاط الصوت", 5000)
                }
            }
        }, "tarjuman-capture").apply { priority = Thread.MAX_PRIORITY; start() }
        return true
    }

    private fun startInForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("run", "ترجمان", NotificationManager.IMPORTANCE_LOW))
        val stopPi = PendingIntent.getService(
            this, 1, Intent(this, CaptureService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = Notification.Builder(this, "run")
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("ترجمان يعمل")
            .setContentText("اضغط زر الريموت (${Prefs.keyName(Prefs.toggleKey(this))}) للإيقاف")
            .setOngoing(true)
            .addAction(Notification.Action.Builder(Icon.createWithResource(this, R.drawable.ic_stat), "إيقاف", stopPi).build())
            .build()
        startForeground(7, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
    }

    override fun onDestroy() {
        stopping = true
        try { record?.stop() } catch (_: Exception) { }
        try { worker?.join(400) } catch (_: Exception) { }
        try { record?.release() } catch (_: Exception) { }
        screenWatcher?.stop()
        pipeline?.close()
        val wasRunning = running
        running = false
        try { projection?.stop() } catch (_: Exception) { }
        projection = null
        if (wasRunning) overlay?.flash("⏸ الترجمة متوقفة")
        super.onDestroy()
    }
}
