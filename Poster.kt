package com.tarjuman.tv

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** When a film / series is recognised, shows its poster, name and rating for a few seconds. */
object Poster {
    @Volatile private var lastId = ""

    fun show(sink: SubSink?, id: String, fallbackName: String) {
        val imdb = id.substringBefore(':')
        if (sink == null || !imdb.startsWith("tt") || imdb == lastId) return
        lastId = imdb
        val type = if (id.contains(':')) "series" else "movie"
        try {
            val meta = JSONObject(text("https://v3-cinemeta.strem.io/meta/$type/$imdb.json") ?: return)
                .optJSONObject("meta") ?: return
            val name = meta.optString("name").ifBlank { fallbackName.substringBefore(" S") }
            val rating = meta.optString("imdbRating").takeIf { it.isNotBlank() && it != "0" }
            val year = meta.optString("releaseInfo").ifBlank { meta.optString("year") }.takeIf { it.isNotBlank() }
            val genres = meta.optJSONArray("genres")?.let { a ->
                (0 until minOf(2, a.length())).joinToString(" · ") { a.optString(it) }
            }?.takeIf { it.isNotBlank() }
            val line = listOfNotNull(rating?.let { "★ $it IMDb" }, year, genres).joinToString("   ")
            val bmp = meta.optString("poster").takeIf { it.startsWith("http") }?.let { image(it) }
            sink.card(bmp, name, line, 8000)
        } catch (_: Exception) { }
    }

    private fun open(u: String): HttpURLConnection = (URL(u).openConnection() as HttpURLConnection).apply {
        connectTimeout = 6000; readTimeout = 8000; instanceFollowRedirects = true
        setRequestProperty("User-Agent", "Tarjuman/1.0")
    }

    private fun text(u: String): String? = try {
        val c = open(u); if (c.responseCode != 200) null else c.inputStream.bufferedReader().use { it.readText() }
    } catch (_: Exception) { null }

    private fun image(u: String): Bitmap? = try {
        val c = open(u)
        if (c.responseCode != 200) null else c.inputStream.use { s ->
            val o = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.RGB_565 }
            BitmapFactory.decodeStream(s, null, o)
        }
    } catch (_: Exception) { null }
}
