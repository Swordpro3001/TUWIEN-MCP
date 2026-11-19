package dev.lecturetubemcp

import com.sun.net.httpserver.HttpServer
import dev.lecturetubemcp.api.Cue
import dev.lecturetubemcp.media.Ffmpeg
import dev.lecturetubemcp.media.MediaException
import dev.lecturetubemcp.media.MediaSource
import dev.lecturetubemcp.media.MediaTarget
import dev.lecturetubemcp.media.registerMediaTools
import dev.testkit.args
import dev.testkit.call
import dev.testkit.text
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.types.ImageContent
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertTrue

/**
 * transcribe_recording, transcription_status and analyze_keyframes end to end: a generated test video served over
 * local HTTP, transcribed by a mock speech API. Skipped without ffmpeg.
 */
class MediaToolsFlowTest {
    private val ffmpeg = Ffmpeg.ffmpeg
    private val dir: Path = Files.createTempDirectory("mediatools")
    private val http = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    private val port get() = http.address.port

    private fun video(): Path = dir.resolve("talk.mp4").also { out ->
        // 10 s: red for 5 s, then blue (one scene change), with a tone.
        val (code, log) = runBlocking {
            Ffmpeg.run(listOf(ffmpeg!!, "-v", "error", "-f", "lavfi", "-i", "color=red:s=320x240:d=5", "-f", "lavfi", "-i", "color=blue:s=320x240:d=5",
                "-f", "lavfi", "-i", "sine=frequency=400:duration=10", "-filter_complex", "[0:v][1:v]concat=n=2:v=1:a=0[v]",
                "-map", "[v]", "-map", "2:a", "-c:v", "libx264", "-pix_fmt", "yuv420p", "-c:a", "aac", "-shortest", "-y", out.toString()))
        }
        check(code == 0) { log }
    }

    private fun startServer(file: Path) {
        http.createContext("/talk.mp4") { ex ->
            val b = Files.readAllBytes(file); ex.sendResponseHeaders(200, b.size.toLong()); ex.responseBody.use { it.write(b) }
        }
        http.createContext("/v1/audio/transcriptions") { ex ->
            ex.requestBody.readAllBytes()
            val b = """{"text":"x","segments":[{"start":0.5,"end":4.0,"text":" Rote Folie","no_speech_prob":0.0,"avg_logprob":-0.1},
                {"start":5.5,"end":9.0,"text":" Blaue Folie","no_speech_prob":0.0,"avg_logprob":-0.1}]}""".toByteArray()
            ex.responseHeaders.add("Content-Type", "application/json"); ex.sendResponseHeaders(200, b.size.toLong()); ex.responseBody.use { it.write(b) }
        }
        http.start()
        Files.createDirectories(Config.dataDir)
        Files.writeString(Config.dataDir.resolve("transcription.json"),
            """{"engine":"api","provider":"custom","apiUrl":"http://127.0.0.1:$port/v1","apiKey":"k","model":"m","pricePerHour":0.04}""")
    }

    @AfterTest fun cleanup() {
        http.stop(0)
        Files.deleteIfExists(Config.dataDir.resolve("transcription.json"))
    }

    private fun server(key: String, captions: Boolean = false): Server {
        val s = Server(Implementation("t", "1"), ServerOptions(ServerCapabilities(tools = ServerCapabilities.Tools(false))))
        registerMediaTools(
            s, listOf("lecture" to buildJsonObject { put("type", "string") }),
            resolve = { a ->
                if (a["lecture"].toString().contains("bad")) throw MediaException("no such recording")
                val src = MediaSource(key, "Talk", "http://127.0.0.1:$port/talk.mp4")
                MediaTarget(key, "Talk", "de", captions, audio = { src }, video = { src },
                    cues = { listOf(Cue(0, 4000, "Rote Folie"), Cue(5000, 9000, "Blaue Folie")) })
            },
            onError = { e -> if (e is MediaException) e.message else null },
        )
        return s
    }

    @Test fun transcribeThenStatus() {
        if (ffmpeg == null) return
        startServer(video())
        val key = "flow-" + System.nanoTime()
        val s = server(key)
        assertContains(s.call("transcription_status", args("lecture" to "x", "waitSeconds" to 0)).text(), "No transcription for \"Talk\" is running")

        val t = s.call("transcribe_recording", args("lecture" to "x", "engine" to "api", "language" to "de", "waitSeconds" to 120))
        assertTrue(t.isError != true, t.text())
        assertContains(t.text(), "Blaue Folie")
        assertContains(s.call("transcribe_recording", args("lecture" to "x")).text(), "Rote Folie") // cached now
        assertContains(s.call("transcription_status", args("lecture" to "x")).text(), "Transcript ready")
        assertContains(s.call("transcription_status").text(), "Talk") // job list
        assertContains(server(key, captions = true).call("transcribe_recording", args("lecture" to "x")).text(), "already has captions")
        assertContains(s.call("transcribe_recording", args("lecture" to "bad")).text(), "no such recording")
    }

    @Test fun keyframes() {
        if (ffmpeg == null) return
        startServer(video())
        val s = server("kf-" + System.nanoTime())
        val grid = s.call("analyze_keyframes", args("lecture" to "x", "threshold" to 0.1))
        assertTrue(grid.isError != true, grid.text())
        assertTrue(grid.content.filterIsInstance<ImageContent>().isNotEmpty())
        val frames = s.call("analyze_keyframes", args("lecture" to "x", "mode" to "interval", "intervalSeconds" to 3, "layout" to "frames", "width" to 160))
        assertTrue(frames.content.filterIsInstance<ImageContent>().isNotEmpty(), frames.text())
        assertContains(frames.text(), "Folie")
        val at = s.call("analyze_keyframes", args("lecture" to "x", "atMinutes" to listOf(0.1)))
        assertTrue(at.content.filterIsInstance<ImageContent>().size == 1, at.text())
        assertTrue(s.call("analyze_keyframes", args("lecture" to "x", "fromMinute" to 50)).isError == true)
    }
}
