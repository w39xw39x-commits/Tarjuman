package com.tarjuman.tv

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
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
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/** Settings screen, built for the TV remote (D-pad). Every row is one press. */
class MainActivity : Activity() {
    private val gold = 0xFFFFC94A.toInt()
    private val card = 0xFF19213F.toInt()
    private val cardFocus = 0xFF26306A.toInt()
    private val dim = 0xFFA9B1D6.toInt()

    private lateinit var statusPill: TextView
    private lateinit var mainBtn: TextView
    private val rows = HashMap<String, TextView>()
    private val ui = Handler(Looper.getMainLooper())
    private val refresher = object : Runnable {
        override fun run() { refresh(); ui.postDelayed(this, 1000) }
    }

    private fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.decorView.layoutDirection = View.LAYOUT_DIRECTION_RTL
        setContentView(build())
        mainBtn.requestFocus()
        val sp = getSharedPreferences("tarjuman", MODE_PRIVATE)
        if (sp.getBoolean("just_updated", false)) {
            sp.edit().putBoolean("just_updated", false).apply()
            Toast.makeText(this, "✓ تم التحديث إلى ${BuildConfig.VERSION_NAME}", Toast.LENGTH_LONG).show()
        }
        Updater.checkAndPrompt(this)
    }

    override fun onResume() { super.onResume(); ui.post(refresher) }
    override fun onPause() { super.onPause(); ui.removeCallbacks(refresher); RemoteKeyService.learning = false }

    // ---------- UI ----------

    private fun build(): View {
        val m = resources.displayMetrics
        val landscape = m.widthPixels > m.heightPixels
        val side = if (landscape) (m.widthPixels * 0.2).toInt() else dp(18f)

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(side, dp(28f), side, dp(28f))
        }

        // Header
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val logo = ImageView(this).apply { setImageResource(R.drawable.ic_fg); scaleX = 1.3f; scaleY = 1.3f }
        header.addView(logo, LinearLayout.LayoutParams(dp(72f), dp(72f)))
        val titles = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16f), 0, dp(16f), 0)
            addView(text("ترجمان", 30f, Color.WHITE, true))
            addView(text("ترجمة فورية لأي قناة أو فيلم أو IPTV", 15f, dim, false))
        }
        header.addView(titles, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        statusPill = text("", 14f, Color.WHITE, true).apply {
            setPadding(dp(14f), dp(6f), dp(14f), dp(6f))
        }
        header.addView(statusPill)
        col.addView(header)

        // Big on/off button
        mainBtn = text("", 22f, Color.WHITE, true).apply {
            gravity = Gravity.CENTER
            setPadding(dp(20f), dp(18f), dp(20f), dp(18f))
            focusable(this, big = true)
            setOnClickListener { Toggle.toggle(this@MainActivity); ui.postDelayed({ refresh() }, 600) }
        }
        col.addView(mainBtn, lp(top = 24f))

        col.addView(section("الترجمة"))
        row(col, "target", "ترجمة إلى") { cycle(Prefs.targetIdx(this), Config.TARGETS.size) { Prefs.setTargetIdx(this, it) } }
        row(col, "source", "لغة الفيلم") { cycle(Prefs.sourceIdx(this), Config.SOURCES.size) { Prefs.setSourceIdx(this, it) } }
        row(col, "mode", "الدقة والسرعة") { cycle(Prefs.modeIdx(this), Config.MODES.size) { Prefs.setModeIdx(this, it) } }
        row(col, "font", "حجم الخط") { cycle(Prefs.fontIdx(this), Config.FONT_SIZES.size) { Prefs.setFontIdx(this, it) } }
        row(col, "face", "نوع الخط") { cycle(Prefs.faceIdx(this), Config.FACES.size) { Prefs.setFaceIdx(this, it) } }
        row(col, "color", "لون الخط") { cycle(Prefs.colorIdx(this), Config.COLORS.size) { Prefs.setColorIdx(this, it) } }
        row(col, "stext", "ترجمة الرسائل والأوراق في المشهد") { Prefs.setScreenText(this, !Prefs.screenText(this)) }
        row(col, "test", "معاينة شكل الترجمة") { preview() }

        col.addView(section("الريموت"))
        row(col, "a11y", "خدمة زر الريموت") { openA11y() }
        row(col, "key", "زر الترجمة في الريموت") { learnKey() }
        row(col, "kact", "عند الضغط على الزر") { cycle(Prefs.keyAction(this), Config.KEY_ACTIONS.size) { Prefs.setKeyAction(this, it) } }

        col.addView(section("الاتصال"))
        row(col, "apikey", "مفتاح الترجمة") { editKey() }
        row(col, "overlay", "العرض فوق التطبيقات (بديل)") { openOverlay() }
        row(col, "version", "الإصدار") {
            Toast.makeText(this, "جارٍ البحث عن تحديث…", Toast.LENGTH_SHORT).show()
            Updater.checkAndPrompt(this) { Toast.makeText(this, "لديك أحدث إصدار ✓", Toast.LENGTH_SHORT).show() }
        }

        col.addView(text(
            "• ضغطة على زر الريموت: حسب اختيارك (تشغيل/إيقاف أو اللوحة السريعة).\n" +
                "• ضغطة مطوّلة (ثانية ونصف): تنفّذ الخيار الآخر.\n" +
                "• اللوحة السريعة: ▲▼ للاختيار، ◀▶ للتغيير، رجوع أو زر الترجمة للإخفاء.\n" +
                "• أول مرة سيطلب أندرويد إذن التقاط الصوت — اختر «ابدأ الآن».",
            14f, dim, false
        ).apply { setLineSpacing(0f, 1.3f) }, lp(top = 22f))

        return ScrollView(this).apply {
            isFillViewport = true
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(0xFF0B1022.toInt(), 0xFF121A3A.toInt(), 0xFF0A2A33.toInt())
            )
            addView(col)
        }
    }

    private fun text(s: String, size: Float, color: Int, bold: Boolean) = TextView(this).apply {
        text = s
        setTextColor(color)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
        if (bold) typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
    }

    private fun section(title: String) = text(title, 14f, gold, true).apply {
        setPadding(dp(6f), dp(22f), dp(6f), dp(8f))
    }

    private fun lp(top: Float = 0f, bottom: Float = 0f) =
        LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(top); bottomMargin = dp(bottom) }

    private fun bg(fill: Int, stroke: Int?, radius: Float) = GradientDrawable().apply {
        cornerRadius = dp(radius).toFloat()
        setColor(fill)
        if (stroke != null) setStroke(dp(3f), stroke)
    }

    private fun focusable(v: View, big: Boolean = false) {
        v.isFocusable = true
        v.isClickable = true
        if (!big) {
            v.background = StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_focused), bg(cardFocus, gold, 16f))
                addState(intArrayOf(android.R.attr.state_pressed), bg(cardFocus, gold, 16f))
                addState(intArrayOf(), bg(card, null, 16f))
            }
        }
        v.setOnFocusChangeListener { view, has ->
            view.animate().scaleX(if (has) 1.03f else 1f).scaleY(if (has) 1.03f else 1f).setDuration(120).start()
        }
    }

    private fun row(parent: LinearLayout, id: String, label: String, onClick: () -> Unit) {
        val r = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20f), dp(15f), dp(20f), dp(15f))
            focusable(this)
            setOnClickListener { onClick(); refresh() }
        }
        r.addView(text(label, 18f, Color.WHITE, false), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        val value = text("", 17f, gold, true)
        r.addView(value)
        rows[id] = value
        parent.addView(r, lp(top = 8f))
    }

    private fun cycle(cur: Int, size: Int, set: (Int) -> Unit) = set((cur + 1) % size)

    private fun refresh() {
        val on = CaptureService.running
        statusPill.text = if (on) "● تعمل" else "○ متوقفة"
        statusPill.background = bg(if (on) 0xFF117A4A.toInt() else 0xFF3A3F5C.toInt(), null, 30f)
        mainBtn.text = if (on) "⏸  إيقاف الترجمة" else "▶  تفعيل الترجمة"
        mainBtn.background = StateListDrawable().apply {
            val base = if (on) 0xFF8A2836.toInt() else 0xFF1E8C6E.toInt()
            addState(intArrayOf(android.R.attr.state_focused), bg(base, gold, 20f))
            addState(intArrayOf(), bg(base, null, 20f))
        }

        rows["target"]?.text = Prefs.target(this).label
        rows["source"]?.text = Prefs.source(this).label
        rows["mode"]?.text = Prefs.mode(this).label
        rows["font"]?.text = "${Prefs.fontSp(this)}"
        rows["face"]?.text = Config.FACES[Prefs.faceIdx(this)].label
        rows["color"]?.text = Config.COLORS[Prefs.colorIdx(this)].first
        rows["kact"]?.text = Config.KEY_ACTIONS[Prefs.keyAction(this)]
        rows["stext"]?.text = if (Prefs.screenText(this)) "تعمل ✓" else "متوقفة"
        rows["test"]?.text = "عرض"
        rows["a11y"]?.text = if (RemoteKeyService.isEnabled(this)) "مفعّلة ✓" else "غير مفعّلة — اضغط"
        rows["key"]?.text = if (RemoteKeyService.learning) "اضغط الزر الآن…" else Prefs.keyName(Prefs.toggleKey(this))
        rows["apikey"]?.text = Prefs.apiKey(this).let { if (it.isBlank()) "غير مُدخل — اضغط" else "••••" + it.takeLast(4) }
        rows["version"]?.text = BuildConfig.VERSION_NAME
        rows["overlay"]?.text = if (Settings.canDrawOverlays(this)) "مسموح ✓" else "اختياري"
    }

    // ---------- actions ----------

    private fun preview() {
        val o = OverlayHost.get(this)
        if (o == null) {
            Toast.makeText(this, "فعّل «خدمة زر الريموت» أولاً لتظهر الترجمة فوق القنوات", Toast.LENGTH_LONG).show()
            return
        }
        o.show("هذا مثال على شكل الترجمة\nيظهر متزامناً مع كلام الممثل", 4000)
    }

    private fun openA11y() {
        try {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            Toast.makeText(this, "اختر «ترجمان» ثم فعّله", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(this, "افتح الإعدادات ← إمكانية الوصول ← ترجمان", Toast.LENGTH_LONG).show()
        }
    }

    private fun openOverlay() {
        try {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        } catch (e: Exception) {
            Toast.makeText(this, "غير متاح على هذا الجهاز", Toast.LENGTH_SHORT).show()
        }
    }

    private fun learnKey() {
        RemoteKeyService.learning = true
        Toast.makeText(this, "اضغط الآن الزر الذي تريده في الريموت (مثل الأحمر أو F1)", Toast.LENGTH_LONG).show()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (RemoteKeyService.learning && event.action == KeyEvent.ACTION_UP) {
            val k = event.keyCode
            val reserved = setOf(
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_DPAD_UP,
                KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
                KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_HOME, KeyEvent.KEYCODE_POWER,
                KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN,
            )
            if (k in reserved) {
                Toast.makeText(this, "هذا الزر محجوز للتنقل، اختر زراً آخر", Toast.LENGTH_SHORT).show()
                if (k == KeyEvent.KEYCODE_BACK) { RemoteKeyService.learning = false; refresh() }
                return true
            }
            Prefs.setToggleKey(this, k)
            RemoteKeyService.learning = false
            Toast.makeText(this, "تم تعيين الزر: ${Prefs.keyName(k)}", Toast.LENGTH_SHORT).show()
            refresh()
            return true
        }
        if (RemoteKeyService.learning && event.action == KeyEvent.ACTION_DOWN &&
            event.keyCode != KeyEvent.KEYCODE_DPAD_CENTER && event.keyCode != KeyEvent.KEYCODE_ENTER) return true
        return super.dispatchKeyEvent(event)
    }

    private fun editKey() {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            setText(Prefs.apiKey(this@MainActivity))
            hint = ""
            setTextColor(Color.WHITE)
            backgroundTintList = ColorStateList.valueOf(gold)
            textDirection = View.TEXT_DIRECTION_LTR
        }
        val box = FrameLayout(this).apply {
            setPadding(dp(20f), dp(8f), dp(20f), 0)
            addView(input)
        }
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("مفتاح الترجمة")
            .setView(box)
            .setPositiveButton("حفظ") { _, _ -> Prefs.setApiKey(this, input.text.toString()); refresh() }
            .setNegativeButton("إلغاء", null)
            .show()
    }
}
