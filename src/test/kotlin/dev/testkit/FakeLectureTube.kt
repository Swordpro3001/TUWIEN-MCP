package dev.testkit

import dev.lecturetubemcp.api.LectureTubeClient
import dev.lecturetubemcp.api.LectureTubeService
import dev.lecturetubemcp.auth.Session

/** Canned LectureTube backend: Tobira GraphQL, Opencast search API and the CDN. */
object FakeLectureTube {
    const val SERIES_UUID = "11111111-2222-3333-4444-555555555555"
    const val EVENT_UUID = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"
    const val VTT = "WEBVTT\n\n00:00:01.000 --> 00:00:04.000\nHallo und willkommen zur Algebra.\n\n" +
        "00:01:05.000 --> 00:01:09.000\nHeute geht es um Gruppen.\n\n00:02:10.000 --> 00:02:15.000\nEine Gruppe ist eine Menge mit Verknüpfung.\n"

    private fun event(id: String, uuid: String, captions: Boolean) = """
        {"__typename":"AuthorizedEvent","id":"$id","opencastId":"$uuid","title":"Algebra VO ${id.takeLast(3)}",
         "description":"Gruppen und Ringe","created":"2025-10-01T10:00:00Z","creators":["Prof. Noether"],
         "metadata":{"dcterms":{"language":["deu"]}},"isLive":false,"hasPassword":false,
         "series":{"id":"srAbCdEfGhIjK","opencastId":"$SERIES_UUID","title":"104.271 Algebra (2025W)"},
         "syncedData":{"duration":5400000,"startTime":null,"endTime":null,"thumbnail":"https://cdn.video.tuwien.ac.at/t.jpg","audioOnly":false},
         "authorizedData":{
           "tracks":[
             {"uri":"https://cdn.video.tuwien.ac.at/presenter-360.mp4","flavor":"presenter/delivery","mimetype":"video/mp4","resolution":[640,360],"isMaster":false},
             {"uri":"https://cdn.video.tuwien.ac.at/presentation-1080.mp4","flavor":"presentation/delivery","mimetype":"video/mp4","resolution":[1920,1080],"isMaster":false}],
           "captions":${if (captions) """[{"uri":"https://cdn.video.tuwien.ac.at/de.vtt","lang":"de"},{"uri":"https://cdn.video.tuwien.ac.at/en.vtt","lang":"en"}]""" else "[]"},
           "segments":[{"uri":"https://cdn.video.tuwien.ac.at/s1.jpg","startTime":0},{"uri":"https://cdn.video.tuwien.ac.at/s2.png","startTime":120000},
                       {"uri":"https://cdn.video.tuwien.ac.at/s3.jpg","startTime":600000}]}}
    """.trimIndent()

    private val series = """
        {"id":"srAbCdEfGhIjK","opencastId":"$SERIES_UUID","title":"104.271 Algebra (2025W)","description":"VO","created":"2025-09-01",
         "entries":[
           {"__typename":"AuthorizedEvent","id":"evKgSNekCVlvX","title":"VO 1","created":"2025-10-01","creators":[],"isLive":false,
            "syncedData":{"duration":5400000},"authorizedData":{"captions":[{"lang":"de"}],"segments":[{"startTime":0}]}},
           {"__typename":"NotAllowed"}]}
    """.trimIndent()

    fun graphql(q: String, vars: String): String = when {
        "eventById" in q || "eventByOpencastId" in q -> when {
            "Forbidden12" in vars -> """{"data":{"e":{"__typename":"NotAllowed"}}}"""
            "Missing1234" in vars -> """{"data":{"e":null}}"""
            "NoCaptions1" in vars -> """{"data":{"e":${event("evNoCaptions1", "bbbbbbbb-bbbb-cccc-dddd-eeeeeeeeeeee", false)}}}"""
            else -> """{"data":{"e":${event("evKgSNekCVlvX", EVENT_UUID, true)}}}"""
        }
        "seriesById" in q || "seriesByOpencastId" in q ->
            if ("Missing1234" in vars) """{"data":{"s":null,"seriesById":null}}""" else """{"data":{"s":$series,"seriesById":$series}}"""
        "search(" in q -> if ("\"q\":\"x\"" in vars) """{"data":{"search":{"__typename":"EmptyQuery"}}}""" else """
            {"data":{"search":{"__typename":"SearchResults","totalHits":4,"items":[
              {"__typename":"SearchEvent","id":"esKgSNekCVlvX","title":"VO 1","seriesId":"ssAbCdEfGhIjK","seriesTitle":"Algebra","creators":["N"],
               "duration":5400000,"created":"2025-10-01","isLive":false,"audioOnly":false,"hasPassword":false,"userIsAuthorized":true,
               "textMatches":[{"start":65000,"duration":4000,"text":"Gruppen","ty":"CAPTION"}]},
              {"__typename":"SearchSeries","id":"ssAbCdEfGhIjK","opencastId":"$SERIES_UUID","title":"Algebra","description":"VO"},
              {"__typename":"SearchRealm","id":"r1","name":"E104","path":"/lectures/E104","ancestorNames":[]},
              {"__typename":"SearchPlaylist","id":"plX","playlistOpencastId":"p","title":"PL","description":null}]}}}
        """.trimIndent()
        "realmByPath" in q -> if ("/nowhere" in vars) """{"data":{"r":null}}""" else """
            {"data":{"r":{"id":"r","name":"Lectures","path":"/lectures","children":[{"name":"E104","path":"/lectures/E104","numberOfDescendants":3}],
              "blocks":[{"__typename":"TitleBlock","content":"Welcome"},{"__typename":"TextBlock","content":"Some text"},
                {"__typename":"SeriesBlock","series":{"id":"srAbCdEfGhIjK","title":"Algebra"}},
                {"__typename":"VideoBlock","event":{"__typename":"AuthorizedEvent","id":"evKgSNekCVlvX","title":"VO 1"}},
                {"__typename":"PlaylistBlock","playlist":{"__typename":"AuthorizedPlaylist","id":"plX","title":"PL"}}]}}}
        """.trimIndent()
        "myVideos" in q -> """{"data":{"currentUser":{"myVideos":{"totalCount":1,"pageInfo":{"hasNextPage":false},"items":[{"id":"evKgSNekCVlvX","title":"Mine"}]}}}}"""
        "mySeries" in q -> """{"data":{"currentUser":{"mySeries":{"totalCount":0,"pageInfo":{"hasNextPage":false},"items":[]}}}}"""
        "mutationLike" in q -> """{"errors":[{"message":"denied","extensions":{"kind":"NOT_AUTHORIZED"}}]}"""
        "currentUser" in q -> """{"data":{"currentUser":{"username":"e123","displayName":"Ada Student","email":"a@b"}}}"""
        else -> """{"data":{"echo":true}}"""
    }

    val handler: (Req) -> Resp = { r ->
        when {
            r.uri.host == "video.tuwien.ac.at" && r.path == "/graphql" -> {
                val j = r.json
                text(graphql(j["query"].toString(), j["variables"].toString()))
            }
            r.path == "/search/episode.json" -> text(
                """{"total":10000,"result":[{"mediapackage":{"id":"$EVENT_UUID","title":"VO 1","seriestitle":"Algebra","series":"$SERIES_UUID",
                  "start":"2025-10-01","duration":5400000,"language":"de",
                  "media":{"track":[{"mimetype":"text/vtt"},{"mimetype":"video/mp4"}]},
                  "attachments":{"attachment":{"type":"presentation/segment+preview"}}}}]}""",
            )
            r.path == "/search/series.json" -> text(
                if ((r.params["offset"] ?: "0") != "0") """{"total":1,"result":[]}""" else
                    """{"total":1,"result":[{"dc":{"identifier":["$SERIES_UUID"],"title":["104.271 Algebra (2025W)"],"created":["2025-09-01"],
                      "description":["VO"]},"searchable_acl":{"read":["TUWEL_42_Learner"],"write":[]}}]}""",
            )
            r.path == "/info/me.json" -> text("""{"user":{"username":"e123"}}""")
            r.path.endsWith(".vtt") -> text(VTT, contentType = "text/vtt")
            r.path.endsWith(".jpg") || r.path.endsWith(".png") -> Resp(200, byteArrayOf(1, 2, 3))
            else -> text("not found", 404, "text/plain")
        }
    }

    fun service(http: FakeHttp = FakeHttp(handler), session: Session? = null) =
        LectureTubeService(LectureTubeClient({ session }, http))
}
