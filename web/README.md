# Haul web

The website version of Haul: paste a link, pick video quality or an audio format and the audio
language, and download. It runs [yt-dlp](https://github.com/yt-dlp/yt-dlp) and FFmpeg on a server
and exposes the same steps as a REST API, so the Android app, a browser extension or a script can
use it too.

Python 3.11+ with FastAPI. The website is plain HTML, CSS and JS in `static/`, served by the same
server, so there's nothing to build.

| Path | What it is |
| --- | --- |
| `app/main.py` | The API routes and the static site |
| `app/media.py` | Format choices (same picks as the Android app's format sheet) |
| `app/engine.py` | yt-dlp calls and plain-language errors |
| `app/jobs.py` | The download queue, progress, and file cleanup |
| `app/security.py` | Link checks (http/https only, no private addresses) |
| `static/` | The website |
| `tests/` | `pytest` tests; they don't touch the network |

## Run it

With Docker (includes FFmpeg):

```sh
docker compose up -d --build
# open http://localhost:8000
```

Without Docker, you need Python and FFmpeg on your PATH:

```sh
python -m venv .venv && . .venv/bin/activate     # Windows: .venv\Scripts\activate
pip install -r requirements.txt
uvicorn app.main:app --reload
```

Tests:

```sh
pip install -r requirements.txt -r requirements-dev.txt
pytest
```

## Settings

All optional, set as environment variables.

| Variable | Default | What it does |
| --- | --- | --- |
| `HAUL_API_KEYS` | (off) | Comma-separated keys. When set, every API call needs one (`X-API-Key` header or `?key=`). The website asks for it once. |
| `HAUL_CORS_ORIGINS` | `*` | Sites allowed to call the API from a browser. |
| `HAUL_MAX_CONCURRENT` | `3` | Downloads running at once on the server. |
| `HAUL_MAX_ACTIVE_PER_CLIENT` | `3` | Running downloads per IP (or per key). |
| `HAUL_FILE_TTL_MIN` | `60` | Minutes a finished file stays on the server. |
| `HAUL_MAX_FILESIZE_MB` | `2048` | Biggest file the server will download. |
| `HAUL_MAX_DURATION_MIN` | `240` | Longest video the server will download. |
| `HAUL_COOKIES_FILE` | (none) | A Netscape `cookies.txt`, for sites that need sign-in or that block servers ("confirm you're not a bot"). |
| `HAUL_PROXY` | (none) | Proxy for yt-dlp, e.g. `socks5://host:1080`. |
| `HAUL_TRUST_PROXY` | off | Set `1` behind a reverse proxy so per-client limits use `X-Forwarded-For`. |
| `HAUL_DATA_DIR` | `./data` | Where downloads are kept while they wait to be fetched. |
| `HAUL_UPDATE_ON_START` | off | Docker only: update yt-dlp each time the container starts. |
| `HAUL_ALLOW_PRIVATE` | off | Allow links to private and loopback addresses. Only for local use and tests. |

## Hosting notes

- **yt-dlp needs updating.** Sites change and old versions stop working. Rebuild the image every
  week or two, or keep `HAUL_UPDATE_ON_START=1` and restart.
- **YouTube often blocks data-centre IPs** with a "sign in to confirm you're not a bot" error. A
  home server or a residential proxy (`HAUL_PROXY`) works best; cookies (`HAUL_COOKIES_FILE`) help
  too, but use a spare account.
- Files pass through the server, so bandwidth and disk matter more than CPU. MP3 conversion and
  merging use FFmpeg and some CPU.
- Put it behind HTTPS (Caddy or nginx) before sharing it. The event stream needs proxy buffering
  off (`X-Accel-Buffering: no` is already sent for nginx).
- Jobs are kept in memory. One server process only (no multiple uvicorn workers); a restart
  forgets jobs and clears the download folder.
- Link checks block private addresses when the link is looked up, but a site could still redirect
  to one. Don't run it on a network with sensitive internal services without a firewall.

## API

Interactive docs live at `/api/docs` (Swagger) and `/api/redoc`; the schema is
`/api/openapi.json`. Errors come back as `{"detail": "plain message"}` with a 4xx status.

### `GET /api/health`

Server status, yt-dlp and FFmpeg versions, whether keys are needed, and limits.

### `POST /api/info`

```json
{ "url": "https://www.youtube.com/watch?v=…", "container": "mp4", "language": null, "playlist": true }
```

`container` (`mp4`, `mkv`, `webm`) and `language` change which codecs and audio track the video
choices use. `GET /api/info?url=…` does the same. Returns either a playlist:

```json
{ "kind": "playlist", "title": "…", "count": 12, "entries": [ { "id": "…", "title": "…", "url": "…", "duration": 212 } ] }
```

or one video:

```json
{
  "kind": "media", "id": "…", "title": "…", "uploader": "…", "duration": 212, "thumbnail": "…",
  "audio_languages": [ { "code": "en", "name": "English", "original": true }, { "code": "es", "name": "Spanish", "original": false } ],
  "video": [ { "height": 1080, "label": "1080p · Full HD", "spec": "30fps", "codecs": "AVC + AAC", "size": 52000000, "selector": "137+140/…" } ],
  "recommended_height": 1080,
  "audio": [ { "format": "mp3", "label": "MP3", "spec": "Plays everywhere · re-encoded", "size": 5100000, "badge": null } ],
  "subtitles": ["en", "es"]
}
```

`audio_languages` is empty unless the video has more than one audio track. `size` is an estimate
and can be `null`.

### `POST /api/jobs`

Starts a download and returns the job (status `201`).

```json
{
  "url": "https://…",
  "kind": "video",          // or "audio"
  "height": 1080,           // video; leave out for the recommended height
  "container": "mp4",       // video: mp4, mkv, webm
  "audio_format": "mp3",    // audio: mp3, m4a, opus
  "audio_bitrate": 192,     // mp3 only: 128, 192, 256, 320
  "language": "es",         // optional audio track
  "embed_cover": true       // audio: thumbnail as cover art
}
```

`429` means the caller already has too many downloads running.

### `GET /api/jobs/{id}`

```json
{
  "id": "…", "status": "running", "stage": "downloading", "percent": 42.5,
  "downloaded": 22000000, "total": 52000000, "speed": 3100000, "eta": 9,
  "title": "…", "label": "1080p MP4", "filename": null, "file_url": null, "error": null, "expires": null
}
```

`status` is `queued`, `running`, `done`, `error` or `cancelled`. While running, `stage` is
`downloading`, `merging`, `converting`, `tagging` or `processing`. `percent` can be `null` when
the site doesn't give a size.

### `GET /api/jobs/{id}/events`

A [server-sent events](https://developer.mozilla.org/docs/Web/API/EventSource) stream. Each
`progress` event carries the same JSON as above; the stream ends when the job does. With keys on,
pass `?key=` since `EventSource` can't send headers.

### `GET /api/jobs/{id}/file`

The finished file, as an attachment with its real name. `409` until it's ready.

### `DELETE /api/jobs/{id}`

Cancels a running download, or deletes a finished file.

### Example

```sh
H='Content-Type: application/json'
curl -s -X POST localhost:8000/api/info -H "$H" -d '{"url":"https://youtu.be/…"}'
ID=$(curl -s -X POST localhost:8000/api/jobs -H "$H" -d '{"url":"https://youtu.be/…","kind":"audio","audio_format":"mp3"}' | jq -r .id)
curl -sN localhost:8000/api/jobs/$ID/events
curl -OJ localhost:8000/api/jobs/$ID/file
```

## Linking in from the app or an extension

`/?url=<link>` opens the site with the link filled in and looked up, so a browser extension can
open `https://your-site/?url=` + the current tab's address.

## Legal

Only download content you own, content in the public domain, or content you have permission to
save. If you run this publicly, add a takedown contact and expect sites' terms to forbid it.
