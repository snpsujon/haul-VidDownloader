# Haul for Android

Save video and audio from links to your phone. Kotlin and Jetpack Compose, with
[yt-dlp](https://github.com/yt-dlp/yt-dlp) running on the device through
[youtubedl-android](https://github.com/JunkFood02/youtubedl-android) (Python, FFmpeg and aria2c
included). Distributed as a signed APK from your own website, with a built-in update check.

"Haul" and everything in `[BRACKETS]` are placeholders.

## What's in here

| Path | What it is |
| --- | --- |
| `app/` | The Android app |
| `website/` | Download page, privacy page and a sample `app/version.json` |
| `scripts/package_release.py` | Renames and hashes release APKs and writes `version.json` |
| `changelog/<version>.txt` | One line per change; shown in the in-app update sheet |
| `.github/workflows/build.yml` | Tests and debug APKs on every push, signed release on a `v*` tag |

App code, by area (`app/src/main/java/app/haul/`):

- `engine/` runs yt-dlp: link info, format choices, progress parsing, plain-language errors, engine updates.
- `download/` is the queue (Room), the foreground service and its notifications, network rules.
- `data/` holds settings (DataStore), the database and saving files (MediaStore, or a folder picked with the system picker).
- `update/` checks `version.json`, downloads the APK, checks its SHA-256 and installs it with PackageInstaller.
- `player/` has the video player and the background audio player (Media3).
- `ui/` has the screens, one package per area, and `ui/components` + `ui/theme` hold the design system.

## Placeholders to replace

| Where | What |
| --- | --- |
| `app/build.gradle.kts` → `appSite` (or the `HAUL_SITE` repo variable) | Your website. Update checks stay off while it is `example.com`. |
| `app/build.gradle.kts` → `applicationId` | The package name. **It can never change after the first public release.** |
| `app/build.gradle.kts` → `TAKEDOWN_EMAIL` | Copyright and takedown contact |
| `app/src/main/res/values/strings.xml` → `app_name` | The app's name |
| `website/*.html` | `[YOUR CERTIFICATE FINGERPRINT]`, `[SOURCE CODE URL]`, `[TAKEDOWN EMAIL]`, `[DATE]`, `[CONTACT EMAIL]` |

The source and privacy links in the app point at `<site>/source` and `<site>/privacy`.

## Building

You need JDK 17 and the Android SDK (Android Studio sets both up).

```sh
./gradlew testDebugUnitTest   # unit tests for formats, progress, errors and formatting
./gradlew assembleDebug       # app/build/outputs/apk/debug/
```

Debug builds install next to release builds (`.debug` package suffix).

## Signing

Create the release key once and keep it safe. Losing it means existing users can't update.

```sh
keytool -genkeypair -v -keystore haul-release.jks -alias haul -keyalg RSA -keysize 4096 -validity 10000
keytool -list -v -keystore haul-release.jks -alias haul | grep SHA256   # the fingerprint for the website
```

Locally, put a `keystore.properties` next to `settings.gradle.kts` (it is git-ignored):

```properties
storeFile=/absolute/path/haul-release.jks
storePassword=...
keyAlias=haul
keyPassword=...
```

On GitHub, add these under **Settings → Secrets and variables → Actions**:

- Secrets: `HAUL_KEYSTORE_BASE64` (output of `base64 -w0 haul-release.jks`), `HAUL_KEYSTORE_PASSWORD`, `HAUL_KEY_ALIAS`, `HAUL_KEY_PASSWORD`
- Variables: `HAUL_SITE` (for example `https://haul.example.org`), optionally `HAUL_MIN_SUPPORTED`

## Releasing

1. Write `changelog/1.3.0.txt`, one change per line.
2. Tag and push: `git tag v1.3.0 && git push origin v1.3.0`. The version code is worked out from the tag (`1.3.0` → `10300`).
3. CI builds signed APKs for each CPU type plus a universal one, writes `version.json` with each file's SHA-256, and drafts a GitHub release.
4. Upload the `app/` folder from `haul-website-upload.zip` to your site, so `https://your.site/app/version.json` exists. Upload `website/index.html` and `privacy.html` once.

Installed copies check `version.json` once a day. To force everyone off an old version, set
`HAUL_MIN_SUPPORTED` to the lowest version code that still works; older copies then show the
"Update required" screen.

## yt-dlp

Each CI build downloads the latest yt-dlp release into `app/src/main/res/raw/ytdlp`, which replaces
the copy inside youtubedl-android. On the phone, yt-dlp then updates itself from GitHub (Stable or
Nightly, at most once a day on Wi-Fi, or from Settings → Download engine). "Restore bundled version"
goes back to the copy in the APK.

## Fonts

Bricolage Grotesque, Instrument Sans and JetBrains Mono, under the SIL Open Font License (texts in
`app/src/main/assets/licenses`). Android can't pick axes from variable fonts on older versions, so
`res/font` holds static instances cut with fonttools:

```sh
pip install fonttools
fonttools varLib.instancer BricolageGrotesque[opsz,wdth,wght].ttf opsz=96 wdth=100 wght=700 -o bricolage_display_bold.ttf
fonttools varLib.instancer BricolageGrotesque[opsz,wdth,wght].ttf opsz=24 wdth=100 wght=700 -o bricolage_bold.ttf
fonttools varLib.instancer BricolageGrotesque[opsz,wdth,wght].ttf opsz=24 wdth=100 wght=600 -o bricolage_semibold.ttf
```

The same for Instrument Sans (400, 500, 600) and JetBrains Mono (500, 600).

## Known limits

- yt-dlp needs a JavaScript runtime for some YouTube formats, and youtubedl-android doesn't ship one yet. Those formats may be missing until it does.
- The audio player's waveform is a fixed pattern per file used as a seek bar, not the decoded audio.
- Video plays only while its screen is open; audio keeps playing with the screen off.

## Licence

GPL-3.0. See `LICENSE`.
