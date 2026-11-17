# TU Wien LectureTube: capability analysis

Probed on **2026-11-07**, anonymously, from outside TU Wien.
Related files:

- `openapi.yaml`: OpenAPI 3.1 spec of every endpoint found
- `tobira-schema.graphql`: full GraphQL SDL from introspection
- `tobira-introspection.json`: raw introspection result, for codegen

## 1. What it is

`video.tuwien.ac.at` runs **[Tobira](https://github.com/elan-ev/tobira) v3.15**, released
2026-04-09, commit `fd439057`. It sits in front of an **Opencast** cluster:

```
              ┌──────────────────────────────┐
 browser ───▶ │ video.tuwien.ac.at  (Tobira) │  POST /graphql, /~rss/*, /!v/*, /~embed/*
              └──────────────┬───────────────┘
                             │ sync (every 23 s)
              ┌──────────────▼──────────────────────────────┐
              │ Opencast                                    │
              │  lecturetube.video.tuwien.ac.at  (present.) │  GET /search/episode.json  (public)
              │  admin.lecturetube…  (admin, Studio, editor)│  Shibboleth only
              │  ingest.lecturetube… (upload)               │
              └──────────────┬──────────────────────────────┘
                             │
              ┌──────────────▼──────────────┐
              │ cdn.video.tuwien.ac.at      │  MP4, HLS, WebVTT, JPEG. No auth.
              └─────────────────────────────┘
```

The player is Paella 7. Matomo tracking is enabled (site 7) and logs the user ID.

## 2. Public API surface

| Interface | Anonymous | Notes |
|---|---|---|
| `POST /graphql` (Tobira) | ✅ read | Introspection is on. GraphiQL is at `/~graphiql`. Note that `/~graphql` returns 405. |
| `GET /search/episode.json` (Opencast) | ✅ | Full mediapackages. At most 250 per page. Supports `id`, `sid`, `q`, `sort`, `offset`. |
| `GET /search/series.json` (Opencast) | ✅ | 639 series |
| `GET /~rss/series/{key}` | ✅ | Podcast feed with MP4 enclosures |
| CDN static files | ✅ | `authStaticFiles: false`, so a URL alone is enough to download |
| `GET /~metrics` | ✅ | Prometheus metrics. Exposing these publicly is probably unintentional. |
| Opencast External API `/api/*` | ❌ | Redirects to Shibboleth |
| Opencast REST docs | ❌ | Redirects to Shibboleth |

### What an anonymous client can do

- **Search** (`search`): searches titles, descriptions, creators, series and realms, and
  also **caption text**. Each hit comes with `textMatches[]` holding timestamp, duration and
  a text snippet. It returns at most 30 items (`totalHits` can be higher) and has no
  pagination. You can filter by `itemType` (EVENT/SERIES/REALM) and by a `start`/`end` date.
- **Event detail** (`eventById` / `eventByOpencastId`): returns title, creators, language
  (`metadata.dcterms.language`), series, duration and thumbnail. `authorizedData` returns:
  - `tracks[]`: progressive MP4 at 720p and 1080p, plus HLS master and variants. Flavors are
    `presenter/delivery` (camera) and `presentation/delivery` (screen capture).
  - `captions[]`: WebVTT URLs per language. Some lectures have both `de` and `en`.
  - `segments[]`: a JPEG keyframe and start time for each detected slide change.
- **Series** (`seriesById`): lists every episode. A series is usually one course in one
  semester, named like `101.883 Course Name (2025S)`.
- **Page tree** (`rootRealm`, `realmByPath`): `/lectures/E100`, … (faculty → institute →
  course). Only E100 (8 sub-pages) and E350 (3) have sub-pages so far. Most courses are
  reachable only through search and series.
- **Bulk enumeration**: use Opencast `search/episode.json?limit=250&offset=…`, because
  Tobira search can't page.

### What requires login (Shibboleth)

- Content readable only by `ROLE_TUWEL`, `ROLE_USER` or course roles such as
  `TUWEL_<courseId>_Learner`. Most TUWEL course recordings are probably in this group.
  When an anonymous client requests such an event, `eventById` returns `NotAllowed`.
- `currentUser.myVideos` / `mySeries` / `myPlaylists`, and `searchAllEvents` (which also
  covers unlisted items).
- All mutations: realm editing, metadata, ACLs, playlists, deletion.

Login is **Shibboleth SAML through the TU Wien IdP** and is interactive. Tobira has no
API tokens or OAuth. A headless client has to reuse the browser's `_shibsession_*` cookie
(see §5).

### Password-protected events

`authorizedData(user, password)` unlocks events where `hasPassword: true`.

## 3. Content statistics (public catalogue)

| | |
|---|---|
| Public episodes | **8,667** |
| Public series | 639 |
| Total duration | ≈ **7,500 h** |
| Episodes with WebVTT captions | **734 (8.5 %)** |
| Episodes with slide segments | 1,906 (22 %) |
| Language metadata | 61 % unset, 26 % `deu`, 13 % `eng` |

Caption coverage varies a lot by year. Captions seem to be generated automatically for new
uploads, probably by Opencast's Whisper integration.

| Year | Episodes | With captions |
|---|---|---|
| 2020 | 2,893 | 21 |
| 2021 | 3,071 | 41 |
| 2022 | 787 | 6 |
| 2023 | 287 | 8 |
| 2024 | 305 | 188 (62 %) |
| 2025 | 257 | 208 (81 %) |
| 2026 | 368 | 262 (71 %) |

**Conclusion:** recent lectures (2024 and later) can mostly be summarised straight from the
WebVTT captions. The large 2020–2021 COVID archive needs **speech-to-text**. Download the
smallest MP4 (or the audio from HLS) and run Whisper on it.

Slide segments contain only images and timestamps; Opencast ran no OCR on them (no
`VideoText` in the MPEG-7 catalog). A multimodal model can read the slide keyframes to add
the slide content to the summary.

## 3b. Authenticated findings (student account, 2026-11-07)

These results come from a real TU Wien student login, captured through this project's browser login. Only
read-only requests were made.

| | Anonymous | Student login |
|---|---|---|
| Roles | `ROLE_ANONYMOUS` | `ROLE_USER`, `ROLE_TUWIEN`, `ROLE_STUDENT`, `ROLE_USER_<matr>` (Opencast reports 7) |
| Opencast episodes visible | 8,667 | **14,451** (`total` reports a capped 10,000; paging beyond works) |
| Opencast series visible | 639 | **1,174** |
| Tobira `search` hits ("Informatik") | 29 | 37 |
| Restricted event (`ROLE_TUWEL`/`ROLE_TUWIEN` only) | `NotAllowed` | `AuthorizedEvent` with tracks |

Login-only GraphQL fields, as tested:

| Field | Result for a student |
|---|---|
| `currentUser` | works. `canUpload/canUseStudio/canUseEditor/canCreateSeries: false`, `canCreatePlaylists/canCreateUserRealm: true` |
| `currentUser.myVideos` | works (videos where the user is listed in the ACL, e.g. TUWEL tutorials) |
| `mySeries`, `myPlaylists` | work (empty for this student) |
| `searchAllEvents/Series/Playlists` | work. They search only items the user can *write* or that are listed |
| `searchKnownUsers` | works and returns other users (names). This is privacy-sensitive, so the MCP server doesn't expose it |
| `knownGroups` | works (empty) |
| `adminDashboardInfo` | `null` (not admin) |

Opencast node with the Shibboleth session (`lecturetube.video.tuwien.ac.at`):

- `/info/me.json` returns the user and roles. `/search/*.json` returns the extra restricted content.
- **External API (`/api/*`), `/services/*` and `/engage/ui/*` are forbidden for students.** Opencast returns an
  HTML page titled `Opencast :: Forbidden` **with HTTP 200**, so clients must check that the body is JSON.
- `/info/components.json` lists 32 REST services (saved as `opencast-components.json`).
- `/docs.html?path=/search` confirms the Search API parameters: `id`, `q`, `sid`, `sname` (unique series
  name), `sort` (title|contributor|creator|created|modified [asc|desc]), `limit` (≤250 for non-admins),
  `offset`, `sign`, `live`, and for `series.json` also `episodes=true`. The docs call `offset` a page
  number, but it behaves as an **item offset**.

**Security note:** `authStaticFiles: false` also applies to *restricted* lectures. A media URL of a
course-only recording returned `200` to a request **without any cookie**. Access control only covers who
can *discover* the URLs (they are long and random). The MCP server only fetches URLs that Tobira hands to
the current user. This may be worth reporting to the LectureTube team (ZID / TELC).

## 4. Proposed MCP server

### Tools

| Tool | Backed by | Purpose |
|---|---|---|
| `search_lectures(query, type?, from?, to?)` | GraphQL `search` | Find lectures and series. Returns caption hits with timestamps. |
| `list_series(query?, offset?)` | Opencast `search/series.json` | Browse courses (paged) |
| `get_series(series_id)` | GraphQL `seriesById` / `seriesByOpencastId` | List the episodes of a course |
| `get_lecture(event_id)` | GraphQL `eventById` | Metadata, available captions, tracks, slide count |
| `get_transcript(event_id, lang?, format=text\|vtt\|chunks)` | CDN VTT, with STT fallback | The main input for summaries |
| `get_slides(event_id, max?)` | `authorizedData.segments` | Slide keyframes (image URLs or base64) with timestamps |
| `summarize_lecture(event_id, style?)` *(optional)* | transcript + slides → LLM | Or let the client LLM do it: return the transcript and use a prompt |

### Resources and prompts

- Resource `lecturetube://event/{id}/transcript` and `lecturetube://series/{id}`
- Prompt `lecture_summary` (outline, key concepts, formulas, timestamps) and
  `exam_prep` (questions per section).

### Implementation notes

1. **IDs**: accept every form of input. `ev…`, `es…` (rewrite to `ev`), a bare key, an
   Opencast UUID, or a URL like `https://video.tuwien.ac.at/!v/<key>`.
2. **VTT → text**: merge cues, drop duplicates (Whisper output often repeats lines), and keep
   coarse timestamps such as `[00:12:30]` every N seconds so summaries can cite times.
   Split long lectures (90 min ≈ 15–20k tokens) into chunks of about 10 minutes.
3. **STT fallback** (*implemented: `transcribe_recording`, local whisper.cpp; see README*): pick the lowest-resolution `video/mp4` track and use
   `ffmpeg -vn -ac 1 -ar 16000` to extract audio, then run `faster-whisper` locally or a
   hosted STT. Cache results by Opencast ID, because a lecture's media doesn't change.
4. **Caching**: CDN files send `max-age=604800`. Cache transcripts on disk.
5. **Errors**: handle `NotAllowed`, the `SearchUnavailable` / `EmptyQuery` unions, and
   plain-text HTTP 500 responses (caused by invalid field combinations).
6. **Rate**: be polite. Make requests sequentially and cap Opencast paging at 250 per page.
7. **Auth (later)**: allow an optional `LECTURETUBE_COOKIE` environment variable that holds
   the `_shibsession_*` cookie copied from the browser. Send it to `/graphql` to reach
   `ROLE_TUWEL` content. Shibboleth sessions usually expire after a few hours, so show a clear error
   when `currentUser` comes back `null`.

### Legal / ethical

- LectureTube content falls under TU Wien terms and per-video licenses (`dcterms.license`).
  Restricted lectures are meant for enrolled students. Keep summaries of restricted lectures
  private to the user, and don't republish them.
- The fact that static files have no auth is an implementation detail. Don't use it to get
  around ACLs: only fetch URLs that GraphQL returned for the current user.
