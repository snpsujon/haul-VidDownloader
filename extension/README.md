# Haul for Chrome and Edge

A browser extension that goes with the Haul Android app. Click the Haul button on any video page and pick:

- **To phone**: shows a QR code. Scan it with your phone camera and the link opens in Haul.
- **On this PC**: saves the video (MP4) or audio (MP3, M4A or Opus) on this computer, using yt-dlp.

Right-clicking a page or link gives the same three choices: *Send to phone*, *Download video on this PC* and *Download audio on this PC*.

## What's in here

| Folder | What it is |
| --- | --- |
| `chrome/` | The extension (Manifest V3). Works in Chrome, Edge, Brave and other Chromium browsers. |
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

The extension has a fixed ID (`pjolfkooklpnndljgomfbnnhigaoecoe`, from the `key` in `manifest.json`). The helper only accepts that ID. If the extension is ever published on the Chrome Web Store or Edge Add-ons, the store gives it a new ID, and that ID needs adding to `allowed_origins` in `helper-source/install.go`.

## Building the helper

Needs Go 1.22 or newer.

```
cd helper-source
GOOS=windows GOARCH=amd64 go build -trimpath -ldflags "-s -w" -o haul-helper.exe .
```

On Linux or macOS, `go build` produces a helper for that system, which is handy for testing. It uses FFmpeg from `PATH`.
