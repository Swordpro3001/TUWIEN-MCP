package dev.lecturetubemcp.api

/** How to look up an item: by Tobira ID or by Opencast UUID. */
sealed interface ItemRef {
    data class Tobira(val id: String) : ItemRef
    data class Opencast(val uuid: String) : ItemRef
}

object Ids {
    private val uuid = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")
    private val key = Regex("^[A-Za-z0-9_-]{11}$")

    /**
     * Accepts every form a user or the LLM might pass:
     * `evKgSNekCVlvX`, `esKgSNekCVlvX`, `KgSNekCVlvX`, `:<uuid>`, `<uuid>`,
     * `https://video.tuwien.ac.at/!v/KgSNekCVlvX`, `…/~embed/!v/:<uuid>`, `…/play/<uuid>`.
     */
    fun event(input: String): ItemRef = parse(input, marker = "/!v/", tobiraPrefix = "ev", searchPrefix = "es")

    /** Same for series: `srXXX`, `ssXXX`, key, UUID, `…/!s/<key>`. */
    fun series(input: String): ItemRef = parse(input, marker = "/!s/", tobiraPrefix = "sr", searchPrefix = "ss")

    fun playlist(input: String): ItemRef = parse(input, marker = "/!p/", tobiraPrefix = "pl", searchPrefix = "sp")

    private fun parse(input: String, marker: String, tobiraPrefix: String, searchPrefix: String): ItemRef {
        var s = input.trim()
        val m = s.indexOf(marker)
        if (m >= 0) s = s.substring(m + marker.length)
        listOf("/play/", "watch.html?id=", "id=").forEach { p ->
            val i = s.indexOf(p); if (i >= 0) s = s.substring(i + p.length)
        }
        s = s.substringBefore('?').substringBefore('#').substringBefore('/').trim()
        s = s.removePrefix(":")
        if (uuid.matches(s)) return ItemRef.Opencast(s.lowercase())
        if (s.length == 13 && (s.startsWith(tobiraPrefix) || s.startsWith(searchPrefix)) && key.matches(s.substring(2))) {
            return ItemRef.Tobira(tobiraPrefix + s.substring(2))
        }
        if (key.matches(s)) return ItemRef.Tobira(tobiraPrefix + s)
        throw IllegalArgumentException(
            "Unrecognized ID '$input'. Use a Tobira ID (e.g. ${tobiraPrefix}KgSNekCVlvX), an Opencast UUID, or a video.tuwien.ac.at URL.",
        )
    }

    /** Public URL key without the 2-letter prefix. */
    fun key(tobiraId: String): String = tobiraId.drop(2)
}
