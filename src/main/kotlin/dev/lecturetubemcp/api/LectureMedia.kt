package dev.lecturetubemcp.api

import dev.lecturetubemcp.media.MediaException
import dev.lecturetubemcp.media.MediaSource
import dev.lecturetubemcp.media.TranscriptionJob
import dev.lecturetubemcp.media.Transcriber
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

private fun JsonObject.height(): Int = ((this["resolution"] as? JsonArray)?.getOrNull(1)?.toString()?.toIntOrNull()) ?: 0

private fun mp4s(e: LectureTubeService.Event): List<JsonObject> =
    e.tracks.filter { it["mimetype"].s() == "video/mp4" || it["mimetype"].s()?.startsWith("audio/") == true }

private fun langCode(l: String?) = when (l) { "deu", "ger", "de" -> "de"; "eng", "en" -> "en"; else -> null }

/** The smallest progressive MP4 of the camera/main flavor: enough for speech-to-text. */
fun LectureTubeService.audioSource(e: LectureTubeService.Event): MediaSource {
    val tracks = mp4s(e)
    if (tracks.isEmpty()) {
        throw MediaException(
            if (!e.hasAuthorizedData) "No access to the media of \"${e.title}\". Call `login` (course recordings need a TU Wien login)."
            else "\"${e.title}\" has no downloadable MP4 track.",
        )
    }
    val t = tracks.sortedWith(compareBy({ if (it["flavor"].s()?.startsWith("presenter") == true) 0 else 1 }, { it.height() })).first()
    return MediaSource("lt-${e.opencastId}-${t["flavor"].s()}-${t.height()}", e.title, t["uri"].s()!!, language = langCode(e.language))
}

/**
 * The track to take keyframes from. `slides` prefers the screen capture (presentation flavor), `camera` the
 * presenter. Highest resolution up to 1080p.
 */
fun LectureTubeService.videoSource(e: LectureTubeService.Event, track: String = "auto"): MediaSource {
    val tracks = mp4s(e).filter { it["mimetype"].s() == "video/mp4" }
    if (tracks.isEmpty()) throw MediaException("No downloadable video track for \"${e.title}\"" + if (!e.hasAuthorizedData) " (try `login`)." else ".")
    val wantPresentation = track != "camera"
    val t = tracks.sortedWith(
        compareBy<JsonObject>({ (it["flavor"].s()?.startsWith("presentation") == true) != wantPresentation })
            .thenByDescending { it.height().takeIf { h -> h <= 1080 } ?: 0 },
    ).first()
    return MediaSource("lt-${e.opencastId}-${t["flavor"].s()}-${t.height()}", e.title, t["uri"].s()!!, language = langCode(e.language))
}

/** Starts speech-to-text for a LectureTube lecture (cached by Opencast id). */
suspend fun LectureTubeService.startTranscription(lecture: String, lang: String?, model: String?, fromSec: Int?, toSec: Int?): TranscriptionJob {
    val e = event(lecture)
    val src = audioSource(e)
    return Transcriber.start(e.opencastId, e.title, lang ?: src.language, model, fromSec, toSec) { src }
}
