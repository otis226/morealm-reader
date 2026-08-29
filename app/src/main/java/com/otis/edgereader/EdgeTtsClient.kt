package com.otis.edgereader

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class EdgeTtsClient {
    data class WordBoundary(
        val text: String,
        val offsetMs: Long,
        val durationMs: Long,
    )

    data class SynthesisResult(
        val audio: ByteArray,
        val words: List<WordBoundary>,
    )

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    @Volatile
    private var clockSkewSeconds: Long = 0L

    fun synthesize(text: String, voice: String, speed: Float, pitchHz: Int): SynthesisResult {
        var last: Throwable? = null
        repeat(3) { attempt ->
            try {
                return synthesizeOnce(text, voice, speed, pitchHz)
            } catch (e: EdgeException) {
                last = e
                if ((e.code == 401 || e.code == 403) && adjustClock(e.serverDate)) {
                    // retry with corrected clock
                } else if (attempt == 2) {
                    throw e
                }
            } catch (t: Throwable) {
                last = t
                if (attempt == 2) throw t
            }
            Thread.sleep(220L * (attempt + 1))
        }
        throw last ?: IllegalStateException("Edge TTS không phản hồi")
    }

    private fun synthesizeOnce(
        text: String,
        voice: String,
        speed: Float,
        pitchHz: Int,
    ): SynthesisResult {
        val requestId = UUID.randomUUID().toString().replace("-", "")
        val connectionId = UUID.randomUUID().toString().replace("-", "")
        val gec = generateSecMsGec()
        val url = "$WSS_URL&ConnectionId=$connectionId&Sec-MS-GEC=$gec&Sec-MS-GEC-Version=$SEC_MS_GEC_VERSION"
        val muid = UUID.randomUUID().toString().replace("-", "").uppercase(Locale.US)

        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Origin", ORIGIN)
            .header("Pragma", "no-cache")
            .header("Cache-Control", "no-cache")
            .header("Accept-Language", "en-US,en;q=0.9")
            .header("Cookie", "muid=$muid;")
            .build()

        val done = CountDownLatch(1)
        val audio = ByteArrayOutputStream()
        val words = Collections.synchronizedList(mutableListOf<WordBoundary>())
        val error = AtomicReference<Throwable?>(null)

        val socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                val config = "X-Timestamp:${edgeTimestamp()}\r\n" +
                    "Content-Type:application/json; charset=utf-8\r\n" +
                    "Path:speech.config\r\n\r\n" +
                    "{\"context\":{\"synthesis\":{\"audio\":{\"metadataoptions\":{\"sentenceBoundaryEnabled\":\"false\",\"wordBoundaryEnabled\":\"true\"},\"outputFormat\":\"audio-24khz-48kbitrate-mono-mp3\"}}}}\r\n"
                webSocket.send(config)

                val pct = ((speed.coerceIn(0.5f, 2.0f) - 1f) * 100f).toInt()
                val rate = if (pct >= 0) "+$pct%" else "$pct%"
                val pitch = if (pitchHz >= 0) "+${pitchHz}Hz" else "${pitchHz}Hz"
                val ssml = "<speak version='1.0' xmlns='http://www.w3.org/2001/10/synthesis' xml:lang='en-US'>" +
                    "<voice name='$voice'><prosody pitch='$pitch' rate='$rate' volume='+0%'>" +
                    escapeXml(cleanText(text)) +
                    "</prosody></voice></speak>"
                val message = "X-RequestId:$requestId\r\n" +
                    "Content-Type:application/ssml+xml\r\n" +
                    "X-Timestamp:${edgeTimestamp()}Z\r\n" +
                    "Path:ssml\r\n\r\n$ssml"
                webSocket.send(message)
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                val data = bytes.toByteArray()
                if (data.size < 3) return
                val headerLen = ((data[0].toInt() and 0xff) shl 8) or (data[1].toInt() and 0xff)
                val payloadStart = headerLen + 2
                if (headerLen <= 0 || payloadStart > data.size) return
                val header = runCatching { String(data, 2, headerLen, Charsets.UTF_8) }.getOrDefault("")
                if (header.contains("Path:audio", ignoreCase = true) && payloadStart < data.size) {
                    synchronized(audio) { audio.write(data, payloadStart, data.size - payloadStart) }
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (text.contains("Path:audio.metadata", ignoreCase = true)) {
                    parseWordMetadata(text, words)
                }
                if (text.contains("Path:turn.end", ignoreCase = true)) {
                    done.countDown()
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                error.compareAndSet(
                    null,
                    EdgeException(
                        response?.code ?: -1,
                        response?.header("Date"),
                        t.message ?: "WebSocket lỗi",
                        t,
                    )
                )
                done.countDown()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                done.countDown()
            }
        })

        if (!done.await(55, TimeUnit.SECONDS)) {
            socket.cancel()
            throw IllegalStateException("Edge TTS quá thời gian chờ")
        }
        socket.cancel()
        error.get()?.let { throw it }
        val result = synchronized(audio) { audio.toByteArray() }
        if (result.isEmpty()) {
            throw IllegalStateException("Edge không trả âm thanh cho voice $voice")
        }
        val boundarySnapshot = synchronized(words) { words.toList() }
            .sortedBy { it.offsetMs }
        return SynthesisResult(result, boundarySnapshot)
    }

    private fun parseWordMetadata(message: String, out: MutableList<WordBoundary>) {
        runCatching {
            val separator = message.indexOf("\r\n\r\n")
            if (separator < 0 || separator + 4 >= message.length) return
            val body = message.substring(separator + 4).trim()
            if (body.isBlank()) return
            val metadata = JSONObject(body).optJSONArray("Metadata") ?: return
            for (i in 0 until metadata.length()) {
                val item = metadata.optJSONObject(i) ?: continue
                if (!item.optString("Type").equals("WordBoundary", ignoreCase = true)) continue
                val data = item.optJSONObject("Data") ?: continue
                val word = data.optJSONObject("text")?.optString("Text").orEmpty()
                if (word.isBlank()) continue
                out.add(
                    WordBoundary(
                        text = word,
                        offsetMs = data.optLong("Offset", 0L) / 10_000L,
                        durationMs = data.optLong("Duration", 0L) / 10_000L,
                    )
                )
            }
        }
    }

    private fun cleanText(input: String): String = buildString(input.length) {
        input.forEach { c ->
            val code = c.code
            append(if ((code in 0..8) || (code in 11..12) || (code in 14..31)) ' ' else c)
        }
    }

    private fun escapeXml(s: String): String = s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")

    private fun edgeTimestamp(): String {
        val fmt = SimpleDateFormat("EEE MMM dd yyyy HH:mm:ss 'GMT+0000 (Coordinated Universal Time)'", Locale.US)
        fmt.timeZone = TimeZone.getTimeZone("UTC")
        return fmt.format(Date(System.currentTimeMillis() + clockSkewSeconds * 1000L))
    }

    private fun generateSecMsGec(): String {
        var seconds = System.currentTimeMillis() / 1000L + clockSkewSeconds + WINDOWS_EPOCH
        seconds -= seconds % 300L
        val ticks = seconds * 10_000_000L
        val payload = "$ticks$TRUSTED_CLIENT_TOKEN"
        val digest = MessageDigest.getInstance("SHA-256").digest(payload.toByteArray(Charsets.US_ASCII))
        return digest.joinToString("") { "%02X".format(it.toInt() and 0xff) }
    }

    private fun adjustClock(serverDate: String?): Boolean {
        if (serverDate.isNullOrBlank()) return false
        return try {
            val fmt = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US)
            fmt.timeZone = TimeZone.getTimeZone("UTC")
            val server = fmt.parse(serverDate)?.time ?: return false
            clockSkewSeconds += (server - System.currentTimeMillis()) / 1000L
            true
        } catch (_: Throwable) {
            false
        }
    }

    private class EdgeException(
        val code: Int,
        val serverDate: String?,
        message: String,
        cause: Throwable? = null,
    ) : RuntimeException("Edge TTS lỗi${if (code > 0) " HTTP $code" else ""}: $message", cause)

    companion object {
        private const val TRUSTED_CLIENT_TOKEN = "6A5AA1D4EAFF4E9FB37E23D68491D6F4"
        private const val WSS_URL = "wss://speech.platform.bing.com/consumer/speech/synthesize/readaloud/edge/v1?TrustedClientToken=$TRUSTED_CLIENT_TOKEN"
        private const val SEC_MS_GEC_VERSION = "1-143.0.3650.75"
        private const val WINDOWS_EPOCH = 11_644_473_600L
        private const val ORIGIN = "chrome-extension://jdiccldimpdaibmpdkjnbmckianbfold"
        private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/143.0.0.0 Safari/537.36 Edg/143.0.0.0"
    }
}
