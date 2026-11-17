package dev.lecturetubemcp.api

import dev.lecturetubemcp.Config
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** Domain operations on top of [LectureTubeClient], returning compact JSON for the LLM. */
class LectureTubeService(val client: LectureTubeClient) {

    private val eventFragment = """
        fragment Ev on AuthorizedEvent {
          id opencastId title description created creators metadata isLive hasPassword
          series { id opencastId title }
          syncedData { duration startTime endTime thumbnail audioOnly }
          authorizedData {
            tracks { uri flavor mimetype resolution isMaster }
            captions { uri lang }
            segments { uri startTime }
          }
        }
    """.trimIndent()

    data class Event(
        val raw: JsonObject,
        val id: String,
        val opencastId: String,
        val title: String,
        val durationMs: Long,
        val language: String?,
        val captions: List<Pair<String?, String>>, // lang to uri
        val segments: List<Pair<Long, String>>, // startMs to image uri
        val tracks: List<JsonObject>,
        val hasAuthorizedData: Boolean,
    )

    suspend fun event(input: String): Event {
        val ref = Ids.event(input)
        val (field, arg, type) = when (ref) {
            is ItemRef.Tobira -> Triple("eventById", ref.id, "ID!")
            is ItemRef.Opencast -> Triple("eventByOpencastId", ref.uuid, "String!")
        }
        val q = "query(\$id: $type) { e: $field(id: \$id) { __typename ...Ev } }\n$eventFragment"
        val data = client.graphql(q, buildJsonObject { put("id", arg) })
        val e = data["e"].obj() ?: throw ApiException("No lecture found for '$input'.")
        when (e["__typename"].s()) {
            "NotAllowed" -> throw ApiException(
                "You are not allowed to access this lecture" +
                    (if (dev.lecturetubemcp.auth.SessionStore.current == null) " while logged out. Call the `login` tool first." else "."),
                notAuthorized = true,
            )
        }
        val ad = e["authorizedData"].obj()
        val lang = e["metadata"]["dcterms"]["language"].arr().firstOrNull().s()
        return Event(
            raw = e,
            id = e["id"].s()!!,
            opencastId = e["opencastId"].s()!!,
            title = e["title"].s() ?: "",
            durationMs = e["syncedData"]["duration"].d()?.toLong() ?: 0,
            language = lang,
            captions = ad?.get("captions").arr().map { it["lang"].s() to it["uri"].s()!! },
            segments = ad?.get("segments").arr().map { (it["startTime"].d()?.toLong() ?: 0) to it["uri"].s()!! }.sortedBy { it.first },
            tracks = ad?.get("tracks").arr().mapNotNull { it.obj() },
            hasAuthorizedData = ad != null,
        )
    }

    fun summarizeEvent(e: Event): JsonObject {
        val r = e.raw
        return buildJsonObject {
            put("id", e.id)
            put("opencastId", e.opencastId)
            put("title", e.title)
            r["description"].s()?.let { put("description", it) }
            put("url", "${Config.TOBIRA}/!v/${Ids.key(e.id)}")
            put("created", r["created"].s())
            putJsonArray("creators") { r["creators"].arr().forEach { add(it) } }
            r["series"].obj()?.let { s ->
                putJsonObject("series") { put("id", s["id"].s()); put("opencastId", s["opencastId"].s()); put("title", s["title"].s()) }
            }
            put("duration", Vtt.fmt(e.durationMs))
            put("durationMs", e.durationMs)
            e.language?.let { put("language", it) }
            put("isLive", r["isLive"].b() ?: false)
            put("audioOnly", r["syncedData"]["audioOnly"].b() ?: false)
            put("hasPassword", r["hasPassword"].b() ?: false)
            r["metadata"].obj()?.let { put("metadata", it) }
            put("accessToMedia", e.hasAuthorizedData)
            putJsonArray("captions") { e.captions.forEach { (l, u) -> addJsonObject { put("lang", l); put("uri", u) } } }
            put("slideCount", e.segments.size)
            putJsonArray("tracks") {
                e.tracks.forEach { t ->
                    addJsonObject {
                        put("flavor", t["flavor"].s()); put("mimetype", t["mimetype"].s())
                        t["resolution"]?.let { put("resolution", it) }
                        put("uri", t["uri"].s())
                    }
                }
            }
            put("thumbnail", r["syncedData"]["thumbnail"].s())
        }
    }

    suspend fun search(query: String, itemType: String?, from: String?, to: String?): JsonObject {
        val q = """
            query(${'$'}q: String!, ${'$'}f: Filters!) {
              search(query: ${'$'}q, filters: ${'$'}f) {
                __typename
                ... on SearchResults {
                  totalHits
                  items {
                    __typename
                    ... on SearchEvent { id title seriesId seriesTitle creators duration created isLive audioOnly hasPassword userIsAuthorized
                                         textMatches { start duration text ty } }
                    ... on SearchSeries { id opencastId title description }
                    ... on SearchRealm { id name path ancestorNames }
                    ... on SearchPlaylist { id playlistOpencastId: opencastId title description }
                  }
                }
              }
            }
        """.trimIndent()
        val filters = buildJsonObject {
            itemType?.uppercase()?.takeIf { it in setOf("EVENT", "SERIES", "REALM") }?.let { put("itemType", it) }
            from?.let { put("start", isoDate(it)) }
            to?.let { put("end", isoDate(it, endOfDay = true)) }
        }
        val d = client.graphql(q, buildJsonObject { put("q", query); put("f", filters) })
        val s = d["search"].obj()!!
        return when (s["__typename"].s()) {
            "SearchUnavailable" -> throw ApiException("LectureTube search is temporarily unavailable.")
            "EmptyQuery" -> throw ApiException("Query too short.")
            else -> buildJsonObject {
                put("totalHits", s["totalHits"] ?: JsonPrimitive(0))
                put("note", "At most 30 items are returned (no pagination). Refine the query or use list_lectures for paging.")
                putJsonArray("items") {
                    s["items"].arr().forEach { item ->
                        when (item["__typename"].s()) {
                            "SearchEvent" -> addJsonObject {
                                put("type", "lecture")
                                put("id", "ev" + item["id"].s()!!.drop(2))
                                put("title", item["title"].s())
                                put("series", item["seriesTitle"].s())
                                item["seriesId"].s()?.let { put("seriesId", "sr" + it.drop(2)) }
                                put("creators", item["creators"] ?: JsonArray(emptyList()))
                                put("created", item["created"].s())
                                put("duration", Vtt.fmt(item["duration"].d()?.toLong() ?: 0))
                                put("canWatch", item["userIsAuthorized"].b() ?: false)
                                val tm = item["textMatches"].arr()
                                if (tm.isNotEmpty()) putJsonArray("transcriptMatches") {
                                    tm.take(5).forEach { m ->
                                        addJsonObject {
                                            put("at", Vtt.fmt(m["start"].d()?.toLong() ?: 0))
                                            put("source", m["ty"].s())
                                            put("text", m["text"].s())
                                        }
                                    }
                                }
                            }
                            "SearchSeries" -> addJsonObject {
                                put("type", "series"); put("id", "sr" + item["id"].s()!!.drop(2))
                                put("opencastId", item["opencastId"].s()); put("title", item["title"].s())
                                item["description"].s()?.let { put("description", it.take(300)) }
                            }
                            "SearchRealm" -> addJsonObject {
                                put("type", "page"); put("path", item["path"].s()); put("name", item["name"].s())
                            }
                            "SearchPlaylist" -> addJsonObject {
                                put("type", "playlist"); put("id", item["id"].s()); put("title", item["title"].s())
                            }
                        }
                    }
                }
            }
        }
    }

    suspend fun series(input: String): JsonObject {
        val ref = Ids.series(input)
        val (field, arg, type) = when (ref) {
            is ItemRef.Tobira -> Triple("seriesById", ref.id, "ID!")
            is ItemRef.Opencast -> Triple("seriesByOpencastId", ref.uuid, "String!")
        }
        val q = """
            query(${'$'}id: $type) {
              s: $field(id: ${'$'}id) {
                id opencastId title description created
                entries {
                  __typename
                  ... on AuthorizedEvent { id title created creators isLive syncedData { duration } authorizedData { captions { lang } segments { startTime } } }
                }
              }
            }
        """.trimIndent()
        val s = client.graphql(q, buildJsonObject { put("id", arg) })["s"].obj()
            ?: throw ApiException("No series found for '$input'.")
        val entries = s["entries"].arr()
        val hidden = entries.count { it["__typename"].s() != "AuthorizedEvent" }
        return buildJsonObject {
            put("id", s["id"].s()); put("opencastId", s["opencastId"].s()); put("title", s["title"].s())
            s["description"].s()?.let { put("description", it) }
            put("url", "${Config.TOBIRA}/!s/${Ids.key(s["id"].s()!!)}")
            put("rss", "${Config.TOBIRA}/~rss/series/${Ids.key(s["id"].s()!!)}")
            put("lectureCount", entries.size - hidden)
            if (hidden > 0) put("notAccessible", "$hidden lecture(s) are not accessible with the current login state")
            putJsonArray("lectures") {
                entries.filter { it["__typename"].s() == "AuthorizedEvent" }
                    .sortedBy { it["created"].s() }
                    .forEach { e ->
                        addJsonObject {
                            put("id", e["id"].s()); put("title", e["title"].s()); put("created", e["created"].s())
                            put("duration", Vtt.fmt(e["syncedData"]["duration"].d()?.toLong() ?: 0))
                            put("captions", buildJsonArray { e["authorizedData"]["captions"].arr().forEach { add(it["lang"] ?: JsonPrimitive("?")) } })
                            put("slides", e["authorizedData"]["segments"].arr().size)
                        }
                    }
            }
        }
    }

    /** Paged listing via the Opencast Search API (Tobira's search can't page). */
    suspend fun listLectures(query: String?, seriesId: String?, sort: String?, offset: Int, limit: Int): JsonObject {
        val sid = seriesId?.let { sidInput ->
            when (val r = Ids.series(sidInput)) {
                is ItemRef.Opencast -> r.uuid
                is ItemRef.Tobira -> client.graphql(
                    "query(\$id: ID!) { seriesById(id: \$id) { opencastId } }",
                    buildJsonObject { put("id", r.id) },
                )["seriesById"]["opencastId"].s() ?: throw ApiException("Unknown series '$sidInput'")
            }
        }
        val d = client.opencast(
            "/search/episode.json",
            mapOf("q" to query, "sid" to sid, "sort" to (sort ?: "created desc"), "offset" to offset, "limit" to limit.coerceIn(1, 250)),
        )
        return buildJsonObject {
            val total = d["total"].d()?.toLong() ?: 0
            put("total", total); put("offset", offset); put("limit", limit)
            // The search index reports at most 10000 hits; paging beyond that still works.
            if (total >= 10_000) put("totalIsLowerBound", true)
            putJsonArray("lectures") {
                d["result"].arr().forEach { r ->
                    val mp = r["mediapackage"]
                    val tracks = mp["media"]["track"].asList()
                    val atts = mp["attachments"]["attachment"].asList()
                    addJsonObject {
                        put("opencastId", mp["id"].s()); put("title", mp["title"].s())
                        put("series", mp["seriestitle"].s()); put("seriesOpencastId", mp["series"].s())
                        put("start", mp["start"].s())
                        put("duration", Vtt.fmt(mp["duration"].d()?.toLong() ?: 0))
                        mp["language"].s()?.let { put("language", it) }
                        put("captions", tracks.count { it["mimetype"].s() == "text/vtt" })
                        put("slides", atts.count { it["type"].s()?.contains("segment+preview") == true })
                    }
                }
            }
        }
    }

    suspend fun listSeries(query: String?, sort: String?, offset: Int, limit: Int): JsonObject {
        val d = client.opencast(
            "/search/series.json",
            mapOf("q" to query, "sort" to (sort ?: "modified desc"), "offset" to offset, "limit" to limit.coerceIn(1, 250)),
        )
        return buildJsonObject {
            put("total", d["total"] ?: JsonPrimitive(0)); put("offset", offset); put("limit", limit)
            putJsonArray("series") {
                d["result"].arr().forEach { r ->
                    addJsonObject {
                        put("opencastId", r["dc"]["identifier"].arr().firstOrNull().s())
                        put("title", r["dc"]["title"].arr().firstOrNull().s())
                        r["dc"]["created"].arr().firstOrNull().s()?.let { put("created", it) }
                        r["dc"]["description"].arr().firstOrNull().s()?.let { put("description", it.take(300)) }
                    }
                }
            }
        }
    }

    suspend fun browse(path: String): JsonObject {
        val q = """
            query(${'$'}p: String!) {
              r: realmByPath(path: ${'$'}p) {
                id name path
                children { name path numberOfDescendants }
                blocks {
                  __typename
                  ... on TitleBlock { content }
                  ... on TextBlock { content }
                  ... on SeriesBlock { series { id title } }
                  ... on VideoBlock { event { __typename ... on AuthorizedEvent { id title } } }
                  ... on PlaylistBlock { playlist { __typename ... on AuthorizedPlaylist { id title } } }
                }
              }
            }
        """.trimIndent()
        val r = client.graphql(q, buildJsonObject { put("p", path) })["r"].obj()
            ?: throw ApiException("No page at '$path'. Start with '/'.")
        return buildJsonObject {
            put("path", r["path"].s()); put("name", r["name"].s())
            put("url", Config.TOBIRA + r["path"].s())
            put("children", r["children"] ?: JsonArray(emptyList()))
            putJsonArray("content") {
                r["blocks"].arr().forEach { b ->
                    when (b["__typename"].s()) {
                        "TitleBlock" -> addJsonObject { put("title", b["content"].s()) }
                        "TextBlock" -> addJsonObject { put("text", b["content"].s()?.take(1000)) }
                        "SeriesBlock" -> b["series"].obj()?.let { s -> addJsonObject { put("series", s["title"].s()); put("seriesId", s["id"].s()) } }
                        "VideoBlock" -> b["event"].obj()?.takeIf { it["id"] != null }?.let { e -> addJsonObject { put("lecture", e["title"].s()); put("id", e["id"].s()) } }
                        "PlaylistBlock" -> b["playlist"].obj()?.takeIf { it["id"] != null }?.let { p -> addJsonObject { put("playlist", p["title"].s()); put("id", p["id"].s()) } }
                    }
                }
            }
        }
    }

    suspend fun me(): JsonObject {
        val q = """
            { currentUser { username displayName email userRole roles canUpload canUseStudio canUseEditor canCreateSeries isTobiraAdmin } }
        """.trimIndent()
        return client.graphql(q)["currentUser"].obj() ?: throw ApiException("Not logged in.", notAuthorized = true)
    }

    suspend fun myVideos(offset: Int, limit: Int): JsonObject {
        val q = """
            query(${'$'}o: Int!, ${'$'}l: Int!) {
              currentUser {
                myVideos(offset: ${'$'}o, limit: ${'$'}l) {
                  totalCount pageInfo { hasNextPage }
                  items { id title created series { id title } syncedData { duration } authorizedData { captions { lang } } }
                }
              }
            }
        """.trimIndent()
        val u = client.graphql(q, buildJsonObject { put("o", offset); put("l", limit.coerceIn(1, 100)) })["currentUser"].obj()
            ?: throw ApiException("Not logged in. Call the `login` tool first.", notAuthorized = true)
        return u["myVideos"].obj()!!
    }

    suspend fun mySeries(offset: Int, limit: Int): JsonObject {
        val q = """
            query(${'$'}o: Int!, ${'$'}l: Int!) {
              currentUser { mySeries(offset: ${'$'}o, limit: ${'$'}l) { totalCount pageInfo { hasNextPage } items { id opencastId title created } } }
            }
        """.trimIndent()
        val u = client.graphql(q, buildJsonObject { put("o", offset); put("l", limit.coerceIn(1, 100)) })["currentUser"].obj()
            ?: throw ApiException("Not logged in. Call the `login` tool first.", notAuthorized = true)
        return u["mySeries"].obj()!!
    }

    private fun isoDate(s: String, endOfDay: Boolean = false): String =
        if (s.contains('T')) s else s + if (endOfDay) "T23:59:59Z" else "T00:00:00Z"

    companion object {
        /** Very small guard so the raw GraphQL tool stays read-only. */
        fun isReadOnlyDocument(doc: String): Boolean {
            val noStrings = doc.replace(Regex("\"\"\"[\\s\\S]*?\"\"\"|\"(?:\\\\.|[^\"\\\\])*\""), "\"\"")
            val noComments = noStrings.lines().joinToString("\n") { it.substringBefore('#') }
            return !Regex("(^|[\\s{}])(mutation|subscription)\\b").containsMatchIn(noComments)
        }
    }
}
