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
import android.graphics.Bitmap
import android.graphics.Outline
import android.view.ViewOutlineProvider
import android.widget.ImageView
import android.widget.LinearLayout

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

/** Anything that can show a subtitle line: the overlay above other apps, or the built-in player. */
interface SubSink {
    fun show(text: String, durationMs: Long)
    fun flash(text: String, durationMs: Long = 2200)
    fun hideSubtitle()
    fun restyle()
    /** Poster card (film / series recognised). */
    fun card(poster: Bitmap?, title: String, info: String, durationMs: Long = 8000) {}
}

/** Draws the subtitle (bottom) and short status messages (top corner) above any app. */
class SubtitleOverlay(private val ctx: Context, private val type: Int) : SubSink {
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

    private fun applyStyle(v: OutlineTextView) = SubStyle.apply(ctx, v)

    override fun show(text: String, durationMs: Long) { main.post {
        try {
            val v = sub ?: make(true).also { sub = it }
            applyStyle(v)
            v.text = text
            v.visibility = View.VISIBLE
            main.removeCallbacks(hideSub)
            main.postDelayed(hideSub, durationMs)
        } catch (_: Exception) { }
    } }

    /** Re-style the visible line right away (used by the quick panel). */
    override fun restyle() { main.post { sub?.let { applyStyle(it) } } }

    override fun flash(text: String, durationMs: Long) { main.post {
        try {
            val v = status ?: make(false).also { status = it }
            v.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            v.text = text
            v.visibility = View.VISIBLE
            main.removeCallbacks(hideStatus)
            main.postDelayed(hideStatus, durationMs)
        } catch (_: Exception) { }
    } }

    override fun hideSubtitle() { main.post { main.removeCallbacks(hideSub); sub?.visibility = View.INVISIBLE } }

    private var cardView: View? = null
    private val hideCard = Runnable {
        val v = cardView ?: return@Runnable
        v.animate().alpha(0f).setDuration(450).withEndAction {
            try { wm.removeView(v) } catch (_: Exception) { }
            if (cardView === v) cardView = null
        }.start()
    }

    override fun card(poster: Bitmap?, title: String, info: String, durationMs: Long) { main.post {
        try {
            cardView?.let { try { wm.removeView(it) } catch (_: Exception) { } }
            val m = ctx.resources.displayMetrics
            val box = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutDirection = View.LAYOUT_DIRECTION_RTL
                val p = dp(12f).toInt(); setPadding(p, p, p, p)
                background = GradientDrawable().apply {
                    cornerRadius = dp(20f); setColor(0xB80B0E1A.toInt()); setStroke(dp(1f).toInt(), 0x33FFFFFF)
                }
            }
            if (poster != null) box.addView(ImageView(ctx).apply {
                setImageBitmap(poster); scaleType = ImageView.ScaleType.CENTER_CROP
                clipToOutline = true
                outlineProvider = object : ViewOutlineProvider() {
                    override fun getOutline(v: View, o: Outline) { o.setRoundRect(0, 0, v.width, v.height, dp(12f)) }
                }
            }, LinearLayout.LayoutParams(dp(96f).toInt(), dp(144f).toInt()))
            val col = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(16f).toInt(), 0, dp(8f).toInt(), 0)
            }
            col.addView(TextView(ctx).apply {
                text = title; setTextColor(Color.WHITE); textSize = 21f; typeface = Typeface.DEFAULT_BOLD
                maxWidth = dp(320f).toInt(); maxLines = 2
            })
            if (info.isNotBlank()) col.addView(TextView(ctx).apply {
                text = info; setTextColor(0xFFF5C451.toInt()); textSize = 15f
                setPadding(0, dp(8f).toInt(), 0, 0); maxWidth = dp(320f).toInt()
            })
            box.addView(col)
            val lp = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT, type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.RIGHT
                x = (m.widthPixels * 0.03).toInt(); y = (m.heightPixels * 0.06).toInt()
            }
            box.alpha = 0f
            wm.addView(box, lp)
            cardView = box
            box.animate().alpha(1f).setDuration(400).start()
            main.removeCallbacks(hideCard)
            main.postDelayed(hideCard, durationMs)
        } catch (_: Exception) { }
    } }

    fun detach() = main.post {
        main.removeCallbacksAndMessages(null)
        listOfNotNull(sub, status, cardView).forEach { try { wm.removeView(it) } catch (_: Exception) { } }
        sub = null; status = null; cardView = null
    }
}

/** One place for the subtitle look (font, size, colour, outline). */
object SubStyle {
    fun apply(ctx: Context, v: OutlineTextView) {
        val d = ctx.resources.displayMetrics.density
        v.typeface = Fonts.get(ctx, Prefs.faceIdx(ctx))
        val sizeSp = Prefs.fontSp(ctx).toFloat()
        v.setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        v.setTextColor(Config.COLORS[Prefs.colorIdx(ctx)].second)
        v.strokePx = maxOf(sizeSp * 0.11f * d, 2.5f * d)
        v.setShadowLayer(4f * d, 0f, 1.5f * d, 0xAA000000.toInt())
        v.invalidate()
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
