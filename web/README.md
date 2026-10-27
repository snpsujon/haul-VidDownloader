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
| `app/cookies.py` | The server's YouTube cookies.txt |
| `app/admin.py` | Admin API (`/admin` page) |
| `app/legacy.py` | The old server's routes, for the published extension |
| `app/store.py` | Request log (SQLite) |
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

## What it does

- Paste one link, several links (one per line), or a playlist. Playlists can be downloaded whole
  (or just the videos you tick) as one ZIP.
- Video from 8K down to 144p as MP4, MKV or WebM; audio as MP3 (128 to 320k), M4A or Opus with
  cover art. Pick the audio language on dubbed videos.
- Embed subtitles (uploaded or YouTube's automatic ones), or download just a clip: type the
  start and end, or tap a chapter.
- Live progress, play the file in the page, copy a link to it, and direct links that skip the server.
- Installs as an app on Android and desktop, and shows up in the share sheet.
- Admin page at `/admin`: YouTube cookies, what's downloading, a request log with CSV export,
  disk use, and one-click yt-dlp updates.
- Keeps the old server's API, so the published "Universal Media Downloader" extension works
  when its server points here.

## Downloading on the visitor's PC

When the Haul browser extension (with its `haul-helper`) is installed, the site downloads on the
visitor's own computer, like the extension's popup does: the server only looks the link up, and
the helper runs yt-dlp and FFmpeg locally and saves to the extension's download folder. Visitors
can switch to the server with the "Download on" toggle. Clips, subtitles, picking the audio
language and MKV/WebM still go through the server, because the helper doesn't do those yet.

The extension only talks to sites listed in its `externally_connectable` (stackforge-pro.xyz,
localhost and 127.0.0.1). Add your domain there if you host the site somewhere else. The site
looks for the extension IDs in `HAUL_EXTENSION_IDS`.

Files made on the server are deleted a few minutes after they've been downloaded once
(`HAUL_SEND_GRACE_MIN`), or after `HAUL_FILE_TTL_MIN` if nobody fetches them.

## Stats, blocking, alerts and site settings

All on the admin page, saved in `$HAUL_DATA_DIR/settings.json` (no restart needed):

- **Site settings:** the site's name (logo, page titles, link previews), an announcement banner
  shown on every page (`GET /api/site`), and the contact email (overrides `HAUL_CONTACT_EMAIL`).
- **Blocking:** block an address or range (`1.2.3.0/24`), also from the Request log, and a daily
  download limit per visitor. Behind Cloudflare, nginx or Caddy set `HAUL_TRUST_PROXY=1`, or every
  visitor has the proxy's address. `CF-Connecting-IP` is used when it's there.
- **Alerts:** Telegram (bot token + chat ID) and/or email (SMTP). Checked every 10 minutes; the
  same problem is sent again after 12 hours if it's still there.
- **Stats:** top sites, countries (from Cloudflare's `CF-IPCountry`), where downloads start, and
  app, extension and helper downloads per day. Store links go through `/get/extension/store/<store>`
  so clicks are counted.
- **Server:** "Update yt-dlp now" installs the newest yt-dlp and restarts when nothing is
  downloading; "Download backup" gives a zip of the data folder's settings, log, pairings, cookies
  and releases list (optionally the release files).

## App, extension and helper downloads

The admin page has an upload card for the Android app (one or more APKs plus version and
changelog), the browser extension (store links for Chrome, Edge, Firefox and Opera, plus the .zip and a Firefox file to install by hand), the Windows PC
helper, and any other file. What's uploaded shows on the main page under "Get Haul", and the
section stays hidden until something is uploaded. Files live in `$HAUL_DATA_DIR/releases`, so
they survive rebuilds when that folder is a volume.

- `GET /get/android`, `/get/extension`, `/get/helper` always give the latest one.
- `/about`, `/privacy`, `/terms`, `/copyright` and `/contact` are info pages linked in the footer.
- The PC helper slot takes a Windows .exe plus Mac (`haul-helper-mac`), Linux x64 and Linux ARM64
  builds, served at `/get/helper/mac`, `/get/helper/linux-x64` and `/get/helper/linux-arm64`.
  Mac and Linux users install with `curl -fsSL <site>/install.sh | sh` (add `-s uninstall` to remove);
  `static/install.sh` is served as plain text with LF line endings, pointed at this site.
- The extension's version and browser are read from each file's manifest.json (a .xpi, or a manifest
  with a `gecko` key, is Firefox). The main page shows each visitor the button for their own browser.
  The Firefox file is served at `/get/extension/firefox`.
- `/helper` is a download page for the PC helper with setup steps, for linking from the extension.
- Publishing the Android app: drop the release APK(s) on the admin page. The version name, version
  code and CPU type are read from each APK; debug builds and older versions are refused. A
  `version.json` from `scripts/package_release.py` can be dropped too, for its changelog.
- `GET /app/version.json` is the Android app's update file (same format as
  `scripts/package_release.py`), published when you upload APKs. The version code can't go down.
- `GET /api/releases` lists everything as JSON and is open to any origin.

Set `HAUL_PUBLIC_URL` (like `https://haul.example.com`) when the server sits behind a proxy that
doesn't pass the right host, so the links in `version.json` are correct.

## Admin dashboard

Charts of downloads per day (14 or 30 days) and each site's failure rate. Alerts at the top say
when YouTube downloads keep failing, cookies stop working or are about to expire, the disk is
nearly full, or the nightly update failed.

## Pages for search engines

`/` and the site pages `/youtube-downloader`, `/youtube-to-mp3`, `/instagram-downloader`,
`/tiktok-downloader`, `/facebook-video-downloader` and `/twitter-video-downloader` are each the
working downloader with their own title, description, help text and FAQ (with schema.org data).
`/sitemap.xml` and `/robots.txt` are generated. Set `HAUL_PUBLIC_URL` so canonical links use your
real address, then submit the sitemap in Google Search Console.

## Send to phone

The extension can pair with Haul on a phone once and then send it links through the site:
`POST /api/pair` (gives `pair_id`, `send_key`, `receive_token`), `POST /api/pair/claim` (phone),
`GET /api/pair/status?id=&key=` (extension), `POST /api/pair/send` (extension),
`GET /api/pair/inbox?id=&token=` (phone, returns and removes waiting links) and
`POST /api/pair/unpair`. Unclaimed pairs expire after 10 minutes, links wait up to 7 days, and a
phone holds at most 50. Wrong keys get `404 {"ok": false}`. These are open to any origin.

## YouTube cookies

YouTube blocks a lot of servers with "Sign in to confirm you're not a bot". Cookies from a
signed-in account get past it. Set `HAUL_ADMIN_PASSWORD`, open `/admin`, and upload a
`cookies.txt` (the page explains how to export one). The server checks it with YouTube right away,
shows when it stops working, and the downloader shows a notice when YouTube starts refusing.
Use a spare Google account. The file is kept in `HAUL_DATA_DIR` with private permissions, and each
download works on its own copy so parallel downloads can't corrupt it.

When YouTube still refuses, the server retries with other YouTube player clients
(`HAUL_YOUTUBE_CLIENTS`) and remembers the one that worked.

## Settings

All optional, set as environment variables.

| Variable | Default | What it does |
| --- | --- | --- |
| `HAUL_API_KEYS` | (off) | Comma-separated keys. When set, every API call needs one (`X-API-Key` header or `?key=`). The website asks for it once. |
| `HAUL_CORS_ORIGINS` | `*` | Sites allowed to call the API from a browser. |
| `HAUL_MAX_CONCURRENT` | `3` | Downloads running at once on the server. |
| `HAUL_MAX_ACTIVE_PER_CLIENT` | `3` | Running downloads per IP (or per key). |
| `HAUL_FILE_TTL_MIN` | `30` | Minutes a finished file stays on the server when nobody downloads it. |
| `HAUL_DELETE_AFTER_SEND` | `1` | Delete a file soon after it has been downloaded once. |
| `HAUL_SEND_GRACE_MIN` | `5` | Minutes a downloaded file is kept, so a cut-off download can be retried. |
| `HAUL_EXTENSION_IDS` | store and unpacked IDs | Haul extensions the site asks to download on the visitor's PC. |
| `HAUL_MAX_FILESIZE_MB` | `2048` | Biggest file the server will download. |
| `HAUL_MAX_DURATION_MIN` | `240` | Longest video the server will download. |
| `HAUL_ADMIN_PASSWORD` | (off) | Turns on the `/admin` page. |
| `HAUL_COOKIES_FILE` | (none) | A `cookies.txt` to start with. It's copied in once; after that, manage cookies on the admin page. |
| `HAUL_YOUTUBE_CLIENTS` | `tv,web_safari,mweb,android` | YouTube player clients to retry with when YouTube refuses. |
| `HAUL_MAX_PLAYLIST_ITEMS` | `50` | Most videos one playlist download takes. |
| `HAUL_LOG_DAYS` | `30` | Days the request log keeps rows. |
| `HAUL_MAX_UPLOAD_MB` | `1024` | Biggest file the admin can upload (APKs, extension, helper, other files). |
| `HAUL_PUBLIC_URL` | from the request | The site's public address, used in download links. |
| `HAUL_AUTO_UPDATE` | `1` | Update yt-dlp every night and re-check the YouTube cookies. `0` turns it off. |
| `HAUL_AUTO_UPDATE_HOUR` | `4` | Hour of the night (server time) for the update. |
| `HAUL_AUTO_RESTART` | `1` | Restart once downloads finish to load a new yt-dlp (needs Docker's restart policy). |
| `HAUL_CONTACT_EMAIL` | none | Email shown on the Contact page (privacy requests and copyright notices point there). |
| `HAUL_LEGACY_API` | `1` | The old `/api/download`, `/api/progress`, `/downloads/…` routes. Set `0` to turn off. They don't need an API key, because the old extension can't send one. |
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

Also on `POST /api/jobs`: `"subtitles": ["en"]` (video), `"start": 75, "end": 130` (clip, in
seconds), and `"playlist": true` with an optional `"playlist_items": [1, 3]` to download a playlist
as one ZIP. Send `X-Haul-Client: app/1.2.0` (or `extension/2.0.0`) so the admin log shows where
requests come from.

### `POST /api/jobs/batch`

`{"urls": ["…", "…"], "options": { …same fields as /api/jobs… }}`. Up to 20 links; each becomes its
own job. Returns `{"results": [{"url", "job"} or {"url", "error", "status"}]}`.

### `GET /api/direct-links?url=…`

Links straight to the site's files (single files with sound, or audio only), so the server's
bandwidth isn't used. Many sites, YouTube included, tie these to the server's IP, so they may not
open elsewhere, and they expire.

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

The finished file, as an attachment with its real name. `409` until it's ready. Add `?inline=1`
to play it in a browser.

### Admin API

`/api/admin/*`, with `X-Admin-Token` from `POST /api/admin/login {"password"}`: `overview`, `jobs`,
`cookies` (GET, PUT `{"text"}`, DELETE, `POST cookies/check`), `requests` (+ `export`, DELETE),
`files/clear`, `ytdlp/update`, `restart`. All listed in `/api/docs`.

### Old API

The old server's routes still work: `POST /api/download` (form `url`, `format`, `quality`,
`playlist`) returns a `session_id`; `GET /api/progress` with `X-Session-ID`; files at
`/downloads/<filename>`; `POST /api/cancel`; `GET /api/direct-links?url=…&format=video|audio`.

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
