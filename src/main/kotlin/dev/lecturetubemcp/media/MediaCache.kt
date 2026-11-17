package dev.lecturetubemcp.media

import dev.lecturetubemcp.Config
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.future.await
import kotlinx.coroutines.withContext
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.time.Duration

/**
 * A downloadable media file. [url] may contain credentials (e.g. a TUWEL token); it is never passed
 * on a command line. [headers] are sent with the request (e.g. a session cookie).
 */
data class MediaSource(
    /** Stable cache key, e.g. the Opencast UUID + flavor, or a hash of the TUWEL file URL. */
    val key: String,
    val title: String,
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val language: String? = null,
)

/** Download cache for lecture videos with resume (HTTP Range) and retries, limited in total size. */
object MediaCache {
    private val dir: Path get() = Config.dataDir.resolve("media")
    private val maxBytes: Long get() = (System.getenv("LECTURETUBE_MEDIA_CACHE_GB")?.toDoubleOrNull() ?: 5.0).times(1024 * 1024 * 1024).toLong()
    private val http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).connectTimeout(Duration.ofSeconds(20)).build()

    fun safeName(key: String): String =
        key.replace(Regex("[^A-Za-z0-9._-]"), "_").take(80) + "-" + sha1(key).take(8)

    fun sha1(s: String): String = MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    fun cached(src: MediaSource): Path? = dir.resolve(safeName(src.key) + ".media").takeIf { Files.exists(it) }

    /** Downloads [src] (or returns the cached file). [progress] receives (bytesDone, bytesTotal or -1). */
    suspend fun fetch(src: MediaSource, progress: (Long, Long) -> Unit = { _, _ -> }): Path = withContext(Dispatchers.IO) {
        Files.createDirectories(dir)
        val target = dir.resolve(safeName(src.key) + ".media")
        if (Files.exists(target)) {
            Files.setLastModifiedTime(target, java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis()))
            return@withContext target
        }
        val part = dir.resolve(safeName(src.key) + ".part")
        var attempt = 0
        var total = -1L
        while (true) {
            val have = if (Files.exists(part)) Files.size(part) else 0L
            if (total in 1..have) break
            try {
                val req = HttpRequest.newBuilder(URI.create(src.url)).timeout(Duration.ofMinutes(30)).header("User-Agent", Config.USER_AGENT)
                src.headers.forEach { (k, v) -> req.header(k, v) }
                if (have > 0) req.header("Range", "bytes=$have-")
                val resp = http.sendAsync(req.GET().build(), HttpResponse.BodyHandlers.ofInputStream()).await()
                val code = resp.statusCode()
                if (code == 416) break // already complete
                if (code !in listOf(200, 206)) throw MediaException("HTTP $code downloading ${src.title}")
                val append = code == 206
                total = when {
                    append -> resp.headers().firstValue("Content-Range").orElse("").substringAfter('/').toLongOrNull() ?: -1
                    else -> resp.headers().firstValueAsLong("Content-Length").orElse(-1)
                }
                val ct = resp.headers().firstValue("Content-Type").orElse("")
                if (ct.startsWith("text/html")) throw MediaException("Got an HTML page instead of a media file (login required?)")
                resp.body().use { input ->
                    Files.newOutputStream(part, StandardOpenOption.CREATE, if (append) StandardOpenOption.APPEND else StandardOpenOption.TRUNCATE_EXISTING).use { out ->
                        val buf = ByteArray(1 shl 16)
                        var done = if (append) have else 0L
                        var lastReport = 0L
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n); done += n
                            if (done - lastReport > 4_000_000) { progress(done, total); lastReport = done }
                        }
                        progress(done, total)
                    }
                }
                if (total < 0 || Files.size(part) >= total) break
            } catch (e: MediaException) {
                throw e
            } catch (e: Exception) {
                if (++attempt > 6) throw MediaException("Download of ${src.title} failed after retries: ${e.message}")
                delay(2000L * attempt)
            }
        }
        Files.move(part, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        evict(keep = target)
        target
    }

    /** Removes least recently used files while the cache is larger than the limit. */
    private fun evict(keep: Path) {
        val files = Files.list(dir).use { s -> s.filter { it.toString().endsWith(".media") }.toList() }
            .sortedBy { Files.getLastModifiedTime(it).toMillis() }
        var size = files.sumOf { Files.size(it) }
        for (f in files) {
            if (size <= maxBytes) break
            if (f == keep) continue
            size -= Files.size(f); Files.deleteIfExists(f)
        }
    }
}
