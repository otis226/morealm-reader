package com.otis.edgereader.core.tts.edge

import com.otis.edgereader.core.tts.TtsAudio
import com.otis.edgereader.core.tts.TtsCancelledException
import com.otis.edgereader.core.tts.TtsEngine
import com.otis.edgereader.core.tts.TtsRequest
import com.otis.edgereader.core.tts.WordBoundary
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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Edge read-aloud adapter following the protocol used by edge-tts 7.2.x.
 * Generation ids are monotonic; cancelling generation N also cancels older work.
 */
class EdgeTtsEngine : TtsEngine, AutoCloseable {
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    private val activeGenerations = ConcurrentHashMap.newKeySet<Long>()
    private val cancelThrough = AtomicLong(0L)
    private val clockSkewSeconds = AtomicLong(0L)

    override fun synthesize(request: TtsRequest): TtsAudio {
        if (isCancelled(request.generation)) throw TtsCancelledException()
        activeGenerations += request.generation
        try {
            var last: Throwable? = null
            repeat(MAX_ATTEMPTS) { attempt ->
                if (isCancelled(request.generation)) throw TtsCancelledException()
                try {
                    return synthesizeOnce(request)
                } catch (cancelled: TtsCancelledException) {
                    throw cancelled
                } catch (e: EdgeProtocolException) {
                    last = e
                    if ((e.code == 401 || e.code == 403) && adjustClock(e.serverDate)) {
                        // retry after correcting local/server clock skew
                    } else if (attempt == MAX_ATTEMPTS - 1) {
                        throw e
                    }
                } catch (t: Throwable) {
                    last = t
                    if (attempt == MAX_ATTEMPTS - 1) throw t
                }
                sleepCancellable(BACKOFF_MS * (attempt + 1), request.generation)
            }
            throw last ?: IllegalStateException("Edge TTS không phản hồi")
        } finally {
            activeGenerations -= request.generation
        }
    }

    override fun cancelGeneration(generation: Long) {
        cancelThrough.accumulateAndGet(generation) { current, candidate -> maxOf(current, candidate) }
    }

    override fun cancelAll() {
        activeGenerations.maxOrNull()?.let(::cancelGeneration)
    }

    override fun close() {
        cancelAll()
        client.dispatcher.executorService.shutdownNow()
        client.connectionPool.evictAll()
    }

    private fun synthesizeOnce(request: TtsRequest): TtsAudio {
        val requestId = connectionId()
        val url = "$WSS_URL&ConnectionId=${connectionId()}&Sec-MS-GEC=${generateSecMsGec()}&Sec-MS-GEC-Version=$SEC_MS_GEC_VERSION"
        val requestBuilder = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Origin", ORIGIN)
            .header("Pragma", "no-cache")
            .header("Cache-Control", "no-cache")
            .header("Accept-Language", "en-US,en;q=0.9")
            .header("Cookie", "muid=${connectionId().uppercase(Locale.US)};")

        val done = CountDownLatch(1)
        val audio = ByteArrayOutputStream()
        val words = Collections.synchronizedList(mutableListOf<WordBoundary>())
        val error = AtomicReference<Throwable?>(null)

        val socket = client.newWebSocket(requestBuilder.build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (isCancelled(request.generation)) {
                    webSocket.cancel()
                    done.countDown()
                    return
                }
                webSocket.send(speechConfigMessage())
                webSocket.send(ssmlMessage(requestId, request))
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                val data = bytes.toByteArray()
                if (data.size < 3) return
                val headerLength = ((data[0].toInt() and 0xff) shl 8) or (data[1].toInt() and 0xff)
                val payloadStart = headerLength + 2
                if (headerLength <= 0 || payloadStart > data.size) return
                val header = runCatching { String(data, 2, headerLength, Charsets.UTF_8) }.getOrDefault("")
                if (header.contains("Path:audio", ignoreCase = true) && payloadStart < data.size) {
                    synchronized(audio) { audio.write(data, payloadStart, data.size - payloadStart) }
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val separator = text.indexOf("\r\n\r\n")
                val header = if (separator >= 0) text.substring(0, separator) else text
                if (header.contains("Path:audio.metadata", ignoreCase = true) && separator >= 0) {
                    parseWordMetadata(text.substring(separator + 4), words)
                }
                if (header.contains("Path:turn.end", ignoreCase = true)) done.countDown()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (!isCancelled(request.generation)) {
                    error.compareAndSet(
                        null,
                        EdgeProtocolException(
                            code = response?.code ?: -1,
                            serverDate = response?.header("Date"),
                            message = t.message ?: "WebSocket lỗi",
                            cause = t,
                        )
                    )
                }
                done.countDown()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                done.countDown()
            }
        })

        val deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(RECEIVE_TIMEOUT_SECONDS)
        while (true) {
            if (isCancelled(request.generation)) {
                socket.cancel()
                throw TtsCancelledException()
            }
            if (done.await(CANCEL_POLL_MS, TimeUnit.MILLISECONDS)) break
            if (System.nanoTime() >= deadlineNanos) {
                socket.cancel()
                throw IllegalStateException("Edge TTS quá thời gian chờ")
            }
        }
        socket.cancel()
        if (isCancelled(request.generation)) throw TtsCancelledException()
        error.get()?.let { throw it }

        val audioBytes = synchronized(audio) { audio.toByteArray() }
        if (audioBytes.isEmpty()) {
            throw IllegalStateException("Edge không trả âm thanh cho voice ${request.voice}")
        }
        val boundaries = synchronized(words) { words.toList() }.sortedBy { it.offsetMs }
        return TtsAudio(audioBytes, boundaries, request.generation)
    }

    private fun speechConfigMessage(): String =
        "X-Timestamp:${edgeTimestamp()}\r\n" +
            "Content-Type:application/json; charset=utf-8\r\n" +
            "Path:speech.config\r\n\r\n" +
            "{\"context\":{\"synthesis\":{\"audio\":{\"metadataoptions\":{\"sentenceBoundaryEnabled\":\"false\",\"wordBoundaryEnabled\":\"true\"},\"outputFormat\":\"audio-24khz-48kbitrate-mono-mp3\"}}}}\r\n"

    private fun ssmlMessage(requestId: String, request: TtsRequest): String {
        val percent = ((request.speed.coerceIn(0.5f, 2.0f) - 1f) * 100f).toInt()
        val rate = if (percent >= 0) "+$percent%" else "$percent%"
        val pitch = if (request.pitchHz >= 0) "+${request.pitchHz}Hz" else "${request.pitchHz}Hz"
        val ssml = "<speak version='1.0' xmlns='http://www.w3.org/2001/10/synthesis' xml:lang='en-US'>" +
            "<voice name='${request.voice}'><prosody pitch='$pitch' rate='$rate' volume='+0%'>" +
            escapeXml(cleanText(request.text)) +
            "</prosody></voice></speak>"
        return "X-RequestId:$requestId\r\n" +
            "Content-Type:application/ssml+xml\r\n" +
            "X-Timestamp:${edgeTimestamp()}Z\r\n" +
            "Path:ssml\r\n\r\n$ssml"
    }

    private fun parseWordMetadata(body: String, out: MutableList<WordBoundary>) {
        runCatching {
            val metadata = JSONObject(body.trim()).optJSONArray("Metadata") ?: return
            for (i in 0 until metadata.length()) {
                val item = metadata.optJSONObject(i) ?: continue
                if (!item.optString("Type").equals("WordBoundary", ignoreCase = true)) continue
                val data = item.optJSONObject("Data") ?: continue
                val word = data.optJSONObject("text")?.optString("Text").orEmpty()
                if (word.isBlank()) continue
                out += WordBoundary(
                    text = word,
                    offsetMs = data.optLong("Offset", 0L) / 10_000L,
                    durationMs = data.optLong("Duration", 0L) / 10_000L,
                )
            }
        }
    }

    private fun isCancelled(generation: Long): Boolean = generation <= cancelThrough.get()

    private fun sleepCancellable(totalMs: Long, generation: Long) {
        var remaining = totalMs
        while (remaining > 0) {
            if (isCancelled(generation)) throw TtsCancelledException()
            val step = minOf(50L, remaining)
            Thread.sleep(step)
            remaining -= step
        }
    }

    private fun cleanText(input: String): String = buildString(input.length) {
        input.forEach { c ->
            val code = c.code
            append(if ((code in 0..8) || (code in 11..12) || (code in 14..31)) ' ' else c)
        }
    }

    private fun escapeXml(value: String): String = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")

    private fun connectionId(): String = UUID.randomUUID().toString().replace("-", "")

    private fun edgeTimestamp(): String {
        val format = SimpleDateFormat(
            "EEE MMM dd yyyy HH:mm:ss 'GMT+0000 (Coordinated Universal Time)'",
            Locale.US,
        )
        format.timeZone = TimeZone.getTimeZone("UTC")
        return format.format(Date(System.currentTimeMillis() + clockSkewSeconds.get() * 1000L))
    }

    private fun generateSecMsGec(): String {
        var seconds = System.currentTimeMillis() / 1000L + clockSkewSeconds.get() + WINDOWS_EPOCH
        seconds -= seconds % 300L
        val ticks = seconds * 10_000_000L
        val payload = "$ticks$TRUSTED_CLIENT_TOKEN"
        val digest = MessageDigest.getInstance("SHA-256").digest(payload.toByteArray(Charsets.US_ASCII))
        return digest.joinToString("") { "%02X".format(it.toInt() and 0xff) }
    }

    private fun adjustClock(serverDate: String?): Boolean {
        if (serverDate.isNullOrBlank()) return false
        return try {
            val format = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US)
            format.timeZone = TimeZone.getTimeZone("UTC")
            val serverTime = format.parse(serverDate)?.time ?: return false
            clockSkewSeconds.addAndGet((serverTime - System.currentTimeMillis()) / 1000L)
            true
        } catch (_: Throwable) {
            false
        }
    }

    private class EdgeProtocolException(
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
        private const val MAX_ATTEMPTS = 3
        private const val BACKOFF_MS = 220L
        private const val RECEIVE_TIMEOUT_SECONDS = 55L
        private const val CANCEL_POLL_MS = 100L
    }
}
