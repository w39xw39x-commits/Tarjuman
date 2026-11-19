package com.tarjuman.tv

import android.content.Context
import android.view.KeyEvent

data class Lang(val code: String, val label: String, val english: String)

/** Speed/accuracy presets. Shorter segments = less delay, longer = more context and accuracy. */
data class Mode(
    val label: String,
    val minMs: Int,
    val maxMs: Int,
    val silenceMs: Int,
    val sttModel: String,
)

object Config {
    val TARGETS = listOf(
        Lang("ar", "العربية", "Arabic"),
        Lang("en", "English", "English"),
        Lang("fr", "Français", "French"),
        Lang("tr", "Türkçe", "Turkish"),
        Lang("es", "Español", "Spanish"),
        Lang("de", "Deutsch", "German"),
        Lang("ur", "اردو", "Urdu"),
        Lang("fa", "فارسی", "Persian"),
        Lang("hi", "हिन्दी", "Hindi"),
        Lang("id", "Indonesia", "Indonesian"),
        Lang("ru", "Русский", "Russian"),
    )

    val SOURCES = listOf(
        Lang("auto", "تلقائي (أي لغة)", "auto"),
        Lang("en", "إنجليزي", "English"),
        Lang("tr", "تركي", "Turkish"),
        Lang("hi", "هندي", "Hindi"),
        Lang("ko", "كوري", "Korean"),
        Lang("es", "إسباني", "Spanish"),
        Lang("fr", "فرنسي", "French"),
        Lang("ja", "ياباني", "Japanese"),
        Lang("zh", "صيني", "Chinese"),
        Lang("ru", "روسي", "Russian"),
        Lang("de", "ألماني", "German"),
        Lang("it", "إيطالي", "Italian"),
        Lang("pt", "برتغالي", "Portuguese"),
        Lang("fa", "فارسي", "Persian"),
        Lang("ur", "أوردو", "Urdu"),
    )

    val MODES = listOf(
        Mode("سريع (تأخير أقل)", 800, 3000, 280, "gemini-flash-lite-latest"),
        Mode("متوازن (موصى به)", 1100, 4500, 340, "gemini-flash-lite-latest"),
        Mode("دقيق جداً", 1400, 6500, 420, "gemini-flash-latest"),
    )

    val FONT_SIZES = listOf(20, 24, 28, 32, 36, 42, 48)

    data class Face(val label: String, val res: Int)
    val FACES = listOf(
        Face("خط ١", R.font.khat1),
        Face("خط ٢", R.font.khat2),
        Face("خط ٣", R.font.khat3),
    )

    val COLORS = listOf(
        "أبيض" to 0xFFFFFFFF.toInt(),
        "أصفر" to 0xFFFFE14A.toInt(),
        "سماوي" to 0xFF6EE7FF.toInt(),
        "أخضر" to 0xFF8CFF9E.toInt(),
        "ذهبي" to 0xFFFFC94A.toInt(),
    )

    val KEY_ACTIONS = listOf("تشغيل/إيقاف الترجمة", "فتح اللوحة السريعة")

    const val CHAT_MODEL = "gemini-flash-lite-latest"
}

object Prefs {
    private fun sp(c: Context) = c.getSharedPreferences("tarjuman", Context.MODE_PRIVATE)

    fun apiKey(c: Context): String =
        listOfNotNull(sp(c).getString("key", null)?.trim()?.takeIf { it.isNotEmpty() }, BuildConfig.DEFAULT_KEY).joinToString(",")

    fun setApiKey(c: Context, v: String) = sp(c).edit().putString("key", v.trim()).apply()

    fun targetIdx(c: Context) = sp(c).getInt("target", 0).coerceIn(0, Config.TARGETS.size - 1)
    fun setTargetIdx(c: Context, v: Int) = sp(c).edit().putInt("target", v).apply()
    fun target(c: Context) = Config.TARGETS[targetIdx(c)]

    fun sourceIdx(c: Context) = sp(c).getInt("source", 0).coerceIn(0, Config.SOURCES.size - 1)
    fun setSourceIdx(c: Context, v: Int) = sp(c).edit().putInt("source", v).apply()
    fun source(c: Context) = Config.SOURCES[sourceIdx(c)]

    fun modeIdx(c: Context) = sp(c).getInt("mode", 1).coerceIn(0, Config.MODES.size - 1)
    fun setModeIdx(c: Context, v: Int) = sp(c).edit().putInt("mode", v).apply()
    fun mode(c: Context) = Config.MODES[modeIdx(c)]

    fun fontIdx(c: Context) = sp(c).getInt("font", 3).coerceIn(0, Config.FONT_SIZES.size - 1)
    fun setFontIdx(c: Context, v: Int) = sp(c).edit().putInt("font", v).apply()
    fun fontSp(c: Context) = Config.FONT_SIZES[fontIdx(c)]

    fun faceIdx(c: Context) = sp(c).getInt("face", 0).coerceIn(0, Config.FACES.size - 1)
    fun setFaceIdx(c: Context, v: Int) = sp(c).edit().putInt("face", v).apply()
    fun colorIdx(c: Context) = sp(c).getInt("color", 0).coerceIn(0, Config.COLORS.size - 1)
    fun setColorIdx(c: Context, v: Int) = sp(c).edit().putInt("color", v).apply()
    /** 0 = short press toggles subtitles, 1 = short press opens the quick panel. */
    fun keyAction(c: Context) = sp(c).getInt("kact", 0).coerceIn(0, Config.KEY_ACTIONS.size - 1)
    fun setKeyAction(c: Context, v: Int) = sp(c).edit().putInt("kact", v).apply()

    fun screenText(c: Context) = sp(c).getBoolean("stext3", true) // place names / captions in the scene
    fun setScreenText(c: Context, v: Boolean) = sp(c).edit().putBoolean("stext3", v).apply()

    /** Use ready-made subtitle files (perfect sync) when the programme is recognised. */
    fun readySubs(c: Context) = sp(c).getBoolean("ready2", true)
    fun setReadySubs(c: Context, v: Boolean) = sp(c).edit().putBoolean("ready2", v).apply()

    /** Fine sync from the remote (+/-), in ms. Positive = subtitles later. */
    fun syncAdjustMs(c: Context) = sp(c).getLong("adj", 0L)
    fun setSyncAdjustMs(c: Context, v: Long) = sp(c).edit().putLong("adj", v.coerceIn(-10000L, 10000L)).apply()

    fun toggleKey(c: Context) = sp(c).getInt("tkey", KeyEvent.KEYCODE_PROG_RED)
    fun setToggleKey(c: Context, v: Int) = sp(c).edit().putInt("tkey", v).apply()

    fun keyName(code: Int): String = KeyEvent.keyCodeToString(code).removePrefix("KEYCODE_")
}
