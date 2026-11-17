package dev.lecturetubemcp.auth

import dev.lecturetubemcp.Config
import dev.lecturetubemcp.media.Ffmpeg
import dev.lecturetubemcp.media.Transcriber
import dev.lecturetubemcp.media.WhisperModels
import java.nio.file.Files

/** `doctor` CLI command: shows which browser the login uses and checks that everything needed works. */
object Doctor {
    suspend fun run(out: (String) -> Unit): Int {
        var problems = 0
        fun line(ok: Boolean?, text: String) { out("${when (ok) { true -> "✔"; false -> "✘"; null -> "•" }} $text") }

        val d = DefaultBrowser.detect()
        line(null, "OS default browser: " + (d?.let { "${it.name}${if (it.command.isNotEmpty()) " (${it.command.joinToString(" ")})" else ""}" } ?: "unknown"))
        val exe = runCatching { Browsers.pick(null) }
        exe.onSuccess { line(true, "Login browser: ${Browsers.lastChoiceNote} → ${it.path}") }
            .onFailure { problems++; line(false, "Login browser: ${it.message}") }
        exe.getOrNull()?.let { b ->
            // Start it headless with the debugging protocol and read cookies, then close it again.
            val r = runCatching {
                val drv = Browsers.launch(b, headless = true)
                try { drv.cookies().size } finally { drv.close() }
            }
            r.onSuccess { line(true, "Browser automation works (${b.kind.name.lowercase().replace("chromium", "DevTools protocol").replace("firefox", "WebDriver BiDi")})") }
                .onFailure { problems++; line(false, "Could not control ${b.name}: ${it.message}") }
        }
        val others = Browsers.detect().map { it.name }
        line(null, "Other supported browsers: ${others.joinToString().ifEmpty { "none" }}")

        line(SessionStore.current != null, "Stored session: " + (SessionStore.current?.let { "saved ${it.savedAt} (${it.source})" } ?: "none (run `login`)"))
        val ff = Ffmpeg.ffmpeg
        line(ff != null, "ffmpeg: ${ff ?: "not found: needed for transcription and keyframes"}").also { if (ff == null) problems++ }
        val apiLine = dev.lecturetubemcp.media.TranscribeConfig.describe()
        line(!apiLine.startsWith("API misconfigured"), "Transcription API: $apiLine").also { if (apiLine.startsWith("API misconfigured")) problems++ }
        line(null, "Default transcription engine: ${dev.lecturetubemcp.media.TranscribeConfig.defaultEngine()}; local model ${WhisperModels.default}" +
            if (Files.exists(WhisperModels.file(WhisperModels.default))) " (downloaded)" else " (downloaded on first use, ~${WhisperModels.approxMb[WhisperModels.default]} MB)")
        val recovered = runCatching { Transcriber.recover() }.getOrDefault(false)
        if (recovered) line(true, "Finished transcription(s) that were interrupted before the result was written")
        line(null, "Data directory: ${Config.dataDir}")
        return if (problems == 0) 0 else 1
    }
}
