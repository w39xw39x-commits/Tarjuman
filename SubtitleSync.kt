package com.tarjuman.tv

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlin.math.abs

/** One line of a subtitle file, in the film's own time (ms from the start of the film/episode). */
class FileCue(val startMs: Long, val endMs: Long, val text: String)

/**
 * Ready-made subtitles, perfectly in time (same idea as the receiver's built-in translator):
 *
 *  1. IDENTIFY: a line just heard is looked up ("search by quote") -> film / episode + the exact
 *     moment of that line in the film. Two lines that agree = locked.
 *  2. DOWNLOAD: the human Arabic subtitle file for that film / episode (or the original-language
 *     file, translated ahead of time a minute in advance - still zero delay).
 *  3. FOLLOW: every line is shown at film time + offset, so it appears WITH the speech, not after.
 *     The offset keeps being re-checked (ad breaks, channel change); +/- on the remote fine-tunes it.
 *
 * When nothing is found (live shows, news, documentaries without files) the live translation is used.
 */
class SubtitleSync(
    ctx: Context,
    private val overlay: SubSink?,
    private val ai: AiClient,
    /** called when a subtitle file takes over the screen (live lines must stop) */
    private val onTakeOver: () -> Unit,
) {
    companion object {
        @Volatile var current: SubtitleSync? = null
            private set
    }

    enum class State { SEARCHING, LOCKED, LOST }

    private val app = ctx.applicationContext
    private val key = BuildConfig.WYZIE_KEY
    private val sources = SubtitleSources(key, BuildConfig.SUBDL_KEY)
    private val io = Executors.newSingleThreadExecutor()
    private val translator = Executors.newSingleThreadExecutor()
    private val ui = Handler(Looper.getMainLooper())

    @Volatile var state = State.SEARCHING
        private set
    @Volatile var title: String? = null
        private set

    private var mediaId: String? = null          // imdb[:season:episode]
    @Volatile private var cues: List<FileCue> = emptyList()
    /** original-language lines of the same film, only to recognise "this is still the film" */
    @Volatile private var matchCues: List<FileCue> = emptyList()
    @Volatile private var liveMiss = 0
    @Volatile private var lostAt = 0L
    @Volatile private var needsTranslation = false
    private val translated = ConcurrentHashMap<Int, String>()
    @Volatile private var offsetMs = 0L          // device time = film time + offset
    @Volatile private var lastConfirmAt = 0L
    @Volatile private var misses = 0
    @Volatile private var lastQuoteAt = 0L
    @Volatile private var closed = false
    private var shownIdx = -1

    /** (mediaId, offset, when) of recent quote matches, to require agreement before locking. */
    private val candidates = ArrayList<Triple<String, Long, Long>>()
    /** heard (translated) lines while locked: used to fine-tune the file's timing. */
    private val deltas = ArrayList<Long>()

    val enabled: Boolean get() = key.isNotBlank() && Prefs.readySubs(app)
    val showing: Boolean get() = state == State.LOCKED && cues.isNotEmpty()

    /** An ad break (or pause) inside a recognised film: show nothing at all, wait for the film. */
    val inBreak: Boolean get() = state == State.LOST && cues.isNotEmpty() &&
        SystemClock.elapsedRealtime() - lostAt < 4 * 60000

    init { current = this }

    // ------------------------------------------------------------------ input from the live pipeline

    /**
     * @param heard  original-language sentences with the device time they started
     * @param heardTr the same sentences translated (for fine-tuning against an Arabic file)
     */
    fun observe(heard: List<Pair<String, Long>>, lang: String, heardTr: List<Pair<String, Long>>) {
        if (!enabled || closed) return
        val now = SystemClock.elapsedRealtime()
        if (showing) { fineTune(heardTr); checkExpected(heard, heardTr) }

        val best = heard
            .filter { it.first.split(' ').count { w -> w.length > 1 } >= 4 && it.first.length >= 16 }
            .maxByOrNull { it.first.length } ?: return
        val every = when (state) { State.LOCKED -> 12000L; State.LOST -> 4000L; else -> 5000L }
        if (now - lastQuoteAt < every) return
        lastQuoteAt = now
        io.execute { try { quote(best.first, best.second, lang) } catch (e: Exception) { Log.w("Tarjuman", "quote", e) } }
    }

    private fun quote(line: String, spokenAt: Long, lang: String) {
        val q = line.take(200)
        val l = if (lang.length == 2) lang else "all"
        val arr = getJsonArray("https://sub.wyzie.io/quote?q=${enc(q)}&language=$l&limit=3&key=${enc(key)}")
        val top = arr?.optJSONObject(0)
        val now = SystemClock.elapsedRealtime()
        if (top == null) { Log.i("TJSYNC", "quote miss: ${q.take(50)}"); onMiss(); return }

        val imdb = top.optString("imdbId")
        val season = top.optInt("season", 0)
        val episode = top.optInt("episode", 0)
        val id = if (season > 0 && episode > 0) "$imdb:$season:$episode" else imdb
        val off = spokenAt - top.optLong("timeMs")
        val name = top.optString("title") + (if (season > 0) " S${season}E$episode" else "")
        Log.i("TJSYNC", "quote hit $name id=$id off=$off state=$state")

        synchronized(this) {
            if (state == State.LOST && id == mediaId) {
                // film is back after the break: resume at once with the new position
                offsetMs = off; deltas.clear(); liveMiss = 0; misses = 0
                lastConfirmAt = now; state = State.LOCKED; lockedAt = now
                onTakeOver(); startTicker()
                return
            }
            if (state != State.SEARCHING && id == mediaId) {
                // same film: confirm or re-anchor (ad break / pause moves the offset)
                if (abs(off - offsetMs) < 2500) {
                    offsetMs = (offsetMs * 0.6 + off * 0.4).toLong()
                    confirm(now)
                } else {
                    addCandidate(id, off, now)
                    if (agreeing(id, off, now)) { offsetMs = off; deltas.clear(); confirm(now) } else onMiss()
                }
                return
            }
            addCandidate(id, off, now)
            if (!agreeing(id, off, now)) { if (state == State.LOCKED) onMiss(); return }
        }
        // a new film / episode agreed by two lines: load its subtitles
        load(id, imdb, season, episode, name, off, lang)
    }

    private fun confirm(now: Long) {
        lastConfirmAt = now; misses = 0
        if (state == State.LOST && cues.isNotEmpty()) { state = State.LOCKED; onTakeOver(); startTicker() }
    }

    private fun onMiss() {
        if (state != State.LOCKED) return
        misses++
        // two misses in a row (~25 s): probably ads or another programme -> stop showing file lines
        if (misses >= 2) goLost()
    }

    private fun goLost() {
        if (state != State.LOCKED) return
        state = State.LOST; lostAt = SystemClock.elapsedRealtime(); liveMiss = 0
        overlay?.hideSubtitle()
    }

    /**
     * Fast break detection (~5-8 s): each sentence just heard must match a subtitle line expected
     * around that moment. Two sentences in a row that do not = ads / pause -> stop showing.
     */
    private fun checkExpected(heard: List<Pair<String, Long>>, heardTr: List<Pair<String, Long>>) {
        val useOriginal = matchCues.isNotEmpty() || needsTranslation
        val list = if (matchCues.isNotEmpty()) matchCues else cues
        val src = if (useOriginal) heard else heardTr
        val need = if (useOriginal) 0.30 else 0.20
        var any = false; var hit = false
        for ((text, at) in src) {
            val w = Srt.words(text)
            if (w.size < 3) continue
            any = true
            val v = at - offsetMs
            var i = Srt.indexAt(list, v - 9000).coerceAtLeast(0)
            while (i < list.size && list[i].startMs <= v + 9000) {
                if (Srt.similarity(w, Srt.words(list[i].text)) >= need) { hit = true; break }
                i++
            }
            if (hit) break
        }
        if (!any) return
        if (hit) { liveMiss = 0; lastConfirmAt = SystemClock.elapsedRealtime(); misses = 0 }
        else if (++liveMiss >= (if (useOriginal) 2 else 3)) goLost()
    }

    private fun addCandidate(id: String, off: Long, now: Long) {
        candidates.add(Triple(id, off, now))
        candidates.removeAll { now - it.third > 180000 }
    }

    private fun agreeing(id: String, off: Long, now: Long): Boolean =
        candidates.count { it.first == id && abs(it.second - off) < 3000 && now - it.third < 180000 } >= 2

    // ------------------------------------------------------------------ download

    private fun load(id: String, imdb: String, season: Int, episode: Int, name: String, off: Long, srcLang: String) {
        val target = Prefs.target(app)
        // several sites, best first; each file decoded and checked to really be in that language
        val found = sources.find(imdb, season, episode, name, target, srcLang) ?: return
        val f = found.cues
        val translate = found.needsTranslation
        // original-language lines of the same film, only to recognise ad breaks quickly
        val match: List<FileCue> = if (!translate && srcLang.length == 2 && srcLang != target.code)
            sources.human(imdb, season, episode, srcLang)?.cues ?: emptyList()
        else emptyList()
        Log.i("TJSYNC", "subtitles from ${found.source}" +
            (if (translate) " (translated ahead)" else "") + (if (found.machine) " (machine-translated)" else ""))
        synchronized(this) {
            if (closed) return
            mediaId = id; title = name; offsetMs = off
            cues = f; matchCues = match; needsTranslation = translate; translated.clear(); deltas.clear(); liveMiss = 0
            state = State.LOCKED; lastConfirmAt = SystemClock.elapsedRealtime(); misses = 0
        }
        Log.i("TJSYNC", "LOCKED $name off=$off cues=${f.size}")
        onTakeOver()
        lockedAt = SystemClock.elapsedRealtime()
        overlay?.flash("✓ ترجمة جاهزة: $name" + (if (found.machine) " (آلية)" else ""), 3500)
        val pid = id; val pname = name
        Thread { Poster.show(overlay, pid, pname) }.start()
        startTicker()
    }

    // ------------------------------------------------------------------ fine tuning & display

    /** Matches what the viewer just heard (translated) to nearby file lines; nudges the offset. */
    private fun fineTune(heardTr: List<Pair<String, Long>>) {
        if (needsTranslation) return
        val list = cues
        for ((tr, at) in heardTr) {
            val v = at - offsetMs
            val words = Srt.words(tr)
            if (words.size < 3) continue
            var best = -1; var bestScore = 0.0
            for (i in Srt.indexAt(list, v - 20000).coerceAtLeast(0) until list.size) {
                val c = list[i]
                if (c.startMs > v + 20000) break
                val s = Srt.similarity(words, Srt.words(c.text))
                if (s > bestScore) { bestScore = s; best = i }
            }
            if (best >= 0 && bestScore >= 0.34) {
                synchronized(this) {
                    deltas.add(list[best].startMs - v)
                    if (deltas.size >= 3) {
                        val sorted = deltas.sorted(); val med = sorted[sorted.size / 2]
                        if (sorted.count { abs(it - med) < 900 } >= 3 && abs(med) > 250) offsetMs -= med
                        deltas.clear()
                        lastConfirmAt = SystemClock.elapsedRealtime(); misses = 0
                    }
                }
            }
        }
    }

    private val tick = object : Runnable {
        override fun run() {
            if (closed || state != State.LOCKED) { shownIdx = -1; return }
            val v = SystemClock.elapsedRealtime() - offsetMs - Prefs.syncAdjustMs(app)
            val list = cues
            val i = Srt.indexAt(list, v)
            if (i >= 0 && list[i].startMs <= v && v < list[i].endMs) {
                if (i != shownIdx) {
                    val text = if (needsTranslation) translated[i] else list[i].text
                    if (text != null) {
                        shownIdx = i
                        overlay?.show(text, (list[i].endMs - v + 120).coerceIn(500L, 8000L))
                    }
                }
            } else if (shownIdx >= 0 && i != shownIdx) {
                shownIdx = -1
            }
            if (needsTranslation) translateAhead(list, v)
            val now = SystemClock.elapsedRealtime()
            if (now - lastAlignAt > 8000 && now - lockedAt > 25000) {
                lastAlignAt = now
                io.execute { try { autoAlign() } catch (e: Exception) { Log.w("Tarjuman", "align", e) } }
            }
            ui.postDelayed(this, 80)
        }
    }

    @Volatile private var lockedAt = 0L
    @Volatile private var lastAlignAt = 0L

    /**
     * Precise sync on the device: slides the subtitle timeline -6..+6 s over the last ~90 s of
     * detected speech and keeps the shift where speech and subtitle lines overlap best.
     */
    private fun autoAlign() {
        if (state != State.LOCKED) return
        val (firstBin, act) = SpeechActivity.snapshot()
        val n = minOf(act.size, 900)
        if (n < 250) return
        val a = act.copyOfRange(act.size - n, act.size)
        val start = firstBin + (act.size - n)
        val mean = a.average().toFloat()
        if (mean < 0.08f || mean > 0.92f) return          // silence or wall of music: no information
        val list = cues
        val off = offsetMs
        val B = SpeechActivity.BIN
        val maxShift = 60                                  // x 100 ms = 6 s
        // subtitle occupancy for film times covering every tested shift
        val filmFrom = start * B - off - maxShift * B
        val len = n + 2 * maxShift
        val occ = FloatArray(len)
        var ci = Srt.indexAt(list, filmFrom).coerceAtLeast(0)
        for (k in 0 until len) {
            val ft = filmFrom + k * B
            while (ci < list.size - 1 && list[ci].endMs < ft) ci++
            val c = list.getOrNull(ci)
            occ[k] = if (c != null && c.startMs <= ft && ft < c.endMs) 1f else 0f
        }
        val om = occ.average().toFloat()
        fun score(shift: Int): Double {
            var sc = 0.0
            // device bin i  ->  film time (start+i)*B - (off + shift*B)  ->  occ index i + maxShift - shift
            for (i in 0 until n) sc += (a[i] - mean) * (occ[i + maxShift - shift] - om)
            return sc
        }
        var best = 0; var bestSc = score(0); val zero = bestSc
        for (sh in -maxShift..maxShift) { val v = score(sh); if (v > bestSc) { bestSc = v; best = sh } }
        if (best == 0 || bestSc <= 0) return
        if (bestSc < zero * 1.12 + 2.0) return              // not clearly better: keep
        val move = best * B
        synchronized(this) { offsetMs += if (kotlin.math.abs(move) > 1500) move / 2 else move }
    }

    private fun startTicker() { ui.removeCallbacks(tick); ui.post(tick) }

    @Volatile private var translating = false
    private var lastAheadAt = 0L

    /** Original-language file: translate the next ~90 s in advance, in batches (still zero delay). */
    private fun translateAhead(list: List<FileCue>, v: Long) {
        val now = SystemClock.elapsedRealtime()
        if (translating || now - lastAheadAt < 1500) return
        lastAheadAt = now
        val from = Srt.indexAt(list, v).coerceAtLeast(0)
        val todo = (from until list.size).filter { list[it].startMs < v + 90000 && !translated.containsKey(it) }.take(25)
        if (todo.isEmpty()) return
        translating = true
        translator.execute {
            try {
                val out = ai.translateLines(todo.map { list[it].text }, Prefs.target(app), Config.CHAT_MODEL)
                todo.forEachIndexed { k, idx -> out.getOrNull(k)?.takeIf { it.isNotBlank() }?.let { translated[idx] = it } }
            } catch (e: Exception) { Log.w("Tarjuman", "ahead", e) } finally { translating = false }
        }
    }

    fun close() {
        closed = true
        ui.removeCallbacksAndMessages(null)
        io.shutdownNow(); translator.shutdownNow()
        if (current === this) current = null
    }

    // ------------------------------------------------------------------ http helpers

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun getText(url: String): String? = try {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 8000; c.readTimeout = 15000
        c.setRequestProperty("Accept", "*/*")
        if (c.responseCode in 200..299) c.inputStream.use { String(it.readBytes(), Charsets.UTF_8) } else null
    } catch (_: Exception) { null }

    private fun getJsonArray(url: String): JSONArray? {
        val t = getText(url)?.trim() ?: return null
        return try {
            if (t.startsWith("[")) JSONArray(t)
            else JSONObject(t).let { it.optJSONArray("results") ?: it.optJSONArray("data") ?: JSONArray().put(it) }
        } catch (_: Exception) { null }
    }
}

/** Minimal SRT parser + text helpers. */
object Srt {
    private val time = Regex("""(\d+):(\d+):(\d+)[,.](\d+)\s*-->\s*(\d+):(\d+):(\d+)[,.](\d+)""")
    private val tags = Regex("""<[^>]+>|\{[^}]*\}""")

    fun parse(src: String): List<FileCue> {
        val out = ArrayList<FileCue>()
        val blocks = src.replace("﻿", "").replace("\r\n", "\n").replace('\r', '\n').split(Regex("\n\\s*\n"))
        for (b in blocks) {
            val lines = b.trim().split('\n')
            val ti = lines.indexOfFirst { time.containsMatchIn(it) }
            if (ti < 0) continue
            val m = time.find(lines[ti]) ?: continue
            val g = m.groupValues
            fun ms(h: String, mi: String, s: String, f: String) =
                h.toLong() * 3600000 + mi.toLong() * 60000 + s.toLong() * 1000 + f.padEnd(3, '0').take(3).toLong()
            val start = ms(g[1], g[2], g[3], g[4]); val end = ms(g[5], g[6], g[7], g[8])
            val text = lines.drop(ti + 1).joinToString("\n") { it.replace(tags, "").trim() }.trim()
            if (text.isNotEmpty() && end > start) out.add(FileCue(start, end, text))
        }
        return out.sortedBy { it.startMs }
    }

    /** Index of the last cue starting at or before t (or -1). */
    fun indexAt(list: List<FileCue>, t: Long): Int {
        var lo = 0; var hi = list.size - 1; var ans = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (list[mid].startMs <= t) { ans = mid; lo = mid + 1 } else hi = mid - 1
        }
        return ans
    }

    private val diacritics = Regex("[ً-ْـ]")
    fun words(s: String): Set<String> = s.lowercase()
        .replace(diacritics, "").replace('أ', 'ا').replace('إ', 'ا').replace('آ', 'ا').replace('ة', 'ه').replace('ى', 'ي')
        .split(Regex("[^\\p{L}\\p{N}]+")).filter { it.length > 1 }.toSet()

    fun similarity(a: Set<String>, b: Set<String>): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        return a.intersect(b).size.toDouble() / minOf(a.size, b.size)
    }
}
