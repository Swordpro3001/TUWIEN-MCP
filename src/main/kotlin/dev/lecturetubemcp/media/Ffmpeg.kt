package dev.lecturetubemcp.media

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.Path

class MediaException(message: String) : Exception(message)

data class ProbeInfo(val durationSec: Double, val hasAudio: Boolean, val hasVideo: Boolean, val width: Int?, val height: Int?)

/** Thin wrapper around the ffmpeg / ffprobe command-line tools. */
object Ffmpeg {
    private fun find(name: String, env: String): String? {
        System.getenv(env)?.takeIf { File(it).canExecute() }?.let { return it }
        val exe = if (System.getProperty("os.name").lowercase().contains("win")) "$name.exe" else name
        return System.getenv("PATH").orEmpty().split(File.pathSeparator).map { File(it, exe) }.firstOrNull { it.isFile && it.canExecute() }?.absolutePath
            ?: listOf("/opt/homebrew/bin/$name", "/usr/local/bin/$name").firstOrNull { File(it).canExecute() }
    }

    val ffmpeg: String? by lazy { find("ffmpeg", "FFMPEG") }
    val ffprobe: String? by lazy { find("ffprobe", "FFPROBE") ?: ffmpeg?.let { File(File(it).parentFile, "ffprobe").takeIf(File::canExecute)?.path } }

    fun require(): String = ffmpeg ?: throw MediaException(
        "ffmpeg is required for transcription and keyframes but was not found. Install it " +
            "(Arch: `sudo pacman -S ffmpeg`, Debian/Ubuntu: `sudo apt install ffmpeg`, macOS: `brew install ffmpeg`, " +
            "Windows: `winget install ffmpeg`) or set FFMPEG to its path.",
    )

    /** Runs a command, returns (exit code, stderr+stdout text). */
    suspend fun run(cmd: List<String>, timeoutSec: Long = 3600): Pair<Int, String> = withContext(Dispatchers.IO) {
        val p = ProcessBuilder(cmd).redirectErrorStream(true).redirectInput(ProcessBuilder.Redirect.PIPE).start()
        val out = StringBuilder()
        val reader = Thread { p.inputStream.bufferedReader().forEachLine { synchronized(out) { if (out.length < 4_000_000) out.append(it).append('\n') } } }
        reader.isDaemon = true; reader.start()
        if (!p.waitFor(timeoutSec, java.util.concurrent.TimeUnit.SECONDS)) {
            p.destroyForcibly(); throw MediaException("${File(cmd[0]).name} timed out")
        }
        reader.join(2000)
        p.exitValue() to synchronized(out) { out.toString() }
    }

    suspend fun probe(file: Path): ProbeInfo {
        val probe = ffprobe ?: throw MediaException("ffprobe not found (it ships with ffmpeg).")
        val (code, out) = run(listOf(probe, "-v", "error", "-show_entries", "format=duration:stream=codec_type,width,height", "-of", "default=nw=1", file.toString()), 120)
        if (code != 0) throw MediaException("ffprobe failed: ${out.take(300)}")
        val lines = out.lines()
        val dur = lines.firstOrNull { it.startsWith("duration=") }?.substringAfter('=')?.toDoubleOrNull() ?: 0.0
        val types = lines.filter { it.startsWith("codec_type=") }.map { it.substringAfter('=') }
        return ProbeInfo(
            dur, "audio" in types, "video" in types,
            lines.firstOrNull { it.startsWith("width=") }?.substringAfter('=')?.toIntOrNull(),
            lines.firstOrNull { it.startsWith("height=") }?.substringAfter('=')?.toIntOrNull(),
        )
    }

    /** Silent intervals (start, end) in seconds, via `silencedetect`. Fast: decodes audio only. */
    suspend fun silences(file: Path, noiseDb: Int = -35, minDur: Double = 0.4): List<Pair<Double, Double>> {
        val (_, out) = run(listOf(require(), "-nostdin", "-hide_banner", "-vn", "-i", file.toString(), "-af", "silencedetect=n=${noiseDb}dB:d=$minDur", "-f", "null", "-"), 1800)
        val starts = Regex("""silence_start: ([\d.]+)""").findAll(out).map { it.groupValues[1].toDouble() }.toList()
        val ends = Regex("""silence_end: ([\d.]+)""").findAll(out).map { it.groupValues[1].toDouble() }.toList()
        return starts.zip(ends)
    }
}
