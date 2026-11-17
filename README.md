# TU Wien MCP servers: TUWEL + LectureTube

Two unofficial [MCP](https://modelcontextprotocol.io) servers that let an AI assistant (Claude Desktop, Claude Code, …)
work with your TU Wien courses:

| Jar | Service | What the AI can do |
|---|---|---|
| `tuwel-mcp.jar` | [TUWEL](https://tuwel.tuwien.ac.at) | courses, files (PDF, Office, ZIP, …), assignments, deadlines, grades, forums, course recordings, summaries |
| `lecturetube-mcp.jar` | [LectureTube](https://video.tuwien.ac.at) | search lectures, read transcripts and slides, summarise recordings |

Both share one TU Wien login. Recordings without captions can be transcribed, and slides can be read from the video.

> Not affiliated with TU Wien. Respect the terms of use and the licenses of the material.
> Summaries of course-only recordings are for your personal use.

## Quick start

1. **Build** (needs a JDK; the jars run on Java 17+):
   ```bash
   ./gradlew allJars          # → build/libs/tuwel-mcp.jar and build/libs/lecturetube-mcp.jar
   ```
2. **Add to your MCP client.** Claude Desktop (`claude_desktop_config.json`):
   ```json
   {
     "mcpServers": {
       "tuwel":       { "command": "java", "args": ["-jar", "/path/to/build/libs/tuwel-mcp.jar"] },
       "lecturetube": { "command": "java", "args": ["-jar", "/path/to/build/libs/lecturetube-mcp.jar"] }
     }
   }
   ```
   Claude Code: `claude mcp add tuwel -- java -jar /path/to/build/libs/tuwel-mcp.jar` (same for `lecturetube`).
3. **Ask**, e.g. *"Log me in to TUWEL and summarise this week's material of Analysis 2."*

For transcription install `ffmpeg`. `java -jar build/libs/tuwel-mcp.jar doctor` checks the setup.

## Tools

**TUWEL:** `list_courses`, `search_courses`, `get_course`, `search_course`, `course_updates`, `list_files`,
`read_file` (PDF, docx/pptx/xlsx, ODF, ipynb, HTML, code, images), `read_zip`, `read_module`,
`upcoming_deadlines`, `calendar`, `list_assignments`, `get_assignment`, `get_grades`, `list_forums`,
`list_discussions`, `read_discussion`, `notifications`, `course_recordings`, `listen_recording`, `summarize`.
Prompts: `weekly_briefing`, `study_week`, `assignment_helper`, `exam_prep`.

**LectureTube:** `search_lectures` (also searches spoken words), `list_lectures`, `list_series`, `get_series`,
`get_lecture`, `get_transcript`, `get_slides`, `browse_pages`, `my_profile`, `my_videos`, `my_series`,
`graphql_query` (read-only). Public lectures work without login. Prompts: `summarize_lecture`, `exam_prep`.

**Both:** `login`, `auth_status`, `logout`, and for recordings:
- `transcribe_recording`: creates captions from the MP4, either in the cloud or locally (see below).
- `transcription_status`: waits for a running job and returns the transcript as soon as it is ready.
  A desktop notification also tells you when a long job finishes (`TRANSCRIBE_NOTIFY=0` turns it off).
- `analyze_keyframes`: finds slide changes in the video and shows them with what was said at that point.

## Login

`login` opens the TU Wien login page in **your default browser**, using a separate profile. You log in as usual
(including 2FA); the server never sees your password. Afterwards it reads the session cookies, signs in to TUWEL,
LectureTube and Opencast, and closes the window. For TUWEL it also gets a Moodle app token, which you can revoke
under *Preferences → Security keys*.

The session is stored in `~/.config/lecturetube-mcp/session.json` (owner-only). It contains the TU Wien SSO
cookie, so later logins can run silently. Treat the file like a password. `logout` deletes it.

Chromium-based browsers (Chrome, Edge, Brave, Opera, Vivaldi, …) and Firefox-based ones are supported. To use a
different browser, set `LECTURETUBE_BROWSER` to its executable.

## Transcription

Only about 8.5% of LectureTube recordings have captions, and videos uploaded to TUWEL have none.

**Cloud (recommended for laptops):** any OpenAI-compatible speech API; the default is Groq
(`whisper-large-v3-turbo`, about $0.04 per audio hour). Your computer only finds the speech in the audio
(about 15 CPU-seconds per lecture). Silences are not uploaded. Add a key to the server's environment:

```json
"tuwel": { "command": "java", "args": ["-jar", "/path/to/tuwel-mcp.jar"], "env": { "TRANSCRIBE_API_KEY": "gsk_…" } }
```

or put `{ "apiKey": "gsk_…" }` into `~/.config/lecturetube-mcp/transcription.json` (`chmod 600`).

> **Privacy:** with the cloud engine the recording's audio is sent to the provider. Use `engine: local` for
> recordings where that is not acceptable. Uploads only happen once you configure a transcription API key.

**Local:** Whisper (whisper.cpp) is bundled and runs on the CPU. It needs no account, but it keeps all cores
busy for several minutes (`small`: about 11 minutes for a 90-minute lecture). A GPU build of `whisper-cli` is
used automatically if it is installed.

Results are cached and resumed after interruptions.

## Configuration

| Variable | Purpose |
|---|---|
| `TRANSCRIBE_API_KEY` | Enables cloud transcription |
| `TRANSCRIBE_ENGINE` | `api` or `local` (default engine) |
| `TRANSCRIBE_API_PROVIDER`, `TRANSCRIBE_API_URL`, `TRANSCRIBE_API_MODEL` | `groq` (default), `openai` or `custom` with a base URL |
| `TRANSCRIBE_API_FORMAT` | Upload as `flac` (default) or `opus` (about 8× smaller) |
| `TRANSCRIBE_WAIT_SECONDS` | How long one tool call waits for a transcript (default 50) |
| `LECTURETUBE_WHISPER_MODEL` | Local model: tiny, base, small (default), medium, turbo, large |
| `WHISPER_CLI`, `FFMPEG`, `FFPROBE` | Paths to external tools |
| `LECTURETUBE_BROWSER` | Login browser instead of the system default |
| `LECTURETUBE_MCP_HOME` | Data folder (default `~/.config/lecturetube-mcp`) |
| `LECTURETUBE_MEDIA_CACHE_GB` | Video cache limit (default 5) |

Every setting can also go into `transcription.json` (`engine`, `provider`, `apiUrl`, `model`, `audioFormat`, `parallel`).

## CLI

```bash
java -jar tuwel-mcp.jar login | status | logout | doctor
java -jar lecturetube-mcp.jar login | status | logout | doctor
```

## Development

```bash
./gradlew test
```

```
docs/                     API research (OpenAPI for LectureTube and TUWEL, Tobira GraphQL schema)
src/main/kotlin/dev/
  lecturetubemcp/         LectureTube server
    auth/                 shared TU Wien login (browser automation, session store)
    api/                  Tobira GraphQL / Opencast client, transcripts
    media/                transcription (local + cloud), keyframes, video cache
    mcp/                  MCP tools
  tuwelmcp/               TUWEL server
    moodle/               Moodle web service client, TUWEL operations, course recordings
    files/                PDF / Office / ZIP readers
    mcp/                  MCP tools
```
