package dev.lecturetubemcp.media

import dev.lecturetubemcp.api.Cue
import dev.lecturetubemcp.api.Vtt
import io.modelcontextprotocol.kotlin.sdk.types.ContentBlock
import io.modelcontextprotocol.kotlin.sdk.types.ImageContent
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Base64
import kotlin.time.Duration.Companion.seconds

/** MCP-facing helpers shared by the LectureTube and TUWEL servers. */
object MediaTools {

    /** Paragraphs with [hh:mm:ss] markers, windowed and truncated with a "continue with fromMinute=…" hint. */
    fun transcriptText(
        header: String, cues: List<Cue>, fromMinute: Int?, toMinute: Int?, format: String = "text",
        paragraphSeconds: Int = 60, maxChars: Int = 100_000,
    ): String {
        val from = fromMinute?.let { it * 60_000L } ?: 0L
        val to = toMinute?.let { it * 60_000L } ?: Long.MAX_VALUE
        val window = cues.filter { it.startMs in from..<to }
        val body = when (format) {
            "cues" -> window.joinToString("\n") { "[${Vtt.fmt(it.startMs)}] ${it.text}" }
            "vtt" -> "WEBVTT\n\n" + window.joinToString("") { "${Transcriber.vttTs(it.startMs)} --> ${Transcriber.vttTs(it.endMs)}\n${it.text}\n\n" }
            else -> Vtt.toParagraphs(window, paragraphSeconds.coerceIn(10, 600))
        }
        val max = maxChars.coerceAtLeast(1000)
        if (body.length <= max) return header + body
        val text = body.substring(0, body.lastIndexOf('\n', max).takeIf { it > 0 } ?: max)
        val last = Regex("""\[(\d{2}):(\d{2}):\d{2}]""").findAll(text).lastOrNull()
        val next = last?.let { it.groupValues[1].toInt() * 60 + it.groupValues[2].toInt() }
        return header + text + "\n\n[… truncated." + (next?.let { " Continue with fromMinute=$it" } ?: "") + "]"
    }

    /** Reports progress of a waiting tool call to the client: (fraction 0..1 or null, message). */
    fun interface Progress { suspend fun report(fraction: Double?, message: String) }

    /** Per-call wait limit: below the tool-call timeout of common MCP clients (about 60 s). */
    val defaultWaitSeconds: Int = System.getenv("TRANSCRIBE_WAIT_SECONDS")?.toIntOrNull()?.coerceIn(0, 3600) ?: 50

    /**
     * Waits until the job ends or [waitSeconds] pass. Returns as soon as the job finishes (no fixed polling interval),
     * and sends a progress notification every few seconds meanwhile, so clients can show a progress bar.
     */
    suspend fun waitForJob(job: TranscriptionJob, waitSeconds: Int, progress: Progress? = null) {
        val deadline = System.currentTimeMillis() + waitSeconds.coerceIn(0, 3600) * 1000L
        while (true) {
            val left = deadline - System.currentTimeMillis()
            if (left <= 0) return
            if (withTimeoutOrNull(minOf(left, 5_000L)) { job.deferred.await() } != null || job.deferred.isCompleted) return
            runCatching { progress?.report(job.progress.takeIf { job.state == JobState.TRANSCRIBING }, job.summary()) }
        }
    }

    /** Like [waitForJob] for a job of another server process (watched through its files). Returns true when it ended. */
    suspend fun waitForForeign(key: String, waitSeconds: Int, progress: Progress? = null): Boolean {
        val deadline = System.currentTimeMillis() + waitSeconds.coerceIn(0, 3600) * 1000L
        while (System.currentTimeMillis() < deadline) {
            val status = Transcriber.foreignStatus(key) ?: return true
            runCatching { progress?.report(null, status) }
            delay(minOf(3_000L, (deadline - System.currentTimeMillis()).coerceAtLeast(1)))
        }
        return Transcriber.foreignStatus(key) == null
    }

    /** Text telling the model how to get the result without busy polling. */
    fun stillRunningHint(job: TranscriptionJob?): String {
        val eta = job?.etaSec
        return "Call transcription_status for this recording to wait for it: that call returns the moment the transcript is ready " +
            "(it waits up to $defaultWaitSeconds s per call, so do not add pauses or call it in quick succession). " +
            (if (Notifier.enabled) "The user also gets a desktop notification when it is done. " else "") +
            (if (eta != null && eta > 180) "About ${eta / 60} min remain: tell the user, and stop waiting instead of calling repeatedly; " +
                "they can ask you to continue once the transcript is ready. " else "") +
            "The finished transcript is cached and used automatically by the transcript tools."
    }

    /** Waits up to [waitSeconds] for a job; returns the transcript when done, otherwise a status report. */
    suspend fun awaitJob(
        job: TranscriptionJob, waitSeconds: Int, progress: Progress? = null, render: (List<Cue>, TranscriptMeta?) -> String,
    ): Pair<Boolean, String> {
        waitForJob(job, waitSeconds, progress)
        return when (job.state) {
            JobState.DONE -> {
                val c = Transcriber.cached(job.key)
                if (c != null && job.fromSec == null && job.toSec == null) true to render(c.first, c.second)
                else true to render(job.partialCues(), null)
            }
            JobState.FAILED -> false to "Transcription failed: ${job.error}"
            else -> {
                val partial = job.partialCues()
                val sb = StringBuilder("Transcription running in the background: ${job.summary()}.\n")
                sb.append("Engine: ${Transcriber.engineName}. ").append(stillRunningHint(job))
                if (partial.isNotEmpty()) {
                    sb.append("\n\nPartial transcript so far (${partial.size} captions):\n\n")
                    sb.append(Vtt.toParagraphs(partial).take(20_000))
                }
                true to sb.toString()
            }
        }
    }

    fun statusText(): String {
        val jobs = Transcriber.allJobs()
        if (jobs.isEmpty()) return "No transcriptions in this session. Engine: ${Transcriber.engineName}; default model: ${WhisperModels.default}."
        return "Engine: ${Transcriber.engineName}\n" + jobs.joinToString("\n") { "• " + it.summary() }
    }

    /**
     * Keyframes as MCP content: a text index (timestamps + what was said around each frame) and either
     * one contact-sheet image ([layout] = "grid") or the individual frames.
     */
    fun keyframeBlocks(title: String, frames: List<Frame>, cues: List<Cue>?, layout: String, source: String): List<ContentBlock> {
        val out = mutableListOf<ContentBlock>()
        val index = buildString {
            append("# Keyframes of \"$title\" (${frames.size} frames, source: $source)\n")
            if (cues == null) append("No transcript available yet, so frames are shown without spoken context (use transcribe_recording).\n")
            frames.forEachIndexed { i, f ->
                append("\n${i + 1}. [${Vtt.fmt(f.timeMs)}]")
                cues?.let { all ->
                    val said = all.filter { it.startMs in (f.timeMs - 10_000)..(f.timeMs + 25_000) }.joinToString(" ") { it.text }
                    if (said.isNotBlank()) append(" said: \"").append(said.take(350)).append(if (said.length > 350) "…\"" else "\"")
                }
            }
        }
        out += TextContent(index)
        if (layout == "grid") {
            frames.chunked(16).forEachIndexed { n, chunk ->
                val offset = n * 16
                out += TextContent("Contact sheet ${n + 1}: frames ${offset + 1}–${offset + chunk.size}")
                out += ImageContent(Base64.getEncoder().encodeToString(Keyframes.grid(chunk, { f -> "${offset + chunk.indexOf(f) + 1}  ${Vtt.fmt(f.timeMs)}" })), "image/jpeg")
            }
        } else {
            frames.forEachIndexed { i, f ->
                out += TextContent("Frame ${i + 1} at [${Vtt.fmt(f.timeMs)}]")
                out += ImageContent(Base64.getEncoder().encodeToString(f.jpeg), "image/jpeg")
            }
        }
        return out
    }
}
