package dev.lecturetubemcp.media

import dev.lecturetubemcp.Config
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.Files

/** An OpenAI-compatible speech-to-text API (`POST {baseUrl}/audio/transcriptions`). */
data class ApiConfig(
    val provider: String,
    val baseUrl: String,
    val apiKey: String,
    val model: String,
    /** USD per audio hour, for the cost estimate (null = unknown). */
    val pricePerHour: Double?,
    /** Upload limit per request in bytes (Groq free tier and OpenAI: 25 MB). */
    val maxUploadBytes: Long = 24L * 1024 * 1024,
    val parallel: Int = 3,
    /** Upload format: "flac" (lossless, ~2 MB per audio minute) or "ogg" = Opus 32 kbps (~0.25 MB per minute). */
    val audioFormat: String = "flac",
) {
    val label: String get() = "$provider ($model)"
    override fun toString() = "ApiConfig($provider, $baseUrl, $model, key=${apiKey.take(4)}…)"
}

@Serializable
private data class ConfigFile(
    val engine: String? = null, val provider: String? = null, val apiUrl: String? = null,
    val apiKey: String? = null, val model: String? = null, val pricePerHour: Double? = null, val parallel: Int? = null,
    val audioFormat: String? = null,
)

/**
 * Which speech-to-text engine to use. Uploading audio to a cloud API is opt-in: it is used only when the
 * user configured it explicitly (`TRANSCRIBE_ENGINE=api`, `TRANSCRIBE_API_KEY`, or `transcription.json` in the
 * data directory). Environment variables override the file. A generic `OPENAI_API_KEY`/`GROQ_API_KEY` alone
 * never enables uploads; it is only used as the key once the API engine is enabled.
 */
object TranscribeConfig {
    private data class Preset(val url: String, val model: String, val price: Map<String, Double>)
    private val presets = mapOf(
        "groq" to Preset("https://api.groq.com/openai/v1", "whisper-large-v3-turbo", mapOf("whisper-large-v3-turbo" to 0.04, "whisper-large-v3" to 0.111)),
        "openai" to Preset("https://api.openai.com/v1", "whisper-1", emptyMap()),
    )

    private val json = Json { ignoreUnknownKeys = true }

    private fun file(): ConfigFile? = runCatching {
        json.decodeFromString<ConfigFile>(Files.readString(Config.dataDir.resolve("transcription.json")))
    }.getOrNull()

    private fun env(name: String) = System.getenv(name)?.takeIf { it.isNotBlank() }

    /** "api" or "local", as configured (default local unless an API is configured). */
    fun defaultEngine(): String {
        val f = file()
        val e = env("TRANSCRIBE_ENGINE") ?: f?.engine
        return when {
            e != null -> if (e.equals("api", true)) "api" else "local"
            env("TRANSCRIBE_API_KEY") != null || f?.apiKey != null -> "api"
            else -> "local"
        }
    }

    /** The API configuration, or null if none is set up. Throws if the API is enabled but incomplete. */
    fun api(): ApiConfig? {
        val f = file()
        val enabled = (env("TRANSCRIBE_ENGINE") ?: f?.engine)?.equals("api", true) == true ||
            env("TRANSCRIBE_API_KEY") != null || f?.apiKey != null
        if (!enabled) return null
        val url = env("TRANSCRIBE_API_URL") ?: f?.apiUrl
        val provider = (env("TRANSCRIBE_API_PROVIDER") ?: f?.provider ?: when {
            url == null -> "groq"
            url.contains("groq.com") -> "groq"
            url.contains("openai.com") -> "openai"
            else -> "custom"
        }).lowercase()
        val preset = presets[provider]
        val key = env("TRANSCRIBE_API_KEY") ?: f?.apiKey
            ?: (if (provider == "groq") env("GROQ_API_KEY") else null)
            ?: (if (provider == "openai") env("OPENAI_API_KEY") else null)
            ?: throw MediaException("Transcription API ($provider) is enabled but no API key is configured. Set TRANSCRIBE_API_KEY or apiKey in ${Config.dataDir.resolve("transcription.json")}.")
        val baseUrl = (url ?: preset?.url ?: throw MediaException("Set TRANSCRIBE_API_URL for the custom transcription API.")).trimEnd('/')
        val model = env("TRANSCRIBE_API_MODEL") ?: f?.model ?: preset?.model ?: "whisper-1"
        val price = env("TRANSCRIBE_API_PRICE_PER_HOUR")?.toDoubleOrNull() ?: f?.pricePerHour ?: preset?.price?.get(model)
        val parallel = env("TRANSCRIBE_API_PARALLEL")?.toIntOrNull() ?: f?.parallel ?: 3
        val format = (env("TRANSCRIBE_API_FORMAT") ?: f?.audioFormat ?: "flac").lowercase().let { if (it == "opus" || it == "ogg") "ogg" else "flac" }
        return ApiConfig(provider, baseUrl, key, model, price, parallel = parallel.coerceIn(1, 8), audioFormat = format)
    }

    /** One line for `doctor`/status, never including the key. */
    fun describe(): String = runCatching {
        val a = api()
        if (a == null) "local Whisper (no transcription API configured)"
        else "${a.label} at ${a.baseUrl}, upload as ${if (a.audioFormat == "ogg") "Opus" else "FLAC"}" + (a.pricePerHour?.let { " (≈ $${"%.3f".format(it)} per audio hour)" } ?: "") +
            if (defaultEngine() == "api") ", used by default" else ", used when engine=api"
    }.getOrElse { "API misconfigured: ${it.message}" }
}
