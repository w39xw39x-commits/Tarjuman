package com.tarjuman.tv

import android.annotation.SuppressLint
import android.content.AttributionSource
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ApplicationInfo
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.os.Build
import android.os.Looper
import java.io.BufferedOutputStream
import kotlin.math.sqrt

/**
 * Runs as the ADB shell user (like the receiver's own translator does):
 *   CLASSPATH=<apk> app_process / com.tarjuman.tv.ShellCapture probe
 *   CLASSPATH=<apk> app_process / com.tarjuman.tv.ShellCapture stream <source> <deviceType>
 * The shell user may record the satellite tuner and the full output (CAPTURE_AUDIO_OUTPUT,
 * CAPTURE_TUNER_AUDIO_INPUT), which normal apps cannot. "stream" writes 16 kHz mono PCM to stdout.
 */
object ShellCapture {
    private const val PKG = "com.android.shell"
    private val SOURCES = intArrayOf(1998 /*FM_TUNER*/, 1997 /*ECHO_REFERENCE*/, 8 /*REMOTE_SUBMIX*/, 0 /*DEFAULT*/, 1 /*MIC*/, 9 /*UNPROCESSED*/)

    class FakeContext : ContextWrapper(sysContext) {
        override fun getDeviceId(): Int = 0
        override fun getPackageName(): String = PKG
        override fun getOpPackageName(): String = PKG
        @SuppressLint("NewApi")
        override fun getAttributionSource(): AttributionSource =
            AttributionSource.Builder(2000 /* shell */).setPackageName(PKG).build()
        override fun getApplicationContext(): Context = this
        override fun getSystemService(name: String): Any? = sysContext?.getSystemService(name)
        companion object { var sysContext: Context? = null }
    }

    @SuppressLint("PrivateApi", "DiscouragedPrivateApi")
    private fun prepare() {
        try { Looper.prepareMainLooper() } catch (_: Throwable) { }
        try {
            val at = Class.forName("android.app.ActivityThread")
            val ctor = at.getDeclaredConstructor().apply { isAccessible = true }
            val thread = ctor.newInstance()
            at.getDeclaredField("sCurrentActivityThread").apply { isAccessible = true }.set(null, thread)
            val abd = Class.forName("android.app.ActivityThread\$AppBindData")
            val data = abd.getDeclaredConstructor().apply { isAccessible = true }.newInstance()
            val ai = ApplicationInfo().apply { packageName = PKG }
            abd.getDeclaredField("appInfo").apply { isAccessible = true }.set(data, ai)
            at.getDeclaredField("mBoundApplication").apply { isAccessible = true }.set(thread, data)
            FakeContext.sysContext = at.getMethod("getSystemContext").invoke(thread) as Context
        } catch (e: Throwable) { System.err.println("prepare: $e") }
    }

    @SuppressLint("MissingPermission", "NewApi")
    private fun open(source: Int, dev: AudioDeviceInfo?): AudioRecord? = try {
        val fmt = AudioFormat.Builder().setSampleRate(16000).setChannelMask(AudioFormat.CHANNEL_IN_MONO)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build()
        val b = AudioRecord.Builder().setAudioFormat(fmt).setBufferSizeInBytes(32000)
        if (Build.VERSION.SDK_INT >= 31) b.setContext(FakeContext())
        try { b.setAudioSource(source) } catch (_: Throwable) {
            // hidden sources: set through attributes
            val attrs = android.media.AudioAttributes.Builder()
            android.media.AudioAttributes.Builder::class.java.getMethod("setInternalCapturePreset", Int::class.java).invoke(attrs, source)
            AudioRecord.Builder::class.java.getMethod("setAudioAttributes", android.media.AudioAttributes::class.java).invoke(b, attrs.build())
        }
        val r = b.build()
        if (r.state != AudioRecord.STATE_INITIALIZED) { r.release(); null }
        else { if (dev != null) r.preferredDevice = dev; r }
    } catch (e: Throwable) { System.err.println("open src=$source dev=${dev?.type}: ${e.javaClass.simpleName} ${e.message}"); null }

    private fun rms(r: AudioRecord, ms: Int): Double {
        val buf = ShortArray(320); var sum = 0.0; var n = 0L
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) {
            val k = r.read(buf, 0, buf.size); if (k <= 0) break
            for (i in 0 until k) { val v = buf[i].toDouble(); sum += v * v }; n += k
        }
        return if (n == 0L) -1.0 else sqrt(sum / n)
    }

    private fun inputs(): List<AudioDeviceInfo> = try {
        (FakeContext.sysContext?.getSystemService(Context.AUDIO_SERVICE) as AudioManager)
            .getDevices(AudioManager.GET_DEVICES_INPUTS).toList()
    } catch (e: Throwable) { System.err.println("inputs: $e"); emptyList() }

    @JvmStatic fun main(args: Array<String>) {
        prepare()
        when (args.getOrNull(0)) {
            "stream" -> stream(args.getOrNull(1)?.toIntOrNull() ?: 1998, args.getOrNull(2)?.toIntOrNull() ?: -1)
            else -> probe()
        }
        System.exit(0)
    }

    private fun probe() {
        val devs = inputs()
        println("inputs: " + devs.joinToString { "${it.type}/${it.productName}/${it.address}" })
        val cand: List<AudioDeviceInfo?> = devs.filter { it.type == AudioDeviceInfo.TYPE_TV_TUNER || it.type == 28 || it.type == AudioDeviceInfo.TYPE_REMOTE_SUBMIX } + listOf(null)
        for (d in cand) for (s in SOURCES) {
            val r = open(s, d) ?: continue
            val level = try { r.startRecording(); Thread.sleep(150); rms(r, 800) } catch (e: Throwable) { -2.0 }
            val routed = try { r.routedDevice?.type } catch (_: Throwable) { null }
            try { r.stop() } catch (_: Throwable) { }; r.release()
            println("src=$s dev=${d?.type} routed=$routed rms=${"%.1f".format(level)}")
        }
    }

    private fun stream(source: Int, devType: Int) {
        val dev = inputs().firstOrNull { it.type == devType }
        val r = open(source, dev) ?: run { System.err.println("cannot open"); return }
        r.startRecording()
        val out = BufferedOutputStream(System.out, 8192)
        val buf = ByteArray(3200) // 100 ms
        while (true) {
            val k = r.read(buf, 0, buf.size)
            if (k <= 0) break
            out.write(buf, 0, k); out.flush()
        }
    }
}
