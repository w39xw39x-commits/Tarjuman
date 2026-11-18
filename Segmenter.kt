package com.tarjuman.tv

import android.os.SystemClock
import kotlin.math.max
import kotlin.math.sqrt

class Segment(val seq: Int, val pcm: ShortArray, val startMs: Long, val endMs: Long) {
    val durMs: Long get() = endMs - startMs
}

/**
 * Splits the continuous audio stream into speech-sized chunks.
 * - Adaptive noise floor so steady background music does not trigger constant cuts.
 * - Cuts at natural pauses; when a sentence runs too long it cuts at the quietest
 *   point of the last second, so words are never chopped in half.
 * Works on 20 ms frames of 16 kHz mono PCM (320 samples).
 */
class Segmenter(private val mode: Mode, private val emit: (Segment) -> Unit) {
    private val frameMs = 20
    private val frames = ArrayList<ShortArray>()
    private val rmsList = ArrayList<Double>()
    private val speechList = ArrayList<Boolean>()
    private val pre = ArrayDeque<Triple<ShortArray, Double, Boolean>>()

    private var active = false
    private var speechRun = 0
    private var silenceRun = 0
    private var noise = 150.0
    private var hpIn = 0.0
    private var hpOut = 0.0
    private var segStart = 0L
    private var seq = 0

    @Volatile var lastRms = 0.0
        private set

    fun push(buf: ShortArray, n: Int) {
        val frame = buf.copyOf(n)
        var sum = 0.0
        for (s in frame) { // simple high-pass (~100 Hz) so bass/drums count less
            val x = s.toDouble()
            val y = 0.96 * (hpOut + x - hpIn)
            hpIn = x; hpOut = y
            sum += y * y
        }
        val rms = sqrt(sum / max(1, n))
        lastRms = rms
        val speech = rms > max(noise * 2.4, 110.0)
        noise = if (speech) noise * 0.9995 + rms * 0.0005 else noise * 0.97 + rms * 0.03
        noise = noise.coerceIn(25.0, 2500.0)
        val now = SystemClock.elapsedRealtime()

        if (!active) {
            pre.addLast(Triple(frame, rms, speech))
            if (pre.size > 12) pre.removeFirst()
            speechRun = if (speech) speechRun + 1 else 0
            if (speechRun >= 3) {
                active = true
                segStart = now - pre.size * frameMs
                for ((f, r, sp) in pre) add(f, r, sp)
                pre.clear()
                silenceRun = 0
            }
            return
        }

        add(frame, rms, speech)
        silenceRun = if (speech) 0 else silenceRun + 1
        val dur = frames.size * frameMs
        val silentMs = silenceRun * frameMs

        when {
            silentMs >= mode.silenceMs && dur >= mode.minMs -> cut(frames.size)
            silentMs >= 700 -> cut(frames.size) // short line followed by a pause
            dur >= mode.maxMs -> cut(quietestCutPoint())
        }
    }

    private fun add(f: ShortArray, r: Double, sp: Boolean) {
        frames.add(f); rmsList.add(r); speechList.add(sp)
    }

    private fun quietestCutPoint(): Int {
        val from = max(1, frames.size - 1000 / frameMs)
        var best = frames.size
        var bestR = Double.MAX_VALUE
        for (i in from until frames.size) {
            if (rmsList[i] < bestR) { bestR = rmsList[i]; best = i + 1 }
        }
        return best
    }

    private fun cut(count: Int) {
        var trailing = 0
        while (trailing < count && !speechList[count - 1 - trailing]) trailing++
        val end = count - max(0, trailing - 10) // keep 200 ms of tail
        val speechFrames = (0 until end).count { speechList[it] }

        if (speechFrames >= 15) { // at least ~300 ms of real sound
            val total = (0 until end).sumOf { frames[it].size }
            val pcm = ShortArray(total)
            var p = 0
            for (i in 0 until end) {
                val f = frames[i]
                System.arraycopy(f, 0, pcm, p, f.size); p += f.size
            }
            emit(Segment(seq++, pcm, segStart, segStart + end * frameMs))
        }

        // carry over what came after the cut point
        repeat(count) { frames.removeAt(0); rmsList.removeAt(0); speechList.removeAt(0) }
        segStart += count * frameMs
        silenceRun = 0
        if (frames.isEmpty() || speechList.none { it }) {
            frames.clear(); rmsList.clear(); speechList.clear()
            active = false
            speechRun = 0
        }
    }
}
