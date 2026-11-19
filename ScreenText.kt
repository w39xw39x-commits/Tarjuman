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
import kotlin.math.abs

/**
 * Reads text that the camera shows on purpose (a letter, a note, a phone message)
 * and hands it over for translation in the same subtitle spot.
 *
 * Looks at a small copy of the screen about once a second, entirely on the device.
 * Translates place / town / landmark captions and letters or messages shown in the scene.
 * NEVER the news ticker: wide bottom bands and text cut by the screen edge are skipped,
 * and a text must stay still in the same place for 2 looks (a ticker keeps moving).
 * Channel logo corners and clocks are skipped too.
 */
class ScreenTextWatcher(
    private val projection: MediaProjection?,
    private val widthPx: Int,
    private val heightPx: Int,
    private val dpi: Int,
    /** frames from our own player (no screen capture needed) */
    private val frames: (() -> Bitmap?)? = null,
    private val onText: (String) -> Unit,
) {
    private val thread = HandlerThread("tarjuman-screen").apply { start() }
    private val h = Handler(thread.looper)
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private var reader: ImageReader? = null
    private var display: VirtualDisplay? = null
    @Volatile private var stopped = false


    fun start() {
        // small capture keeps it light: ~640 px wide is plenty for letters filling the screen
        val w = 640
        val hgt = (640.0 * heightPx / widthPx).toInt().coerceAtLeast(240)
        if (frames != null || projection == null) { h.postDelayed(tick, 1500); return }
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
        val own = frames
        val bmp: Bitmap
        if (own != null) bmp = own() ?: return
        else { val img = reader?.acquireLatestImage() ?: return
        try {
            val plane = img.planes[0]
            val rowPx = plane.rowStride / plane.pixelStride
            val full = Bitmap.createBitmap(rowPx, img.height, Bitmap.Config.ARGB_8888)
            full.copyPixelsFromBuffer(plane.buffer)
            bmp = if (rowPx != img.width) Bitmap.createBitmap(full, 0, 0, img.width, img.height) else full
        } finally {
            img.close()
        } }
        val H = bmp.height.toFloat()
        val W = bmp.width.toFloat()
        val result = Tasks.await(recognizer.process(InputImage.fromBitmap(bmp, 0)), 3, TimeUnit.SECONDS)
        val now = SystemClock.elapsedRealtime()

        val seen = HashSet<String>()
        for (b in result.textBlocks) {
            val box = b.boundingBox ?: continue
            val top = box.top / H; val bottom = box.bottom / H
            val left = box.left / W; val right = box.right / W
            val width = right - left
            // channel logo / clock corners
            if (top < 0.15f && (left < 0.25f || right > 0.75f)) continue
            if (top < 0.06f) continue
            // NEWS TICKER: a wide band at the bottom, or text cut by the screen edge -> never translated
            if (bottom > 0.80f && (width > 0.50f || left < 0.02f || right > 0.98f)) continue
            if (bottom > 0.93f) continue
            val t = b.text.replace('\n', ' ').replace(Regex("\\s+"), " ").trim()
            if (t.count { it.isLetter() } < 4) continue                 // numbers, clocks, single letters
            val key = t.lowercase()
            seen.add(key)
            // must stay still in the same place for 2 looks (a ticker moves, a caption stays)
            val prev = steady[key]
            val cx = (left + right) / 2; val cy = (top + bottom) / 2
            if (prev != null && abs(prev.first - cx) < 0.02f && abs(prev.second - cy) < 0.02f) {
                val n = prev.third + 1
                steady[key] = Triple(cx, cy, n)
                if (n == 2) emitIfNew(t, now)
            } else {
                steady[key] = Triple(cx, cy, 1)
            }
        }
        steady.keys.retainAll(seen)
    }

    /** text -> (center x, center y, consecutive steady looks) */
    private val steady = HashMap<String, Triple<Float, Float, Int>>()
    private val done = HashMap<String, Long>()

    private fun emitIfNew(text: String, now: Long) {
        val norm = text.lowercase()
        done.entries.removeAll { now - it.value > 10 * 60000 }    // same caption again after 10 min is fine
        if (done.keys.any { similar(it, norm) }) return
        done[norm] = now
        onText(text.take(400))
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
