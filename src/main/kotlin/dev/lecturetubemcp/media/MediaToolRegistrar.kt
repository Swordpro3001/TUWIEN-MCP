package dev.lecturetubemcp.media

import dev.lecturetubemcp.api.Cue
import dev.lecturetubemcp.api.Vtt
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.ProgressNotification
import io.modelcontextprotocol.kotlin.sdk.types.ProgressNotificationParams
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolAnnotations
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/** A recording that can be transcribed and analysed, independent of where it comes from. */
class MediaTarget(
    /** Transcript cache key (Opencast UUID for LectureTube, a hash for TUWEL files). */
    val key: String,
    val title: String,
    val language: String?,
    /** True if official captions exist (then transcription is usually unnecessary). */
    val hasCaptions: Boolean,
    val audio: suspend () -> MediaSource,
    val video: suspend (track: String) -> MediaSource,
    /** Existing captions or a generated transcript, for annotating keyframes. */
    val cues: suspend () -> List<Cue>?,
)

/**
 * Registers `transcribe_recording`, `transcription_status` and `analyze_keyframes` on a server.
 * [targetProps] describe how a recording is identified (e.g. `lecture`, or TUWEL file arguments).
 */
fun registerMediaTools(
    server: Server,
    targetProps: List<Pair<String, JsonObject>>,
    resolve: suspend (JsonObject) -> MediaTarget,
    onError: (Exception) -> String?,
) {
    fun prop(type: String, desc: String, items: String? = null) = buildJsonObject {
        put("type", type); put("description", desc); items?.let { putJsonObject("items") { put("type", it) } }
    }
    fun schema(vararg extra: Pair<String, JsonObject>) =
        ToolSchema(properties = buildJsonObject { (targetProps + extra).forEach { (k, v) -> put(k, v) } })

    fun add(name: String, desc: String, schema: ToolSchema, readOnly: Boolean, handler: suspend (JsonObject, MediaTools.Progress?) -> CallToolResult) =
        server.addTool(name = name, description = desc, inputSchema = schema, toolAnnotations = ToolAnnotations(readOnlyHint = readOnly, openWorldHint = true)) { req ->
            val a = req.arguments ?: JsonObject(emptyMap())
            // MCP progress notifications, if the client asked for them (progressToken): shown as a progress bar by some
            // clients, and they keep the call alive in clients that reset their timeout on progress.
            val token = runCatching { req.meta?.progressToken }.getOrNull()
            val progress = token?.let { tk ->
                var n = 0.0
                MediaTools.Progress { fraction, message ->
                    n = maxOf(n + 0.001, (fraction ?: 0.0) * 100)
                    notification(ProgressNotification(ProgressNotificationParams(tk, n, if (fraction != null) 100.0 else null, message)))
                }
            }
            try {
                handler(a, progress)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                CallToolResult(listOf(TextContent(onError(e) ?: "${e::class.simpleName}: ${e.message}")), isError = true)
            }
        }

    fun JsonObject.str(k: String) = (this[k] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }
    fun JsonObject.int(k: String) = (this[k] as? JsonPrimitive)?.content?.toDoubleOrNull()?.toInt()
    fun JsonObject.num(k: String) = (this[k] as? JsonPrimitive)?.content?.toDoubleOrNull()
    fun JsonObject.bool(k: String) = (this[k] as? JsonPrimitive)?.content?.toBooleanStrictOrNull()
    fun ok(t: String) = CallToolResult(listOf(TextContent(t)))
    fun err(t: String) = CallToolResult(listOf(TextContent(t)), isError = true)

    add(
        "transcribe_recording",
        "Create captions for a recording that has none: downloads the MP4, extracts the speech and transcribes it. " +
            "Engine (see `engine`): either a configured cloud speech-to-text API (fast, almost no local CPU; the audio is uploaded to " +
            "that provider) or local Whisper on this computer (nothing leaves the machine, but it uses the CPU for several minutes). " +
            "The result is cached as WebVTT and used automatically by the transcript tools afterwards. This call returns the transcript " +
            "as soon as it is ready if that is within waitSeconds, else a progress report with partial text; then wait for the result " +
            "with transcription_status (it blocks until done, no need to poll or sleep). " +
            "Use fromMinute/toMinute to transcribe only a part. Current default: " + Transcriber.engineName + ".",
        schema(
            "language" to prop("string", "Spoken language, e.g. de or en (default: from metadata, else auto-detect)."),
            "engine" to prop("string", "api (configured cloud speech API) or local (Whisper on this computer). Default: " + TranscribeConfig.defaultEngine() + "."),
            "model" to prop("string", "Local engine only: tiny, base, small (default, good German), medium, turbo (best, slow on CPU), large."),
            "fromMinute" to prop("integer", "Only transcribe from this minute."),
            "toMinute" to prop("integer", "Only transcribe until this minute."),
            "waitSeconds" to prop("integer", "How long this call waits for the result at most; it returns immediately when done (default ${MediaTools.defaultWaitSeconds})."),
            "force" to prop("boolean", "Transcribe even if official captions exist or a transcript is cached."),
            "maxChars" to prop("integer", "Maximum characters of transcript returned (default 100000)."),
        ),
        readOnly = false,
    ) { a, progress ->
        val t = resolve(a)
        val force = a.bool("force") == true
        val from = a.int("fromMinute"); val to = a.int("toMinute")
        val max = a.int("maxChars") ?: 100_000
        if (t.hasCaptions && !force) return@add ok("\"${t.title}\" already has captions; read them with the transcript tool (get_transcript / listen_recording). Pass force=true to transcribe anyway.")
        if (!force) Transcriber.cached(t.key)?.let { (cues, meta) ->
            return@add ok(MediaTools.transcriptText(header(t.title, meta), cues, from, to, maxChars = max))
        }
        val job = Transcriber.start(t.key, t.title, a.str("language") ?: t.language, a.str("model"), from?.times(60), to?.times(60), a.str("engine")) { t.audio() }
        val (good, text) = MediaTools.awaitJob(job, a.int("waitSeconds") ?: MediaTools.defaultWaitSeconds, progress) { cues, meta ->
            MediaTools.transcriptText(header(t.title, meta ?: Transcriber.cached(t.key)?.second), cues, null, null, maxChars = max)
        }
        if (good) ok(text) else err(text)
    }

    add(
        "transcription_status",
        "Waits for a speech-to-text job of the given recording and returns the transcript the moment it is ready. " +
            "It blocks up to waitSeconds (default ${MediaTools.defaultWaitSeconds}) and returns immediately when the job finishes, " +
            "so call it again only if it reports that the job is still running; never sleep between calls. waitSeconds=0 gives an " +
            "instant status. Without a recording it lists all jobs (state, percent, time left).",
        schema(
            "waitSeconds" to prop("integer", "Maximum time to wait for the result (default ${MediaTools.defaultWaitSeconds}; 0 = status only)."),
            "partial" to prop("boolean", "If still running: include the partial transcript so far (default true)."),
            "maxChars" to prop("integer", "Maximum characters of transcript returned (default 100000)."),
        ),
        readOnly = true,
    ) { a, progress ->
        val identified = targetProps.any { (k, _) -> a[k] != null }
        if (!identified) return@add ok(MediaTools.statusText())
        val t = resolve(a)
        val wait = a.int("waitSeconds") ?: MediaTools.defaultWaitSeconds
        val max = a.int("maxChars") ?: 100_000
        val withPartial = a.bool("partial") != false
        fun partialText(cues: List<Cue>) =
            if (withPartial && cues.isNotEmpty()) "\n\nPartial transcript so far (${cues.size} captions):\n\n" + Vtt.toParagraphs(cues).take(30_000) else ""
        val job = Transcriber.job(t.key)
        if (job != null && job.state != JobState.DONE && job.state != JobState.FAILED) MediaTools.waitForJob(job, wait, progress)
        else if (job == null && Transcriber.foreignStatus(t.key) != null) MediaTools.waitForForeign(t.key, wait, progress)
        val cached = Transcriber.cached(t.key)
        val foreign = if (cached == null) Transcriber.foreignStatus(t.key) else null
        when {
            job?.state == JobState.FAILED && cached == null -> err("Transcription of \"${t.title}\" failed: ${job.error}")
            job != null && job.state != JobState.DONE && job.state != JobState.FAILED ->
                ok("Still running: ${job.summary()}.\n" + MediaTools.stillRunningHint(job) + partialText(job.partialCues()))
            foreign != null -> ok("Still running: $foreign.\n" + MediaTools.stillRunningHint(null) + partialText(Transcriber.foreignPartialCues(t.key)))
            cached != null && (job?.state != JobState.DONE || (job.fromSec == null && job.toSec == null)) ->
                ok("Transcript ready.\n\n" + MediaTools.transcriptText(header(t.title, cached.second), cached.first, null, null, maxChars = max))
            job?.state == JobState.DONE -> ok("Transcript ready (partial range).\n\n" + MediaTools.transcriptText(header(t.title, null), job.partialCues(), null, null, maxChars = max))
            else -> ok("No transcription for \"${t.title}\" is running.${if (t.hasCaptions) " It has official captions." else " Start one with transcribe_recording."}")
        }
    }

    add(
        "analyze_keyframes",
        "Look at a recording: extracts keyframes from the MP4 (slide changes via scene detection, or every N seconds, " +
            "or at given times), removes duplicates and returns them as images, together with what was said around each frame " +
            "(from captions or a generated transcript). layout=grid returns contact sheets (16 frames per image, cheap overview); " +
            "layout=frames returns each frame separately (to read slide text, formulas, code or board writing). Typical use: grid first, " +
            "then atMinutes for the frames you want to read in detail. The video is downloaded once and cached.",
        schema(
            "fromMinute" to prop("number", "Window start (minutes)."),
            "toMinute" to prop("number", "Window end (minutes)."),
            "mode" to prop("string", "scenes (default: slide/scene changes) or interval."),
            "intervalSeconds" to prop("integer", "For mode=interval: seconds between frames (default 60)."),
            "threshold" to prop("number", "Minimum scene-change score (default adaptive; slide changes score about 0.02–0.1, cuts above 0.3)."),
            "maxFrames" to prop("integer", "Maximum frames (default 16, max 48)."),
            "atMinutes" to prop("array", "Exact times in minutes (e.g. [12.5, 30]) to extract single full-size frames.", items = "number"),
            "layout" to prop("string", "grid (default) or frames."),
            "track" to prop("string", "LectureTube: auto/slides (screen capture, default) or camera."),
            "width" to prop("integer", "Frame width in pixels (default 1280 for frames, 960 for grids)."),
        ),
        readOnly = true,
    ) { a, _ ->
        val t = resolve(a)
        val src = t.video(a.str("track") ?: "auto")
        val file = MediaCache.fetch(src)
        val at = (a["atMinutes"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content?.toDoubleOrNull() }
        val layout = a.str("layout") ?: if (!at.isNullOrEmpty()) "frames" else "grid"
        val width = a.int("width") ?: if (layout == "grid") 960 else 1280
        val frames = if (!at.isNullOrEmpty()) {
            Keyframes.at(file, at.map { it * 60 }, width)
        } else {
            Keyframes.extract(
                file, (a.num("fromMinute") ?: 0.0) * 60, a.num("toMinute")?.times(60), a.str("mode") ?: "scenes",
                a.num("threshold")?.coerceIn(0.001, 0.9), (a.int("intervalSeconds") ?: 60).coerceAtLeast(2),
                (a.int("maxFrames") ?: 16).coerceIn(1, 48), width,
            )
        }
        if (frames.isEmpty()) return@add err("No frames extracted (check the time window).")
        val cues = runCatching { t.cues() }.getOrNull()
        CallToolResult(MediaTools.keyframeBlocks(t.title, frames, cues, layout, src.url.substringAfterLast('/').substringBefore('?')))
    }
}

private fun header(title: String, meta: TranscriptMeta?) =
    "# $title\nTranscript generated by " + (meta?.let { sourceLabel(it) } ?: "speech recognition") +
        " from the recording's audio. Expect recognition errors in names and technical terms.\n\n"

/** "local Whisper (small, de)" or "speech recognition via groq (whisper-large-v3-turbo, de)". */
fun sourceLabel(m: TranscriptMeta): String =
    if (m.engine == "bundled" || m.engine == "whisper-cli" || m.engine == "?") "local Whisper (model ${m.model}, language ${m.language})"
    else "speech recognition via ${m.engine} (${m.model}, language ${m.language})"
