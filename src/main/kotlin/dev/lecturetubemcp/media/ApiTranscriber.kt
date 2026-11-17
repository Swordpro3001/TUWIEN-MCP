package dev.lecturetubemcp.media

import dev.lecturetubemcp.Config
import dev.lecturetubemcp.api.Cue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.future.await
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import java.io.ByteArrayOutputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Path
import java.time.Duration
import java.util.UUID
import kotlin.math.max

/**
 * Speech-to-text through an OpenAI-compatible API (Groq, OpenAI, or a self-hosted server).
 *
 * The local machine only decodes the audio once to find speech (a few seconds of one CPU core per lecture hour),
 * cuts it at pauses into chunks of up to [maxChunkSec], and uploads each chunk as 16 kHz mono FLAC (the format Groq
 * recommends: lossless and small). Long silences are not uploaded: they cost money and make Whisper hallucinate.
 */
class ApiTranscriber(private val cfg: ApiConfig, private val maxChunkSec: Double = 600.0, private val splitGapSec: Double = 10.0) {
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build()
    private val json = Json { ignoreUnknownKeys = true }

    /** Speech chunks [start, end] in seconds within [a, b]; empty if the range contains no speech. */
    suspend fun plan(ffmpeg: String, media: Path, a: Double, b: Double): List<List<Double>> = withContext(Dispatchers.IO) {
        val p = ProcessBuilder(
            ffmpeg, "-nostdin", "-hide_banner", "-loglevel", "error", "-ss", "$a", "-i", media.toString(), "-t", "${b - a}",
            "-vn", "-ac", "1", "-ar", "${Speech.RATE}", "-f", "s16le", "-",
        ).redirectError(ProcessBuilder.Redirect.DISCARD).start()
        val meter = Speech.LevelMeter()
        p.inputStream.buffered(1 shl 20).use { input ->
            val buf = ByteArray(1 shl 16)
            val f = FloatArray(buf.size / 2)
            while (true) {
                val n = input.readNBytes(buf, 0, buf.size)
                if (n <= 0) break
                val sb = ByteBuffer.wrap(buf, 0, n - n % 2).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                val cnt = sb.remaining()
                for (i in 0 until cnt) f[i] = sb.get(i) / 32768f
                meter.feed(f, cnt)
            }
        }
        if (p.waitFor() != 0) throw MediaException("ffmpeg could not decode the audio.")
        chunks(Speech.windowsFromLevels(meter.levels()), a)
    }

    /** Groups speech windows into upload chunks: split at silences longer than [splitGapSec] or at [maxChunkSec]. */
    fun chunks(windows: List<Speech.Window>, offsetSec: Double): List<List<Double>> {
        val out = mutableListOf<List<Double>>()
        var cs = -1.0; var ce = -1.0
        for (w in windows) {
            val s = offsetSec + w.start.toDouble() / Speech.RATE
            val e = offsetSec + w.end.toDouble() / Speech.RATE
            if (cs < 0) { cs = s; ce = e; continue }
            if (s - ce > splitGapSec || e - cs > maxChunkSec) { out += listOf(cs, ce); cs = s; ce = e } else ce = e
        }
        if (cs >= 0) out += listOf(cs, ce)
        // Very long single windows (no pause at all) are cut into pieces of maxChunkSec.
        return out.flatMap { (s, e) ->
            if (e - s <= maxChunkSec) listOf(listOf(s, e))
            else generateSequence(s) { it + maxChunkSec }.takeWhile { it < e }.map { listOf(it, minOf(it + maxChunkSec, e)) }.toList()
        }
    }

    /** Transcribes all [parts] that are not complete yet, [parallel] at a time. Returns billed audio seconds. */
    suspend fun run(job: TranscriptionJob, ffmpeg: String, media: Path, lang: String) = withContext(Dispatchers.IO) {
        val gate = Semaphore(cfg.parallel)
        job.parts.filterNot { it.complete }.map { part ->
            async {
                gate.withPermit {
                    val cues = transcribeChunk(ffmpeg, media, part.start, part.end, lang, job.title)
                    // Written in one go: the file only exists once the chunk is done (= complete).
                    java.nio.file.Files.writeString(
                        part.out,
                        "WEBVTT\n\n" + cues.joinToString("") { "${Transcriber.vttTs(it.startMs)} --> ${Transcriber.vttTs(it.endMs)}\n${it.text}\n\n" },
                    )
                    part.doneUpTo = part.end
                }
            }
        }.awaitAll()
        job.parts.sumOf { max(10.0, it.end - it.start) }
    }

    private suspend fun encode(ffmpeg: String, media: Path, s: Double, e: Double, codec: String): ByteArray {
        val args = when (codec) {
            "flac" -> listOf("-c:a", "flac", "-f", "flac")
            else -> listOf("-c:a", "libopus", "-b:a", "32k", "-f", "ogg")
        }
        val cmd = listOf(ffmpeg, "-nostdin", "-hide_banner", "-loglevel", "error", "-ss", "$s", "-i", media.toString(), "-t", "${e - s}", "-vn", "-map", "0:a:0", "-ac", "1", "-ar", "16000") + args + "-"
        return withContext(Dispatchers.IO) {
            val p = ProcessBuilder(cmd).redirectError(ProcessBuilder.Redirect.DISCARD).start()
            val bytes = p.inputStream.use { it.readAllBytes() }
            if (p.waitFor() != 0 || bytes.isEmpty()) throw MediaException("ffmpeg could not encode the audio chunk ($codec).")
            bytes
        }
    }

    suspend fun transcribeChunk(ffmpeg: String, media: Path, s: Double, e: Double, lang: String, title: String): List<Cue> {
        var codec = cfg.audioFormat
        var audio = encode(ffmpeg, media, s, e, codec)
        if (audio.size > cfg.maxUploadBytes) { codec = "ogg"; audio = encode(ffmpeg, media, s, e, codec) }
        var attempt = 0
        while (true) {
            attempt++
            val (code, body, retryAfter) = try {
                post(audio, if (codec == "flac") "audio.flac" else "audio.ogg", if (codec == "flac") "audio/flac" else "audio/ogg", lang, title)
            } catch (ex: java.io.IOException) {
                if (attempt >= 5) throw MediaException("Transcription API not reachable: ${ex.message}")
                delay(2000L * attempt); continue
            }
            when {
                code == 200 -> return parse(body, s, e)
                code == 413 && codec == "flac" -> { codec = "ogg"; audio = encode(ffmpeg, media, s, e, codec) }
                code == 429 && attempt < 10 -> delay(((retryAfter ?: (5.0 * attempt)) * 1000).toLong().coerceIn(1000, 120_000))
                code >= 500 && attempt < 5 -> delay(3000L * attempt)
                code == 401 || code == 403 -> throw MediaException("The transcription API (${cfg.provider}) rejected the API key (HTTP $code).")
                else -> throw MediaException("Transcription API error HTTP $code: ${errorMessage(body)}")
            }
        }
    }

    private suspend fun post(audio: ByteArray, filename: String, mime: String, lang: String, title: String): Triple<Int, String, Double?> {
        val boundary = "----mcp" + UUID.randomUUID().toString().replace("-", "")
        val body = ByteArrayOutputStream()
        fun field(name: String, value: String) {
            body.write("--$boundary\r\nContent-Disposition: form-data; name=\"$name\"\r\n\r\n$value\r\n".toByteArray())
        }
        field("model", cfg.model)
        if (cfg.model == "whisper-1" || cfg.provider == "groq" || cfg.provider == "custom") {
            field("response_format", "verbose_json")
            field("timestamp_granularities[]", "segment")
        } else {
            field("response_format", "json")
        }
        field("temperature", "0")
        if (lang != "auto") field("language", lang)
        // A short prompt with the lecture title helps with the spelling of names and terms (limit: 224 tokens).
        if (title.isNotBlank()) field("prompt", title.take(200))
        body.write("--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"$filename\"\r\nContent-Type: $mime\r\n\r\n".toByteArray())
        body.write(audio)
        body.write("\r\n--$boundary--\r\n".toByteArray())
        val req = HttpRequest.newBuilder(URI.create("${cfg.baseUrl}/audio/transcriptions"))
            .timeout(Duration.ofMinutes(10))
            .header("Authorization", "Bearer ${cfg.apiKey}")
            .header("User-Agent", Config.USER_AGENT)
            .header("Content-Type", "multipart/form-data; boundary=$boundary")
            .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray()))
            .build()
        val resp = http.sendAsync(req, HttpResponse.BodyHandlers.ofString()).await()
        val retry = resp.headers().firstValue("retry-after").orElse(null)?.toDoubleOrNull()
        return Triple(resp.statusCode(), resp.body(), retry)
    }

    private fun errorMessage(body: String): String = runCatching {
        ((json.parseToJsonElement(body).jsonObject["error"] as? JsonObject)?.get("message") as? JsonPrimitive)?.content
    }.getOrNull() ?: body.take(300)

    /**
     * Converts a response into cues with absolute times. `verbose_json` segments carry their own times; a plain
     * `text` response is split into sentences whose times are spread over the chunk (approximate).
     */
    fun parse(body: String, s: Double, e: Double): List<Cue> {
        val obj = json.parseToJsonElement(body).jsonObject
        val segs = obj["segments"] as? JsonArray
        val base = (s * 1000).toLong()
        if (segs != null && segs.isNotEmpty()) {
            return segs.mapNotNull { seg ->
                val o = seg.jsonObject
                val text = (o["text"] as? JsonPrimitive)?.content?.trim().orEmpty()
                val noSpeech = (o["no_speech_prob"] as? JsonPrimitive)?.doubleOrNull ?: 0.0
                val logprob = (o["avg_logprob"] as? JsonPrimitive)?.doubleOrNull ?: 0.0
                // Typical hallucination signature: model is confident there is no speech and unsure about the text.
                if (text.isEmpty() || (noSpeech > 0.8 && logprob < -1.0)) return@mapNotNull null
                val t0 = base + (((o["start"] as? JsonPrimitive)?.doubleOrNull ?: 0.0) * 1000).toLong()
                val t1 = base + (((o["end"] as? JsonPrimitive)?.doubleOrNull ?: 0.0) * 1000).toLong()
                Cue(t0, maxOf(t1, t0 + 1), text)
            }
        }
        val text = (obj["text"] as? JsonPrimitive)?.content?.trim().orEmpty()
        if (text.isEmpty()) return emptyList()
        val sentences = text.split(Regex("(?<=[.!?])\\s+")).filter { it.isNotBlank() }
        val totalChars = sentences.sumOf { it.length }.coerceAtLeast(1)
        val span = ((e - s) * 1000).toLong()
        var acc = 0L
        return sentences.map { sent ->
            val t0 = base + span * acc / totalChars
            acc += sent.length
            Cue(t0, base + span * acc / totalChars, sent)
        }
    }

    fun cost(billedSec: Double): Double? = cfg.pricePerHour?.let { it * billedSec / 3600 }
}
