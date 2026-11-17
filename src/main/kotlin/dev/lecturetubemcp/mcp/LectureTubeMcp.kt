package dev.lecturetubemcp.mcp

import dev.lecturetubemcp.Config
import dev.lecturetubemcp.api.ApiException
import dev.lecturetubemcp.api.LectureTubeService
import dev.lecturetubemcp.api.Vtt
import dev.lecturetubemcp.api.s
import dev.lecturetubemcp.api.audioSource
import dev.lecturetubemcp.api.cuesFor
import dev.lecturetubemcp.api.transcript
import dev.lecturetubemcp.api.videoSource
import dev.lecturetubemcp.media.MediaException
import dev.lecturetubemcp.media.MediaTarget
import dev.lecturetubemcp.media.registerMediaTools
import dev.lecturetubemcp.auth.LoginManager
import dev.lecturetubemcp.auth.SessionStore
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.ContentBlock
import io.modelcontextprotocol.kotlin.sdk.types.GetPromptResult
import io.modelcontextprotocol.kotlin.sdk.types.ImageContent
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.PromptArgument
import io.modelcontextprotocol.kotlin.sdk.types.PromptMessage
import io.modelcontextprotocol.kotlin.sdk.types.Role
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolAnnotations
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.util.Base64
import kotlin.time.Duration.Companion.seconds

private val prettyJson = Json { prettyPrint = true }

private const val INSTRUCTIONS = """
Unofficial MCP server for TU Wien LectureTube (https://video.tuwien.ac.at), for finding and summarising lectures.

Typical workflow for a summary:
  1. search_lectures / list_series / get_series   → find the lecture ID (ev…)
  2. get_lecture                                  → metadata, available captions, slide count
  3. get_transcript                               → timestamped transcript (main source)
  4. get_slides / analyze_keyframes (optional)    → slides, board and code as images
Cite timestamps like [00:12:30] in summaries.

No captions? Most recordings before 2024 have none. transcribe_recording downloads the MP4 and
creates captions (cloud speech API or local Whisper; runs in the background). transcription_status
waits for a running job and returns the transcript the moment it is ready: don't poll or sleep between calls.
The generated transcript is then used by get_transcript automatically.
analyze_keyframes extracts slide changes from the video itself (works for every recording).

Public lectures work without login. Many course recordings (TUWEL) need a TU Wien login:
call `login`. It opens a browser at the TU Wien SSO page; the student logs in there and the
session cookie is reused automatically. Use `auth_status` to check.
"""

class LectureTubeMcp(private val svc: LectureTubeService, private val logins: LoginManager) {

    fun build(): Server {
        val server = Server(
            Implementation(name = "lecturetube-mcp", version = Config.VERSION, title = "TU Wien LectureTube"),
            ServerOptions(
                capabilities = ServerCapabilities(
                    tools = ServerCapabilities.Tools(listChanged = false),
                    prompts = ServerCapabilities.Prompts(listChanged = false),
                ),
            ),
            INSTRUCTIONS.trimIndent(),
        )
        registerAuthTools(server)
        registerContentTools(server)
        registerMediaTools(
            server,
            listOf("lecture" to prop("string", "Lecture ID (ev…), URL (…/!v/…) or Opencast UUID.")),
            resolve = { a ->
                val e = svc.event(a.str("lecture") ?: throw IllegalArgumentException("Pass `lecture`."))
                MediaTarget(
                    key = e.opencastId, title = e.title, language = null, hasCaptions = e.captions.isNotEmpty(),
                    audio = { svc.audioSource(e) }, video = { track -> svc.videoSource(e, track) }, cues = { svc.cuesFor(e) },
                )
            },
            onError = { e ->
                when (e) {
                    is ApiException -> (e.message ?: "LectureTube error") + if (e.notAuthorized && SessionStore.current == null) " (Not logged in: call `login`.)" else ""
                    is MediaException, is IllegalArgumentException -> e.message
                    else -> null
                }
            },
        )
        registerPrompts(server)
        return server
    }

    private fun registerAuthTools(server: Server) {
        server.tool(
            "login",
            "Log in to TU Wien LectureTube. Opens a browser window at the TU Wien SSO login page; the student " +
                "enters their credentials there. The browser session cookie is then captured and used for all " +
                "requests that need a login. Returns as soon as the login completes, or after waitSeconds (the login " +
                "keeps running in the background; check with auth_status). The window always opens in the user's " +
                "default browser; it cannot be chosen here.",
            schema(
                "waitSeconds" to prop("integer", "How long this call waits for the login to finish (default 90)."),
                "timeoutSeconds" to prop("integer", "How long the background login waits in total (default 600)."),
            ),
            readOnly = false,
        ) { args ->
            val timeout = (args.int("timeoutSeconds") ?: 600).coerceIn(30, 3600)
            val wait = (args.int("waitSeconds") ?: 90).coerceIn(0, timeout)
            // No browser argument on purpose: the model must not override the user's default browser
            // (the user can still set LECTURETUBE_BROWSER in the server config).
            val job = logins.start(null, timeout.seconds)
            try {
                val r = withTimeout(wait.seconds) { job.await() }
                if (r.ok) ok(r.message) else err(r.message)
            } catch (_: TimeoutCancellationException) {
                ok(
                    "A browser window with the TU Wien login page is open. Please log in there. " +
                        "The login keeps waiting in the background for up to ${timeout}s; call auth_status to check.",
                )
            }
        }

        server.tool("auth_status", "Show whether a TU Wien login is active and which user it belongs to.", schema()) {
            val s = SessionStore.current
            val tobira = runCatching { svc.client.currentUser() }.getOrNull()
            val ocUser = svc.client.opencastMe()?.get("user")?.let { it.jsonObject["username"].s() }
            val text = buildString {
                when {
                    tobira != null -> append("Logged in as ${tobira.second} (${tobira.first}).")
                    s != null -> append("A saved session exists but LectureTube no longer accepts it (expired). Call `login` again.")
                    else -> append("Not logged in. Only public lectures are accessible. Call `login` to sign in.")
                }
                if (s != null) append("\nSession source: ${s.source}, saved ${s.savedAt}.")
                append("\nOpencast node user: ${ocUser ?: "unknown"}.")
                if (logins.inProgress) append("\nA browser login is currently in progress.")
                logins.lastResult?.takeIf { !it.ok }?.let { append("\nLast login attempt: ${it.message}") }
            }
            ok(text)
        }

        server.tool("logout", "Forget the stored LectureTube session on this computer.", schema(), readOnly = false) {
            SessionStore.clear()
            ok("Local session removed. (The TU Wien SSO session in the login browser profile may still be active.)")
        }

        server.tool(
            "set_session_cookie",
            "Fallback if the automatic browser login doesn't work: store a Cookie header copied from the browser " +
                "dev tools (Network tab, any request to video.tuwien.ac.at/graphql). Must include the _shibsession_… cookie.",
            schema(
                "cookie" to prop("string", "Cookie header value, e.g. `_shibsession_6465…=_abc123; …`"),
                "host" to prop("string", "Host the cookie belongs to (default video.tuwien.ac.at)."),
                required = listOf("cookie"),
            ),
            readOnly = false,
        ) { args ->
            val host = args.str("host") ?: Config.TOBIRA_HOST
            val candidate = SessionStore.fromCookieHeader(args.reqStr("cookie"), host)
            val merged = SessionStore.current?.let { cur ->
                cur.copy(cookies = cur.cookies.filterNot { it.domain == host } + candidate.cookies, source = "manual")
            } ?: candidate
            val user = runCatching { svc.client.currentUser(merged) }.getOrNull()
            if (host == Config.TOBIRA_HOST && user == null) {
                err("LectureTube did not accept this cookie (currentUser is null). Nothing was saved.")
            } else {
                SessionStore.save(merged.copy(username = user?.first, displayName = user?.second))
                ok("Session saved" + (user?.let { " for ${it.second} (${it.first})" } ?: "") + ".")
            }
        }
    }

    private fun registerContentTools(server: Server) {
        server.tool(
            "search_lectures",
            "Full-text search over LectureTube: lecture titles, series (courses), lecturers, and spoken words in " +
                "captions (returns timestamps of matching transcript passages). Returns at most 30 results.",
            schema(
                "query" to prop("string", "Search terms, e.g. a course name, number (\"101.883\"), lecturer or topic."),
                "type" to prop("string", "Optional filter: EVENT (lectures), SERIES (courses) or REALM (pages)."),
                "from" to prop("string", "Optional: only items from this date (YYYY-MM-DD)."),
                "to" to prop("string", "Optional: only items until this date (YYYY-MM-DD)."),
                required = listOf("query"),
            ),
        ) { a -> json(svc.search(a.reqStr("query"), a.str("type"), a.str("from"), a.str("to"))) }

        server.tool(
            "list_lectures",
            "Paged list of lectures from the Opencast search API (newest first by default). Use it to enumerate a " +
                "course (series) or to browse beyond the 30-result limit of search_lectures. Includes caption availability.",
            schema(
                "query" to prop("string", "Optional free-text filter."),
                "series" to prop("string", "Optional series ID (sr…), series URL or Opencast series UUID."),
                "sort" to prop("string", "created desc (default), created asc, title asc, modified desc, …"),
                "offset" to prop("integer", "Default 0."),
                "limit" to prop("integer", "1–250, default 25."),
            ),
        ) { a -> json(svc.listLectures(a.str("query"), a.str("series"), a.str("sort"), a.int("offset") ?: 0, a.int("limit") ?: 25)) }

        server.tool(
            "list_series",
            "Paged list of series (a series is usually one course in one semester, e.g. \"101.883 … (2025S)\").",
            schema(
                "query" to prop("string", "Optional free-text filter (course name or number)."),
                "sort" to prop("string", "modified desc (default), title asc, created desc, …"),
                "offset" to prop("integer", "Default 0."),
                "limit" to prop("integer", "1–250, default 25."),
            ),
        ) { a -> json(svc.listSeries(a.str("query"), a.str("sort"), a.int("offset") ?: 0, a.int("limit") ?: 25)) }

        server.tool(
            "get_series",
            "All lectures of a series (course), in chronological order, with caption and slide availability.",
            schema("series" to prop("string", "Series ID (sr…), URL (…/!s/…) or Opencast UUID."), required = listOf("series")),
        ) { a -> json(svc.series(a.reqStr("series"))) }

        server.tool(
            "get_lecture",
            "Metadata of one lecture: title, lecturers, series, date, duration, language, available caption " +
                "languages, number of slides, and media URLs.",
            schema("lecture" to prop("string", "Lecture ID (ev…), URL (…/!v/…) or Opencast UUID."), required = listOf("lecture")),
        ) { a -> json(svc.summarizeEvent(svc.event(a.reqStr("lecture")))) }

        server.tool(
            "get_transcript",
            "Transcript of a lecture from its captions (or from a transcript generated with transcribe_recording), as paragraphs with [hh:mm:ss] timestamps. This is the main " +
                "input for summaries. Long lectures can be fetched in windows with fromMinute/toMinute.",
            schema(
                "lecture" to prop("string", "Lecture ID (ev…), URL or Opencast UUID."),
                "lang" to prop("string", "Caption language (e.g. de, en). Default: the lecture's language, else the first available."),
                "format" to prop("string", "text (default, paragraphs), vtt (raw WebVTT) or cues (one line per caption)."),
                "fromMinute" to prop("integer", "Optional start of window in minutes."),
                "toMinute" to prop("integer", "Optional end of window in minutes."),
                "paragraphSeconds" to prop("integer", "Paragraph length for format=text (default 60)."),
                "maxChars" to prop("integer", "Truncate output to this many characters (default 100000)."),
                required = listOf("lecture"),
            ),
        ) { a -> transcript(a) }

        server.tool(
            "get_slides",
            "Slide keyframes of a lecture (one image per detected slide change) with timestamps. Returns the list and, " +
                "if maxImages > 0, the images themselves (evenly spaced if there are more slides than maxImages).",
            schema(
                "lecture" to prop("string", "Lecture ID (ev…), URL or Opencast UUID."),
                "maxImages" to prop("integer", "Images to include (0–20, default 6). 0 = only the list."),
                "fromMinute" to prop("integer", "Optional window start."),
                "toMinute" to prop("integer", "Optional window end."),
                required = listOf("lecture"),
            ),
        ) { a -> slides(a) }

        server.tool(
            "browse_pages",
            "Browse the LectureTube page tree (e.g. / → /lectures → /lectures/E100 faculty pages) and the series/videos placed on a page.",
            schema("path" to prop("string", "Page path, default \"/\". Example: /lectures/E100")),
        ) { a -> json(svc.browse(a.str("path") ?: "/")) }

        server.tool("my_profile", "Details of the logged-in user (roles, permissions). Requires login.", schema()) { json(svc.me()) }

        server.tool(
            "my_videos",
            "Videos owned by / shared for editing with the logged-in user (\"My videos\"). Requires login.",
            schema("offset" to prop("integer", "Default 0."), "limit" to prop("integer", "1–100, default 25.")),
        ) { a -> json(svc.myVideos(a.int("offset") ?: 0, a.int("limit") ?: 25)) }

        server.tool(
            "my_series",
            "Series owned by / editable by the logged-in user. Requires login.",
            schema("offset" to prop("integer", "Default 0."), "limit" to prop("integer", "1–100, default 25.")),
        ) { a -> json(svc.mySeries(a.int("offset") ?: 0, a.int("limit") ?: 25)) }

        server.tool(
            "graphql_query",
            "Advanced: run a read-only GraphQL query against the Tobira API (https://video.tuwien.ac.at/graphql) " +
                "with the current login. Mutations are rejected. Use for fields the other tools don't expose.",
            schema(
                "query" to prop("string", "GraphQL query document."),
                "variables" to prop("object", "Optional variables object."),
                required = listOf("query"),
            ),
        ) { a ->
            val q = a.reqStr("query")
            if (!LectureTubeService.isReadOnlyDocument(q)) {
                err("Only queries are allowed. Mutations and subscriptions are rejected.")
            } else {
                json(svc.client.graphql(q, (a["variables"] as? JsonObject) ?: JsonObject(emptyMap())))
            }
        }
    }

    private suspend fun transcript(a: JsonObject): CallToolResult {
        val r = svc.transcript(
            lecture = a.reqStr("lecture"),
            lang = a.str("lang"),
            format = a.str("format") ?: "text",
            fromMinute = a.int("fromMinute"),
            toMinute = a.int("toMinute"),
            paragraphSeconds = a.int("paragraphSeconds") ?: 60,
            maxChars = a.int("maxChars") ?: 100_000,
        )
        return if (r.ok) ok(r.text) else err(r.text)
    }

    private suspend fun slides(a: JsonObject): CallToolResult {
        val e = svc.event(a.reqStr("lecture"))
        val from = a.int("fromMinute")?.let { it * 60_000L } ?: 0L
        val to = a.int("toMinute")?.let { it * 60_000L } ?: Long.MAX_VALUE
        val segs = e.segments.filter { it.first in from..<to }
        if (segs.isEmpty()) return err("\"${e.title}\" has no slide keyframes" + if (from > 0 || to < Long.MAX_VALUE) " in this time window." else ".")
        val max = (a.int("maxImages") ?: 6).coerceIn(0, 20)
        val picked = if (segs.size <= max) segs else List(max) { i -> segs[(i * segs.size) / max] }
        val content = mutableListOf<ContentBlock>()
        content += TextContent(
            "# Slides of \"${e.title}\" (${segs.size} in window, ${picked.size} images attached)\n" +
                segs.joinToString("\n") { "[${Vtt.fmt(it.first)}] ${it.second}" },
        )
        for ((ms, uri) in picked) {
            val bytes = runCatching { svc.client.getBytes(uri) }.getOrNull() ?: continue
            content += TextContent("Slide at [${Vtt.fmt(ms)}]:")
            content += ImageContent(Base64.getEncoder().encodeToString(bytes), if (uri.endsWith(".png")) "image/png" else "image/jpeg")
        }
        return CallToolResult(content)
    }

    private fun registerPrompts(server: Server) {
        server.addPrompt(
            "summarize_lecture",
            "Create a structured summary of a TU Wien lecture from its transcript (and slides).",
            listOf(
                PromptArgument("lecture", "Lecture ID, URL or title", required = true),
                PromptArgument("language", "Output language (default: the lecture's language)", required = false),
                PromptArgument("detail", "short | normal | detailed", required = false),
            ),
        ) { req ->
            val lecture = req.arguments?.get("lecture").orEmpty()
            val lang = req.arguments?.get("language")?.let { "Write the summary in $it." } ?: "Write the summary in the lecture's language."
            val detail = req.arguments?.get("detail") ?: "normal"
            GetPromptResult(
                listOf(
                    PromptMessage(
                        Role.User,
                        TextContent(
                            """
                            Summarise the TU Wien LectureTube lecture "$lecture".
                            1. If this is not an ID (ev…) or URL, find it with search_lectures.
                            2. Call get_lecture, then get_transcript (fetch all windows if it is truncated).
                            3. If there are slides, call get_slides with a few images to capture formulas and diagrams.
                            Output ($detail detail): title, lecturer, date; a 3-sentence overview; an outline of the sections with
                            [hh:mm:ss] timestamps; key concepts and definitions; important formulas (LaTeX); examples; open questions.
                            Mark anything that is uncertain because of caption recognition errors. $lang
                            """.trimIndent(),
                        ),
                    ),
                ),
                description = "Lecture summary",
            )
        }
        server.addPrompt(
            "exam_prep",
            "Generate exam-style practice questions with answers from one lecture or a whole course.",
            listOf(PromptArgument("target", "Lecture or series ID/URL/title", required = true)),
        ) { req ->
            val t = req.arguments?.get("target").orEmpty()
            GetPromptResult(
                listOf(
                    PromptMessage(
                        Role.User,
                        TextContent(
                            "Using the LectureTube tools, read the transcript(s) of \"$t\" (for a series use get_series and go " +
                                "through the lectures). Create 10–15 exam-style questions (mix of conceptual, calculation and " +
                                "short-answer), each with a model answer and the [hh:mm:ss] timestamp + lecture where it is covered.",
                        ),
                    ),
                ),
            )
        }
    }

    private fun Server.tool(
        name: String,
        description: String,
        inputSchema: ToolSchema,
        readOnly: Boolean = true,
        handler: suspend (JsonObject) -> CallToolResult,
    ) {
        addTool(
            name = name,
            description = description,
            inputSchema = inputSchema,
            toolAnnotations = ToolAnnotations(readOnlyHint = readOnly, openWorldHint = true),
        ) { req: CallToolRequest ->
            try {
                handler(req.arguments ?: JsonObject(emptyMap()))
            } catch (e: ApiException) {
                val msg = e.message ?: "error"
                val hint = if (e.notAuthorized && SessionStore.current == null && !msg.contains("login")) " (Not logged in: call the `login` tool.)" else ""
                err(msg + hint)
            } catch (e: IllegalArgumentException) {
                err(e.message ?: "invalid argument")
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                err("${e::class.simpleName}: ${e.message}")
            }
        }
    }

    private fun schema(vararg props: Pair<String, JsonObject>, required: List<String> = emptyList()) =
        ToolSchema(properties = buildJsonObject { props.forEach { (k, v) -> put(k, v) } }, required = required.ifEmpty { null })

    private fun prop(type: String, description: String): JsonObject = buildJsonObject {
        put("type", type); put("description", description)
    }

    private fun ok(text: String) = CallToolResult(listOf(TextContent(text)))
    private fun err(text: String) = CallToolResult(listOf(TextContent(text)), isError = true)
    private fun json(o: JsonElement) = ok(prettyJson.encodeToString(JsonElement.serializer(), o))

    private fun JsonObject.str(k: String): String? = (this[k] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }
    private fun JsonObject.reqStr(k: String) = str(k) ?: throw IllegalArgumentException("Missing required argument '$k'.")
    private fun JsonObject.int(k: String): Int? = (this[k] as? JsonPrimitive)?.content?.toDoubleOrNull()?.toInt()
}
