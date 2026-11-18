package com.tarjuman.tv

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.KeyEvent
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.Toast

/**
 * Listens for ONE remote key (chosen by the user). From the settings the user picks
 * what a short press does: subtitles on/off, or the quick style panel.
 * A long press always does the other one.
 * Also hosts the subtitle overlay, which needs no extra permission here.
 */
class RemoteKeyService : AccessibilityService() {
    companion object {
        @Volatile var instance: RemoteKeyService? = null
            private set
        @Volatile var learning = false

        fun isEnabled(c: Context): Boolean {
            val list = Settings.Secure.getString(c.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
            return list.contains("${c.packageName}/") && list.contains("RemoteKeyService")
        }
    }

    var overlay: SubtitleOverlay? = null
        private set
    private val h = Handler(Looper.getMainLooper())
    private var longFired = false
    var panel: QuickPanel? = null
        private set
    private val longPress = Runnable {
        longFired = true
        if (Prefs.keyAction(this) == 0) panel?.show() else Toggle.toggle(this)
    }

    override fun onServiceConnected() {
        instance = this
        overlay = SubtitleOverlay(this, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY)
        panel = QuickPanel(this) { overlay }
        h.postDelayed(updateCheck, 20000)
    }

    /** Checks for a new version every 6 hours, only while subtitles are off. */
    private val updateCheck = object : Runnable {
        override fun run() {
            if (!CaptureService.running && !UpdateActivity.showing) Updater.checkAndPrompt(this@RemoteKeyService)
            h.postDelayed(this, 6 * 3600 * 1000L)
        }
    }

    private var swallowUpOf = -1

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (learning) return false
        val key = Prefs.toggleKey(this)
        val down = event.action == KeyEvent.ACTION_DOWN

        // finish a press that was already consumed (e.g. the press that closed the panel)
        if (!down && event.keyCode == swallowUpOf) { swallowUpOf = -1; return true }
        // holding the subtitle key auto-repeats: ignore repeats entirely
        if (event.keyCode == key && event.repeatCount > 0) return true

        val p = panel
        if (p != null && p.isShown && !(event.keyCode == key && !down && longFired)) {
            if (p.onKey(event, key)) {
                if (down) swallowUpOf = event.keyCode
                return true
            }
            return false
        }

        if (event.keyCode != key) return false
        if (down) {
            longFired = false
            h.postDelayed(longPress, 1500)
        } else {
            h.removeCallbacks(longPress)
            if (!longFired) {
                if (Prefs.keyAction(this) == 1) panel?.show() else Toggle.toggle(this)
            }
            longFired = false
        }
        return true
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    override fun onDestroy() {
        instance = null
        h.removeCallbacksAndMessages(null)
        panel?.hide()
        overlay?.detach()
        overlay = null
        super.onDestroy()
    }
}

object Toggle {
    fun toggle(c: Context) {
        if (CaptureService.running) CaptureService.stop(c) else start(c)
    }

    fun start(c: Context) {
        if (Prefs.apiKey(c).isBlank()) {
            Toast.makeText(c, "أدخل مفتاح Groq أولاً من الإعدادات", Toast.LENGTH_LONG).show()
            c.startActivity(Intent(c, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        }
        c.startActivity(
            Intent(c, ConsentActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
        )
    }
}
