package com.tarjuman.tv

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder

class HttpError(val code: Int, val retryAfterSec: Int, msg: String) : IOException("HTTP $code: $msg")

/** One spoken sentence inside a chunk, with its timing relative to the chunk start. */
data class SttSeg(val text: String, val startMs: Long, val endMs: Long)
data class Stt(val segs: List<SttSeg>, val language: String) {
    val text: String get() = segs.joinToString(" ") { it.text }
}

/** Speech-to-text (Whisper) and translation (LLM) over Groq's OpenAI-compatible API. */
class GroqClient(private val key: String) {
    private val base = "https://api.groq.com/openai/v1"

    fun transcribe(wav: ByteArray, model: String, lang: String?, prompt: String): Stt {
        val boundary = "----tarjuman" + System.nanoTime()
        val conn = open("$base/audio/transcriptions", "multipart/form-data; boundary=$boundary", 15000)
        conn.outputStream.use { os ->
            fun field(name: String, value: String) {
                os.w("--$boundary\r\nContent-Disposition: form-data; name=\"$name\"\r\n\r\n$value\r\n")
            }
            field("model", model)
            field("response_format", "verbose_json")
            field("temperature", "0")
            if (lang != null) field("language", lang)
            if (prompt.isNotBlank()) field("prompt", prompt)
            os.w("--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"a.wav\"\r\nContent-Type: audio/wav\r\n\r\n")
            os.write(wav)
            os.w("\r\n--$boundary--\r\n")
        }
        val json = JSONObject(read(conn))
        val arr = json.optJSONArray("segments")
        val out = ArrayList<SttSeg>()
        if (arr != null) {
            for (i in 0 until arr.length()) {
                val g = arr.getJSONObject(i)
                val noSpeech = g.optDouble("no_speech_prob", 0.0)
                val logProb = g.optDouble("avg_logprob", 0.0)
                val compression = g.optDouble("compression_ratio", 1.0)
                // Drop what Whisper itself is unsure about: music, noise, hallucinations.
                if (noSpeech > 0.6 && logProb < -0.6) continue
                if (logProb < -1.15) continue
                if (compression > 2.4) continue
                val t = Filters.clean(g.optString("text")) ?: continue
                out.add(SttSeg(t, (g.optDouble("start", 0.0) * 1000).toLong(), (g.optDouble("end", 0.0) * 1000).toLong()))
            }
        } else {
            Filters.clean(json.optString("text"))?.let { out.add(SttSeg(it, 0, 0)) }
        }
        return Stt(out, json.optString("language", ""))
    }

    /**
     * Translates several lines in one request (faster) and keeps them aligned 1:1.
     * Dialogue between different speakers comes back with a leading "- " per line.
     */
    fun translateLines(lines: List<String>, history: List<String>, target: Lang, model: String): List<String> {
        val style = if (target.code == "ar")
            "Write clear, natural Modern Standard Arabic exactly like professional Arabic TV/cinema subtitles."
        else "Write natural, fluent subtitles."
        val sys = "You are a professional subtitle translator for live TV movies and series. " +
            "Translate each numbered input line into ${target.english}. $style " +
            "Return JSON {\"lines\":[...]} with exactly ${lines.size} strings; string i is the translation of line i. " +
            "Keep each line short (max ~42 characters per row, at most 2 rows). Keep names as they sound. " +
            "If consecutive lines are an exchange between DIFFERENT speakers (question/answer, reply, interruption), " +
            "start each of those lines with \"- \". A single speaker's continued sentence gets no dash. " +
            "Use previous lines only as context, never translate them. " +
            "For noise, song lyrics or meaningless input return an empty string for that line."
        val user = buildString {
            if (history.isNotEmpty()) {
                append("Previous lines (context only):\n")
                history.forEach { append("- ").append(it).append('\n') }
                append('\n')
            }
            append("Lines to translate:\n")
            lines.forEachIndexed { i, l -> append(i + 1).append(". ").append(l).append('\n') }
        }
        val body = JSONObject()
            .put("model", model)
            .put("temperature", 0.2)
            .put("max_tokens", 120 + lines.size * 80)
            .put("response_format", JSONObject().put("type", "json_object"))
            .put(
                "messages", JSONArray()
                    .put(JSONObject().put("role", "system").put("content", sys))
                    .put(JSONObject().put("role", "user").put("content", user))
            )
        val conn = open("$base/chat/completions", "application/json; charset=utf-8", 12000)
        conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
        val json = JSONObject(read(conn))
        val content = json.getJSONArray("choices").getJSONObject(0).getJSONObject("message").optString("content", "{}")
        val arr = try { JSONObject(content).optJSONArray("lines") } catch (_: Exception) { null }
        val res = ArrayList<String>()
        if (arr != null) for (i in 0 until arr.length()) res.add(arr.optString(i).trim().trim('"', '«', '»', '“', '”').trim())
        if (res.size == lines.size) return res
        // model did not keep the count: show everything as one line rather than misalign
        val joined = res.joinToString(" ").trim()
        return List(lines.size) { if (it == 0) joined else "" }
    }

    /** Text the camera shows (letter, note, phone message): short, faithful subtitle translation. */
    fun translateScreen(text: String, target: Lang, model: String): String {
        val sys = "You translate text that appears ON SCREEN in a movie or series (a letter, note, sign the camera focuses on, " +
            "or a phone message received by a character) into ${target.english}. " +
            "Output ONLY the translation, faithful and concise, keeping line breaks of a message. Max 3 short lines. " +
            "If it is not meaningful readable text (UI garbage, credits, logos, advertising), output exactly: -"
        val body = JSONObject()
            .put("model", model).put("temperature", 0.1).put("max_tokens", 200)
            .put("messages", JSONArray()
                .put(JSONObject().put("role", "system").put("content", sys))
                .put(JSONObject().put("role", "user").put("content", text)))
        val conn = open("$base/chat/completions", "application/json; charset=utf-8", 12000)
        conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
        val json = JSONObject(read(conn))
        return json.getJSONArray("choices").getJSONObject(0).getJSONObject("message")
            .optString("content", "").trim().trim('"', '«', '»').trim()
    }

    private fun open(url: String, contentType: String, readTimeout: Int): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = "POST"
        c.doOutput = true
        c.connectTimeout = 8000
        c.readTimeout = readTimeout
        c.setRequestProperty("Authorization", "Bearer $key")
        c.setRequestProperty("Content-Type", contentType)
        return c
    }

    /** Opens the TLS connection early so the first subtitle is not slowed by the handshake. */
    fun warmUp() {
        try {
            val c = URL("$base/models").openConnection() as HttpURLConnection
            c.connectTimeout = 6000; c.readTimeout = 6000
            c.setRequestProperty("Authorization", "Bearer $key")
            c.inputStream.use { it.readBytes() }
        } catch (_: Exception) { }
    }

    private fun read(c: HttpURLConnection): String {
        try {
            val code = c.responseCode
            if (code !in 200..299) {
                val ra = c.getHeaderField("retry-after")?.toDoubleOrNull()?.toInt() ?: 6
                val err = c.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
                throw HttpError(code, ra.coerceIn(1, 60), err.take(200))
            }
            return c.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } catch (e: IOException) {
            c.disconnect(); throw e
        }
    }

    private fun OutputStream.w(s: String) = write(s.toByteArray(Charsets.UTF_8))
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
