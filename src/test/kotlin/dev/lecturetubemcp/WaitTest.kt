package dev.lecturetubemcp

import dev.lecturetubemcp.media.JobState
import dev.lecturetubemcp.media.MediaTools
import dev.lecturetubemcp.media.TranscriptionJob
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertTrue

class WaitTest {
    private fun job(): Pair<TranscriptionJob, CompletableDeferred<Path?>> {
        val j = TranscriptionJob("k", "Test lecture", "small", null, null)
        val d = CompletableDeferred<Path?>()
        j.deferred = d
        j.state = JobState.TRANSCRIBING
        return j to d
    }

    @Test
    fun returnsTheMomentTheJobFinishes() = runBlocking {
        val (j, d) = job()
        launch { delay(7_000); j.state = JobState.DONE; d.complete(null) }
        val reports = mutableListOf<String>()
        val t0 = System.currentTimeMillis()
        MediaTools.waitForJob(j, 60) { _, msg -> reports += msg }
        val took = System.currentTimeMillis() - t0
        assertTrue(took in 6_900..8_000, "returned after $took ms")
        assertTrue(reports.size == 1 && reports[0].contains("Test lecture"), "progress reports: $reports")
    }

    @Test
    fun givesUpAfterWaitSeconds() = runBlocking {
        val (j, _) = job()
        val t0 = System.currentTimeMillis()
        MediaTools.waitForJob(j, 1)
        val took = System.currentTimeMillis() - t0
        assertTrue(took in 900..1_500, "returned after $took ms")
        MediaTools.waitForJob(j, 0) // status only: no wait
        assertTrue(System.currentTimeMillis() - t0 < 1_600)
    }
}
