package dev.lecturetubemcp

import dev.lecturetubemcp.api.Ids
import dev.lecturetubemcp.api.ItemRef
import dev.lecturetubemcp.api.LectureTubeService
import dev.lecturetubemcp.api.Vtt
import dev.lecturetubemcp.auth.Session
import dev.lecturetubemcp.auth.StoredCookie
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ParsingTest {
    private val uuid = "97c381cc-88f8-44ab-a98e-98d10716e96d"

    @Test fun eventIds() {
        val t = ItemRef.Tobira("evKgSNekCVlvX")
        assertEquals(t, Ids.event("evKgSNekCVlvX"))
        assertEquals(t, Ids.event("esKgSNekCVlvX"))
        assertEquals(t, Ids.event("KgSNekCVlvX"))
        assertEquals(t, Ids.event("https://video.tuwien.ac.at/!v/KgSNekCVlvX?t=10"))
        assertEquals(ItemRef.Opencast(uuid), Ids.event(uuid))
        assertEquals(ItemRef.Opencast(uuid), Ids.event("https://video.tuwien.ac.at/~embed/!v/:$uuid"))
        assertEquals(ItemRef.Opencast(uuid), Ids.event("https://lecturetube.video.tuwien.ac.at/play/$uuid"))
        assertFailsWith<IllegalArgumentException> { Ids.event("nonsense") }
    }

    @Test fun seriesIds() {
        assertEquals(ItemRef.Tobira("srD_IbwaCU8_t"), Ids.series("ssD_IbwaCU8_t"))
        assertEquals(ItemRef.Tobira("srD_IbwaCU8_t"), Ids.series("https://video.tuwien.ac.at/!s/D_IbwaCU8_t"))
    }

    @Test fun vttParsingAndDedupe() {
        val vtt = """
            WEBVTT

            1
            00:00:01.000 --> 00:00:03.000
            <v Speaker>Hallo</v> zusammen

            00:00:03.000 --> 00:00:04.000
            Hallo zusammen

            01:02:03.500 --> 01:02:05.000
            Ende &amp; Schluss
        """.trimIndent()
        val cues = Vtt.parse(vtt)
        assertEquals(2, cues.size)
        assertEquals("Hallo zusammen", cues[0].text)
        assertEquals(3_723_500L, cues[1].startMs)
        assertEquals("Ende & Schluss", cues[1].text)
        assertEquals("[00:00:01] Hallo zusammen\n\n[01:02:03] Ende & Schluss", Vtt.toParagraphs(cues))
    }

    @Test fun cookieDomainMatching() {
        val s = Session(listOf(
            StoredCookie("_shibsession_abc", "1", "video.tuwien.ac.at"),
            StoredCookie("_shibsession_def", "2", "lecturetube.video.tuwien.ac.at"),
            StoredCookie("wide", "3", ".tuwien.ac.at"),
            StoredCookie("old", "4", "video.tuwien.ac.at", expires = 1),
        ))
        assertEquals("_shibsession_abc=1; wide=3", s.cookieHeader("video.tuwien.ac.at"))
        assertEquals("_shibsession_def=2; wide=3", s.cookieHeader("lecturetube.video.tuwien.ac.at"))
        assertEquals("wide=3", s.cookieHeader("cdn.video.tuwien.ac.at"))
        assertNull(s.cookieHeader("example.org"))
        assertTrue(s.hasShibSession("video.tuwien.ac.at"))
    }

    @Test fun readOnlyGuard() {
        assertTrue(LectureTubeService.isReadOnlyDocument("{ search(query: \"mutation\", filters: {}) { __typename } }"))
        assertTrue(LectureTubeService.isReadOnlyDocument("query Q { currentUser { username } } # mutation"))
        assertFalse(LectureTubeService.isReadOnlyDocument("mutation { deleteEvent(id: \"x\") { id } }"))
        assertFalse(LectureTubeService.isReadOnlyDocument("{a} mutation M { x }"))
    }
}
