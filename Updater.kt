package com.tarjuman.tv

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

data class UpdateInfo(val code: Int, val name: String, val apk: String, val notes: List<String>)

object Updater {
    private val base get() = BuildConfig.UPDATE_BASE.trimEnd('/')

    /** Network call: returns info only when a newer version exists. */
    fun fetch(): UpdateInfo? {
        if (base.isBlank()) return null
        return try {
            val c = URL("$base/version.json?t=${System.currentTimeMillis()}").openConnection() as HttpURLConnection
            c.connectTimeout = 6000; c.readTimeout = 6000
            c.setRequestProperty("Cache-Control", "no-cache")
            val j = JSONObject(c.inputStream.bufferedReader().use { it.readText() })
            val code = j.getInt("versionCode")
            if (code <= BuildConfig.VERSION_CODE) return null
            val notesArr = j.optJSONArray("notes")
            val notes = ArrayList<String>()
            if (notesArr != null) for (i in 0 until minOf(4, notesArr.length())) notes.add(notesArr.getString(i))
            val apk = j.optString("apk").ifBlank { "$base/Tarjuman.apk" }
            UpdateInfo(code, j.optString("versionName", "$code"), apk, notes)
        } catch (_: Exception) { null }
    }

    /** Check in the background; open the forced update screen if needed. */
    fun checkAndPrompt(ctx: Context, onNone: (() -> Unit)? = null) {
        val app = ctx.applicationContext
        thread(name = "update-check") {
            val info = fetch()
            Handler(Looper.getMainLooper()).post {
                if (info != null) UpdateActivity.open(app, info) else onNone?.invoke()
            }
        }
    }
}

/** Forced update window: cannot be dismissed; downloads, installs, then the app reopens itself. */
class UpdateActivity : Activity() {
    companion object {
        @Volatile var showing = false
        fun open(c: Context, u: UpdateInfo) {
            if (showing) return
            c.startActivity(
                Intent(c, UpdateActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    .putExtra("code", u.code).putExtra("name", u.name).putExtra("apk", u.apk)
                    .putExtra("notes", u.notes.toTypedArray())
            )
        }
    }

    private lateinit var bar: ProgressBar
    private lateinit var status: TextView
    private lateinit var btn: TextView
    private var busy = false
    private var apkFile: File? = null
    private val ui = Handler(Looper.getMainLooper())

    private fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        showing = true
        if (CaptureService.running) CaptureService.stop(this)
        window.decorView.layoutDirection = View.LAYOUT_DIRECTION_RTL

        val name = intent.getStringExtra("name") ?: ""
        val notes = intent.getStringArrayExtra("notes") ?: emptyArray()

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(32f), dp(28f), dp(32f), dp(24f))
            background = GradientDrawable().apply {
                cornerRadius = dp(24f).toFloat(); setColor(0xFF151C3C.toInt()); setStroke(dp(2f), 0x99FFC94A.toInt())
            }
        }
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(ImageView(this).apply { setImageResource(R.drawable.ic_fg) }, LinearLayout.LayoutParams(dp(60f), dp(60f)))
        head.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12f), 0, dp(12f), 0)
            addView(tv("تحديث جديد متوفر", 24f, Color.WHITE, true))
            addView(tv("الإصدار $name · مطلوب للمتابعة", 15f, 0xFFFFC94A.toInt(), true))
        })
        card.addView(head)
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(14f), 0, dp(10f)) }
        notes.forEach { list.addView(tv("• $it", 17f, 0xFFE4E8FA.toInt(), false).apply { setLineSpacing(0f, 1.25f) }) }
        card.addView(list)
        bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100; progress = 0; visibility = View.INVISIBLE
            progressTintList = android.content.res.ColorStateList.valueOf(0xFFFFC94A.toInt())
        }
        card.addView(bar, LinearLayout.LayoutParams(MATCH_PARENT, dp(12f)))
        status = tv("", 14f, 0xFFA9B1D6.toInt(), false)
        card.addView(status)
        btn = tv("تحديث الآن", 20f, 0xFF10142E.toInt(), true).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(14f), 0, dp(14f))
            isFocusable = true; isClickable = true
            background = GradientDrawable().apply { cornerRadius = dp(16f).toFloat(); setColor(0xFFFFC94A.toInt()); setStroke(dp(3f), Color.WHITE) }
            setOnClickListener { start() }
        }
        card.addView(btn, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(12f) })
        card.addView(tv("بعد التثبيت يُغلق التطبيق ويعود للعمل تلقائياً خلال ثانيتين", 13f, 0xFFA9B1D6.toInt(), false).apply {
            gravity = Gravity.CENTER; setPadding(0, dp(10f), 0, 0)
        })

        val root = FrameLayout(this).apply { setBackgroundColor(0xF2060914.toInt()) }
        val w = minOf(dp(600f), (resources.displayMetrics.widthPixels * 0.9).toInt())
        root.addView(card, FrameLayout.LayoutParams(w, WRAP_CONTENT, Gravity.CENTER))
        setContentView(root)
        btn.requestFocus()
    }

    private fun tv(s: String, size: Float, color: Int, bold: Boolean) = TextView(this).apply {
        text = s; setTextColor(color); setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    override fun onResume() {
        super.onResume()
        // came back from "allow installs from this app": continue automatically
        val f = apkFile
        if (f != null && !busy && packageManager.canRequestPackageInstalls()) install(f)
    }

    override fun onDestroy() { showing = false; super.onDestroy() }

    // forced: Back does nothing
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK) return true
        return super.dispatchKeyEvent(event)
    }

    private fun start() {
        if (busy) return
        apkFile?.let { install(it); return }
        busy = true
        bar.visibility = View.VISIBLE
        btn.text = "جارٍ التحميل…"
        val url = intent.getStringExtra("apk") ?: return
        thread(name = "update-dl") {
            try {
                val out = File(cacheDir, "update.apk")
                val c = URL(url).openConnection() as HttpURLConnection
                c.connectTimeout = 10000; c.readTimeout = 20000; c.instanceFollowRedirects = true
                val total = c.contentLengthLong
                var done = 0L
                c.inputStream.use { inp ->
                    out.outputStream().use { o ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = inp.read(buf); if (n < 0) break
                            o.write(buf, 0, n); done += n
                            if (total > 0) {
                                val pct = (done * 100 / total).toInt()
                                val txt = "جارٍ التحميل… $pct٪   ${"%.1f".format(done / 1048576.0)} / ${"%.1f".format(total / 1048576.0)} ميجا"
                                ui.post { bar.progress = pct; status.text = txt }
                            }
                        }
                    }
                }
                ui.post { busy = false; apkFile = out; install(out) }
            } catch (e: Exception) {
                ui.post { busy = false; btn.text = "إعادة المحاولة"; status.text = "تعذّر التحميل، تحقق من الإنترنت" }
            }
        }
    }

    private fun install(f: File) {
        if (!packageManager.canRequestPackageInstalls()) {
            status.text = "اسمح لترجمان بتثبيت التحديثات ثم ارجع"
            try {
                startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
            } catch (_: Exception) { }
            return
        }
        busy = true
        btn.text = "جارٍ التثبيت…"
        status.text = "سيُعاد فتح التطبيق تلقائياً"
        thread(name = "update-install") {
            try {
                val pi = packageManager.packageInstaller
                val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
                    setAppPackageName(packageName)
                    // Android 12+: later updates install without asking, once we are the installer
                    if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
                }
                val id = pi.createSession(params)
                pi.openSession(id).use { s ->
                    s.openWrite("base.apk", 0, f.length()).use { o -> f.inputStream().use { it.copyTo(o) }; s.fsync(o) }
                    val cb = PendingIntent.getBroadcast(
                        this, id, Intent(this, InstallReceiver::class.java),
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
                    )
                    s.commit(cb.intentSender)
                }
            } catch (e: Exception) {
                ui.post { busy = false; btn.text = "إعادة المحاولة"; status.text = "تعذّر التثبيت" }
            }
        }
    }
}

/** Receives the installer's answer; shows the system confirm screen when Android requires it. */
class InstallReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        when (i.getIntExtra(PackageInstaller.EXTRA_STATUS, -999)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm: Intent? = if (Build.VERSION.SDK_INT >= 33)
                    i.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                else @Suppress("DEPRECATION") i.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                confirm?.let { c.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }
            PackageInstaller.STATUS_SUCCESS -> Unit // the app is replaced; ReplacedReceiver relaunches it
            else -> c.startActivity(Intent(c, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}

/** Fires right after the update is installed: reopens the app (about 1–2 seconds). */
class ReplacedReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        if (i.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        c.getSharedPreferences("tarjuman", Context.MODE_PRIVATE).edit().putBoolean("just_updated", true).apply()
        c.startActivity(Intent(c, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
