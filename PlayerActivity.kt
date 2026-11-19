package com.tarjuman.tv

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.TeeAudioProcessor
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import kotlin.math.max
import kotlin.math.min

/** Renderers whose audio also flows into our speech tap (used by the hidden "ahead" player). */
@OptIn(UnstableApi::class)
class TapRenderersFactory(ctx: Context, private val tap: PcmTap) : DefaultRenderersFactory(ctx) {
    override fun buildAudioSink(context: Context, enableFloatOutput: Boolean, enableAudioTrackPlaybackParams: Boolean): AudioSink? =
        DefaultAudioSink.Builder(context)
            .setAudioProcessors(arrayOf<androidx.media3.common.audio.AudioProcessor>(TeeAudioProcessor(tap)))
            .build()
}

/**
 * IPTV player with translation in perfect sync:
 * the hidden "ahead" player listens to the live stream, the screen player shows the same stream a
 * few seconds later, and each translated line is shown exactly when the screen reaches its sentence.
 * The delay tunes itself (hidden): if a line ever arrives late, the screen waits a bit more.
 */
@OptIn(UnstableApi::class)
class PlayerActivity : Activity() {
    companion object {
        const val EXTRA_INDEX = "index"
        @Volatile var active: PlayerActivity? = null
            private set

        fun open(c: Context, index: Int) {
            c.startActivity(Intent(c, PlayerActivity::class.java).putExtra(EXTRA_INDEX, index)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        }

        /** Most accurate settings: the picture waits for the text, so there is time for quality. */
        private val PLAYER_MODE = Mode("مشغّل", 1400, 6500, 420, AiClient.FALLBACK)
    }

    private val ui = Handler(Looper.getMainLooper())
    private lateinit var playerView: PlayerView
    private lateinit var subView: OutlineTextView
    private lateinit var banner: TextView
    private lateinit var status: TextView

    private var channels: List<Channel> = emptyList()
    private var index = 0

    private var tee: StreamTee? = null
    private var ahead: ExoPlayer? = null
    private var screen: ExoPlayer? = null
    private var pipeline: Pipeline? = null
    private var segmenter: Segmenter? = null
    private var hls = false

    /** how far the screen is behind the listener (ms) - tuned automatically, never shown */
    @Volatile private var delayMs = 5000L
    private var subsOn = true

    /** (device time, ahead-player media time) samples to place sentences on the film timeline */
    private val clock = ArrayDeque<Pair<Long, Long>>()
    /** translated lines waiting for the screen to reach them, ordered by film time */
    private val queue = ArrayList<Pair<Long, Cue>>()
    private var lastShownAt = 0L

    private fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (CaptureService.running) CaptureService.stop(this) // no screen/sound capture needed here
        channels = Playlist.channels(this)
        index = intent.getIntExtra(EXTRA_INDEX, 0).coerceIn(0, max(0, channels.size - 1))
        delayMs = getSharedPreferences("tarjuman", MODE_PRIVATE).getLong("player_delay", 5000L)

        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        playerView = PlayerView(this).apply {
            useController = false
            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
            setShutterBackgroundColor(Color.BLACK)
        }
        root.addView(playerView, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))

        subView = OutlineTextView(this).apply {
            gravity = Gravity.CENTER
            textDirection = View.TEXT_DIRECTION_FIRST_STRONG
            setLineSpacing(0f, 1.1f)
            maxWidth = (resources.displayMetrics.widthPixels * 0.88).toInt()
            visibility = View.INVISIBLE
        }
        root.addView(subView, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
            bottomMargin = (resources.displayMetrics.heightPixels * 0.07).toInt()
        })

        banner = TextView(this).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(18f).toInt(), dp(10f).toInt(), dp(18f).toInt(), dp(10f).toInt())
            background = GradientDrawable().apply { cornerRadius = dp(14f); setColor(0x99000000.toInt()) }
            visibility = View.INVISIBLE
        }
        root.addView(banner, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.TOP or Gravity.START).apply {
            topMargin = dp(28f).toInt(); marginStart = dp(28f).toInt()
        })

        status = TextView(this).apply {
            setTextColor(0xFFFFC94A.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            typeface = Typeface.DEFAULT_BOLD
            text = "جارٍ تجهيز القناة والترجمة…"
        }
        root.addView(status, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.CENTER))
        setContentView(root)
    }

    override fun onStart() {
        super.onStart()
        active = this
        if (channels.isEmpty()) { status.text = "لا توجد قنوات — أضف قائمة القنوات من الإعدادات"; return }
        play(index)
        ui.post(tick)
    }

    override fun onStop() {
        super.onStop()
        active = null
        ui.removeCallbacksAndMessages(null)
        release()
        getSharedPreferences("tarjuman", MODE_PRIVATE).edit().putLong("player_delay", delayMs).apply()
    }

    // ------------------------------------------------------------------ playback

    private fun play(i: Int) {
        release()
        index = (i + channels.size) % channels.size
        val ch = channels[index]
        showBanner("${index + 1}  ${ch.name}")
        status.visibility = View.VISIBLE
        status.text = "جارٍ تجهيز القناة والترجمة…"
        queue.clear(); clock.clear(); subView.visibility = View.INVISIBLE
        hls = ch.url.contains(".m3u8", true)

        // listener side: speech -> segments -> translation, placed on the film timeline
        val seg = Segmenter(PLAYER_MODE) { pipeline?.submit(it) }
        segmenter = seg
        pipeline = Pipeline(this, sink, cueSink = { cue -> ui.post { place(cue) } }, modeOverride = PLAYER_MODE)
        val tap = PcmTap { f, n -> seg.push(f, n) }

        val a = ExoPlayer.Builder(this, TapRenderersFactory(this, tap)).build()
        a.trackSelectionParameters = a.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, true)
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            .build()
        a.volume = 0f
        val s = ExoPlayer.Builder(this).build()
        playerView.player = s
        ahead = a; screen = s

        if (hls) {
            val http = DefaultHttpDataSource.Factory().setUserAgent("Mozilla/5.0 (Linux; Android 14) Tarjuman/1.0")
            val f = HlsMediaSource.Factory(http)
            a.setMediaSource(f.createMediaSource(MediaItem.fromUri(ch.url)))
            a.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(state: Int) {
                    if (state == Player.STATE_READY && s.mediaItemCount == 0) {
                        val live = a.currentLiveOffset
                        val target = (if (live != C.TIME_UNSET) live else 0L) + delayMs
                        val item = MediaItem.Builder().setUri(ch.url).setLiveConfiguration(
                            MediaItem.LiveConfiguration.Builder().setTargetOffsetMs(target)
                                .setMinOffsetMs(target).setMaxOffsetMs(target + 2000).build()
                        ).build()
                        s.setMediaSource(f.createMediaSource(item)); s.prepare(); s.playWhenReady = true
                    }
                }
            })
        } else {
            val t = StreamTee(ch.url).also { it.delayMs = delayMs; it.start() }
            tee = t
            a.setMediaSource(ProgressiveMediaSource.Factory(TeeDataSource.Factory(t) { 0L }).createMediaSource(MediaItem.fromUri(ch.url)))
            s.setMediaSource(ProgressiveMediaSource.Factory(TeeDataSource.Factory(t) { delayMs }).createMediaSource(MediaItem.fromUri(ch.url)))
            s.prepare(); s.playWhenReady = true
        }
        a.prepare(); a.playWhenReady = true

        s.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY) status.visibility = View.GONE
            }
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                status.visibility = View.VISIBLE
                status.text = "تعذّر تشغيل القناة — جرّب قناة أخرى"
            }
        })
    }

    private fun release() {
        pipeline?.close(); pipeline = null
        segmenter = null
        try { ahead?.release() } catch (_: Exception) { }
        try { screen?.release() } catch (_: Exception) { }
        ahead = null; screen = null
        tee?.close(); tee = null
    }

    /** Film time of a player: stream clock when known, otherwise its position. */
    private fun mediaNow(p: ExoPlayer): Long {
        val tl = p.currentTimeline
        if (!tl.isEmpty) {
            val w = Timeline.Window()
            tl.getWindow(p.currentMediaItemIndex, w)
            if (w.windowStartTimeMs != C.TIME_UNSET) return w.windowStartTimeMs + p.currentPosition
        }
        return p.currentPosition
    }

    /** ahead-player film time at a past device time (from the recorded samples) */
    private fun aheadMediaAt(t: Long): Long? {
        var best: Pair<Long, Long>? = null
        for (s in clock) { if (s.first <= t) best = s else break }
        val b = best ?: clock.firstOrNull() ?: return null
        return b.second + (t - b.first)
    }

    /** A translated sentence arrives: put it on the film timeline, or tune the delay if it is late. */
    private fun place(cue: Cue) {
        val m = aheadMediaAt(cue.spokenAt) ?: return
        val s = screen ?: return
        val now = mediaNow(s) - Prefs.syncAdjustMs(this)
        if (now > m + 250) {
            // arrived after its moment: the screen should wait a little longer next time (hidden tuning)
            delayMs = min(12000L, delayMs + 1500L)
            tee?.delayMs = delayMs
            if (now - m > cue.spokenDur + 800) return   // far too late for this one
        }
        val i = queue.indexOfFirst { it.first > m }
        if (i < 0) queue.add(m to cue) else queue.add(i, m to cue)
    }

    private val tick = object : Runnable {
        override fun run() {
            val a = ahead; val s = screen
            if (a != null) {
                val now = SystemClock.elapsedRealtime()
                clock.addLast(now to mediaNow(a))
                while (clock.size > 1200) clock.removeFirst()       // ~2 min at 100 ms
            }
            if (s != null && subsOn) {
                val m = mediaNow(s) - Prefs.syncAdjustMs(this@PlayerActivity)
                while (queue.isNotEmpty() && queue.first().first <= m) {
                    val (start, cue) = queue.removeAt(0)
                    val late = m - start
                    if (late > cue.spokenDur + 1200) continue
                    val next = queue.firstOrNull()?.first
                    var dur = max(cue.spokenDur + 450, 900L + cue.text.length * 38L) - late
                    if (next != null) dur = min(dur, max(next - m - 60, 700L))
                    showSub(cue.text, dur.coerceIn(700L, 7000L))
                }
            }
            ui.postDelayed(this, 40)
        }
    }

    // ------------------------------------------------------------------ subtitle drawing

    private val hideSub = Runnable { subView.visibility = View.INVISIBLE }

    private fun showSub(text: String, dur: Long) {
        SubStyle.apply(this, subView)
        subView.text = text
        subView.visibility = View.VISIBLE
        lastShownAt = SystemClock.elapsedRealtime()
        ui.removeCallbacks(hideSub); ui.postDelayed(hideSub, dur)
    }

    private val sink = object : SubSink {
        override fun show(text: String, durationMs: Long) { ui.post { showSub(text, durationMs) } }
        override fun flash(text: String, durationMs: Long) { ui.post { showBanner(text, durationMs) } }
        override fun hideSubtitle() { ui.post { subView.visibility = View.INVISIBLE } }
        override fun restyle() { ui.post { SubStyle.apply(this@PlayerActivity, subView) } }
    }

    private val hideBanner = Runnable { banner.visibility = View.INVISIBLE }
    private fun showBanner(text: String, dur: Long = 3000) {
        banner.text = text
        banner.visibility = View.VISIBLE
        ui.removeCallbacks(hideBanner); ui.postDelayed(hideBanner, dur)
    }

    /** Subtitle key while watching here: subtitles on / off (no capture permission involved). */
    fun toggleSubs() {
        ui.post {
            subsOn = !subsOn
            if (!subsOn) subView.visibility = View.INVISIBLE
            showBanner(if (subsOn) "✓ الترجمة مفعّلة" else "⏸ الترجمة متوقفة", 1800)
        }
    }

    private val digits = StringBuilder()
    private val jump = Runnable {
        val n = digits.toString().toIntOrNull(); digits.setLength(0)
        if (n != null && n in 1..channels.size) play(n - 1)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 && channels.isNotEmpty()) {
            digits.append(keyCode - KeyEvent.KEYCODE_0); showBanner("$digits")
            ui.removeCallbacks(jump); ui.postDelayed(jump, 1400); return true
        }
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_CHANNEL_UP -> { if (channels.isNotEmpty()) play(index + 1); return true }
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_CHANNEL_DOWN -> { if (channels.isNotEmpty()) play(index - 1); return true }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_INFO -> {
                if (channels.isNotEmpty()) showBanner("${index + 1}  ${channels[index].name}")
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }
}
