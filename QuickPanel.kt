package com.tarjuman.tv

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Small translucent panel shown over the channel (corner of the screen).
 * Controlled only with the remote:  ▲▼ choose row · ◀▶ change value ·
 * OK on a row = action · Back / the subtitle key = hide. Auto-hides after 10 s.
 */
class QuickPanel(private val svc: Context, private val overlay: () -> SubtitleOverlay?) {
    private val wm = svc.getSystemService(WindowManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private var root: LinearLayout? = null
    private val values = ArrayList<TextView>()

    private fun label(c: Context) = OutlineTextView(c).apply {
        strokePx = dp(2.2f).toFloat()
        setShadowLayer(dp(2f).toFloat(), 0f, 0f, Color.BLACK)
    }
    private val rowViews = ArrayList<View>()
    private var sel = 0
    private val autoHide = Runnable { hide() }

    val isShown: Boolean get() = root != null

    private class Item(val label: String, val value: () -> String, val change: (Int) -> Unit, val ok: (() -> Unit)? = null)

    private val items: List<Item> = listOf(
        Item("الترجمة", { if (CaptureService.running) "● تعمل" else "○ متوقفة" }, { _ -> }, ok = {
            hide(); Toggle.toggle(svc)
        }),
        Item("التزامن", { fmtAdj(Prefs.syncAdjustMs(svc)) }, { d -> Prefs.setSyncAdjustMs(svc, Prefs.syncAdjustMs(svc) + d * 250L) }, ok = {
            Prefs.setSyncAdjustMs(svc, 0L) // OK = back to 0
        }),
        Item("ترجمة جاهزة", { readyLabel() }, { _ -> Prefs.setReadySubs(svc, !Prefs.readySubs(svc)) }),
        Item("نوع الخط", { Config.FACES[Prefs.faceIdx(svc)].label }, { d -> Prefs.setFaceIdx(svc, wrap(Prefs.faceIdx(svc) + d, Config.FACES.size)) }),
        Item("حجم الخط", { "${Prefs.fontSp(svc)}" }, { d -> Prefs.setFontIdx(svc, (Prefs.fontIdx(svc) + d).coerceIn(0, Config.FONT_SIZES.size - 1)) }),
        Item("لون الخط", { Config.COLORS[Prefs.colorIdx(svc)].first }, { d -> Prefs.setColorIdx(svc, wrap(Prefs.colorIdx(svc) + d, Config.COLORS.size)) }),
        Item("نصوص المشهد", { if (Prefs.screenText(svc)) "تعمل" else "متوقفة" }, { _ -> Prefs.setScreenText(svc, !Prefs.screenText(svc)) }),
        Item("ترجمة إلى", { Prefs.target(svc).label }, { d -> Prefs.setTargetIdx(svc, wrap(Prefs.targetIdx(svc) + d, Config.TARGETS.size)) }),
        Item("الإعدادات الكاملة", { "←" }, { _ -> }, ok = {
            hide()
            svc.startActivity(Intent(svc, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }),
    )

    private fun fmtAdj(ms: Long): String {
        val sign = if (ms > 0) "+" else if (ms < 0) "−" else "±"
        return sign + String.format(java.util.Locale.US, "%.2f", kotlin.math.abs(ms) / 1000.0) + " ث"
    }

    private fun readyLabel(): String {
        if (!Prefs.readySubs(svc)) return "متوقفة"
        val s = SubtitleSync.current ?: return "تعمل"
        return when (s.state) {
            SubtitleSync.State.LOCKED -> (s.title ?: "مطابقة").take(18)
            SubtitleSync.State.LOST -> "إعادة مطابقة…"
            SubtitleSync.State.SEARCHING -> "تبحث…"
        }
    }

    private fun wrap(v: Int, n: Int) = ((v % n) + n) % n
    private fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, svc.resources.displayMetrics).toInt()

    fun show() {
        if (root != null) return
        val m = svc.resources.displayMetrics
        val panel = LinearLayout(svc).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(dp(10f), dp(10f), dp(10f), dp(8f))
            background = GradientDrawable().apply {
                cornerRadius = dp(16f).toFloat()
                setColor(0x24080A18) // ~14% tint: the channel stays fully visible
            }
        }
        panel.addView(label(svc).apply {
            text = "ترجمان"
            setTextColor(0xFFFFC94A.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(8f), 0, dp(8f), dp(6f))
        })
        values.clear(); rowViews.clear()
        items.forEach { item ->
            val row = LinearLayout(svc).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(10f), dp(6f), dp(10f), dp(6f))
            }
            row.addView(label(svc).apply {
                text = item.label
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            val v = label(svc).apply {
                setTextColor(0xFFFFC94A.toInt())
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                typeface = Typeface.DEFAULT_BOLD
                setPadding(dp(12f), 0, 0, 0)
            }
            row.addView(v)
            values.add(v); rowViews.add(row)
            panel.addView(row)
        }
        panel.addView(label(svc).apply {
            text = "▲▼ اختيار   ◀▶ تغيير   رجوع: إخفاء"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(dp(8f), dp(6f), dp(8f), 0)
        })

        val lp = WindowManager.LayoutParams(
            (m.widthPixels * 0.24).toInt().coerceAtLeast(dp(240f)),
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.RIGHT
            x = (m.widthPixels * 0.03).toInt()
            y = (m.heightPixels * 0.06).toInt()
        }
        try { wm.addView(panel, lp) } catch (e: Exception) { return }
        root = panel
        sel = 1
        render()
        sample()
    }

    fun hide() {
        main.removeCallbacks(autoHide)
        root?.let { try { wm.removeView(it) } catch (_: Exception) { } }
        root = null
    }

    /** Returns true when the key was used by the panel. */
    fun onKey(e: KeyEvent, toggleKey: Int): Boolean {
        if (root == null) return false
        if (e.action != KeyEvent.ACTION_DOWN) {
            // swallow the matching UP events of keys we handle
            return e.keyCode in handled || e.keyCode == toggleKey
        }
        when (e.keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> sel = wrap(sel - 1, items.size)
            KeyEvent.KEYCODE_DPAD_DOWN -> sel = wrap(sel + 1, items.size)
            // RTL panel: left = next value / +, right = previous / −
            KeyEvent.KEYCODE_DPAD_LEFT -> { items[sel].change(1); sample() }
            KeyEvent.KEYCODE_DPAD_RIGHT -> { items[sel].change(-1); sample() }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                val ok = items[sel].ok
                if (ok != null) {
                    ok()
                    if (root == null) return true // the action closed the panel
                } else { items[sel].change(1); sample() }
            }
            KeyEvent.KEYCODE_BACK -> { hide(); return true }
            else -> if (e.keyCode == toggleKey) { hide(); return true } else return false
        }
        render()
        return true
    }

    private val handled = setOf(
        KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT,
        KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_BACK,
    )

    private fun render() {
        items.forEachIndexed { i, item ->
            values[i].text = item.value()
            // selected row: thin gold frame only, no fill
            rowViews[i].background = if (i == sel) GradientDrawable().apply {
                cornerRadius = dp(10f).toFloat()
                setStroke(dp(2f), 0xD9FFC94A.toInt())
            } else null
        }
        main.removeCallbacks(autoHide)
        main.postDelayed(autoHide, 10000)
    }

    /** Shows a sample line with the current style so changes are visible instantly. */
    private fun sample() {
        val o = overlay() ?: return
        o.restyle()
        if (!CaptureService.running) o.show("مثال: هكذا ستظهر الترجمة", 3000)
    }
}
