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
class Pipeline(
    ctx: Context,
    private val overlay: SubSink?,
    /**
     * Built-in player mode: finished lines are handed over with the time they were spoken and the
     * player shows them exactly when the (delayed) picture reaches that moment. Null = overlay mode.
     */
    private val cueSink: ((Cue) -> Unit)? = null,
    /** Built-in player mode can afford the most accurate model: the picture waits for the text. */
    private val modeOverride: Mode? = null,
) {
    private val app = ctx.applicationContext
    private val ai = AiClient(Prefs.apiKey(app))
    private val target: Lang get() = Prefs.target(app)
    private val source = Prefs.source(app)
    private val mode = modeOverride ?: Prefs.mode(app)
    private val pool = Executors.newFixedThreadPool(3)
    private val ui = Handler(Looper.getMainLooper())

    private val results = HashMap<Int, List<Cue>>()
    private val dispatched = HashMap<Int, Long>()
    private var next = 0
    private val history = ArrayDeque<String>()

    /** Smoothed processing delay (speech end -> text ready). */
    private var delayMs = 2500.0
    /** When the last scheduled cue starts, so cues never overlap or jump backwards. */
    private var lastCueAt = 0L

    @Volatile private var pausedUntil = 0L
    @Volatile private var lastErrorFlash = 0L
    @Volatile private var closed = false

    /** Ready-made subtitles in perfect sync, when the programme is recognised. */
    private val sync = SubtitleSync(app, overlay, ai) {
        // the subtitle file owns the screen now: drop every live line still waiting
        ui.removeCallbacksAndMessages(null)
        overlay?.hideSubtitle()
    }

    init {
        pool.execute { ai.warmUp() }
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

        val t = target
        val hint = source.takeIf { it.code != "auto" }?.english
        val ctxLines = synchronized(this) { history.toList().takeLast(3) }
        val stt = ai.listen(Wav.encode(s.pcm), mode.sttModel, t, hint, ctxLines)
        if (stt.segs.isEmpty()) return emptyList()

        // Already in the viewer's language (e.g. Arabic film + Arabic target): nothing to do.
        val spoken = stt.language.lowercase()
        if (spoken == t.code || spoken == t.english.lowercase()) return emptyList()

        synchronized(this) {
            stt.segs.forEach { history.addLast(it.translated) }
            while (history.size > 4) history.removeFirst()
        }

        if (cueSink != null) {
            // player mode: no guessing, the player knows exactly when each sentence will be heard
            val out = ArrayList<Cue>()
            for (seg in stt.segs) out.add(Cue(seg.translated, s.startMs + seg.startMs.coerceAtMost(s.durMs), max(600L, seg.endMs - seg.startMs)))
            mergeDialogue(out).forEach { cueSink.invoke(it) }
            return emptyList()
        }

        // feed the ready-subtitle matcher with what was just heard (device time of each sentence)
        sync.observe(
            stt.segs.map { it.text to (s.startMs + it.startMs) },
            stt.language,
            stt.segs.map { it.translated to (s.startMs + it.startMs) },
        )
        // a subtitle file is following the programme: it is already on time, live lines would only lag
        if (sync.showing || sync.inBreak) return emptyList()
        if (SystemClock.elapsedRealtime() - s.endMs > 10000) return emptyList()

        val cues = ArrayList<Cue>()
        for (seg in stt.segs) {
            val start = s.startMs + seg.startMs.coerceAtMost(s.durMs)
            val dur = max(600L, seg.endMs - seg.startMs)
            cues.add(Cue(seg.translated, start, dur))
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
            ui.postDelayed({ if (!closed && !sync.showing && !sync.inBreak) overlay?.show(c.text, dur) }, max(0L, at - now))
        }
    }

    /** Called by ScreenTextWatcher when a letter / message is shown on purpose. */
    fun onScreenText(text: String) {
        if (closed || SystemClock.elapsedRealtime() < pausedUntil) return
        pool.execute {
            try {
                val tr = ai.translateScreen(text, target, Config.CHAT_MODEL)
                if (tr.isBlank() || tr == "-" || closed) return@execute
                val shown = "«" + tr + "»"
                val dur = min(9000L, max(3500L, 1200L + shown.length * 55L))
                if (!sync.showing && !sync.inBreak) overlay?.show(shown, dur)
            } catch (e: Exception) { onError(e) }
        }
    }

    private fun onError(e: Exception) {
        Log.w("Tarjuman", "request failed", e)
        val now = SystemClock.elapsedRealtime()
        // busy / limit: slow down silently, never bother the viewer with it
        if (e is HttpError && (e.code == 429 || e.code >= 500)) {
            pausedUntil = now + e.retryAfterSec * 1000L
            return
        }
        val msg = when {
            e is HttpError && (e.code == 401 || e.code == 403) -> "تعذّر تشغيل الترجمة — حدّث التطبيق"
            e is IOException && e !is HttpError -> "تحقق من اتصال الإنترنت"
            else -> null
        } ?: return
        if (now - lastErrorFlash > 120000) {
            lastErrorFlash = now
            overlay?.flash(msg, 3000)
        }
    }

    fun close() {
        closed = true
        sync.close()
        ui.removeCallbacksAndMessages(null)
        pool.shutdownNow()
        overlay?.hideSubtitle()
    }
}
