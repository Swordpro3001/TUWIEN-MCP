package dev.tuwelmcp

import dev.lecturetubemcp.Config
import dev.lecturetubemcp.api.LectureTubeClient
import dev.lecturetubemcp.api.LectureTubeService
import dev.lecturetubemcp.auth.LoginFlow
import dev.lecturetubemcp.auth.LoginManager
import dev.lecturetubemcp.auth.SessionStore
import dev.lecturetubemcp.auth.SsoTargets
import dev.tuwelmcp.mcp.TuwelMcp
import dev.tuwelmcp.moodle.MoodleClient
import dev.tuwelmcp.moodle.TuwelService
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
    if (dev.lecturetubemcp.Bootstrap.runFromPrivateCopy("dev.tuwelmcp.MainKt", args)) return
    // stdout carries MCP JSON-RPC. All logging goes to stderr.
    System.setProperty("kotlin-logging.logStartupMessage", "false")
    System.setProperty("org.slf4j.simpleLogger.logFile", "System.err")
    System.setProperty("org.slf4j.simpleLogger.defaultLogLevel", System.getenv("TUWEL_LOG") ?: "warn")

    SessionStore.load()
    val moodle = MoodleClient()
    // TUWEL first; the same browser session then also signs in to LectureTube and Opencast.
    val flow = LoginFlow(SsoTargets.orderedFor(SsoTargets.tuwel), log = ::err)

    when (args.firstOrNull() ?: "serve") {
        "serve" -> serve(TuwelService(moodle, LectureTubeService(LectureTubeClient())), LoginManager(flow))
        "login" -> runBlocking {
            val browser = args.indexOf("--browser").takeIf { it >= 0 }?.let { args.getOrNull(it + 1) }
            val r = flow.run(browser, 600.seconds)
            err(r.message)
            if (r.ok) {
                val tok = runCatching { moodle.acquireToken() }
                err(if (tok.isSuccess) "TUWEL app token stored." else "No TUWEL app token: ${tok.exceptionOrNull()?.message}")
                err("Session stored in ${Config.sessionFile}")
            }
            exitProcess(if (r.ok) 0 else 1)
        }
        "status" -> runBlocking {
            val info = runCatching { TuwelService(moodle, LectureTubeService(LectureTubeClient())).siteInfo() }
            err(info.fold({ "Logged in to TUWEL as ${it["fullname"]} (${it["username"]})." }, { "Not logged in: ${it.message}" }))
            exitProcess(if (info.isSuccess) 0 else 1)
        }
        "logout" -> { SessionStore.clear(); err("Session removed.") }
        "doctor" -> runBlocking { exitProcess(dev.lecturetubemcp.auth.Doctor.run(::err)) }
        "--version", "version" -> println(Config.VERSION)
        else -> {
            err(
                """
                tuwel-mcp ${Config.VERSION}. Unofficial MCP server for TU Wien TUWEL (Moodle)
                Usage:
                  java -jar tuwel-mcp.jar            run the MCP server on stdio (default)
                  java -jar tuwel-mcp.jar login [--browser firefox|chrome|<path>]
                  java -jar tuwel-mcp.jar status
                  java -jar tuwel-mcp.jar logout
                  java -jar tuwel-mcp.jar doctor          check browser, ffmpeg and transcription setup
                """.trimIndent(),
            )
            exitProcess(2)
        }
    }
}

private fun serve(svc: TuwelService, logins: LoginManager) = runBlocking {
    // Finish transcriptions whose server was stopped right before writing the result.
    Thread { runCatching { dev.lecturetubemcp.media.Transcriber.recover() } }.apply { isDaemon = true }.start()
    val server = TuwelMcp(svc, logins).build()
    val transport = StdioServerTransport(System.`in`.asSource().buffered(), System.out.asSink().buffered())
    val session = server.createSession(transport)
    val done = Job()
    session.onClose { done.complete() }
    done.join()
}
