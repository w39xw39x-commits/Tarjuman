package com.tarjuman.tv

import android.net.Uri
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.exoplayer.audio.TeeAudioProcessor
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.concurrent.thread

/**
 * ONE network connection, read by TWO players:
 *  - the "ahead" player reads the bytes as soon as they arrive (hidden, it only listens),
 *  - the "screen" player reads the same bytes only once they are [delayMs] old.
 * So the viewer sees and hears everything a few seconds later than the listener,
 * and every translated line is ready BEFORE its sentence is spoken on screen.
 */
class StreamTee(private val url: String) {
    private class Chunk(val data: ByteArray, val pos: Long, val time: Long) { val end get() = pos + data.size }

    private val chunks = ArrayDeque<Chunk>()
    private val lock = Any()
    @Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
    private fun lockWait(ms: Long) = (lock as java.lang.Object).wait(ms)
    @Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
    private fun lockNotify() = (lock as java.lang.Object).notifyAll()
    @Volatile var delayMs: Long = 5000
    @Volatile private var closed = false
    private var total = 0L

    fun start() {
        thread(name = "tarjuman-tee", isDaemon = true) {
            var backoff = 800L
            while (!closed) {
                try {
                    val c = URL(url).openConnection() as HttpURLConnection
                    c.connectTimeout = 10000; c.readTimeout = 20000
                    c.instanceFollowRedirects = true
                    c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) Tarjuman/1.0")
                    c.inputStream.use { inp ->
                        val buf = ByteArray(64 * 1024)
                        backoff = 800L
                        while (!closed) {
                            val n = inp.read(buf)
                            if (n < 0) break
                            val ch = Chunk(buf.copyOf(n), total, SystemClock.elapsedRealtime())
                            synchronized(lock) {
                                chunks.addLast(ch); total += n
                                val now = ch.time
                                while (chunks.size > 4 && now - chunks.first().time > delayMs + 40000) chunks.removeFirst()
                                lockNotify()
                            }
                        }
                    }
                } catch (_: Exception) { }
                if (closed) break
                try { Thread.sleep(backoff) } catch (_: InterruptedException) { break }
                backoff = (backoff * 2).coerceAtMost(8000)
            }
        }
    }

    /**
     * Blocking read from absolute byte [pos]; only bytes at least [minAgeMs] old are handed out.
     * Returns bytes read, -1 when closed, or -2 when [pos] fell out of the buffer.
     */
    fun read(pos: Long, out: ByteArray, off: Int, len: Int, minAgeMs: () -> Long): Int {
        synchronized(lock) {
            while (!closed) {
                val first = chunks.firstOrNull()
                if (first != null && pos < first.pos) return -2
                var ch: Chunk? = null
                for (c in chunks) { if (pos >= c.pos && pos < c.end) { ch = c; break } }
                val now = SystemClock.elapsedRealtime()
                if (ch != null) {
                    val wait = minAgeMs() - (now - ch.time)
                    if (wait <= 0) {
                        val o = (pos - ch.pos).toInt()
                        val n = minOf(len, ch.data.size - o)
                        System.arraycopy(ch.data, o, out, off, n)
                        return n
                    }
                    lockWait(wait.coerceIn(5L, 250L))
                } else lockWait(250)
            }
            return -1
        }
    }

    fun oldestPos(): Long = synchronized(lock) { chunks.firstOrNull()?.pos ?: 0L }

    fun close() { closed = true; synchronized(lock) { lockNotify(); chunks.clear() } }
}

@OptIn(UnstableApi::class)
class TeeDataSource(private val tee: StreamTee, private val minAgeMs: () -> Long) : BaseDataSource(true) {
    private var uri: Uri? = null
    private var pos = 0L
    private var opened = false

    override fun open(dataSpec: DataSpec): Long {
        uri = dataSpec.uri
        transferInitializing(dataSpec)
        pos = maxOf(dataSpec.position, tee.oldestPos())
        opened = true
        transferStarted(dataSpec)
        return C.LENGTH_UNSET.toLong()
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        while (true) {
            val n = tee.read(pos, buffer, offset, length, minAgeMs)
            when {
                n == -2 -> pos = tee.oldestPos()          // fell behind: jump to the oldest kept data
                n < 0 -> return C.RESULT_END_OF_INPUT
                else -> { pos += n; bytesTransferred(n); return n }
            }
        }
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        if (opened) { opened = false; transferEnded() }
        uri = null
    }

    class Factory(private val tee: StreamTee, private val minAgeMs: () -> Long) : DataSource.Factory {
        override fun createDataSource(): DataSource = TeeDataSource(tee, minAgeMs)
    }
}

/**
 * Receives the decoded sound of the hidden "ahead" player, turns it into 16 kHz mono 20 ms frames
 * and hands them to the speech segmenter. Runs on the player's thread, so it stays very light.
 */
@OptIn(UnstableApi::class)
class PcmTap(private val onFrame: (ShortArray, Int) -> Unit) : TeeAudioProcessor.AudioBufferSink {
    private var rate = 48000
    private var channels = 2
    private var encoding = C.ENCODING_PCM_16BIT
    private var phase = 0.0
    private val frame = ShortArray(320)
    private var n = 0

    override fun flush(sampleRateHz: Int, channelCount: Int, encoding: Int) {
        rate = sampleRateHz; channels = maxOf(1, channelCount); this.encoding = encoding
        phase = 0.0; n = 0
    }

    override fun handleBuffer(buffer: ByteBuffer) {
        if (encoding != C.ENCODING_PCM_16BIT || rate <= 0) return
        val b = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        val base = b.position()
        val frames = b.remaining() / (2 * channels)
        val step = rate / 16000.0
        val win = maxOf(1, step.toInt())
        while (phase < frames) {
            val start = phase.toInt()
            var sum = 0L; var cnt = 0
            var k = start
            while (k < start + win && k < frames) {      // small box filter = cheap anti-alias
                for (c in 0 until channels) sum += b.getShort(base + (k * channels + c) * 2)
                cnt += channels; k++
            }
            frame[n++] = (sum / maxOf(1, cnt)).toInt().toShort()
            if (n == frame.size) { onFrame(frame.copyOf(), frame.size); n = 0 }
            phase += step
        }
        phase -= frames
    }
}
