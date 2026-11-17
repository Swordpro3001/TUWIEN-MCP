package dev.lecturetubemcp

import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/**
 * Makes a running server immune to its jar being rebuilt.
 *
 * MCP clients start the servers straight from the jars in `build/libs`. When such a file is overwritten while the
 * server runs (e.g. `./gradlew allJars`), classes that are loaded later are read from the new file at old
 * offsets and fail with `NoClassDefFoundError`; this killed running transcriptions. So at startup the jar is
 * copied to a content-addressed file under `<data dir>/runtime/` (never overwritten) and the application is
 * loaded from that copy in the same JVM. Child processes (transcription workers) use the copy, too.
 *
 * Disabled when not running from a jar (IDE/tests) or with `LECTURETUBE_NO_RELAUNCH=1`.
 */
object Bootstrap {
    private const val MARKER = "lecturetubemcp.private"

    /** Returns true if [mainClass] was run from the private copy (the caller must then return immediately). */
    fun runFromPrivateCopy(mainClass: String, args: Array<String>): Boolean {
        if (System.getProperty(MARKER) != null || System.getenv("LECTURETUBE_NO_RELAUNCH") == "1") return false
        val jar = runCatching { Paths.get(Bootstrap::class.java.protectionDomain.codeSource.location.toURI()) }.getOrNull()
            ?.takeIf { it.toString().endsWith(".jar") && Files.isRegularFile(it) } ?: return false
        val copy = runCatching { privateCopy(jar) }.getOrNull() ?: return false

        System.setProperty(MARKER, copy.toString())
        System.setProperty("java.class.path", copy.toString()) // used to start worker JVMs
        val loader = URLClassLoader(arrayOf(copy.toUri().toURL()), ClassLoader.getPlatformClassLoader())
        Thread.currentThread().contextClassLoader = loader
        val main = loader.loadClass(mainClass).getMethod("main", Array<String>::class.java)
        try {
            main.invoke(null, args)
        } catch (e: java.lang.reflect.InvocationTargetException) {
            throw e.targetException
        }
        return true
    }

    private fun privateCopy(jar: Path): Path {
        val hash = MessageDigest.getInstance("SHA-256").let { md ->
            Files.newInputStream(jar).use { input ->
                val buf = ByteArray(1 shl 16)
                while (true) { val n = input.read(buf); if (n < 0) break; md.update(buf, 0, n) }
            }
            md.digest().take(8).joinToString("") { "%02x".format(it) }
        }
        val dir = Config.dataDir.resolve("runtime")
        Files.createDirectories(dir)
        val name = jar.fileName.toString().removeSuffix(".jar")
        val target = dir.resolve("$name-$hash.jar")
        if (!Files.exists(target)) {
            val tmp = Files.createTempFile(dir, name, ".tmp")
            Files.copy(jar, tmp, StandardCopyOption.REPLACE_EXISTING)
            Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        }
        Files.setLastModifiedTime(target, java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis()))
        // Keep the 4 most recently used copies per server (older ones may still be used by running servers on
        // Linux/macOS, where deleting an open file is harmless; on Windows deletion of an open file just fails).
        Files.list(dir).use { s -> s.filter { it.fileName.toString().startsWith("$name-") && it.toString().endsWith(".jar") }.toList() }
            .sortedByDescending { Files.getLastModifiedTime(it).toMillis() }.drop(4)
            .forEach { runCatching { Files.delete(it) } }
        return target
    }
}
