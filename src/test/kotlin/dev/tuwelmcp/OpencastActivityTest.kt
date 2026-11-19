package dev.tuwelmcp

import dev.tuwelmcp.moodle.OpencastActivity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class OpencastActivityTest {
    private val id = "d1c6c942-c320-4ad4-8cb6-1630d6d79047"
    private val cdn = "https://cdn.video.tuwien.ac.at/static/lecture_tube/engage-player/$id"

    @Test
    fun parsesActivityUrls() {
        assertEquals(3053974L to id, OpencastActivity.parseUrl("https://tuwel.tuwien.ac.at/mod/opencast/view.php?id=3053974&e=$id"))
        assertEquals(3053974L to id, OpencastActivity.parseUrl("https://tuwel.tuwien.ac.at/mod/opencast/view.php?id=3053974&amp;e=$id"))
        assertNull(OpencastActivity.parseUrl("https://tuwel.tuwien.ac.at/mod/opencast/view.php?id=3053974"))
        assertNull(OpencastActivity.parseUrl("https://tuwel.tuwien.ac.at/pluginfile.php/1/a.mp4"))
    }

    @Test
    fun listsEpisodesOfACollection() {
        val html = """<ul class="series"> <li class="episode" title="Pr&auml;dikatenlogik 1 - VU vom 2026-11-17 &amp; Q&amp;A">
            <a href="https://tuwel.tuwien.ac.at/mod/opencast/view.php?id=3053974&amp;e=$id"><img src="x"></a></li>
            <li class="episode" title="Aussagenlogik"> <a href="https://tuwel.tuwien.ac.at/mod/opencast/view.php?id=3053974&amp;e=00000000-0000-0000-0000-000000000001">"""
        val eps = OpencastActivity.episodes(3053974, html)
        assertEquals(listOf(id, "00000000-0000-0000-0000-000000000001"), eps.map { it.id })
        assertEquals("Pr&auml;dikatenlogik 1 - VU vom 2026-11-17 & Q&A", eps[0].title)
        assertEquals("https://tuwel.tuwien.ac.at/mod/opencast/view.php?id=3053974&e=$id", eps[0].url)
    }

    @Test
    fun readsThePlayerConfig() {
        val html = """var cfg = {"metadata":{"id":"$id","title":"Logik [Teil 1] \"live\"","duration":0},
            "streams":[{"sources":{"mp4":[
              {"src":"$cdn/a/19654180.segment-1-720p.mp4","mimetype":"video/mp4","res":{"w":1280,"h":720}},
              {"src":"$cdn/b/19654180.segment-0-1080p.mp4","mimetype":"video/mp4","res":{"w":1920,"h":1080}},
              {"src":"http://insecure.example/x.mp4","mimetype":"video/mp4","res":{"w":640,"h":360}}],
              "hls":[{"src":"$cdn/c/x.m3u8"}]},"content":"presentation","role":"mainAudio"},
              {"sources":{"mp4":[{"src":"$cdn/d/cam-480p.mp4","res":{"w":854,"h":480}}]},"content":"presenter"}],"frameList":[]};"""
        val (title, streams) = OpencastActivity.player(html)
        assertEquals("Logik [Teil 1] \"live\"", title)
        assertEquals(3, streams.size) // the http:// source is dropped
        assertEquals("$cdn/d/cam-480p.mp4", OpencastActivity.pick(streams, forAudio = true).url)
        assertEquals("$cdn/a/19654180.segment-1-720p.mp4", OpencastActivity.pick(streams, forAudio = false, track = "auto").url)
        assertEquals("$cdn/d/cam-480p.mp4", OpencastActivity.pick(streams, forAudio = false, track = "camera").url)
        // a page that shows one recording only lists itself
        assertEquals(listOf(id), OpencastActivity.episodes(1, html).map { it.id })
    }
}
