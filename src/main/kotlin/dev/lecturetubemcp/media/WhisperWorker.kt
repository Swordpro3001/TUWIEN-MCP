package dev.lecturetubemcp.media

import io.github.givimad.whisperjni.WhisperFullParams
import io.github.givimad.whisperjni.WhisperJNI
import io.github.givimad.whisperjni.WhisperSamplingStrategy
import java.io.BufferedWriter
import java.io.DataInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import kotlin.system.exitProcess

/**
 * Speech-to-text worker. Runs in a **separate JVM** (started by [Transcriber]) so that a crash in the
 * native whisper.cpp code can't take the MCP server down.
 *
 * ffmpeg decodes the input (local file or URL) to 16 kHz mono PCM on a pipe. Audio is transcribed in
 * chunks of about [chunkSec] seconds (default 120: frequent progress and partial text), each cut at the quietest point near the boundary so that words are not split.
 * Cues are appended to `<out>.partial` as they are produced (so partial transcripts are readable) and
 * progress is reported on stdout as `PROGRESS <seconds>`. On success the file is renamed to `<out>`.
 *
 * Args: `--model <ggml.bin> --input <file|url> --out <file.vtt> [--lang de|en|auto] [--threads N] [--chunk 120]
 *        [--start seconds] [--duration seconds] [--prompt text] [--ffmpeg path]`
 */
object WhisperWorker {
    private const val RATE = 16_000

    @JvmStatic
    fun main(args: Array<String>) {
        val opt = args.toList().windowed(2, 2, partialWindows = false).associate { it[0].removePrefix("--") to it[1] }
        val model = Paths.get(opt["model"] ?: usage())
        val input = opt["input"] ?: usage()
        val out = Paths.get(opt["out"] ?: usage())
        val lang = opt["lang"] ?: "auto"
        val threads = opt["threads"]?.toInt() ?: Runtime.getRuntime().availableProcessors().coerceAtMost(16)
        val chunkSec = opt["chunk"]?.toInt() ?: 120
        val ffmpeg = opt["ffmpeg"] ?: "ffmpeg"
        val prompt = opt["prompt"]

        // Exit when the parent (MCP server) goes away: it holds our stdin open and never writes to it.
        // Ctrl+C in the terminal of the MCP client sends SIGINT to the whole process group. A transcription must
        // survive that; the parent stops workers with SIGTERM or by closing our stdin (watch-parent).
        runCatching { sun.misc.Signal.handle(sun.misc.Signal("INT")) { } }
        if (opt["watch-parent"] == "1") {
            Thread {
                runCatching { while (System.`in`.read() >= 0) { /* ignore */ } }
                System.err.println("parent gone; stopping"); Runtime.getRuntime().halt(5)
            }.apply { isDaemon = true }.start()
        }

        WhisperJNI.loadLibrary()
        WhisperJNI.setLibraryLogger { } // silence whisper.cpp logging
        val whisper = WhisperJNI()
        val ctx = whisper.init(model)

        val start = opt["start"]?.toLong() ?: 0L
        val cmd = mutableListOf(ffmpeg, "-nostdin", "-hide_banner", "-loglevel", "error")
        if (start > 0) cmd += listOf("-ss", "$start")
        cmd += listOf("-i", input)
        opt["duration"]?.let { cmd += listOf("-t", it) }
        cmd += listOf("-vn", "-ac", "1", "-ar", "$RATE", "-f", "s16le", "-")
        val ff = ProcessBuilder(cmd).redirectError(ProcessBuilder.Redirect.INHERIT).start()
        val pcm = DataInputStream(ff.inputStream.buffered(1 shl 20))

        val partial = out.resolveSibling(out.fileName.toString() + ".partial")
        Files.createDirectories(out.toAbsolutePath().parent)
        val writer: BufferedWriter = Files.newBufferedWriter(partial)
        writer.write("WEBVTT\n\n"); writer.flush()

        val filter = Speech.Filter()
        var carry = FloatArray(0)
        var offsetSamples = start * RATE
        var eof = false
        while (!eof || carry.isNotEmpty()) {
            val want = chunkSec * RATE
            val fresh = FloatArray(want)
            var n = 0
            if (!eof) {
                val buf = ByteArray(want * 2)
                var read = 0
                while (read < buf.size) {
                    val r = pcm.read(buf, read, buf.size - read)
                    if (r < 0) { eof = true; break }
                    read += r
                }
                val bb = ByteBuffer.wrap(buf, 0, read - read % 2).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                n = bb.remaining()
                for (i in 0 until n) fresh[i] = bb.get(i) / 32768f
            }
            val audio = FloatArray(carry.size + n).also { carry.copyInto(it); fresh.copyInto(it, carry.size, 0, n) }
            if (audio.isEmpty()) break
            // Cut at the quietest 100 ms window within the last 4 s (unless this is the end).
            val cut = if (eof) audio.size else quietCut(audio, audio.size - 4 * RATE, audio.size)
            val chunk = audio.copyOfRange(0, cut)
            carry = audio.copyOfRange(cut, audio.size)

            // Only speech goes to Whisper: silence and noise make it hallucinate (and are slow to decode).
            for (w in Speech.windows(chunk)) {
                val piece = chunk.copyOfRange(w.start, w.end)
                if (piece.size < RATE / 2) continue
                val p = WhisperFullParams(WhisperSamplingStrategy.GREEDY).apply {
                    nThreads = threads
                    language = lang
                    translate = false
                    noContext = true // a hallucinated phrase must not be fed into the next window
                    printProgress = false; printRealtime = false; printTimestamps = false; printSpecial = false
                    suppressBlank = true
                    suppressNonSpeechTokens = true
                    if (prompt != null) initialPrompt = prompt
                }
                val rc = whisper.full(ctx, p, piece, piece.size)
                if (rc != 0) {
                    System.err.println("whisper failed with code $rc"); exitProcess(3)
                }
                val base = (offsetSamples + w.start) * 1000 / RATE
                for (i in 0 until whisper.fullNSegments(ctx)) {
                    val text = whisper.fullGetSegmentText(ctx, i).trim()
                    if (text.isEmpty() || text.matches(Regex("""^[\[(].*[])]$"""))) continue // [Musik], (applause)
                    val t0 = base + whisper.fullGetSegmentTimestamp0(ctx, i) * 10
                    val t1 = base + whisper.fullGetSegmentTimestamp1(ctx, i) * 10
                    if (!filter.accept(t0, text)) continue
                    writer.write("${ts(t0)} --> ${ts(t1)}\n$text\n\n")
                }
                writer.flush()
            }
            offsetSamples += chunk.size
            println("PROGRESS ${offsetSamples / RATE}"); System.out.flush()
        }
        writer.close()
        val code = ff.waitFor()
        whisper.free(ctx)
        if (code != 0 && offsetSamples == start * RATE) {
            System.err.println("ffmpeg exited with $code"); exitProcess(4)
        }
        Files.move(partial, out, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        println("DONE ${offsetSamples / RATE}")
        exitProcess(0)
    }

    private fun quietCut(a: FloatArray, from: Int, to: Int): Int {
        val win = RATE / 10
        var best = to
        var bestE = Double.MAX_VALUE
        var i = from.coerceAtLeast(0)
        while (i + win <= to) {
            var e = 0.0
            for (j in i until i + win) e += a[j] * a[j]
            if (e < bestE) { bestE = e; best = i + win / 2 }
            i += win / 2
        }
        return best
    }

    private fun ts(ms: Long): String = "%02d:%02d:%02d.%03d".format(ms / 3_600_000, ms / 60_000 % 60, ms / 1000 % 60, ms % 1000)

    private fun usage(): Nothing {
        System.err.println("usage: WhisperWorker --model <ggml.bin> --input <file|url> --out <out.vtt> [--lang auto] [--threads N]")
        exitProcess(2)
    }
}
