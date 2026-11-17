package dev.lecturetubemcp.media

import dev.lecturetubemcp.Config
import dev.lecturetubemcp.api.Cue
import dev.lecturetubemcp.api.Vtt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.roundToInt

/** Whisper ggml models (from huggingface.co/ggerganov/whisper.cpp), downloaded on first use. */
object WhisperModels {
    val aliases = linkedMapOf(
        "tiny" to "tiny-q5_1", "base" to "base-q5_1", "small" to "small-q5_1",
        "medium" to "medium-q5_0", "turbo" to "large-v3-turbo-q5_0", "large" to "large-v3-q5_0",
    )
    val approxMb = mapOf("tiny" to 32, "base" to 60, "small" to 190, "medium" to 540, "turbo" to 575, "large" to 1080)
    val default: String get() = System.getenv("LECTURETUBE_WHISPER_MODEL")?.takeIf { it in aliases } ?: "small"

    fun file(alias: String): Path = Config.dataDir.resolve("models").resolve("ggml-${aliases[alias] ?: throw MediaException("Unknown model '$alias'. Choose one of ${aliases.keys}.")}.bin")

    suspend fun ensure(alias: String, progress: (Long, Long) -> Unit): Path {
        val f = file(alias)
        if (Files.exists(f)) return f
        val src = MediaSource("model-$alias", "Whisper model $alias", "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/${f.fileName}")
        val tmp = MediaCache.fetch(src, progress)
        Files.createDirectories(f.parent)
        Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING)
        return f
    }
}

@Serializable
data class TranscriptMeta(
    val key: String, val title: String, val model: String, val engine: String, val language: String,
    val created: String, val fromSec: Int? = null, val toSec: Int? = null, val audioSeconds: Int = 0, val tookSeconds: Int = 0,
    /** Estimated API cost in USD (cloud engine only). */
    val costUsd: Double? = null,
)

/** Persisted next to the parts of a running job so any process can resume, report or finish it. */
@Serializable
data class JobFile(
    val key: String, val title: String, val model: String, val language: String,
    val fromSec: Int? = null, val toSec: Int? = null, val engine: String = "bundled",
    val parts: List<List<Double>> = emptyList(), val started: String = Instant.now().toString(),
)

enum class JobState { QUEUED, DOWNLOADING_MODEL, DOWNLOADING_MEDIA, PREPARING, TRANSCRIBING, DONE, FAILED }

class TranscriptionJob(val key: String, val title: String, val model: String, val fromSec: Int?, val toSec: Int?) {
    @Volatile var state = JobState.QUEUED
    @Volatile var detail = ""
    @Volatile var error: String? = null
    @Volatile var startedAt: Long = System.currentTimeMillis()
    @Volatile var transcribeStartedAt: Long = 0
    @Volatile var totalSec = 0.0
    @Volatile var result: Path? = null
    val parts = mutableListOf<Part>()
    lateinit var deferred: Deferred<Path?>

    /** One time range handled by one worker. Retries write new attempt files; all of them are merged. */
    class Part(val index: Int, val start: Double, val end: Double, val dir: Path) {
        @Volatile var doneUpTo: Double = start
        /** Continue numbering after attempts left on disk by an earlier (crashed or restarted) run. */
        @Volatile var attempt = (0..50).lastOrNull { n ->
            Files.exists(dir.resolve("part$index.a$n.vtt")) || Files.exists(dir.resolve("part$index.a$n.vtt.partial"))
        }?.plus(1) ?: 0
        /** A worker renames its output to `.vtt` only after finishing its whole range. */
        val complete: Boolean get() = (0..attempt).any { Files.exists(dir.resolve("part$index.a$it.vtt")) }
        val out: Path get() = dir.resolve("part$index.a$attempt.vtt")
        fun files(): List<Path> = (0..attempt + 1).flatMap { n ->
            val f = dir.resolve("part$index.a$n.vtt")
            listOf(f, f.resolveSibling(f.fileName.toString() + ".partial"))
        }.filter { Files.exists(it) }
        fun cues(): List<Cue> = mergeCues(files().flatMap { Vtt.parse(Files.readString(it)) })
    }

    val progress: Double
        get() = when (state) {
            JobState.DONE -> 1.0
            JobState.TRANSCRIBING -> if (totalSec <= 0) 0.0 else synchronized(parts) { parts.sumOf { it.doneUpTo - it.start } } / totalSec
            else -> 0.0
        }

    val etaSec: Int?
        get() {
            val p = progress
            if (state != JobState.TRANSCRIBING || p < 0.03) return null
            val el = (System.currentTimeMillis() - transcribeStartedAt) / 1000.0
            return (el / p * (1 - p)).roundToInt()
        }

    fun summary(): String = buildString {
        append("\"$title\": ${state.name.lowercase().replace('_', ' ')}")
        if (state == JobState.TRANSCRIBING) append(" ${(progress * 100).roundToInt()}%")
        etaSec?.let { append(", about ${if (it > 90) "${it / 60} min" else "$it s"} left") }
        if (detail.isNotBlank()) append(" ($detail)")
        error?.let { append(": $it") }
        append(" [model $model${if (fromSec != null || toSec != null) ", range ${fromSec ?: 0}s–${toSec?.toString() ?: "end"}" else ""}]")
    }

    /** Transcript produced so far (cues of all parts, in time order). */
    fun partialCues(): List<Cue> = result?.takeIf { Files.exists(it) }?.let { Vtt.parse(Files.readString(it)) }
        ?: mergeCues(synchronized(parts) { parts.toList() }.flatMap { it.cues() })
}

/** Sorts cues and drops ones that overlap the previous kept cue (from retries or part boundaries). */
fun mergeCues(cues: List<Cue>): List<Cue> {
    val out = mutableListOf<Cue>()
    for (c in cues.sortedBy { it.startMs }) {
        val last = out.lastOrNull()
        if (last != null && (c.startMs < last.endMs - 500 || c.text == last.text && c.startMs - last.endMs < 2000)) continue
        out += c
    }
    return out
}

/**
 * Speech-to-text for lecture recordings without captions. Results are cached as WebVTT under
 * `<data dir>/transcripts/<key>.vtt`, so every recording is transcribed only once.
 *
 * Engines:
 * - an installed `whisper-cli` (whisper.cpp; can use CUDA/Metal/Vulkan) if found on PATH or via `WHISPER_CLI`;
 * - otherwise the bundled whisper.cpp (whisper-jni) in parallel child JVMs ([WhisperWorker]).
 */
object Transcriber {
    private val dir: Path get() = Config.dataDir.resolve("transcripts")
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val gate = Semaphore(1) // one transcription at a time; it uses all CPU cores
    private val jobs = ConcurrentHashMap<String, TranscriptionJob>()
    private val processes = ConcurrentHashMap.newKeySet<Process>()

    init {
        Runtime.getRuntime().addShutdownHook(Thread { processes.forEach { it.destroyForcibly() } })
    }

    private fun base(key: String, from: Int?, to: Int?) =
        MediaCache.safeName(key) + if (from != null || to != null) "@${from ?: 0}-${to ?: "end"}" else ""

    private val workRoot: Path get() = dir.resolve("work")
    private fun workDir(key: String, from: Int?, to: Int?, model: String) = workRoot.resolve(base(key, from, to) + "#" + model)

    /** Lock held by the process that is running a job; other processes see the job as running. */
    private val heldLocks = ConcurrentHashMap<Path, java.nio.channels.FileLock>()

    private fun tryLockJob(work: Path): Boolean {
        Files.createDirectories(work)
        val ch = java.nio.channels.FileChannel.open(work.resolve(".lock"), java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.WRITE)
        val lock = runCatching { ch.tryLock() }.getOrNull()
        if (lock == null) { ch.close(); return false }
        heldLocks[work] = lock
        return true
    }

    private fun unlockJob(work: Path) { heldLocks.remove(work)?.let { runCatching { it.release(); it.channel().close() } } }

    /** True if some other process currently runs the job in [work]. */
    private fun lockedElsewhere(work: Path): Boolean {
        if (heldLocks.containsKey(work) || !Files.exists(work.resolve(".lock"))) return false
        if (!tryLockJob(work)) return true
        unlockJob(work); return false
    }

    /** Jobs found on disk (from any process): their description and the directory. */
    private fun diskJobs(): List<Pair<JobFile, Path>> =
        if (!Files.isDirectory(workRoot)) emptyList()
        else Files.list(workRoot).use { st -> st.filter { Files.isDirectory(it) }.toList() }.mapNotNull { d ->
            runCatching { json.decodeFromString<JobFile>(Files.readString(d.resolve("job.json"))) to d }.getOrNull()
        }

    private fun partsOf(jf: JobFile, work: Path) = jf.parts.mapIndexed { i, (s, e) -> TranscriptionJob.Part(i, s, e, work) }

    /**
     * Finishes jobs whose parts are all done but whose owner died before writing the result (e.g. the server was
     * restarted). Returns true if something was finalised.
     */
    fun recover(key: String? = null): Boolean {
        var any = false
        for ((jf, work) in diskJobs()) {
            if (key != null && jf.key != key) continue
            if (jf.parts.isEmpty() || lockedElsewhere(work) || heldLocks.containsKey(work)) continue
            val parts = partsOf(jf, work)
            if (!parts.all { it.complete }) continue
            val cues = Speech.clean(mergeCues(parts.flatMap { it.cues() }))
            writeResult(jf.key, jf.title, jf.model, jf.engine, jf.language, jf.fromSec, jf.toSec, cues, (jf.parts.last()[1] - jf.parts.first()[0]).toInt(), 0)
            work.toFile().deleteRecursively()
            any = true
        }
        return any
    }

    private fun writeResult(key: String, title: String, model: String, engine: String, lang: String, from: Int?, to: Int?, cues: List<Cue>, audioSec: Int, tookSec: Int, costUsd: Double? = null): Path {
        Files.createDirectories(dir)
        val out = dir.resolve(base(key, from, to) + ".vtt")
        Files.writeString(out, "WEBVTT\n\n" + cues.joinToString("") { "${vttTs(it.startMs)} --> ${vttTs(it.endMs)}\n${it.text}\n\n" })
        val meta = TranscriptMeta(key, title, model, engine, lang, Instant.now().toString(), from, to, audioSec, tookSec, costUsd)
        Files.writeString(dir.resolve(base(key, from, to) + ".json"), json.encodeToString(TranscriptMeta.serializer(), meta))
        return out
    }

    /** Status line of a job that runs in another server process (read from its files). */
    fun foreignStatus(key: String): String? {
        val (jf, work) = diskJobs().firstOrNull { it.first.key == key && lockedElsewhere(it.second) } ?: return null
        val parts = partsOf(jf, work)
        val total = jf.parts.sumOf { it[1] - it[0] }.coerceAtLeast(1.0)
        val done = parts.sumOf { p -> if (p.complete) p.end - p.start else ((p.cues().lastOrNull()?.endMs ?: 0) / 1000.0 - p.start).coerceIn(0.0, p.end - p.start) }
        return "\"${jf.title}\": transcribing ${(done * 100 / total).roundToInt()}% in another server process [model ${jf.model}]"
    }

    /** Cues produced so far by a job of another process. */
    fun foreignPartialCues(key: String): List<Cue> =
        diskJobs().firstOrNull { it.first.key == key }?.let { (jf, work) -> mergeCues(partsOf(jf, work).flatMap { it.cues() }) }.orEmpty()

    /** A finished transcript for [key] (full recording), if one was generated before. */
    fun cached(key: String): Pair<List<Cue>, TranscriptMeta>? {
        val f = dir.resolve(base(key, null, null) + ".vtt")
        if (!Files.exists(f) && !(runCatching { recover(key) }.getOrDefault(false) && Files.exists(f))) return null
        val meta = runCatching { json.decodeFromString<TranscriptMeta>(Files.readString(dir.resolve(base(key, null, null) + ".json"))) }.getOrNull()
            ?: TranscriptMeta(key, key, "?", "?", "?", "")
        // Filtered on every read, so filter improvements also apply to transcripts made earlier.
        return Speech.clean(Vtt.parse(Files.readString(f))) to meta
    }

    fun job(key: String): TranscriptionJob? = jobs.values.filter { it.key == key }.maxByOrNull { it.startedAt }

    fun allJobs(): List<TranscriptionJob> = jobs.values.sortedByDescending { it.startedAt }

    val engineName: String get() =
        if (TranscribeConfig.defaultEngine() == "api") TranscribeConfig.describe()
        else whisperCli()?.let { "whisper-cli ($it)" } ?: "bundled whisper.cpp (CPU)"

    private fun whisperCli(): String? =
        System.getenv("WHISPER_CLI")?.takeIf { File(it).canExecute() }
            ?: listOf("whisper-cli", "whisper-cpp").firstNotNullOfOrNull { n ->
                System.getenv("PATH").orEmpty().split(File.pathSeparator).map { File(it, n) }.firstOrNull { it.canExecute() }?.path
            }

    /**
     * Starts (or joins) a transcription. [source] resolves the media lazily (it may need network calls).
     * [language]: ISO code like "de"/"en" or null for auto-detection.
     */
    fun start(
        key: String, title: String, language: String?, model: String?, fromSec: Int?, toSec: Int?,
        engine: String? = null,
        source: suspend () -> MediaSource,
    ): TranscriptionJob {
        val eng = engine?.lowercase()?.takeIf { it == "api" || it == "local" } ?: TranscribeConfig.defaultEngine()
        val m = if (eng == "api") {
            val cfg = TranscribeConfig.api() ?: throw MediaException(
                "No transcription API is configured. Set TRANSCRIBE_API_KEY (Groq by default) in the MCP server's env, " +
                    "or create ${Config.dataDir.resolve("transcription.json")}; or use engine=local.",
            )
            "api-" + cfg.model.replace(Regex("[^A-Za-z0-9._-]"), "_")
        } else {
            (model?.lowercase()?.takeIf { it.isNotBlank() } ?: WhisperModels.default).also {
                if (it !in WhisperModels.aliases) throw MediaException("Unknown model '$it'. Choose one of ${WhisperModels.aliases.keys}.")
            }
        }
        val jobKey = base(key, fromSec, toSec) + "#" + m
        jobs[jobKey]?.takeIf { it.state != JobState.FAILED }?.let { return it }
        if (lockedElsewhere(workDir(key, fromSec, toSec, m))) {
            throw MediaException(foreignStatus(key) ?: "This recording is already being transcribed by another server process.")
        }
        val job = TranscriptionJob(key, title, m, fromSec, toSec)
        jobs[jobKey] = job
        job.deferred = scope.async {
            val r = gate.withPermit {
                runCatching { run(job, language, source) }.getOrElse { e ->
                    job.state = JobState.FAILED; job.error = e.message ?: e::class.simpleName; null
                }
            }
            // Tell the user (not the model, which can't be woken up) when a longer job ends.
            if (System.currentTimeMillis() - job.startedAt > 30_000) {
                if (job.state == JobState.FAILED) Notifier.notify("Transcription failed", "${job.title}: ${job.error}")
                else Notifier.notify("Transcript ready", "${job.title}\nAsk Claude to continue with the lecture.")
            }
            r
        }
        return job
    }

    private suspend fun run(job: TranscriptionJob, language: String?, source: suspend () -> MediaSource): Path {
        if (job.model.startsWith("api-")) return runApiJob(job, language, source)
        val started = System.currentTimeMillis()
        val ffmpeg = Ffmpeg.require()
        job.state = JobState.DOWNLOADING_MODEL
        val modelFile = WhisperModels.ensure(job.model) { d, t -> job.detail = mb(d, t) }
        job.state = JobState.DOWNLOADING_MEDIA; job.detail = ""
        val src = source()
        val media = MediaCache.fetch(src) { d, t -> job.detail = mb(d, t) }
        job.state = JobState.PREPARING; job.detail = "analysing audio"
        val info = Ffmpeg.probe(media)
        if (!info.hasAudio) throw MediaException("The recording has no audio track.")
        val a = (job.fromSec ?: 0).toDouble().coerceIn(0.0, info.durationSec)
        val b = (job.toSec?.toDouble() ?: info.durationSec).coerceIn(a, info.durationSec)
        if (b - a < 1) throw MediaException("Empty time range.")
        job.totalSec = b - a
        val lang = (language ?: src.language)?.lowercase()?.let { mapOf("deu" to "de", "ger" to "de", "eng" to "en")[it] ?: it }?.takeIf { it.length == 2 } ?: "auto"

        val work = workDir(job.key, job.fromSec, job.toSec, job.model)
        if (!tryLockJob(work)) throw MediaException(foreignStatus(job.key) ?: "Another server process is transcribing this recording.")
        try {
            // Only one transcription per machine, also across server processes (it uses all CPU cores).
            job.state = JobState.QUEUED; job.detail = "waiting for another transcription to finish"
            globalLock().use {
                val cli = whisperCli()
                job.state = JobState.TRANSCRIBING; job.transcribeStartedAt = System.currentTimeMillis(); job.detail = ""
                val engine = if (cli != null) {
                    runCli(job, cli, ffmpeg, modelFile, media, a, b, lang, work); "whisper-cli"
                } else {
                    runBundled(job, ffmpeg, modelFile, media, a, b, lang, work); "bundled"
                }
                val cues = Speech.clean(job.partialCues())
                val out = writeResult(job.key, job.title, job.model, engine, lang, job.fromSec, job.toSec, cues, (b - a).toInt(), ((System.currentTimeMillis() - started) / 1000).toInt())
                job.result = out; job.state = JobState.DONE; job.detail = "${cues.size} captions"
                return out
            }
        } finally {
            unlockJob(work)
            // Parts stay on disk if the job did not finish, so a later run resumes instead of starting over.
            if (job.state == JobState.DONE) work.toFile().deleteRecursively()
        }
    }

    /** Cloud transcription: download, find speech, upload chunks; resumable like the local engine. */
    private suspend fun runApiJob(job: TranscriptionJob, language: String?, source: suspend () -> MediaSource): Path {
        val started = System.currentTimeMillis()
        val cfg = TranscribeConfig.api() ?: throw MediaException("The transcription API is no longer configured.")
        val api = ApiTranscriber(cfg)
        val ffmpeg = Ffmpeg.require()
        job.state = JobState.DOWNLOADING_MEDIA; job.detail = ""
        val src = source()
        val media = MediaCache.fetch(src) { d, t -> job.detail = mb(d, t) }
        val info = Ffmpeg.probe(media)
        if (!info.hasAudio) throw MediaException("The recording has no audio track.")
        val a = (job.fromSec ?: 0).toDouble().coerceIn(0.0, info.durationSec)
        val b = (job.toSec?.toDouble() ?: info.durationSec).coerceIn(a, info.durationSec)
        if (b - a < 1) throw MediaException("Empty time range.")
        val lang = (language ?: src.language)?.lowercase()?.let { mapOf("deu" to "de", "ger" to "de", "eng" to "en")[it] ?: it }?.takeIf { it.length == 2 } ?: "auto"

        val work = workDir(job.key, job.fromSec, job.toSec, job.model)
        if (!tryLockJob(work)) throw MediaException(foreignStatus(job.key) ?: "Another server process is transcribing this recording.")
        try {
            val jobFile = work.resolve("job.json")
            val previous = runCatching { json.decodeFromString<JobFile>(Files.readString(jobFile)) }.getOrNull()?.takeIf { it.parts.isNotEmpty() }
            job.state = JobState.PREPARING; job.detail = "finding speech"
            val chunks = previous?.parts ?: api.plan(ffmpeg, media, a, b)
            if (chunks.isEmpty()) throw MediaException("No speech found in this recording (only silence or noise).")
            Files.writeString(jobFile, json.encodeToString(JobFile.serializer(), JobFile(job.key, job.title, job.model, lang, job.fromSec, job.toSec, cfg.provider, chunks)))
            synchronized(job.parts) {
                job.parts.clear()
                chunks.forEachIndexed { i, (s, e) -> job.parts += TranscriptionJob.Part(i, s, e, work) }
            }
            job.parts.filter { it.complete }.forEach { it.doneUpTo = it.end }
            job.totalSec = job.parts.sumOf { it.end - it.start }
            job.state = JobState.TRANSCRIBING; job.transcribeStartedAt = System.currentTimeMillis()
            job.detail = "uploading ${job.parts.size} chunks to ${cfg.label}"
            val billed = api.run(job, ffmpeg, media, lang)
            val cues = Speech.clean(job.partialCues())
            val cost = api.cost(billed)
            val out = writeResult(
                job.key, job.title, cfg.model, cfg.provider, lang, job.fromSec, job.toSec, cues, (b - a).toInt(),
                ((System.currentTimeMillis() - started) / 1000).toInt(), cost,
            )
            job.result = out; job.state = JobState.DONE
            job.detail = "${cues.size} captions" + (cost?.let { ", ≈ $${"%.3f".format(it)}" } ?: "")
            return out
        } finally {
            unlockJob(work)
            if (job.state == JobState.DONE) work.toFile().deleteRecursively()
        }
    }

    /** Machine-wide lock (blocking, polled) so two server processes never transcribe at the same time. */
    private suspend fun globalLock(): java.io.Closeable {
        Files.createDirectories(dir)
        val ch = java.nio.channels.FileChannel.open(dir.resolve(".transcribe.lock"), java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.WRITE)
        while (true) {
            val l = runCatching { ch.tryLock() }.getOrNull()
            if (l != null) return java.io.Closeable { runCatching { l.release() }; ch.close() }
            kotlinx.coroutines.delay(2000)
        }
    }

    /** Split [a,b] into parts at silences and transcribe them in parallel child JVMs. */
    private suspend fun runBundled(job: TranscriptionJob, ffmpeg: String, model: Path, media: Path, a: Double, b: Double, lang: String, work: Path) {
        val cores = Runtime.getRuntime().availableProcessors()
        val workers = (System.getenv("TRANSCRIBE_WORKERS")?.toIntOrNull() ?: (cores / 6).coerceIn(1, 4))
            .coerceAtMost(((b - a) / 120).toInt().coerceAtLeast(1)) // no tiny parts
        val threads = System.getenv("TRANSCRIBE_THREADS")?.toIntOrNull() ?: (cores / workers).coerceIn(2, 8)
        val jobFile = work.resolve("job.json")
        val previous = runCatching { json.decodeFromString<JobFile>(Files.readString(jobFile)) }.getOrNull()?.takeIf { it.parts.isNotEmpty() }
        val bounds: List<Double> = previous?.parts?.let { ps -> ps.map { it[0] } + ps.last()[1] } ?: splitPoints(media, a, b, workers)
        Files.writeString(jobFile, json.encodeToString(JobFile.serializer(), JobFile(job.key, job.title, job.model, lang, job.fromSec, job.toSec, "bundled", bounds.zipWithNext().map { listOf(it.first, it.second) })))
        synchronized(job.parts) {
            job.parts.clear()
            bounds.zipWithNext().forEachIndexed { i, (s, e) -> job.parts += TranscriptionJob.Part(i, s, e, work) }
        }
        job.parts.filter { it.complete }.forEach { it.doneUpTo = it.end }
        val javaBin = ProcessHandle.current().info().command().orElse("java")
        val cp = System.getProperty("java.class.path")
        withContext(Dispatchers.IO) {
            job.parts.filterNot { it.complete }.map { part ->
                async {
                    var lastError = ""
                    val firstAttempt = part.attempt
                    while (true) {
                        // A retry resumes 1 s before the last caption that was already written.
                        val resume = part.cues().lastOrNull()?.let { (it.endMs / 1000.0 - 1).coerceIn(part.start, part.end) } ?: part.start
                        val code = runWorker(javaBin, cp, part, model, media, lang, threads, ffmpeg, resume, work)
                        if (code == 0) break
                        lastError = "exit $code" + signalName(code) + workerLogTail(work, part)
                        if (part.attempt - firstAttempt >= 2) throw MediaException("Transcription of part ${part.index + 1} (${Vtt.fmt((part.start * 1000).toLong())}–${Vtt.fmt((part.end * 1000).toLong())}) failed 3 times: $lastError")
                        part.attempt++
                        kotlinx.coroutines.delay(2000)
                    }
                }
            }.awaitAll()
        }
    }

    private fun runWorker(javaBin: String, cp: String, part: TranscriptionJob.Part, model: Path, media: Path, lang: String, threads: Int, ffmpeg: String, from: Double, work: Path): Int {
        val cmd = listOf(
            javaBin, "--enable-native-access=ALL-UNNAMED", "-Xmx768m", "-cp", cp, WhisperWorker::class.java.name,
            "--model", model.toString(), "--input", media.toString(), "--out", part.out.toString(),
            "--lang", lang, "--threads", "$threads", "--ffmpeg", ffmpeg, "--watch-parent", "1",
            "--start", "${from.toLong()}", "--duration", "${(part.end - from.toLong()).toLong() + 1}",
        )
        val p = ProcessBuilder(cmd).redirectError(work.resolve("part${part.index}.a${part.attempt}.log").toFile()).start()
        processes += p
        try {
            p.inputStream.bufferedReader().forEachLine { line ->
                line.removePrefix("PROGRESS ").toDoubleOrNull()?.let { part.doneUpTo = it.coerceAtMost(part.end) }
                if (line.startsWith("DONE")) part.doneUpTo = part.end
            }
            return p.waitFor()
        } finally {
            processes -= p
        }
    }

    private fun signalName(code: Int) = when (code) {
        130 -> " (SIGINT)"; 137 -> " (SIGKILL — out of memory?)"; 143 -> " (SIGTERM)"; 134 -> " (abort in whisper.cpp)"; 139 -> " (segfault in whisper.cpp)"; else -> ""
    }

    private fun workerLogTail(work: Path, part: TranscriptionJob.Part): String = runCatching {
        Files.readAllLines(work.resolve("part${part.index}.a${part.attempt}.log")).filterNot { it.startsWith("WARNING") }.takeLast(4).joinToString(" | ")
    }.getOrDefault("").let { if (it.isBlank()) "" else ": $it" }

    /** External whisper.cpp CLI: one process on the extracted audio (GPU builds are fast enough). */
    private suspend fun runCli(job: TranscriptionJob, cli: String, ffmpeg: String, model: Path, media: Path, a: Double, b: Double, lang: String, work: Path) {
        val wav = work.resolve("audio.wav")
        job.detail = "extracting audio"
        val (code, out) = Ffmpeg.run(listOf(ffmpeg, "-nostdin", "-hide_banner", "-loglevel", "error", "-ss", "$a", "-i", media.toString(), "-t", "${b - a}", "-vn", "-ac", "1", "-ar", "16000", "-y", wav.toString()))
        if (code != 0) throw MediaException("ffmpeg failed: ${out.take(300)}")
        job.detail = ""
        val outBase = work.resolve("cli")
        val part = TranscriptionJob.Part(0, a, b, work)
        synchronized(job.parts) { job.parts.clear(); job.parts += part }
        val threads = System.getenv("TRANSCRIBE_THREADS")?.toIntOrNull() ?: (Runtime.getRuntime().availableProcessors() / 2).coerceIn(2, 16)
        withContext(Dispatchers.IO) {
            val p = ProcessBuilder(cli, "-m", model.toString(), "-f", wav.toString(), "-l", lang, "-ovtt", "-of", outBase.toString(), "-t", "$threads", "-pp", "-np")
                .redirectErrorStream(true).start()
            processes += p
            try {
                p.inputStream.bufferedReader().forEachLine { line ->
                    Regex("""progress\s*=\s*(\d+)%""").find(line)?.let { part.doneUpTo = a + (b - a) * it.groupValues[1].toInt() / 100.0 }
                }
                if (p.waitFor() != 0) throw MediaException("whisper-cli failed (exit ${p.exitValue()})")
            } finally {
                processes -= p
            }
        }
        val shift = (a * 1000).toLong()
        val cues = Vtt.parse(Files.readString(work.resolve("cli.vtt"))).map { it.copy(startMs = it.startMs + shift, endMs = it.endMs + shift) }
        Files.writeString(part.out, "WEBVTT\n\n" + cues.joinToString("") { "${vttTs(it.startMs)} --> ${vttTs(it.endMs)}\n${it.text}\n\n" })
        part.doneUpTo = b
    }

    /** Boundaries a = x0 < x1 < … < xn = b, each inner one moved to the nearest silence (±40 s). */
    private suspend fun splitPoints(media: Path, a: Double, b: Double, n: Int): List<Double> {
        if (n <= 1) return listOf(a, b)
        val silences = runCatching { Ffmpeg.silences(media) }.getOrDefault(emptyList()).map { (s, e) -> (s + e) / 2 }
        val ideal = (1 until n).map { a + (b - a) * it / n }
        val inner = ideal.map { x -> silences.filter { abs(it - x) <= 40 }.minByOrNull { abs(it - x) } ?: x }
        return (listOf(a) + inner.sorted() + listOf(b)).distinct()
    }

    private fun mb(done: Long, total: Long) =
        if (total > 0) "${done / 1_048_576} / ${total / 1_048_576} MB" else "${done / 1_048_576} MB"

    fun vttTs(ms: Long): String = "%02d:%02d:%02d.%03d".format(ms / 3_600_000, ms / 60_000 % 60, ms / 1000 % 60, ms % 1000)
}
