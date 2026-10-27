# Haul for Chrome and Edge

A browser extension that goes with the Haul Android app. Click the Haul button on any video page and pick:

- **To phone**: shows a QR code. Scan it with your phone camera and the link opens in Haul.
- **On this PC**: saves the video (MP4) or audio (MP3, M4A or Opus) on this computer, using yt-dlp.

Right-clicking a page or link gives the same three choices: *Send to phone*, *Download video on this PC* and *Download audio on this PC*.

More ways in:

- **Button on videos** (off by default; turn it on in settings): point at a video on almost any site and a Haul button appears in its corner. It opens a menu with Send to phone (QR code right there), Download video (with a quality pick) and Download audio (MP3, M4A or Opus), and shows progress while it saves. In feeds like Facebook, Instagram and X, it finds the link to that particular post. It works in fullscreen players and can be hidden per site. On YouTube it shows only if "Also show it on YouTube" is switched on in settings. That switch is off by default, because the Chrome Web Store is strict about YouTube downloaders. Turning it on asks for the "all sites" permission. That's an optional permission, so installing or updating the extension doesn't ask for it.
- **Clips:** in the popup tick "Only a part" and set From/To (or press Now to use where the video is playing), or use "Save a clip" on the video button. Only that part is saved, cut exactly (`--download-sections` with `--force-keyframes-at-cuts`), and the file name says which part.
- **Playlists and channels:** on a playlist or channel page the popup offers "This video / Whole playlist", the video button has "Whole playlist", and the right-click menu has "Download the whole playlist". Everything goes into a folder named after the playlist, numbered in order, with "Video 3 of 25" progress.
- **Download history:** `history.html` (clock icon in the popup, or right-click the toolbar icon). It's kept across restarts (the last 500), and you can search it, filter video/audio, open the file or folder, download again with the same settings, copy the link, and see which files were moved or deleted.
- **Subtitles and cover art** (settings): subtitles inside the video or as .srt, in the languages you list (like `bn,en`), and the thumbnail as cover art inside the file or as .jpg.
- **One-tap send to phone:** pair your phone once in settings (scan a QR code, confirm in Haul). After that, Send to phone in the popup, on the video button, in the right-click menu and on Alt+Shift+Q goes straight to Haul through the website (`/api/pair/*`), with no scanning.
- **Keyboard shortcuts:** Alt+Shift+D downloads the video, Alt+Shift+M downloads the audio, Alt+Shift+Q sends to the phone. You can change them at `chrome://extensions/shortcuts`.

## What's in here

| Folder | What it is |
| --- | --- |
| `chrome/` | The extension (Manifest V3). Works in Chrome, Edge, Brave and other Chromium browsers. `inpage.js` is the button on videos. |
| `helper/haul-helper.exe` | The Windows helper that runs yt-dlp for desktop downloads. |
| `helper-source/` | The helper's Go source. |

## Install the extension

1. Keep this folder where it is. Chrome loads the extension from it, so moving or deleting it removes the extension.
2. Open `chrome://extensions` (or `edge://extensions` in Edge).
3. Turn on **Developer mode**.
4. Click **Load unpacked** and pick the `chrome` folder.
5. Pin Haul from the puzzle-piece menu so its button is always visible.

## Set up desktop downloads (once)

1. Double-click `helper\haul-helper.exe`.
2. If Windows says "Windows protected your PC", click **More info**, then **Run anyway**. It shows this because the helper isn't code-signed yet.
3. The helper copies itself to `%LOCALAPPDATA%\Haul\helper`, downloads yt-dlp and FFmpeg (about 150 MB), and registers itself with Chrome, Edge, Brave and Chromium. It doesn't need admin rights.
4. Open the Haul extension, choose **On this PC**, and download.

Files are saved to `Downloads\Haul` unless you choose another folder in the extension's settings. You can update yt-dlp from the settings page when a site stops working.

To remove the helper, run `haul-helper.exe uninstall` from a terminal, then delete `%LOCALAPPDATA%\Haul\helper`.

## Phone links

With the default setting, the QR code holds `haul://save?url=<the page link>`, which opens Haul's share sheet directly. That needs a Haul app build with the `haul://` link added. On an older build, switch the QR code to **Plain page link** (the "Not opening Haul?" button in the popup, or the settings page): the phone opens the page, and you share it to Haul from there.

## How it fits together

```
Popup / right-click menu
        │  chrome.runtime messages
Service worker (background.js) ── keeps the job list in session storage
        │  native messaging (stdin/stdout JSON)
haul-helper.exe ── runs yt-dlp.exe + ffmpeg.exe from %LOCALAPPDATA%\Haul\helper\bin
```

The `chrome` folder's manifest has a `key`, which gives the unpacked copy a fixed ID (`pjolfkooklpnndljgomfbnnhigaoecoe`). The store copy has the ID `hnicogbjbfkjlkkeajepgjkkhojceeak`. The helper accepts both, and nothing else (see `extensionIDs` in `helper-source/main.go`).

## Website bridge

The Haul website (`stackforge-pro.xyz` and `www.stackforge-pro.xyz`, plus `localhost` / `127.0.0.1` on any port for development) can ask the extension to download on the visitor's PC through the helper. It uses `chrome.runtime.sendMessage(extensionId, msg)` and tries both IDs. Messages: `{cmd:'hello'}`, `{cmd:'download', url, mode, title?, quality?, audioFormat?}`, `{cmd:'jobs', ids?}`, `{cmd:'cancel', id}`, `{cmd:'reveal', id}`. The site only sees and controls the jobs it started, and never gets file paths (`hasFile` instead). To add another site, edit `externally_connectable` in `manifest.json` and `SITE_ORIGINS` in `background.js`.

## Publishing to the Chrome Web Store

Version 2.0.0 replaces the old "Universal Media Downloader" listing as an update to the same store item.

1. Upload `store/haul-2.0.0-chrome-web-store.zip`. It's the `chrome` folder with the `key` line removed, because the store rejects packages that contain one. Go to the developer dashboard, open the existing item, then Package, then Upload new package.
2. Update the listing: name, description, screenshots and the 128 px icon. Keep YouTube out of the text and screenshots, because the store doesn't allow YouTube downloaders. If review rejects the update because of the "Also show it on YouTube" switch, remove it from the store build.
3. On the Privacy tab, explain the new permissions. `nativeMessaging` talks to the optional desktop helper. `notifications` says when a download finishes. `contextMenus` adds the right-click items. `storage` keeps settings. `scripting` adds the button on videos, and the optional all-sites access is requested only when the user turns that button on.
4. Upload `helper/haul-helper.exe` on the website's admin page. The extension's "Download the PC helper" buttons open the helper download page `https://stackforge-pro.xyz/helper` (`HELPER_DOWNLOAD_URL` in `chrome/common.js`; the site and API live at `SITE_URL`). The settings page reads `/api/releases` and offers a newer helper when there is one.

For each later release, raise `version` in `chrome/manifest.json`, then rebuild the store zip. To do that, copy `chrome`, delete the `"key": …` line from the copy's manifest, and zip the copy's contents. The manifest goes at the root of the zip, not inside a folder.

## Building the helper

Needs Go 1.22 or newer.

```
cd helper-source
GOOS=windows GOARCH=amd64 go build -trimpath -ldflags "-s -w" -o haul-helper.exe .
```

On Linux or macOS, `go build` produces a helper for that system, which is handy for testing. It uses FFmpeg from `PATH`.
