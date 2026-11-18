package com.tarjuman.tv

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import java.io.IOException
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.min

/** One subtitle ready to show: its text and when it was spoken (device clock). */
class Cue(val text: String, val spokenAt: Long, val spokenDur: Long)

/**
 * Speech chunk -> Whisper (timed sentences) -> one batched translation -> timed cues.
 *
 * Sync rules:
 *  - every sentence keeps the rhythm it was spoken with (its own start time inside the chunk),
 *    shifted by one constant delay, so lines change exactly as often as the actors speak;
 *  - the delay is measured continuously and kept as small as the network allows;
 *  - chunks are processed in parallel but cues always appear in spoken order;
 *  - anything that would appear too late to match the picture is dropped.
 */
class Pipeline(ctx: Context, private val overlay: SubtitleOverlay?) {
    private val app = ctx.applicationContext
    private val groq = GroqClient(Prefs.apiKey(app))
    private val target: Lang get() = Prefs.target(app)
    private val source = Prefs.source(app)
    private val mode = Prefs.mode(app)
    private val pool = Executors.newFixedThreadPool(3)
    private val ui = Handler(Looper.getMainLooper())

    private val results = HashMap<Int, List<Cue>>()
    private val dispatched = HashMap<Int, Long>()
    private var next = 0
    private val history = ArrayDeque<String>()
    private var lastSource = ""

    /** Smoothed processing delay (speech end -> text ready). */
    private var delayMs = 2500.0
    /** When the last scheduled cue starts, so cues never overlap or jump backwards. */
    private var lastCueAt = 0L

    @Volatile private var pausedUntil = 0L
    @Volatile private var lastErrorFlash = 0L
    @Volatile private var closed = false

    init {
        pool.execute { groq.warmUp() }
    }

    fun submit(s: Segment) {
        if (closed) return
        synchronized(this) { dispatched[s.seq] = SystemClock.elapsedRealtime() }
        pool.execute {
            val cues = try {
                process(s)
            } catch (e: Exception) {
                onError(e); emptyList()
            }
            deliver(s, cues)
        }
    }

    private fun process(s: Segment): List<Cue> {
        val now = SystemClock.elapsedRealtime()
        if (closed || now < pausedUntil || now - s.endMs > 9000) return emptyList()

        val prompt = synchronized(this) { lastSource }
        val lang = source.code.takeIf { it != "auto" }
        val stt = groq.transcribe(Wav.encode(s.pcm), mode.sttModel, lang, prompt)
        if (stt.segs.isEmpty()) return emptyList()

        // Already in the viewer's language (e.g. Arabic film + Arabic target): nothing to do.
        val t = target
        val spoken = stt.language.lowercase()
        if (spoken == t.english.lowercase() || spoken == t.code) return emptyList()

        val ctxLines = synchronized(this) {
            val h = history.toList()
            lastSource = stt.text.takeLast(220)
            stt.segs.forEach { history.addLast(it.text) }
            while (history.size > 4) history.removeFirst()
            h.takeLast(3)
        }
        if (SystemClock.elapsedRealtime() - s.endMs > 10000) return emptyList()

        val tr = groq.translateLines(stt.segs.map { it.text }, ctxLines, t, Config.CHAT_MODEL)
        val cues = ArrayList<Cue>()
        stt.segs.forEachIndexed { i, seg ->
            val line = tr.getOrNull(i)?.takeIf { it.isNotBlank() && it != "-" } ?: return@forEachIndexed
            val start = s.startMs + seg.startMs
            val dur = max(600L, seg.endMs - seg.startMs)
            cues.add(Cue(line, start, dur))
        }
        return mergeDialogue(cues)
    }

    /** Two short dialogue lines spoken back-to-back are shown together, like cinema subtitles. */
    private fun mergeDialogue(cues: List<Cue>): List<Cue> {
        val out = ArrayList<Cue>()
        var i = 0
        while (i < cues.size) {
            val a = cues[i]
            val b = cues.getOrNull(i + 1)
            if (b != null && a.text.startsWith("-") && b.text.startsWith("-") &&
                a.text.length + b.text.length < 80 && a.spokenDur + b.spokenDur < 4500 &&
                b.spokenAt - (a.spokenAt + a.spokenDur) < 900
            ) {
                out.add(Cue(a.text + "\n" + b.text, a.spokenAt, (b.spokenAt + b.spokenDur) - a.spokenAt))
                i += 2
            } else {
                out.add(a); i++
            }
        }
        return out
    }

    private fun deliver(s: Segment, cues: List<Cue>) {
        val ready = ArrayList<Cue>()
        synchronized(this) {
            if (s.seq < next) return
            results[s.seq] = cues
            // learn the real delay from the first sentence's spoken time to "ready";
            // fast-rising (catch up at once), slow-falling (no jitter)
            if (cues.isNotEmpty()) {
                val measured = (SystemClock.elapsedRealtime() - cues.first().spokenAt).toDouble()
                delayMs = if (measured > delayMs) measured else delayMs * 0.85 + measured * 0.15
                delayMs = delayMs.coerceIn(600.0, 9000.0)
            }
            while (true) {
                val r = results.remove(next)
                if (r != null) {
                    dispatched.remove(next); ready.addAll(r); next++
                } else {
                    // A stuck request must not block the following lines.
                    val d = dispatched[next]
                    if (d != null && SystemClock.elapsedRealtime() - d > 9000 && results.isNotEmpty()) {
                        dispatched.remove(next); next++
                    } else break
                }
            }
        }
        if (ready.isEmpty() || closed) return
        schedule(ready)
    }

    /**
     * Places each cue at (spoken time + constant delay). Because the delay is the same for
     * every line, the subtitles follow the actors' rhythm exactly, just shifted slightly.
     */
    private fun schedule(cues: List<Cue>) {
        val now = SystemClock.elapsedRealtime()
        val shift = synchronized(this) { delayMs.toLong() }
        for ((idx, c) in cues.withIndex()) {
            var at = c.spokenAt + shift
            if (at < now) at = now                        // late: show immediately
            if (now - (c.spokenAt + c.spokenDur) > 11000) continue // far too late: skip
            synchronized(this) {
                if (at < lastCueAt + 350) at = lastCueAt + 350 // keep order, never flicker
                lastCueAt = at
            }
            val nextAt = cues.getOrNull(idx + 1)?.let { it.spokenAt + shift }
            val minRead = 900L + c.text.length * 38L       // time needed to read the line
            var dur = max(c.spokenDur + 450, minRead)
            if (nextAt != null) dur = min(dur, max(nextAt - at - 60, 700L))
            dur = min(dur, 7000L)
            ui.postDelayed({ if (!closed) overlay?.show(c.text, dur) }, max(0L, at - now))
        }
    }

    /** Called by ScreenTextWatcher when a letter / message is shown on purpose. */
    fun onScreenText(text: String) {
        if (closed || SystemClock.elapsedRealtime() < pausedUntil) return
        pool.execute {
            try {
                val tr = groq.translateScreen(text, target, Config.CHAT_MODEL)
                if (tr.isBlank() || tr == "-" || closed) return@execute
                val shown = "«" + tr + "»"
                val dur = min(9000L, max(3500L, 1200L + shown.length * 55L))
                overlay?.show(shown, dur)
            } catch (e: Exception) { onError(e) }
        }
    }

    private fun onError(e: Exception) {
        Log.w("Tarjuman", "request failed", e)
        val now = SystemClock.elapsedRealtime()
        val msg = when {
            e is HttpError && e.code == 429 -> {
                pausedUntil = now + e.retryAfterSec * 1000L
                "تم بلوغ حد الاستخدام المجاني مؤقتاً… سيستأنف تلقائياً"
            }
            e is HttpError && (e.code == 401 || e.code == 403) -> "مفتاح Groq غير صحيح — افتح الإعدادات"
            e is HttpError -> "خطأ من الخادم (${e.code})"
            e is IOException -> "تحقق من اتصال الإنترنت"
            else -> null
        } ?: return
        if (now - lastErrorFlash > 20000) {
            lastErrorFlash = now
            overlay?.flash(msg, 3500)
        }
    }

    fun close() {
        closed = true
        ui.removeCallbacksAndMessages(null)
        pool.shutdownNow()
        overlay?.hideSubtitle()
    }
}
