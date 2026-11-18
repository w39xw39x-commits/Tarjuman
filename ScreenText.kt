package com.tarjuman.tv

import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.concurrent.TimeUnit

/**
 * Reads text that the camera shows on purpose (a letter, a note, a phone message)
 * and hands it over for translation in the same subtitle spot.
 *
 * Looks at a small copy of the screen about once a second, entirely on the device.
 * A text is translated only when:
 *  - it sits in the picture area (not the channel logo strip at the top,
 *    nor the ticker / burned-in subtitle band at the bottom),
 *  - it is a real sentence (enough letters and words, not a logo or a clock),
 *  - it stays still for 2 looks in a row (the camera is "reading" it),
 *  - it was not translated in the last minute.
 */
class ScreenTextWatcher(
    private val projection: MediaProjection,
    private val widthPx: Int,
    private val heightPx: Int,
    private val dpi: Int,
    private val onText: (String) -> Unit,
) {
    private val thread = HandlerThread("tarjuman-screen").apply { start() }
    private val h = Handler(thread.looper)
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private var reader: ImageReader? = null
    private var display: VirtualDisplay? = null
    @Volatile private var stopped = false

    private var lastSeen = ""
    private var stableCount = 0
    private val recent = ArrayDeque<Pair<Set<String>, Long>>()

    fun start() {
        // small capture keeps it light: ~640 px wide is plenty for letters filling the screen
        val w = 640
        val hgt = (640.0 * heightPx / widthPx).toInt().coerceAtLeast(240)
        val r = ImageReader.newInstance(w, hgt, PixelFormat.RGBA_8888, 2)
        reader = r
        display = try {
            projection.createVirtualDisplay(
                "tarjuman-text", w, hgt, dpi, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, r.surface, null, h
            )
        } catch (_: Exception) { null }
        h.postDelayed(tick, 1500)
    }

    private val tick = object : Runnable {
        override fun run() {
            if (stopped) return
            try { look() } catch (_: Exception) { }
            if (!stopped) h.postDelayed(this, 1000)
        }
    }

    private fun look() {
        val img = reader?.acquireLatestImage() ?: return
        val bmp: Bitmap
        try {
            val plane = img.planes[0]
            val rowPx = plane.rowStride / plane.pixelStride
            val full = Bitmap.createBitmap(rowPx, img.height, Bitmap.Config.ARGB_8888)
            full.copyPixelsFromBuffer(plane.buffer)
            bmp = if (rowPx != img.width) Bitmap.createBitmap(full, 0, 0, img.width, img.height) else full
        } finally {
            img.close()
        }
        val H = bmp.height.toFloat()
        val result = Tasks.await(recognizer.process(InputImage.fromBitmap(bmp, 0)), 3, TimeUnit.SECONDS)

        val parts = ArrayList<String>()
        for (b in result.textBlocks) {
            val box = b.boundingBox ?: continue
            val top = box.top / H
            val bottom = box.bottom / H
            if (top < 0.10f || bottom > 0.78f) continue           // logo strip / ticker & subtitle band
            val t = b.text.replace('\n', ' ').trim()
            if (t.count { it.isLetter() } < 6) continue            // logos, clocks, numbers
            parts.add(t)
        }
        val text = parts.joinToString("\n").trim()
        val words = text.split(Regex("\\s+")).filter { w -> w.count { it.isLetter() } >= 2 }
        if (words.size < 3 || text.count { it.isLetter() } < 14) { lastSeen = ""; stableCount = 0; return }

        val norm = words.joinToString(" ") { it.lowercase() }
        if (similar(norm, lastSeen)) stableCount++ else { stableCount = 1; lastSeen = norm }
        if (stableCount != 2) return                             // fire once, on the 2nd steady look

        val now = SystemClock.elapsedRealtime()
        val set = norm.split(' ').toSet()
        while (recent.isNotEmpty() && now - recent.first().second > 60000) recent.removeFirst()
        if (recent.any { jaccard(it.first, set) > 0.6 }) return
        recent.addLast(set to now)
        onText(text.take(600))
    }

    private fun similar(a: String, b: String): Boolean =
        b.isNotEmpty() && jaccard(a.split(' ').toSet(), b.split(' ').toSet()) > 0.7

    private fun jaccard(a: Set<String>, b: Set<String>): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        return a.intersect(b).size.toDouble() / a.union(b).size
    }

    fun stop() {
        stopped = true
        h.removeCallbacksAndMessages(null)
        try { display?.release() } catch (_: Exception) { }
        try { reader?.close() } catch (_: Exception) { }
        try { recognizer.close() } catch (_: Exception) { }
        thread.quitSafely()
    }
}
