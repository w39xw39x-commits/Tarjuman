package com.tarjuman.tv

import android.content.Context
import android.util.Log
import java.io.BufferedInputStream
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.net.URLDecoder
import kotlin.concurrent.thread

data class Channel(val name: String, val url: String, val group: String, val logo: String?)

/** M3U / M3U8 channel list parser (the format used by IP Channels and IPTV apps). */
object M3u {
    private val attr = Regex("""([\w-]+)="([^"]*)"""")

    fun parse(text: String): List<Channel> {
        val out = ArrayList<Channel>()
        var name: String? = null; var group = ""; var logo: String? = null
        for (raw in text.replace("﻿", "").lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            if (line.startsWith("#EXTINF", true)) {
                val attrs = attr.findAll(line).associate { it.groupValues[1].lowercase() to it.groupValues[2] }
                name = line.substringAfterLast(',').trim().ifEmpty { attrs["tvg-name"] ?: "قناة" }
                group = attrs["group-title"] ?: ""
                logo = attrs["tvg-logo"]?.takeIf { it.startsWith("http") }
            } else if (line.startsWith("#EXTGRP", true)) {
                group = line.substringAfter(':').trim()
            } else if (!line.startsWith("#")) {
                if (line.startsWith("http") || line.startsWith("rtmp") || line.startsWith("rtsp")) {
                    out.add(Channel(name ?: "قناة ${out.size + 1}", line, group, logo))
                }
                name = null; logo = null
            }
        }
        return out
    }
}

/** Keeps the viewer's channel list on the device (never inside the app or online). */
object Playlist {
    private fun file(c: Context) = File(c.filesDir, "playlist.m3u")
    @Volatile private var cache: List<Channel>? = null

    fun channels(c: Context): List<Channel> {
        cache?.let { return it }
        val f = file(c)
        val own = receiverFile()
        val list = try {
            when {
                own != null -> M3u.parse(own.readText())
                f.exists() -> M3u.parse(f.readText())
                else -> emptyList()
            }
        } catch (_: Exception) { emptyList() }
        if (list.isNotEmpty()) cache = list
        return list
    }

    /** The receiver's own IP channel list (Download/M3U_TV.m3u), readable with all-files access. */
    fun receiverFile(): File? = try {
        val dir = File(android.os.Environment.getExternalStorageDirectory(), "Download")
        File(dir, "M3U_TV.m3u").takeIf { it.canRead() }
            ?: dir.listFiles { x -> x.name.endsWith(".m3u", true) && x.canRead() }?.maxByOrNull { it.length() }
    } catch (_: Exception) { null }

    fun reset() { cache = null }

    fun save(c: Context, text: String): Int {
        val list = M3u.parse(text)
        if (list.isEmpty()) return 0
        file(c).writeText(text)
        cache = list
        return list.size
    }

    /** Downloads a list from a link, saves it and remembers the link for later refreshes. */
    fun fromUrl(c: Context, url: String): Int {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 10000; conn.readTimeout = 30000
        conn.setRequestProperty("User-Agent", "Tarjuman/1.0")
        val text = conn.inputStream.use { String(it.readBytes(), Charsets.UTF_8) }
        val n = save(c, text)
        if (n > 0) c.getSharedPreferences("tarjuman", Context.MODE_PRIVATE).edit().putString("m3u_url", url).apply()
        return n
    }

    fun clear(c: Context) { file(c).delete(); cache = emptyList() }
}

/**
 * Tiny page served by the receiver on the home network: open it on the phone, paste the M3U link
 * or pick the .m3u file, and the list lands on the receiver. No typing with the remote.
 */
class LinkServer(private val ctx: Context, private val onSaved: (Int) -> Unit) {
    private var server: ServerSocket? = null
    @Volatile private var running = false
    val port = 8765

    fun address(): String? = try {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .firstOrNull { it is Inet4Address && !it.isLoopbackAddress }
            ?.hostAddress?.let { "http://$it:$port" }
    } catch (_: Exception) { null }

    fun start() {
        if (running) return
        running = true
        thread(name = "link-server", isDaemon = true) {
            try {
                val s = ServerSocket(port); server = s
                while (running) {
                    val client = try { s.accept() } catch (_: Exception) { break }
                    thread(isDaemon = true) { handle(client) }
                }
            } catch (e: Exception) { Log.w("Tarjuman", "link server", e) }
        }
    }

    fun stop() { running = false; try { server?.close() } catch (_: Exception) { } }

    private fun handle(sock: Socket) {
        sock.use { s ->
            s.soTimeout = 30000
            val inp = BufferedInputStream(s.getInputStream())
            val head = readHead(inp) ?: return
            val first = head.lineSequence().firstOrNull() ?: return
            val method = first.substringBefore(' ')
            val len = Regex("(?i)content-length:\\s*(\\d+)").find(head)?.groupValues?.get(1)?.toInt() ?: 0
            var message = ""
            if (method == "POST" && len in 1..20_000_000) {
                val body = ByteArray(len)
                var got = 0
                while (got < len) { val n = inp.read(body, got, len - got); if (n < 0) break; got += n }
                val ctype = Regex("(?i)content-type:\\s*([^\\r\\n]+)").find(head)?.groupValues?.get(1) ?: ""
                message = try { receive(ctype, body) } catch (e: Exception) { "تعذّر الحفظ: ${e.message}" }
            }
            val html = page(message)
            val bytes = html.toByteArray(Charsets.UTF_8)
            s.getOutputStream().apply {
                write("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
                write(bytes); flush()
            }
        }
    }

    private fun receive(ctype: String, body: ByteArray): String {
        val n: Int = if (ctype.contains("multipart/form-data")) {
            val boundary = ctype.substringAfter("boundary=").trim().trim('"')
            val text = String(body, Charsets.UTF_8)
            val parts = text.split("--$boundary")
            val filePart = parts.firstOrNull { it.contains("name=\"file\"") }
            val urlPart = parts.firstOrNull { it.contains("name=\"url\"") }
            val fileText = filePart?.substringAfter("\r\n\r\n")?.removeSuffix("\r\n")
            val urlText = urlPart?.substringAfter("\r\n\r\n")?.trim()
            when {
                !fileText.isNullOrBlank() && fileText.contains("http") -> Playlist.save(ctx, fileText)
                !urlText.isNullOrBlank() -> Playlist.fromUrl(ctx, urlText)
                else -> 0
            }
        } else {
            val form = String(body, Charsets.UTF_8).split('&').associate {
                URLDecoder.decode(it.substringBefore('='), "UTF-8") to URLDecoder.decode(it.substringAfter('=', ""), "UTF-8")
            }
            val url = form["url"]?.trim().orEmpty()
            if (url.isNotEmpty()) Playlist.fromUrl(ctx, url) else 0
        }
        if (n > 0) onSaved(n)
        return if (n > 0) "✓ تم حفظ $n قناة على الرسيفر" else "لم أجد قنوات في القائمة، تأكد من الرابط أو الملف"
    }

    private fun readHead(inp: InputStream): String? {
        val sb = StringBuilder()
        var last4 = 0
        while (sb.length < 16384) {
            val b = inp.read(); if (b < 0) return null
            sb.append(b.toChar())
            last4 = (last4 shl 8) or b
            if (last4 == 0x0D0A0D0A) return sb.toString()
        }
        return null
    }

    private fun page(msg: String) = """<!doctype html><html lang="ar" dir="rtl"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1"><title>ترجمان — إضافة القنوات</title>
<style>body{margin:0;font-family:system-ui,Tahoma,sans-serif;background:#0B1022;color:#fff;display:flex;justify-content:center}
main{max-width:460px;width:100%;padding:28px}h1{margin:0 0 6px;font-size:26px}p{color:#C3C9E6;line-height:1.7}
label{display:block;margin:18px 0 8px;font-weight:700}input[type=url],input[type=file]{width:100%;box-sizing:border-box;padding:14px;border-radius:12px;border:1px solid #39407a;background:#151C3C;color:#fff;font-size:16px}
button{margin-top:16px;width:100%;padding:15px;border:0;border-radius:14px;background:#FFC94A;color:#10142E;font-weight:800;font-size:18px}
.msg{margin-top:18px;padding:14px;border-radius:12px;background:#117A4A}.or{text-align:center;color:#A9B1D6;margin:20px 0 0}</style></head>
<body><main><h1>ترجمان</h1><p>أضف قائمة قنوات IPTV إلى الرسيفر.</p>
${if (msg.isNotEmpty()) "<div class=msg>$msg</div>" else ""}
<form method="post" enctype="multipart/form-data"><label>رابط القائمة (M3U)</label>
<input type="url" name="url" placeholder="http://...">
<p class="or">أو</p><label>ملف القائمة</label><input type="file" name="file" accept=".m3u,.m3u8,audio/x-mpegurl,*/*">
<button type="submit">حفظ في الرسيفر</button></form></main></body></html>"""
}
