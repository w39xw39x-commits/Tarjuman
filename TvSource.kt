package com.tarjuman.tv

import android.app.Activity
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.tv.TvContract
import android.media.tv.TvInputInfo
import android.media.tv.TvInputManager
import android.media.tv.TvView
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT

/** A channel the receiver itself offers to apps (Android TV input framework). */
data class TvChannel(val id: Long, val number: String, val name: String, val inputId: String, val type: String) {
    val uri: Uri get() = ContentUris.withAppendedId(TvContract.Channels.CONTENT_URI, id)
    val isSatellite: Boolean get() = type == TvContract.Channels.TYPE_DVB_S || type == TvContract.Channels.TYPE_DVB_S2
}

/** One switchable source shown on the left screen (SAT key cycles them). */
data class Source(val key: String, val label: String, val channels: List<TvChannel>, val inputId: String?)

/**
 * Finds what the receiver shares with apps: its TV inputs (satellite tuner, IP channels...) and
 * their channel lists. Many receivers share them; some keep them private to their own player.
 */
object TvSource {
    const val PERM_EPG = "com.android.providers.tv.permission.READ_EPG_DATA"

    fun inputs(c: Context): List<TvInputInfo> = try {
        c.getSystemService(TvInputManager::class.java)?.tvInputList ?: emptyList()
    } catch (e: Exception) { emptyList() }

    fun canReadChannels(c: Context) = c.checkSelfPermission(PERM_EPG) == PackageManager.PERMISSION_GRANTED

    fun channels(c: Context): List<TvChannel> {
        if (!canReadChannels(c)) return emptyList()
        val out = ArrayList<TvChannel>()
        try {
            val cols = arrayOf(
                TvContract.Channels._ID, TvContract.Channels.COLUMN_DISPLAY_NUMBER,
                TvContract.Channels.COLUMN_DISPLAY_NAME, TvContract.Channels.COLUMN_INPUT_ID,
                TvContract.Channels.COLUMN_TYPE, TvContract.Channels.COLUMN_BROWSABLE,
            )
            c.contentResolver.query(TvContract.Channels.CONTENT_URI, cols, null, null, null)?.use { cur ->
                while (cur.moveToNext()) {
                    if (cur.getInt(5) == 0) continue // hidden by the user
                    out.add(TvChannel(cur.getLong(0), cur.getString(1) ?: "", cur.getString(2) ?: "قناة",
                        cur.getString(3) ?: "", cur.getString(4) ?: ""))
                }
            }
        } catch (e: Exception) { Log.w("Tarjuman", "channels", e) }
        return out.sortedWith(compareBy({ it.inputId }, { it.number.toIntOrNull() ?: Int.MAX_VALUE }, { it.number }))
    }

    /** Groups channels into Satellite / IP / other inputs, in that order. */
    fun sources(c: Context): List<Source> {
        val chans = channels(c)
        val inputs = inputs(c)
        val label = inputs.associate { it.id to (it.loadLabel(c)?.toString() ?: it.id) }
        val out = ArrayList<Source>()
        val sat = chans.filter { it.isSatellite }
        if (sat.isNotEmpty()) out.add(Source("sat", "القنوات الفضائية", sat, sat.first().inputId))
        chans.filter { !it.isSatellite }.groupBy { it.inputId }.forEach { (inputId, list) ->
            val name = label[inputId] ?: inputId
            val isIp = name.contains("ip", true) || name.contains("iptv", true) || list.first().type == TvContract.Channels.TYPE_OTHER
            out.add(Source(inputId, if (isIp) "IP Channels" else name, list, inputId))
        }
        // inputs without readable channels (e.g. HDMI / tuner that hides its list): still tunable
        if (out.isEmpty()) inputs.filter { it.type == TvInputInfo.TYPE_TUNER || it.type == TvInputInfo.TYPE_OTHER }
            .forEach { out.add(Source(it.id, label[it.id] ?: "الرسيفر", emptyList(), it.id)) }
        return out
    }

    /** Short diagnostic for the settings screen. */
    fun summary(c: Context): String {
        val ins = inputs(c)
        if (ins.isEmpty()) return "الرسيفر لا يتيح قنواته للتطبيقات"
        val n = channels(c).size
        return if (canReadChannels(c)) "${ins.size} مصدر · $n قناة" else "${ins.size} مصدر · بانتظار إذن القنوات"
    }

    fun tune(view: TvView, src: Source, ch: TvChannel?) {
        try {
            when {
                ch != null -> view.tune(ch.inputId, ch.uri)
                src.inputId != null -> view.tune(src.inputId, TvContract.buildChannelUriForPassthroughInput(src.inputId))
            }
        } catch (e: Exception) { Log.w("Tarjuman", "tune", e) }
    }
}

/** Full-screen view of a receiver channel inside Tarjuman (subtitles come from the overlay). */
class TvActivity : Activity() {
    companion object {
        @Volatile var lastSource: Source? = null
        @Volatile var lastIndex = 0
        fun open(c: Context, src: Source, index: Int) {
            lastSource = src; lastIndex = index
            c.startActivity(Intent(c, TvActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        }
    }

    private lateinit var tv: TvView
    private lateinit var banner: TextView
    private val ui = Handler(Looper.getMainLooper())
    private var src: Source? = null
    private var index = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        tv = TvView(this)
        root.addView(tv, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        val d = resources.displayMetrics.density
        banner = TextView(this).apply {
            setTextColor(Color.WHITE); setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f); typeface = Typeface.DEFAULT_BOLD
            setPadding((18 * d).toInt(), (10 * d).toInt(), (18 * d).toInt(), (10 * d).toInt())
            background = GradientDrawable().apply { cornerRadius = 14 * d; setColor(0x99000000.toInt()) }
            visibility = View.INVISIBLE
        }
        root.addView(banner, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.TOP or Gravity.START).apply {
            topMargin = (28 * d).toInt(); marginStart = (28 * d).toInt()
        })
        setContentView(root)
        src = lastSource; index = lastIndex
        show()
    }

    private fun show() {
        val s = src ?: return finish()
        val ch = s.channels.getOrNull(index)
        TvSource.tune(tv, s, ch)
        banner.text = if (ch != null) "${ch.number}  ${ch.name}" else s.label
        banner.visibility = View.VISIBLE
        ui.removeCallbacksAndMessages(null)
        ui.postDelayed({ banner.visibility = View.INVISIBLE }, 3000)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val s = src
        if (s != null && s.channels.isNotEmpty()) when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_CHANNEL_UP -> { index = (index + 1) % s.channels.size; show(); return true }
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_CHANNEL_DOWN -> { index = (index - 1 + s.channels.size) % s.channels.size; show(); return true }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onStop() { super.onStop(); try { tv.reset() } catch (_: Exception) { } }
}
