package com.tarjuman.tv

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.TextView

/** Text with a solid black outline and NO box behind it, so nothing in the scene is hidden. */
class OutlineTextView(ctx: Context) : TextView(ctx) {
    var strokePx = 0f

    override fun onDraw(canvas: Canvas) {
        val l = layout
        if (l != null && strokePx > 0f) {
            val p = paint
            canvas.save()
            canvas.translate(compoundPaddingLeft.toFloat(), extendedPaddingTop.toFloat())
            p.style = Paint.Style.STROKE
            p.strokeWidth = strokePx
            p.strokeJoin = Paint.Join.ROUND
            p.color = Color.BLACK
            l.draw(canvas)
            canvas.restore()
            p.style = Paint.Style.FILL
        }
        super.onDraw(canvas)
    }
}

object Fonts {
    private val cache = HashMap<Int, Typeface>()
    fun get(ctx: Context, idx: Int): Typeface {
        val f = Config.FACES[idx.coerceIn(0, Config.FACES.size - 1)]
        return cache.getOrPut(f.res) {
            try { ctx.resources.getFont(f.res) } catch (_: Exception) { Typeface.DEFAULT_BOLD }
        }
    }
}

/** Draws the subtitle (bottom) and short status messages (top corner) above any app. */
class SubtitleOverlay(private val ctx: Context, private val type: Int) {
    private val wm = ctx.getSystemService(WindowManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private var sub: OutlineTextView? = null
    private var status: OutlineTextView? = null
    private val hideSub = Runnable { sub?.visibility = View.INVISIBLE }
    private val hideStatus = Runnable { status?.visibility = View.INVISIBLE }

    private fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, ctx.resources.displayMetrics)

    private fun make(bottom: Boolean): OutlineTextView {
        val m = ctx.resources.displayMetrics
        val tv = OutlineTextView(ctx).apply {
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            textDirection = View.TEXT_DIRECTION_FIRST_STRONG
            setLineSpacing(0f, 1.1f)
            val pad = dp(10f).toInt()
            setPadding(pad, pad / 2, pad, pad / 2)
            maxWidth = (m.widthPixels * 0.88).toInt()
            visibility = View.INVISIBLE
            if (!bottom) {
                // status line: barely-there tint, readable thanks to the outline
                background = GradientDrawable().apply {
                    cornerRadius = dp(12f)
                    setColor(0x1F080A18)
                }
                typeface = Typeface.DEFAULT_BOLD
                strokePx = dp(2.5f)
            }
        }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = if (bottom) Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL else Gravity.TOP or Gravity.LEFT
            y = (m.heightPixels * (if (bottom) 0.07 else 0.06)).toInt()
            x = if (bottom) 0 else (m.widthPixels * 0.03).toInt()
        }
        wm.addView(tv, lp)
        return tv
    }

    private fun applyStyle(v: OutlineTextView) {
        v.typeface = Fonts.get(ctx, Prefs.faceIdx(ctx))
        val sizeSp = Prefs.fontSp(ctx).toFloat()
        v.setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        v.setTextColor(Config.COLORS[Prefs.colorIdx(ctx)].second)
        v.strokePx = dp(sizeSp * 0.11f).coerceAtLeast(dp(2.5f))
        v.setShadowLayer(dp(4f), 0f, dp(1.5f), 0xAA000000.toInt())
        v.invalidate()
    }

    fun show(text: String, durationMs: Long) = main.post {
        try {
            val v = sub ?: make(true).also { sub = it }
            applyStyle(v)
            v.text = text
            v.visibility = View.VISIBLE
            main.removeCallbacks(hideSub)
            main.postDelayed(hideSub, durationMs)
        } catch (_: Exception) { }
    }

    /** Re-style the visible line right away (used by the quick panel). */
    fun restyle() = main.post { sub?.let { applyStyle(it) } }

    fun flash(text: String, durationMs: Long = 2200) = main.post {
        try {
            val v = status ?: make(false).also { status = it }
            v.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            v.text = text
            v.visibility = View.VISIBLE
            main.removeCallbacks(hideStatus)
            main.postDelayed(hideStatus, durationMs)
        } catch (_: Exception) { }
    }

    fun hideSubtitle() = main.post { main.removeCallbacks(hideSub); sub?.visibility = View.INVISIBLE }

    fun detach() = main.post {
        main.removeCallbacksAndMessages(null)
        listOfNotNull(sub, status).forEach { try { wm.removeView(it) } catch (_: Exception) { } }
        sub = null; status = null
    }
}

object OverlayHost {
    private var fallback: SubtitleOverlay? = null

    /** Prefer the accessibility overlay (no extra permission); otherwise use "draw over apps". */
    fun get(ctx: Context): SubtitleOverlay? {
        RemoteKeyService.instance?.overlay?.let { return it }
        if (Settings.canDrawOverlays(ctx)) {
            return fallback ?: SubtitleOverlay(
                ctx.applicationContext, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            ).also { fallback = it }
        }
        return null
    }
}
