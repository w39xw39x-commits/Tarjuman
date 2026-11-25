package com.tarjuman.tv

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.zip.ZipInputStream

/** A subtitle file that was downloaded, decoded and checked. */
class FoundSubs(
    val cues: List<FileCue>,
    /** true = file is in the film's language and must be translated ahead of time */
    val needsTranslation: Boolean,
    val source: String,
    /** true = the site says it was machine-translated (lower quality, used last) */
    val machine: Boolean,
)

/**
 * Looks for a subtitle file in several places, best first:
 *
 *  1. human subtitles already in the viewer's language  (Wyzie, then SubDL)
 *  2. human subtitles in the film's language, translated ahead of time by Gemini
 *  3. machine-translated subtitles in the viewer's language (SubtitleCat), as a last resort
 *
 * Every file is decoded with the right character set (many Arabic .srt files are Windows-1256,
 * not UTF-8) and checked to really be in the expected language before it is used.
 */
class SubtitleSources(private val wyzieKey: String, private val subdlKey: String) {

    fun find(imdb: String, season: Int, episode: Int, title: String, target: Lang, srcLang: String): FoundSubs? {
        val t = target.code
        // 1) human subtitles in the viewer's language
        wyzie(imdb, season, episode, t)?.let { return it }
        subdl(imdb, season, episode, t)?.let { return it }
        // 2) the film's own language, translated a minute ahead
        if (srcLang.length == 2 && srcLang != t) {
            human(imdb, season, episode, srcLang)?.let { return FoundSubs(it.cues, true, it.source, it.machine) }
        }
        // 3) last resort: a machine-translated file in the viewer's language
        return subtitleCat(title, season, episode, t)
    }

    /** Human subtitles in one language only (no machine translation), or null. */
    fun human(imdb: String, season: Int, episode: Int, lang: String): FoundSubs? =
        wyzie(imdb, season, episode, lang) ?: subdl(imdb, season, episode, lang)

    // ------------------------------------------------------------------ Wyzie

    private fun wyzie(imdb: String, season: Int, episode: Int, lang: String): FoundSubs? {
        if (wyzieKey.isBlank()) return null
        val url = "https://sub.wyzie.io/search?id=${enc(imdb)}" +
            (if (season > 0) "&season=$season&episode=$episode" else "") +
            "&language=$lang&format=srt&sort=downloads&key=${enc(wyzieKey)}"
        val arr = getJsonArray(url) ?: return null
        val list = (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
            .filter { it.optString("url").isNotBlank() && !it.optBoolean("isHearingImpaired", false) }
            .sortedWith(compareBy<JSONObject> { if (it.optBoolean("ai", false)) 1 else 0 }
                .thenByDescending { it.optInt("downloadCount", 0) })
        for (o in list.take(3)) {
            var u = o.optString("url")
            if (!u.contains("key=")) u += (if (u.contains('?')) "&" else "?") + "key=" + enc(wyzieKey)
            val cues = SubText.read(getBytes(u) ?: continue, lang, season, episode) ?: continue
            return FoundSubs(cues, false, "Wyzie", o.optBoolean("ai", false))
        }
        return null
    }

    // ------------------------------------------------------------------ SubDL (official API, own key)

    private fun subdl(imdb: String, season: Int, episode: Int, lang: String): FoundSubs? {
        if (subdlKey.isBlank()) return null
        val url = "https://api.subdl.com/api/v1/subtitles?api_key=${enc(subdlKey)}&imdb_id=${enc(imdb)}" +
            "&type=${if (season > 0) "tv" else "movie"}" +
            (if (season > 0) "&season_number=$season&episode_number=$episode" else "") +
            "&languages=${lang.uppercase()}&subs_per_page=30"
        val obj = getText(url)?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return null
        val arr = obj.optJSONArray("subtitles") ?: return null
        val list = (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
            .filter { it.optString("url").isNotBlank() && !it.optBoolean("hi", false) }
            // the exact episode before a whole-season pack
            .sortedBy { if (season > 0 && it.optBoolean("full_season", false)) 1 else 0 }
        for (o in list.take(3)) {
            val path = o.optString("url")
            val u = if (path.startsWith("http")) path else "https://dl.subdl.com" + (if (path.startsWith("/")) "" else "/") + path
            val cues = SubText.read(getBytes(u) ?: continue, lang, season, episode) ?: continue
            return FoundSubs(cues, false, "SubDL", false)
        }
        return null
    }

    // ------------------------------------------------------------------ SubtitleCat (machine-translated)

    private val catRow = Regex("""<a href="/?(subs/\d+/[^"]+\.html)">([^<]+)</a>""")

    private fun subtitleCat(title: String, season: Int, episode: Int, lang: String): FoundSubs? {
        val name = title.replace(Regex("""\s+S\d+E\d+$"""), "").trim()
        if (name.length < 2) return null
        val q = if (season > 0) "%s S%02dE%02d".format(name, season, episode) else name
        val html = getText("https://www.subtitlecat.com/index.php?search=${enc(q)}") ?: return null
        val pages = catRow.findAll(html)
            .filter { SubText.sameTitle(name, it.groupValues[2], season, episode) }
            .map { it.groupValues[1] }.distinct().take(3).toList()
        val link = Regex("""href="(/subs/\d+/[^"]+-${Regex.escape(lang)}\.srt)"""")
        for (p in pages) {
            val page = getText("https://www.subtitlecat.com/$p") ?: continue
            val srt = link.find(page)?.groupValues?.get(1) ?: continue
            val cues = SubText.read(getBytes("https://www.subtitlecat.com" + safePath(srt)) ?: continue, lang, season, episode) ?: continue
            return FoundSubs(cues, false, "SubtitleCat", true)
        }
        return null
    }

    /** SubtitleCat links keep raw spaces and brackets, which URL() refuses. */
    private fun safePath(p: String) = p.replace(" ", "%20").replace("[", "%5B").replace("]", "%5D")
        .replace("\"", "%22").replace("#", "%23")

    // ------------------------------------------------------------------ http

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun getBytes(url: String): ByteArray? = try {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 8000; c.readTimeout = 15000
        c.instanceFollowRedirects = true
        c.setRequestProperty("Accept", "*/*")
        c.setRequestProperty("User-Agent", "Tarjuman/1.0")
        if (c.responseCode in 200..299) c.inputStream.use { it.readBytes() } else null
    } catch (e: Exception) { Log.w("Tarjuman", "get $url", e); null }

    private fun getText(url: String): String? = getBytes(url)?.toString(Charsets.UTF_8)

    private fun getJsonArray(url: String): JSONArray? {
        val t = getText(url)?.trim() ?: return null
        return try {
            if (t.startsWith("[")) JSONArray(t)
            else JSONObject(t).let { it.optJSONArray("results") ?: it.optJSONArray("data") ?: JSONArray().put(it) }
        } catch (_: Exception) { null }
    }
}

/** Turns downloaded bytes (.srt or .zip) into checked cues. */
object SubText {
    /** Too few lines = a broken or partial file. */
    private const val MIN_CUES = 20

    fun read(bytes: ByteArray, lang: String, season: Int, episode: Int): List<FileCue>? {
        val raw: ByteArray = if (isZip(bytes)) (unzipSrt(bytes, season, episode) ?: return null) else bytes
        val cues = Srt.parse(decode(raw, lang))
        if (cues.size < MIN_CUES) return null
        if (!inLanguage(cues, lang)) {
            Log.i("Tarjuman", "subtitle file is not in $lang: passed over")
            return null
        }
        return cues
    }

    // -------------------------------------------------------------- character set

    /** UTF-8/UTF-16 when the bytes say so, otherwise the usual legacy code page of that language. */
    fun decode(b: ByteArray, lang: String): String {
        if (b.size >= 3 && b[0] == 0xEF.toByte() && b[1] == 0xBB.toByte() && b[2] == 0xBF.toByte())
            return String(b, 3, b.size - 3, Charsets.UTF_8)
        if (b.size >= 2 && b[0] == 0xFF.toByte() && b[1] == 0xFE.toByte()) return String(b, 2, b.size - 2, Charsets.UTF_16LE)
        if (b.size >= 2 && b[0] == 0xFE.toByte() && b[1] == 0xFF.toByte()) return String(b, 2, b.size - 2, Charsets.UTF_16BE)
        val strict = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        return try {
            strict.decode(ByteBuffer.wrap(b)).toString()
        } catch (_: Exception) {
            String(b, charset(legacyCharset(lang)))
        }
    }

    private fun legacyCharset(lang: String) = when (lang) {
        "ar", "fa", "ur" -> "windows-1256"
        "ru" -> "windows-1251"
        "tr" -> "windows-1254"
        else -> "windows-1252"
    }

    // -------------------------------------------------------------- language check

    /** The file's letters must be mostly in the script of the expected language. */
    fun inLanguage(cues: List<FileCue>, lang: String): Boolean {
        val sample = cues.asSequence().map { it.text }.take(200).joinToString(" ")
        var letters = 0; var hit = 0
        for (ch in sample) {
            if (!ch.isLetter()) continue
            letters++
            if (scriptOf(ch) == scriptFor(lang)) hit++
        }
        if (letters < 200) return true // too little text to judge
        return hit.toDouble() / letters >= 0.6
    }

    private enum class Script { ARABIC, CYRILLIC, DEVANAGARI, CJK, HANGUL, LATIN, OTHER }

    private fun scriptFor(lang: String) = when (lang) {
        "ar", "fa", "ur" -> Script.ARABIC
        "ru" -> Script.CYRILLIC
        "hi" -> Script.DEVANAGARI
        "zh", "ja" -> Script.CJK
        "ko" -> Script.HANGUL
        else -> Script.LATIN
    }

    private fun scriptOf(ch: Char) = when (Character.UnicodeScript.of(ch.code)) {
        Character.UnicodeScript.ARABIC -> Script.ARABIC
        Character.UnicodeScript.CYRILLIC -> Script.CYRILLIC
        Character.UnicodeScript.DEVANAGARI -> Script.DEVANAGARI
        Character.UnicodeScript.HAN, Character.UnicodeScript.HIRAGANA, Character.UnicodeScript.KATAKANA -> Script.CJK
        Character.UnicodeScript.HANGUL -> Script.HANGUL
        Character.UnicodeScript.LATIN -> Script.LATIN
        else -> Script.OTHER
    }

    // -------------------------------------------------------------- zip packs

    private fun isZip(b: ByteArray) = b.size > 4 && b[0] == 'P'.code.toByte() && b[1] == 'K'.code.toByte()

    /** The .srt for this episode inside a zip (whole-season packs), or the largest .srt. */
    private fun unzipSrt(b: ByteArray, season: Int, episode: Int): ByteArray? {
        val files = ArrayList<Pair<String, ByteArray>>()
        ZipInputStream(ByteArrayInputStream(b)).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                if (!e.isDirectory && e.name.lowercase().endsWith(".srt")) files.add(e.name to z.readBytes())
            }
        }
        if (files.isEmpty()) return null
        if (season > 0 && files.size > 1) {
            val ep = Regex("(?i)(s0*${season}e0*${episode}(?!\\d))|((?<!\\d)0*${season}x0*${episode}(?!\\d))")
            files.firstOrNull { ep.containsMatchIn(it.first) }?.let { return it.second }
        }
        return files.maxByOrNull { it.second.size }?.second
    }

    // -------------------------------------------------------------- titles

    /** Where a release name stops being the title: year, episode, resolution, source... */
    private val titleEnd = Regex("""(?i)(?<![\p{L}\p{N}])((19|20)\d{2}|s\d{1,2}e\d{1,3}|\d{1,2}x\d{2}|\d{3,4}p|bluray|brrip|bdrip|web|webrip|web-dl|hdtv|dvdrip|x26[45]|hevc)(?![\p{L}\p{N}])""")
    private val episodeTag = Regex("""(?i)(?<![\p{L}\p{N}])s0*(\d{1,2})e0*(\d{1,3})(?!\d)""")

    /**
     * Is this release name ([row]) the programme we want? Only the title part is compared
     * (so "The Matrix is collapsing" or "The Matrix Resurrections" are not "The Matrix"),
     * and a series must carry the same season/episode.
     */
    fun sameTitle(wanted: String, row: String, season: Int, episode: Int): Boolean {
        val clean = row.replace('.', ' ').replace('_', ' ')
        if (season > 0) {
            val m = episodeTag.find(clean) ?: return false
            if (m.groupValues[1].toInt() != season || m.groupValues[2].toInt() != episode) return false
        }
        val cut = titleEnd.find(clean)?.range?.first ?: clean.length
        val a = Srt.words(wanted); val b = Srt.words(clean.substring(0, cut))
        if (a.isEmpty() || b.isEmpty()) return false
        val jaccard = a.intersect(b).size.toDouble() / a.union(b).size
        return jaccard >= 0.8
    }
}
