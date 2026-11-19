package com.tarjuman.tv

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder

class HttpError(val code: Int, val retryAfterSec: Int, msg: String) : IOException("HTTP $code: $msg")

/** One spoken sentence inside a chunk: original text, its translation and timing (ms from chunk start). */
data class SttSeg(val text: String, val translated: String, val startMs: Long, val endMs: Long)
data class Stt(val segs: List<SttSeg>, val language: String, val ad: Boolean = false) {
    val text: String get() = segs.joinToString(" ") { it.text }
}

/**
 * Google Gemini (free tier). It listens to the audio directly and returns the
 * translated, timed sentences in ONE request, so the delay is lower than STT + translation.
 */
class AiClient(private val key: String) {
    companion object {
        const val FALLBACK = "gemini-flash-lite-latest"
        /** every model has its own free quota: when one is used up, the next answers */
        private val CHAIN = listOf("gemini-flash-lite-latest", "gemini-3.5-flash-lite", "gemini-3.1-flash-lite", "gemini-2.5-flash-lite", "gemini-flash-latest", "gemini-3.8-flash", "gemini-3.5-flash")
        private val coolUntil = java.util.concurrent.ConcurrentHashMap<String, Long>()
        private val keyIdx = java.util.concurrent.atomic.AtomicInteger(0)
        private val LATEX = Regex("""\\?b?egin\{[^}]*\}|\\?end\{[^}]*\}|\{[a-z]+\}""")
    }
    private val base = "https://generativelanguage.googleapis.com/v1beta/models/"
    @Volatile private var thinkingOff = false // current Flash-Lite answers fast without it

    /** Audio chunk -> timed sentences, already translated into the target language. */
    fun listen(wav: ByteArray, model: String, target: Lang, sourceHint: String?, history: List<String>): Stt {
        val style = if (target.code == "ar")
            "clear, natural Modern Standard Arabic exactly like professional Arabic TV/cinema subtitles"
        else "natural, fluent ${target.english} subtitles"
        val prompt = buildString {
            append("You are a professional live subtitler for TV movies and series. ")
            append("Listen to this short audio clip from a TV channel.\n")
            if (sourceHint != null) append("The dialogue language is most likely: $sourceHint.\n")
            append("1) Transcribe ONLY the spoken dialogue. Ignore music, song lyrics, singing, sound effects, laughter and background noise.\n")
            append("2) Split it into short subtitle sentences with start and end time in seconds from the clip start.\n")
            append("3) Translate each sentence into $style. Keep names as they sound. Max ~42 characters per row, at most 2 rows.\n")
            append("4) If consecutive sentences are spoken by DIFFERENT people (question/answer, reply), start each of those translations with \"- \".\n")
            append("5) Set \"ad\":true if this clip is NOT the programme itself: a commercial/advert, channel promo, trailer, sponsor or announcer message (product or brand names, prices, offers, phone numbers, websites, 'coming up', jingles).\n")
            append("If there is no speech, return an empty list. Never invent words.\n")
            if (history.isNotEmpty()) {
                append("Previous subtitle lines for context only (do not repeat them):\n")
                history.forEach { append("- ").append(it).append('\n') }
            }
            append("Return JSON: {\"lang\":\"<ISO 639-1 of the speech>\",\"ad\":false,\"lines\":[{\"start\":0.0,\"end\":1.5,\"src\":\"original\",\"tr\":\"translation\"}]}")
        }
        val parts = JSONArray()
            .put(JSONObject().put("inline_data", JSONObject()
                .put("mime_type", "audio/wav")
                .put("data", Base64.encodeToString(wav, Base64.NO_WRAP))))
            .put(JSONObject().put("text", prompt))
        val json = generate(model, parts, 0.2)
        val out = ArrayList<SttSeg>()
        val lines = json.optJSONArray("lines") ?: JSONArray()
        for (i in 0 until lines.length()) {
            val o = lines.optJSONObject(i) ?: continue
            val src = Filters.clean(o.optString("src")) ?: continue
            val tr = o.optString("tr").replace(LATEX, "").trim().trim('"', '«', '»', '“', '”').trim()
            if (tr.isEmpty()) continue
            val s = (o.optDouble("start", 0.0) * 1000).toLong().coerceAtLeast(0)
            val e = (o.optDouble("end", 0.0) * 1000).toLong().coerceAtLeast(s + 300)
            out.add(SttSeg(src, tr, s, e))
        }
        return Stt(out, json.optString("lang", ""), json.optBoolean("ad", false))
    }

    /** Several subtitle lines at once (for subtitle files in another language), kept 1:1. */
    fun translateLines(lines: List<String>, target: Lang, model: String): List<String> {
        val style = if (target.code == "ar")
            "clear, natural Modern Standard Arabic exactly like professional Arabic TV/cinema subtitles"
        else "natural, fluent ${target.english} subtitles"
        val prompt = buildString {
            append("Translate each numbered subtitle line of a movie/series into $style. ")
            append("Keep names as they sound, keep dialogue dashes, keep each line short. ")
            append("Return JSON {\"lines\":[...]} with exactly ${lines.size} strings, string i = translation of line i.\n\n")
            lines.forEachIndexed { i, l -> append(i + 1).append(". ").append(l.replace('\n', ' ')).append('\n') }
        }
        val json = generate(model, JSONArray().put(JSONObject().put("text", prompt)), 0.2)
        val arr = json.optJSONArray("lines") ?: return emptyList()
        val out = (0 until arr.length()).map { arr.optString(it).trim() }
        return if (out.size == lines.size) out else emptyList()
    }

    /** Text the camera shows (letter, note, phone message). */
    fun translateScreen(text: String, target: Lang, model: String): String {
        val prompt = "This text appears ON SCREEN on a TV channel: a caption naming a place, town, landmark or person, " +
            "a letter, note or sign the camera focuses on, or a phone message received by a character. Never news tickers. Translate it into ${target.english}, faithful and concise, " +
            "keeping message line breaks, max 3 short lines. If it is not meaningful readable text " +
            "(UI garbage, credits, logos, advertising) return an empty translation.\n" +
            "Return JSON: {\"tr\":\"...\"}\n\nText:\n$text"
        val json = generate(model, JSONArray().put(JSONObject().put("text", prompt)), 0.1)
        return json.optString("tr", "").trim()
    }

    /** Opens the TLS connection early so the first subtitle is not slowed by the handshake. */
    fun warmUp() {
        try {
            val c = URL("${base}gemini-flash-lite-latest?key=$key").openConnection() as HttpURLConnection
            c.connectTimeout = 6000; c.readTimeout = 6000
            c.inputStream.use { it.readBytes() }
        } catch (_: Exception) { }
    }

    private fun generate(model: String, parts: JSONArray, temperature: Double): JSONObject {
        val cfg = JSONObject()
            .put("temperature", temperature)
            .put("responseMimeType", "application/json")
            .put("maxOutputTokens", 4096)
        if (thinkingOff) cfg.put("thinkingConfig", JSONObject().put("thinkingBudget", 0)) // fastest answers
        val body = JSONObject()
            .put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", parts)))
            .put("generationConfig", cfg)
        val text = try {
            post(model, body)
        } catch (e: HttpError) {
            when {
                e.code == 400 && thinkingOff -> { // model without thinking control: retry plainly
                    thinkingOff = false
                    cfg.remove("thinkingConfig")
                    post(model, body)
                }
                // strong model busy or missing: the fast model answers instead
                e.code == 503 || e.code == 404 || e.code == 429 -> {
                    coolUntil[model] = System.currentTimeMillis() + (if (e.code == 404) 3600_000L else e.retryAfterSec * 1000L + 20_000L)
                    var last: Exception = e
                    var ok: String? = null
                    for (m in CHAIN) {
                        if (m == model || (coolUntil[m] ?: 0L) > System.currentTimeMillis()) continue
                        try { ok = post(m, body); break } catch (x: HttpError) {
                            last = x
                            coolUntil[m] = System.currentTimeMillis() + (if (x.code == 404) 3600_000L else x.retryAfterSec * 1000L + 20_000L)
                        }
                    }
                    ok ?: throw last
                }
                else -> throw e
            }
        }
        val res = JSONObject(text)
        val cand = res.optJSONArray("candidates")?.optJSONObject(0) ?: return JSONObject()
        val sb = StringBuilder()
        val ps = cand.optJSONObject("content")?.optJSONArray("parts") ?: JSONArray()
        for (i in 0 until ps.length()) sb.append(ps.optJSONObject(i)?.optString("text") ?: "")
        val raw = sb.toString().trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        return try { JSONObject(raw) } catch (_: Exception) { JSONObject() }
    }

    /** several keys (comma separated), each with its own free quota: when one is used up the next one answers */
    private val keys = key.split(',', ';', ' ', '\n').map { it.trim() }.filter { it.isNotEmpty() }.ifEmpty { listOf(key) }

    private fun post(model: String, body: JSONObject): String {
        var last: HttpError? = null
        for (n in keys.indices) {
            val i = (keyIdx.get() + n) % keys.size
            try {
                val r = postWith(keys[i], model, body)
                if (n > 0) keyIdx.set(i)
                return r
            } catch (e: HttpError) {
                if (e.code != 429 && e.code != 403 && e.code != 401) throw e // used-up or not-yet-active key: next one
                last = e
            }
        }
        throw last ?: HttpError(429, 15, "quota")
    }

    private fun postWith(apiKey: String, model: String, body: JSONObject): String {
        val c = URL("$base$model:generateContent").openConnection() as HttpURLConnection
        c.requestMethod = "POST"
        c.doOutput = true
        c.connectTimeout = 8000
        c.readTimeout = 30000 // the service is sometimes slow: the player waits for the text instead of losing it
        c.setRequestProperty("x-goog-api-key", apiKey)
        c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        try {
            c.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = c.responseCode
            if (code !in 200..299) {
                val ra = c.getHeaderField("retry-after")?.toDoubleOrNull()?.toInt() ?: if (code == 429) 15 else 6
                val err = c.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
                throw HttpError(code, ra.coerceIn(1, 60), err.take(300))
            }
            return c.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } catch (e: IOException) {
            c.disconnect(); throw e
        }
    }
}

object Wav {
    fun encode(pcm: ShortArray, rate: Int = 16000): ByteArray {
        val dataLen = pcm.size * 2
        val b = ByteBuffer.allocate(44 + dataLen).order(ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray()).putInt(36 + dataLen).put("WAVE".toByteArray())
        b.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1)
            .putInt(rate).putInt(rate * 2).putShort(2).putShort(16)
        b.put("data".toByteArray()).putInt(dataLen)
        for (s in pcm) b.putShort(s)
        return b.array()
    }
}

object Filters {
    private val bad = listOf(
        "thank you for watching", "thanks for watching", "please subscribe", "subscribe to",
        "amara.org", "subtitles by", "translated by", "captions by", "transcribed by",
        "ترجمة نانسي", "اشتركوا", "اشترك في القناة", "شكرا للمشاهدة", "شكراً للمشاهدة",
        "시청해주셔서", "ご視聴", "продолжение следует", "sous-titres",
    )
    private val bracket = Regex("""\[[^\]]*]|\([^)]*\)|\*[^*]*\*""")
    private val notes = Regex("[♪♫🎵🎶]+")
    private val spaces = Regex("\\s+")

    fun clean(t: String): String? {
        val s = t.replace(bracket, " ").replace(notes, " ").replace(spaces, " ").trim()
        if (s.length < 2) return null
        if (s.none { it.isLetter() }) return null
        val low = s.lowercase()
        if (bad.any { low.contains(it) }) return null
        val words = low.split(' ')
        if (words.size >= 6 && words.toSet().size * 4 <= words.size) return null // looped output
        return s
    }
}
