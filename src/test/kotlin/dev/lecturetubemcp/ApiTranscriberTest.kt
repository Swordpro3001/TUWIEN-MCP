package dev.lecturetubemcp

import com.sun.net.httpserver.HttpServer
import dev.lecturetubemcp.media.ApiConfig
import dev.lecturetubemcp.media.ApiTranscriber
import dev.lecturetubemcp.media.Ffmpeg
import dev.lecturetubemcp.media.JobState
import dev.lecturetubemcp.media.MediaSource
import dev.lecturetubemcp.media.Speech
import dev.lecturetubemcp.media.Transcriber
import kotlinx.coroutines.runBlocking
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.Collections
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ApiTranscriberTest {
    private val api = ApiTranscriber(ApiConfig("custom", "http://unused", "k", "whisper-large-v3-turbo", 0.04))

    @Test fun parsesVerboseSegmentsWithOffsetAndDropsHallucination() {
        val body = """{"text":"…","segments":[
            {"start":0.5,"end":3.2,"text":" Guten Morgen.","no_speech_prob":0.01,"avg_logprob":-0.2},
            {"start":3.2,"end":9.0,"text":" Untertitel","no_speech_prob":0.95,"avg_logprob":-1.6}]}"""
        val cues = api.parse(body, 120.0, 180.0)
        assertEquals(1, cues.size)
        assertEquals(120_500L, cues[0].startMs); assertEquals(123_200L, cues[0].endMs); assertEquals("Guten Morgen.", cues[0].text)
    }

    @Test fun plainTextFallbackSpreadsSentences() {
        val cues = api.parse("""{"text":"Erster Satz. Zweiter, etwas längerer Satz!"}""", 60.0, 70.0)
        assertEquals(listOf("Erster Satz.", "Zweiter, etwas längerer Satz!"), cues.map { it.text })
        assertEquals(60_000L, cues[0].startMs); assertEquals(70_000L, cues[1].endMs)
    }

    @Test fun chunksSplitAtLongSilenceAndMaxLength() {
        val r = Speech.RATE
        fun w(s: Double, e: Double) = Speech.Window((s * r).toInt(), (e * r).toInt())
        val c = ApiTranscriber(ApiConfig("x", "u", "k", "m", null), maxChunkSec = 100.0, splitGapSec = 10.0)
            .chunks(listOf(w(0.0, 30.0), w(32.0, 60.0), w(80.0, 95.0), w(96.0, 150.0), w(151.0, 400.0)), 1000.0)
        // gap 20 s after 60 → split; 80..150 exceeds nothing until 151..400 (> 100 s span) → split; 151..400 cut into 100 s pieces.
        assertEquals(listOf(listOf(1000.0, 1060.0), listOf(1080.0, 1150.0), listOf(1151.0, 1251.0), listOf(1251.0, 1351.0), listOf(1351.0, 1400.0)), c)
    }

    @Test fun endToEndAgainstMockApi() {
        val ffmpeg = Ffmpeg.ffmpeg ?: return // ffmpeg not installed: skip
        val dir = Files.createTempDirectory("apitest")
        val media = dir.resolve("lecture.wav")
        val (code, _) = runBlocking {
            Ffmpeg.run(listOf(ffmpeg, "-v", "error", "-f", "lavfi", "-i", "sine=frequency=300:duration=20", "-f", "lavfi", "-i", "anullsrc=r=16000:cl=mono:d=30",
                "-f", "lavfi", "-i", "sine=frequency=500:duration=20", "-filter_complex", "[0:a][1:a][2:a]concat=n=3:v=0:a=1", "-ar", "16000", "-ac", "1", "-y", media.toString()))
        }
        assertEquals(0, code)

        val requests = Collections.synchronizedList(mutableListOf<String>())
        var calls = 0
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/media.wav") { ex ->
            val b = Files.readAllBytes(media); ex.sendResponseHeaders(200, b.size.toLong()); ex.responseBody.use { it.write(b) }
        }
        server.createContext("/v1/audio/transcriptions") { ex ->
            val body = ex.requestBody.readAllBytes()
            val text = String(body, Charsets.ISO_8859_1)
            requests += "auth=${ex.requestHeaders.getFirst("Authorization")} model=${"name=\"model\"" in text && "whisper-large-v3-turbo" in text} " +
                "verbose=${"verbose_json" in text} lang=${"name=\"language\"\r\n\r\nde" in text} flac=${"fLaC" in text}"
            val resp = synchronized(this) { calls++ }.let { n ->
                if (n == 0) { ex.responseHeaders.add("retry-after", "1"); 429 to """{"error":{"message":"rate limited"}}""" }
                else 200 to """{"text":"x","segments":[{"start":0.5,"end":3.0,"text":" Abschnitt","no_speech_prob":0.0,"avg_logprob":-0.1}]}"""
            }
            val b = resp.second.toByteArray()
            ex.responseHeaders.add("Content-Type", "application/json"); ex.sendResponseHeaders(resp.first, b.size.toLong()); ex.responseBody.use { it.write(b) }
        }
        server.start()
        try {
            val port = server.address.port
            Files.createDirectories(Config.dataDir)
            Files.writeString(Config.dataDir.resolve("transcription.json"),
                """{"engine":"api","provider":"custom","apiUrl":"http://127.0.0.1:$port/v1","apiKey":"test-key","model":"whisper-large-v3-turbo","pricePerHour":0.04}""")
            val key = "api-e2e-" + System.nanoTime()
            val job = Transcriber.start(key, "Testvorlesung", "de", null, null, null) {
                MediaSource(key, "Testvorlesung", "http://127.0.0.1:$port/media.wav")
            }
            runBlocking { job.deferred.await() }
            assertEquals(JobState.DONE, job.state, job.error)
            val (cues, meta) = assertNotNull(Transcriber.cached(key))
            // Two speech chunks (tone, 30 s silence, tone) → two uploads, plus the one 429 that was retried.
            assertEquals(2, cues.size, cues.toString())
            assertTrue(cues[0].startMs in 0..1500 && cues[1].startMs in 49_000..52_000, cues.toString())
            assertEquals(3, requests.size, requests.toString())
            assertTrue(requests.all { it == "auth=Bearer test-key model=true verbose=true lang=true flac=true" }, requests.toString())
            assertEquals("custom", meta.engine); assertTrue((meta.costUsd ?: 0.0) > 0.0)
        } finally {
            server.stop(0)
            Files.deleteIfExists(Config.dataDir.resolve("transcription.json"))
        }
    }
}
