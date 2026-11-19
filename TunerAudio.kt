package com.tarjuman.tv

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.util.Log
import kotlin.math.max
import kotlin.math.sqrt

/** Receiver audio that bypasses the Android mixer (satellite tuner → HDMI). */
object TunerAudio {
    private const val TAG = "TJPROBE"
    private val WANTED = intArrayOf(
        AudioDeviceInfo.TYPE_TV_TUNER, 28 /* ECHO_REFERENCE */,
        AudioDeviceInfo.TYPE_REMOTE_SUBMIX, AudioDeviceInfo.TYPE_BUILTIN_MIC
    )
    private val SOURCES = intArrayOf(1 /*MIC*/, 0 /*DEFAULT*/, 9 /*UNPROCESSED*/, 1998 /*FM_TUNER*/, 1997 /*ECHO_REF*/)

    fun inputs(c: Context): List<AudioDeviceInfo> =
        c.getSystemService(AudioManager::class.java).getDevices(AudioManager.GET_DEVICES_INPUTS).toList()

    @SuppressLint("MissingPermission")
    fun open(source: Int, dev: AudioDeviceInfo?): AudioRecord? = try {
        val minBuf = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val r = AudioRecord(source, 16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, max(minBuf, 32000))
        if (r.state != AudioRecord.STATE_INITIALIZED) { r.release(); null }
        else { if (dev != null) r.preferredDevice = dev; r }
    } catch (e: Throwable) { Log.i(TAG, "open src=$source dev=${dev?.type}: ${e.javaClass.simpleName} ${e.message}"); null }

    fun rms(r: AudioRecord, ms: Int): Double {
        val buf = ShortArray(320); var sum = 0.0; var n = 0L
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) {
            val k = r.read(buf, 0, buf.size); if (k <= 0) break
            for (i in 0 until k) { val v = buf[i].toDouble(); sum += v * v }; n += k
        }
        return if (n == 0L) -1.0 else sqrt(sum / n)
    }

    /** Best working (source, device) for receiver sound, or null. Saved in prefs. */
    fun probe(c: Context): Pair<Int, Int>? {
        val devs = inputs(c)
        Log.i(TAG, "inputs: " + devs.joinToString { "${it.type}/${it.productName}/${it.address}" })
        var best: Triple<Int, Int, Double>? = null
        val cand = devs.filter { it.type in WANTED }.sortedBy { WANTED.indexOf(it.type) }
        for (d in cand + listOf<AudioDeviceInfo?>(null)) for (s in SOURCES) {
            val r = open(s, d) ?: continue
            val level = try { r.startRecording(); Thread.sleep(150); rms(r, 700) } catch (e: Throwable) { -2.0 }
            val routed = try { r.routedDevice?.type } catch (_: Throwable) { null }
            try { r.stop() } catch (_: Throwable) { }; r.release()
            Log.i(TAG, "src=$s dev=${d?.type} routed=$routed rms=${"%.1f".format(level)}")
            if (level > 30 && (best == null || level > best.third)) best = Triple(s, d?.type ?: -1, level)
        }
        Log.i(TAG, "best=$best")
        return best?.let { it.first to it.second }
    }
}

class ProbeReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        val app = c.applicationContext
        Thread { TunerAudio.probe(app) }.start()
    }
}
