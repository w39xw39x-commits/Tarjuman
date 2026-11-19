package com.tarjuman.tv

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.media.tv.TvView
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView

/**
 * Home screen (2026 design): options on the right, the receiver's channels on the left.
 * SAT on the remote switches between satellite channels and IP channels.
 */
@OptIn(UnstableApi::class)
class MainActivity : Activity() {
    // palette
    private val bg = 0xFF080B14.toInt()
    private val card = 0x0DFFFFFF
    private val cardLine = 0x14FFFFFF
    private val gold = 0xFFF5C451.toInt()
    private val goldSoft = 0x29F5C451
    private val ink = 0xFFF3F5FB.toInt()
    private val muted = 0xFF8E97B5.toInt()
    private val green = 0xFF34D399.toInt()
    private val cyan = 0xFF6EE7FF.toInt()

    private val ui = Handler(Looper.getMainLooper())
    private fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)
    private fun dpi(v: Float) = dp(v).toInt()

    // right column
    private lateinit var statusPill: TextView
    private lateinit var masterSub: TextView
    private lateinit var masterKnob: View
    private lateinit var masterTrack: FrameLayout
    private val tileValues = HashMap<String, TextView>()
    private val tileViews = HashMap<String, View>()
    private lateinit var colorDots: LinearLayout
    private lateinit var footer: TextView
    private var adjusting: String? = null

    // left screen
    private lateinit var screen: FrameLayout
    private lateinit var segA: TextView
    private lateinit var segB: TextView
    private lateinit var infoBar: TextView
    private lateinit var strip: LinearLayout
    private lateinit var placeholder: LinearLayout
    private var tvView: TvView? = null
    private var iptv: ExoPlayer? = null
    private var playerView: PlayerView? = null

    private var sources: List<Source> = emptyList()
    private var srcIdx = 0
    private var chanIdx = 0
    private val iptvKey = "iptv"

    // more sheet
    private var sheet: FrameLayout? = null
    private var linkServer: LinkServer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.decorView.layoutDirection = View.LAYOUT_DIRECTION_RTL
        setContentView(build())
        val sp = getSharedPreferences("tarjuman", MODE_PRIVATE)
        if (sp.getBoolean("just_updated", false)) {
            sp.edit().putBoolean("just_updated", false).apply()
            Toast.makeText(this, "✓ تم التحديث إلى ${BuildConfig.VERSION_NAME}", Toast.LENGTH_LONG).show()
        }
        if (TvSource.inputs(this).isNotEmpty() && !TvSource.canReadChannels(this)) {
            try { requestPermissions(arrayOf(TvSource.PERM_EPG), 21) } catch (_: Exception) { }
        }
        Updater.checkAndPrompt(this)
        tileViews["master"]?.requestFocus()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        loadSources()
    }

    override fun onStart() { super.onStart(); loadSources(); ui.post(refresher) }
    override fun onStop() {
        super.onStop()
        ui.removeCallbacksAndMessages(null)
        stopPreview()
        RemoteKeyService.learning = false
    }
    override fun onDestroy() { linkServer?.stop(); super.onDestroy() }

    private val refresher = object : Runnable { override fun run() { refresh(); ui.postDelayed(this, 1000) } }

    // ======================================================================= layout

    private fun build(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(bg)
            setPadding(dpi(24f), dpi(22f), dpi(24f), dpi(22f))
        }
        // RTL: first child = right side
        root.addView(buildOptions(), LinearLayout.LayoutParams(dpi(380f), MATCH_PARENT).apply { marginEnd = dpi(22f) })
        root.addView(buildScreen(), LinearLayout.LayoutParams(0, MATCH_PARENT, 1f))
        val frame = FrameLayout(this)
        frame.addView(root, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        return frame
    }

    private fun txt(s: String, size: Float, color: Int, weight: Int = 0) = TextView(this).apply {
        text = s; setTextColor(color); setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
        typeface = when (weight) { 2 -> Typeface.create("sans-serif", Typeface.BOLD); 1 -> Typeface.create("sans-serif-medium", Typeface.NORMAL); else -> Typeface.create("sans-serif", Typeface.NORMAL) }
        includeFontPadding = false
    }

    private fun round(fill: Int, radius: Float, stroke: Int? = null, strokeW: Float = 1f) = GradientDrawable().apply {
        cornerRadius = dp(radius); setColor(fill); if (stroke != null) setStroke(dpi(strokeW), stroke)
    }

    private fun focusBg(radius: Float, base: Int = card, line: Int = cardLine) = StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_focused), round(goldSoft, radius, gold, 2f))
        addState(intArrayOf(), round(base, radius, line, 1f))
    }

    private fun focusable(v: View, scale: Float = 1.04f) {
        v.isFocusable = true; v.isClickable = true
        v.setOnFocusChangeListener { view, has ->
            view.animate().scaleX(if (has) scale else 1f).scaleY(if (has) scale else 1f).setDuration(140).start()
            if (!has && adjusting != null) { adjusting = null; refresh() }
        }
    }

    private fun icon(res: Int, color: Int, size: Float = 22f) = ImageView(this).apply {
        setImageResource(res); imageTintList = ColorStateList.valueOf(color)
        layoutParams = LinearLayout.LayoutParams(dpi(size), dpi(size))
    }

    private fun buildOptions(): View {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        // header
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(ImageView(this).apply { setImageResource(R.drawable.ic_fg); scaleX = 1.35f; scaleY = 1.35f }, LinearLayout.LayoutParams(dpi(46f), dpi(46f)))
        val titles = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dpi(10f), 0, dpi(10f), 0) }
        titles.addView(txt("ترجمان", 24f, ink, 2))
        titles.addView(txt("ترجمة فورية متزامنة", 13f, muted).apply { setPadding(0, dpi(4f), 0, 0) })
        head.addView(titles, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        statusPill = txt("", 13f, green, 1).apply { setPadding(dpi(12f), dpi(7f), dpi(12f), dpi(7f)) }
        head.addView(statusPill)
        col.addView(head)

        // master switch
        val master = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dpi(18f), dpi(16f), dpi(18f), dpi(16f))
            background = StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_focused), round(0x33F5C451, 24f, gold, 2f))
                addState(intArrayOf(), round(0x14F5C451, 24f, 0x59F5C451, 1f))
            }
            focusable(this, 1.03f)
            setOnClickListener { Toggle.toggle(this@MainActivity); ui.postDelayed({ refresh() }, 600) }
        }
        val iconBox = FrameLayout(this).apply { background = round(0x2EF5C451, 14f) }
        iconBox.addView(icon(R.drawable.ic_translate, gold, 24f).apply {
            layoutParams = FrameLayout.LayoutParams(dpi(24f), dpi(24f), Gravity.CENTER)
        })
        master.addView(iconBox, LinearLayout.LayoutParams(dpi(44f), dpi(44f)))
        val mt = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dpi(14f), 0, dpi(14f), 0) }
        mt.addView(txt("الترجمة", 18f, ink, 2))
        masterSub = txt("", 13f, 0xFFC9CFE3.toInt()).apply { setPadding(0, dpi(5f), 0, 0) }
        mt.addView(masterSub)
        master.addView(mt, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        masterTrack = FrameLayout(this)
        masterKnob = View(this).apply { background = round(0xFF10131F.toInt(), 12f) }
        masterTrack.addView(masterKnob, FrameLayout.LayoutParams(dpi(24f), dpi(24f), Gravity.CENTER_VERTICAL or Gravity.START).apply { marginStart = dpi(3f); marginEnd = dpi(3f) })
        master.addView(masterTrack, LinearLayout.LayoutParams(dpi(54f), dpi(30f)))
        tileViews["master"] = master
        col.addView(master, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dpi(16f) })

        // tiles grid (2 columns)
        val grid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val defs = listOf(
            Triple("lang", R.drawable.ic_globe, "لغة الترجمة"),
            Triple("sync", R.drawable.ic_sync, "التزامن"),
            Triple("font", R.drawable.ic_text, "الخط والحجم"),
            Triple("color", R.drawable.ic_palette, "لون الخط"),
            Triple("scene", R.drawable.ic_screen, "عناوين المشهد"),
            Triple("more", R.drawable.ic_more, "المزيد"),
        )
        for (r in 0 until 3) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            for (c in 0 until 2) {
                val (key, res, label) = defs[r * 2 + c]
                row.addView(tile(key, res, label), LinearLayout.LayoutParams(0, dpi(104f), 1f).apply {
                    if (c == 0) marginEnd = dpi(12f)
                })
            }
            grid.addView(row, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dpi(12f) })
        }
        col.addView(grid)

        footer = txt("", 12f, 0xFF6B7393.toInt()).apply { setPadding(dpi(6f), 0, dpi(6f), 0); setLineSpacing(0f, 1.3f) }
        col.addView(View(this), LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        col.addView(footer)
        return col
    }

    private fun tile(key: String, res: Int, label: String): View {
        val t = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpi(16f), dpi(14f), dpi(16f), dpi(12f))
            background = focusBg(22f)
            focusable(this)
            setOnClickListener { onTile(key) }
        }
        t.addView(icon(res, 0xFF9AA3C7.toInt()))
        t.addView(txt(label, 13f, muted).apply { setPadding(0, dpi(10f), 0, dpi(6f)) })
        if (key == "color") {
            colorDots = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            t.addView(colorDots)
        } else {
            val v = txt("", 16f, ink, 1)
            tileValues[key] = v
            t.addView(v)
        }
        tileViews[key] = t
        return t
    }

    private fun buildScreen(): View {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        screen = FrameLayout(this).apply {
            background = round(0xFF111522.toInt(), 28f)
            clipToOutline = true
        }
        // placeholder (no shared channels)
        placeholder = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            setPadding(dpi(40f), dpi(20f), dpi(40f), dpi(20f))
        }
        placeholder.addView(icon(R.drawable.ic_live, gold, 44f))
        placeholder.addView(txt("شاشة القنوات", 22f, ink, 2).apply { setPadding(0, dpi(14f), 0, dpi(8f)); gravity = Gravity.CENTER })
        placeholder.addView(txt("", 15f, 0xFFB9C0D9.toInt()).apply { tag = "ph"; gravity = Gravity.CENTER; setLineSpacing(0f, 1.35f) })
        screen.addView(placeholder, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))

        // segmented source chip + SAT hint
        val seg = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; setPadding(dpi(4f), dpi(4f), dpi(4f), dpi(4f))
            background = round(0x8C080B14.toInt(), 999f, 0x1FFFFFFF, 1f)
        }
        segA = txt("", 14f, 0xFF10131F.toInt(), 2).apply { setPadding(dpi(16f), dpi(7f), dpi(16f), dpi(7f)); background = round(gold, 999f) }
        segB = txt("", 14f, 0xFFE6E9F5.toInt(), 1).apply { setPadding(dpi(16f), dpi(7f), dpi(16f), dpi(7f)) }
        seg.addView(segA); seg.addView(segB)
        screen.addView(seg, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.TOP or Gravity.START).apply { topMargin = dpi(16f); marginStart = dpi(16f) })
        val sat = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        sat.addView(txt("SAT", 12f, ink, 2).apply { setPadding(dpi(9f), dpi(5f), dpi(9f), dpi(5f)); background = round(0x8C080B14.toInt(), 8f, 0x2EFFFFFF, 1f) })
        sat.addView(txt("للتبديل", 13f, 0xCCFFFFFF.toInt()).apply { setPadding(dpi(8f), 0, 0, 0) })
        screen.addView(sat, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.TOP or Gravity.END).apply { topMargin = dpi(22f); marginEnd = dpi(18f) })

        infoBar = txt("", 16f, ink, 1).apply {
            setPadding(dpi(20f), dpi(14f), dpi(20f), dpi(14f))
            background = GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, intArrayOf(0xD9080B14.toInt(), 0x00080B14))
        }
        screen.addView(infoBar, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.BOTTOM))
        col.addView(screen, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))

        strip = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dpi(4f), dpi(8f), dpi(4f), dpi(8f)) }
        val hs = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; clipToPadding = false; addView(strip) }
        col.addView(hs, LinearLayout.LayoutParams(MATCH_PARENT, dpi(116f)).apply { topMargin = dpi(10f) })
        return col
    }

    // ======================================================================= sources & preview

    override fun onResume() {
        super.onResume()
        if (sources.none { it.key == iptvKey }) { Playlist.reset(); if (Playlist.channels(this).isNotEmpty()) loadSources() }
    }

    private fun loadSources() {
        val list = ArrayList(TvSource.sources(this).filter { it.channels.isNotEmpty() })
        if (Playlist.channels(this).isNotEmpty()) list.add(Source(iptvKey, "IP Channels", emptyList(), null))
        sources = list
        srcIdx = srcIdx.coerceIn(0, maxOf(0, sources.size - 1))
        showSource()
    }

    private fun showSource() {
        stopPreview()
        strip.removeAllViews()
        val src = sources.getOrNull(srcIdx)
        val ph = placeholder.findViewWithTag<TextView>("ph")
        if (src == null) {
            placeholder.visibility = View.VISIBLE
            segA.text = "الرسيفر"; segB.text = ""
            infoBar.text = ""
            ph.text = buildString {
                append(TvSource.summary(this@MainActivity)).append("\n\n")
                append("الترجمة تعمل فوق أي قناة:\nشاهد من مشغّل الرسيفر واضغط زر الترجمة في الريموت.")
            }
            return
        }
        segA.text = src.label
        segB.text = sources.getOrNull((srcIdx + 1) % sources.size)?.takeIf { sources.size > 1 }?.label ?: ""
        segB.visibility = if (segB.text.isNullOrEmpty()) View.GONE else View.VISIBLE

        if (src.key == iptvKey) {
            val chans = Playlist.channels(this)
            chanIdx = chanIdx.coerceIn(0, maxOf(0, chans.size - 1))
            // only a window of tiles around the current channel: thousands of views would be slow
            val from = maxOf(0, chanIdx - 20); val to = minOf(chans.size, from + 60)
            for (i in from until to) strip.addView(chanTile("${i + 1}", chans[i].name, i) { previewIptv(i) })
            previewIptv(chanIdx)
            val at = chanIdx - from
            strip.post { strip.getChildAt(at)?.let { c -> (strip.parent as? android.widget.HorizontalScrollView)?.scrollTo(maxOf(0, c.left - dpi(40f)), 0) } }
        } else {
            src.channels.forEachIndexed { i, ch -> strip.addView(chanTile(ch.number, ch.name, i) { previewTv(src, i) }) }
            chanIdx = chanIdx.coerceIn(0, maxOf(0, src.channels.size - 1))
            previewTv(src, chanIdx)
        }
    }

    private fun chanTile(num: String, name: String, i: Int, onFocus: () -> Unit): View {
        val t = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dpi(16f), 0, dpi(16f), 0)
            background = focusBg(20f)
            focusable(this, 1.06f)
            setOnFocusChangeListener { v, has ->
                v.animate().scaleX(if (has) 1.06f else 1f).scaleY(if (has) 1.06f else 1f).setDuration(140).start()
                if (has) { ui.removeCallbacks(zap); pendingZap = { chanIdx = i; onFocus() }; ui.postDelayed(zap, 450) }
            }
            setOnClickListener { chanIdx = i; openFull() }
        }
        t.addView(txt(num, 12f, gold, 1))
        t.addView(txt(name, 15f, ink, 1).apply { setPadding(0, dpi(6f), 0, 0); maxLines = 1; maxWidth = dpi(150f) })
        t.layoutParams = LinearLayout.LayoutParams(dpi(150f), MATCH_PARENT).apply { marginEnd = dpi(12f) }
        return t
    }

    private var pendingZap: (() -> Unit)? = null
    private val zap = Runnable { pendingZap?.invoke() }

    private fun previewTv(src: Source, i: Int) {
        placeholder.visibility = View.GONE
        if (iptv != null) stopPreview()
        val tv = tvView ?: TvView(this).also {
            tvView = it
            screen.addView(it, 0, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            it.setCallback(object : TvView.TvInputCallback() {
                override fun onVideoUnavailable(inputId: String?, reason: Int) {
                    infoBar.text = "${infoBar.text.toString().substringBefore("  ·")}  · لا تتوفر صورة"
                }
            })
        }
        val ch = src.channels.getOrNull(i)
        TvSource.tune(tv, src, ch)
        infoBar.text = if (ch != null) "●  ${ch.number}  ·  ${ch.name}" else "●  ${src.label}"
    }

    private fun previewIptv(i: Int) {
        val chans = Playlist.channels(this)
        val ch = chans.getOrNull(i) ?: return
        placeholder.visibility = View.GONE
        tvView?.let { try { it.reset() } catch (_: Exception) { }; screen.removeView(it) }
        tvView = null
        val pv = playerView ?: PlayerView(this).also {
            it.useController = false; it.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
            playerView = it; screen.addView(it, 0, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        }
        val p = iptv ?: ExoPlayer.Builder(this).build().also { iptv = it; pv.player = it }
        p.setMediaItem(MediaItem.fromUri(ch.url)); p.prepare(); p.playWhenReady = true
        infoBar.text = "●  ${i + 1}  ·  ${ch.name}"
    }

    private fun stopPreview() {
        ui.removeCallbacks(zap)
        tvView?.let { try { it.reset() } catch (_: Exception) { }; screen.removeView(it) }
        tvView = null
        iptv?.release(); iptv = null
        playerView?.let { screen.removeView(it) }; playerView = null
    }

    private fun openFull() {
        val src = sources.getOrNull(srcIdx) ?: return
        // the IPTV account allows ONE connection: free it before the full player connects
        stopPreview()
        val idx = chanIdx
        ui.postDelayed({ if (src.key == iptvKey) PlayerActivity.open(this, idx) else TvActivity.open(this, src, idx) }, 350)
    }

    private fun nextSource() {
        if (sources.size < 2) {
            // the receiver's IP channel list needs all-files access once
            if (android.os.Build.VERSION.SDK_INT >= 30 && !android.os.Environment.isExternalStorageManager() && Playlist.channels(this).isEmpty()) {
                try { startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName"))); return } catch (_: Exception) { }
            }
            Toast.makeText(this, "لا يوجد مصدر آخر يتيحه الرسيفر", Toast.LENGTH_SHORT).show(); return
        }
        srcIdx = (srcIdx + 1) % sources.size; chanIdx = 0
        showSource()
    }

    // ======================================================================= options

    private fun onTile(key: String) {
        when (key) {
            "lang" -> Prefs.setTargetIdx(this, (Prefs.targetIdx(this) + 1) % Config.TARGETS.size)
            "sync", "font" -> { adjusting = if (adjusting == key) null else key }
            "color" -> { Prefs.setColorIdx(this, (Prefs.colorIdx(this) + 1) % Config.COLORS.size); OverlayHost.get(this)?.restyle() }
            "scene" -> Prefs.setScreenText(this, !Prefs.screenText(this))
            "more" -> openSheet()
        }
        refresh()
    }

    /** ◀▶ / ▲▼ while a tile is in adjust mode (sync, font). */
    private fun adjust(keyCode: Int): Boolean {
        val k = adjusting ?: return false
        val plus = keyCode == KeyEvent.KEYCODE_DPAD_LEFT   // RTL: left = more
        val minus = keyCode == KeyEvent.KEYCODE_DPAD_RIGHT
        when (k) {
            "sync" -> {
                if (plus || minus) Prefs.setSyncAdjustMs(this, Prefs.syncAdjustMs(this) + if (plus) 250 else -250) else return false
            }
            "font" -> when (keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT ->
                    Prefs.setFontIdx(this, (Prefs.fontIdx(this) + if (plus) 1 else -1).coerceIn(0, Config.FONT_SIZES.size - 1))
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN ->
                    Prefs.setFaceIdx(this, (Prefs.faceIdx(this) + 1) % Config.FACES.size)
                else -> return false
            }
        }
        OverlayHost.get(this)?.let { it.restyle(); it.show("مثال: هكذا تظهر الترجمة", 2500) }
        refresh(); return true
    }

    private fun fmtAdj(ms: Long) = (if (ms > 0) "+" else if (ms < 0) "−" else "±") +
        String.format(java.util.Locale.US, "%.2f", kotlin.math.abs(ms) / 1000.0) + " ث"

    private fun refresh() {
        val on = CaptureService.running
        statusPill.text = if (on) "●  تعمل" else "○  متوقفة"
        statusPill.setTextColor(if (on) green else muted)
        statusPill.background = round(if (on) 0x1F34D399 else 0x14FFFFFF, 999f)
        masterSub.text = "إلى ${Prefs.target(this).label} · ${if (Prefs.source(this).code == "auto") "من أي لغة" else Prefs.source(this).label}"
        masterTrack.background = round(if (on) gold else 0x33FFFFFF, 15f)
        (masterKnob.layoutParams as FrameLayout.LayoutParams).gravity = Gravity.CENTER_VERTICAL or (if (on) Gravity.END else Gravity.START)
        masterKnob.background = round(if (on) 0xFF10131F.toInt() else 0xFFE6E9F5.toInt(), 12f)
        masterKnob.requestLayout()

        tileValues["lang"]?.text = Prefs.target(this).label
        tileValues["sync"]?.text = if (adjusting == "sync") "◀  ${fmtAdj(Prefs.syncAdjustMs(this))}  ▶" else "تلقائي  ${fmtAdj(Prefs.syncAdjustMs(this))}"
        tileValues["font"]?.text = if (adjusting == "font") "◀ ${Prefs.fontSp(this)} ▶ · ${Config.FACES[Prefs.faceIdx(this)].label}" else "${Config.FACES[Prefs.faceIdx(this)].label} · ${Prefs.fontSp(this)}"
        tileValues["scene"]?.text = if (Prefs.screenText(this)) "تعمل" else "متوقفة"
        tileValues["more"]?.text = "الريموت والمصادر"
        tileValues["sync"]?.setTextColor(if (adjusting == "sync") cyan else ink)
        tileValues["font"]?.setTextColor(if (adjusting == "font") cyan else ink)

        colorDots.removeAllViews()
        Config.COLORS.forEachIndexed { i, (_, c) ->
            val sel = i == Prefs.colorIdx(this)
            colorDots.addView(View(this).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL; setColor(c)
                    if (sel) setStroke(dpi(2.5f), gold)
                }
            }, LinearLayout.LayoutParams(dpi(if (sel) 22f else 18f), dpi(if (sel) 22f else 18f)).apply { marginEnd = dpi(7f) })
        }
        footer.text = "الإصدار ${BuildConfig.VERSION_NAME}   ·   زر الترجمة: ${Prefs.keyName(Prefs.toggleKey(this))}\n" +
            "ضغطة مطوّلة على زر الترجمة = اللوحة السريعة   ·   SAT = تبديل المصدر"
        sheetRefresh?.invoke()
    }

    // ======================================================================= "more" sheet

    private var sheetRefresh: (() -> Unit)? = null

    private fun openSheet() {
        if (sheet != null) return
        val root = window.decorView.findViewById<ViewGroup>(android.R.id.content).getChildAt(0) as FrameLayout
        val dim = FrameLayout(this).apply { setBackgroundColor(0xB3060912.toInt()); isClickable = true }
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpi(22f), dpi(20f), dpi(22f), dpi(20f))
            background = round(0xF2121726.toInt(), 28f, 0x1FFFFFFF, 1f)
        }
        panel.addView(txt("المزيد", 20f, ink, 2).apply { setPadding(dpi(6f), 0, dpi(6f), dpi(12f)) })
        val rows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val values = HashMap<String, TextView>()
        fun row(id: String, label: String, iconRes: Int, onClick: () -> Unit) {
            val r = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                setPadding(dpi(16f), dpi(13f), dpi(16f), dpi(13f))
                background = focusBg(18f)
                focusable(this, 1.02f)
                setOnClickListener { onClick(); refresh() }
            }
            r.addView(icon(iconRes, 0xFF9AA3C7.toInt(), 20f))
            r.addView(txt(label, 16f, ink).apply { setPadding(dpi(14f), 0, dpi(14f), 0) }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            val v = txt("", 15f, gold, 1); values[id] = v; r.addView(v)
            rows.addView(r, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dpi(8f) })
        }
        row("source", "لغة الفيلم", R.drawable.ic_globe) { Prefs.setSourceIdx(this, (Prefs.sourceIdx(this) + 1) % Config.SOURCES.size) }
        row("mode", "الدقة والسرعة", R.drawable.ic_sync) { Prefs.setModeIdx(this, (Prefs.modeIdx(this) + 1) % Config.MODES.size) }
        row("ready", "ترجمة جاهزة للأفلام", R.drawable.ic_translate) { Prefs.setReadySubs(this, !Prefs.readySubs(this)) }
        row("a11y", "خدمة زر الريموت", R.drawable.ic_remote) { openA11y() }
        row("key", "زر الترجمة في الريموت", R.drawable.ic_remote) { learnKey() }
        row("kact", "عند الضغط على الزر", R.drawable.ic_remote) { Prefs.setKeyAction(this, (Prefs.keyAction(this) + 1) % Config.KEY_ACTIONS.size) }
        row("iptv", "قائمة IPTV من الجوال", R.drawable.ic_list) { startLink() }
        row("overlay", "العرض فوق التطبيقات", R.drawable.ic_screen) {
            try { startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))) } catch (_: Exception) { }
        }
        row("version", "البحث عن تحديث", R.drawable.ic_sync) {
            Updater.checkAndPrompt(this) { Toast.makeText(this, "لديك أحدث إصدار ✓", Toast.LENGTH_SHORT).show() }
        }
        val scroll = ScrollView(this).apply { addView(rows) }
        panel.addView(scroll, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        dim.addView(panel, FrameLayout.LayoutParams(dpi(460f), MATCH_PARENT, Gravity.END).apply {
            setMargins(dpi(24f), dpi(22f), dpi(24f), dpi(22f))
        })
        root.addView(dim, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        sheet = dim
        sheetRefresh = {
            values["source"]?.text = Prefs.source(this).label
            values["mode"]?.text = Prefs.mode(this).label
            values["ready"]?.text = if (Prefs.readySubs(this)) "تعمل" else "متوقفة"
            values["a11y"]?.text = if (RemoteKeyService.isEnabled(this)) "مفعّلة ✓" else "فعّلها"
            values["key"]?.text = if (RemoteKeyService.learning) "اضغط الزر…" else Prefs.keyName(Prefs.toggleKey(this))
            values["kact"]?.text = Config.KEY_ACTIONS[Prefs.keyAction(this)]
            values["iptv"]?.text = Playlist.channels(this).size.let { if (it > 0) "$it قناة" else "إضافة" }
            values["overlay"]?.text = if (Settings.canDrawOverlays(this)) "مسموح ✓" else "اختياري"
            values["version"]?.text = BuildConfig.VERSION_NAME
        }
        refresh()
        rows.getChildAt(0)?.requestFocus()
    }

    private fun closeSheet(): Boolean {
        val s = sheet ?: return false
        (s.parent as? ViewGroup)?.removeView(s)
        sheet = null; sheetRefresh = null
        tileViews["more"]?.requestFocus()
        return true
    }

    private fun startLink() {
        val srv = linkServer ?: LinkServer(this) { n -> ui.post { Toast.makeText(this, "✓ أُضيفت $n قناة", Toast.LENGTH_LONG).show(); loadSources() } }.also { linkServer = it }
        srv.start()
        val addr = srv.address()
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("أضف قائمة IPTV من جوالك")
            .setMessage(if (addr != null) "افتح هذا الرابط في متصفح الجوال (نفس شبكة الواي فاي):\n\n$addr\n\nثم الصق رابط القائمة أو اختر الملف." else "تعذّر معرفة عنوان الرسيفر على الشبكة")
            .setPositiveButton("تم", null)
            .show()
    }

    private fun openA11y() {
        try {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            Toast.makeText(this, "اختر «ترجمان» ثم فعّله", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(this, "افتح الإعدادات ← إمكانية الوصول ← ترجمان", Toast.LENGTH_LONG).show()
        }
    }

    private fun learnKey() {
        RemoteKeyService.learning = true
        Toast.makeText(this, "اضغط الآن الزر الذي تريده في الريموت", Toast.LENGTH_LONG).show()
    }

    // ======================================================================= keys

    private val digits = StringBuilder()
    private val jump = Runnable {
        val n = digits.toString().toIntOrNull(); digits.setLength(0)
        if (n != null && n in 1..Playlist.channels(this).size) { chanIdx = n - 1; showSource() }
    }

    private val satKeys = setOf(
        KeyEvent.KEYCODE_TV_SATELLITE, KeyEvent.KEYCODE_TV_SATELLITE_BS, KeyEvent.KEYCODE_TV_SATELLITE_CS,
        KeyEvent.KEYCODE_TV_SATELLITE_SERVICE, KeyEvent.KEYCODE_TV_INPUT, KeyEvent.KEYCODE_TV_ANTENNA_CABLE,
    )

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val k = event.keyCode
        if (RemoteKeyService.learning) {
            if (event.action == KeyEvent.ACTION_UP) {
                val reserved = setOf(KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_DPAD_UP,
                    KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
                    KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_HOME, KeyEvent.KEYCODE_POWER,
                    KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN)
                if (k in reserved) {
                    if (k == KeyEvent.KEYCODE_BACK) RemoteKeyService.learning = false
                    else Toast.makeText(this, "هذا الزر للتنقل، اختر زراً آخر", Toast.LENGTH_SHORT).show()
                } else {
                    Prefs.setToggleKey(this, k); RemoteKeyService.learning = false
                    Toast.makeText(this, "تم تعيين الزر: ${Prefs.keyName(k)}", Toast.LENGTH_SHORT).show()
                }
                refresh()
            }
            return true
        }
        if (event.action == KeyEvent.ACTION_DOWN) {
            if (k in satKeys) { nextSource(); return true }
            if ((k == KeyEvent.KEYCODE_CHANNEL_UP || k == KeyEvent.KEYCODE_CHANNEL_DOWN) && sources.getOrNull(srcIdx)?.key == iptvKey) {
                val n = Playlist.channels(this).size
                if (n > 0) { chanIdx = (chanIdx + (if (k == KeyEvent.KEYCODE_CHANNEL_UP) 1 else -1) + n) % n; showSource() }
                return true
            }
            if (k in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 && sources.getOrNull(srcIdx)?.key == iptvKey) {
                digits.append(k - KeyEvent.KEYCODE_0)
                Toast.makeText(this, "القناة $digits", Toast.LENGTH_SHORT).show()
                ui.removeCallbacks(jump)
                if (digits.length >= Playlist.channels(this).size.toString().length) jump.run() else ui.postDelayed(jump, 650)
                return true
            }
            if (adjusting != null) {
                if (k == KeyEvent.KEYCODE_BACK || k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER) {
                    adjusting = null; refresh(); return true
                }
                if (adjust(k)) return true
            }
            if (k == KeyEvent.KEYCODE_BACK && closeSheet()) return true
        } else if (event.action == KeyEvent.ACTION_UP && (k in satKeys || (adjusting != null && k in setOf(KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT)))) {
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    @Suppress("unused")
    private fun editKeyDialog() {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            setText(Prefs.apiKey(this@MainActivity)); setTextColor(Color.WHITE)
            textDirection = View.TEXT_DIRECTION_LTR
        }
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("مفتاح الترجمة").setView(input)
            .setPositiveButton("حفظ") { _, _ -> Prefs.setApiKey(this, input.text.toString()) }
            .setNegativeButton("إلغاء", null).show()
    }
}
