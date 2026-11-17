package dev.lecturetubemcp

import dev.lecturetubemcp.api.LectureTubeClient
import dev.lecturetubemcp.api.LectureTubeService
import dev.lecturetubemcp.auth.LoginFlow
import dev.lecturetubemcp.auth.LoginManager
import dev.lecturetubemcp.auth.SessionStore
import dev.lecturetubemcp.auth.SsoTargets
import dev.lecturetubemcp.mcp.LectureTubeMcp
import io.modelcontextprotocol.kotlin.sdk.server.StdioServerTransport
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.io.asSink
import kotlinx.io.asSource
import kotlinx.io.buffered
import kotlin.system.exitProcess
import kotlin.time.Duration.Companion.seconds

private fun err(msg: String) = System.err.println(msg)

fun main(args: Array<String>) {
    // Run from a private copy of the jar so rebuilding build/libs can't break this running server.
    if (dev.lecturetubemcp.Bootstrap.runFromPrivateCopy("dev.lecturetubemcp.MainKt", args)) return
    // stdout carries MCP JSON-RPC. All logging goes to stderr.
    // kotlin-logging otherwise prints a startup banner to stdout.
    System.setProperty("kotlin-logging.logStartupMessage", "false")
    System.setProperty("org.slf4j.simpleLogger.logFile", "System.err")
    System.setProperty("org.slf4j.simpleLogger.defaultLogLevel", System.getenv("LECTURETUBE_LOG") ?: "warn")

    SessionStore.load()
    val client = LectureTubeClient()
    // One login covers LectureTube, TUWEL and Opencast (shared session file).
    val flow = LoginFlow(SsoTargets.orderedFor(SsoTargets.lectureTube), log = ::err)

    when (args.firstOrNull() ?: "serve") {
        "serve" -> serve(LectureTubeService(client), LoginManager(flow))
        "login" -> runBlocking {
            val browser = args.indexOf("--browser").takeIf { it >= 0 }?.let { args.getOrNull(it + 1) }
            val r = flow.run(browser, 600.seconds)
            err(r.message)
            if (r.ok) err("Session stored in ${Config.sessionFile}")
            exitProcess(if (r.ok) 0 else 1)
        }
        "status" -> runBlocking {
            val u = runCatching { client.currentUser() }.getOrNull()
            err(
                when {
                    u != null -> "Logged in as ${u.second} (${u.first}). Session file: ${Config.sessionFile}"
                    SessionStore.current != null -> "Saved session is expired. Run `login` again."
                    else -> "Not logged in."
                },
            )
            exitProcess(if (u != null) 0 else 1)
        }
        "logout" -> { SessionStore.clear(); err("Session removed.") }
        "doctor" -> runBlocking { exitProcess(dev.lecturetubemcp.auth.Doctor.run(::err)) }
        "--version", "version" -> println(Config.VERSION)
        else -> {
            err(
                """
                lecturetube-mcp ${Config.VERSION}. Unofficial MCP server for TU Wien LectureTube
                Usage:
                  java -jar lecturetube-mcp.jar            run the MCP server on stdio (default)
                  java -jar lecturetube-mcp.jar login [--browser firefox|chrome|<path>]
                  java -jar lecturetube-mcp.jar status
                  java -jar lecturetube-mcp.jar logout
                  java -jar lecturetube-mcp.jar doctor          check browser, ffmpeg and transcription setup
                """.trimIndent(),
            )
            exitProcess(2)
        }
    }
}

private fun serve(svc: LectureTubeService, logins: LoginManager) = runBlocking {
    // Finish transcriptions whose server was stopped right before writing the result.
    Thread { runCatching { dev.lecturetubemcp.media.Transcriber.recover() } }.apply { isDaemon = true }.start()
    val server = LectureTubeMcp(svc, logins).build()
    val transport = StdioServerTransport(System.`in`.asSource().buffered(), System.out.asSink().buffered())
    val session = server.createSession(transport)
    val done = Job()
    session.onClose { done.complete() }
    done.join()
}
