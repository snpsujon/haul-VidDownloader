#!/bin/sh
# Haul PC helper installer for macOS and Linux.
#
#   curl -fsSL https://stackforge-pro.xyz/install.sh | sh
#
# Downloads the helper for this computer, then runs "haul-helper install", which puts it in
# ~/Library/Application Support/Haul/helper (macOS) or ~/.config/Haul/helper (Linux), downloads
# yt-dlp and FFmpeg, and connects it to Chrome, Edge, Brave and Chromium.
# To remove it later: run the same line with "-s uninstall" after "sh".
set -eu

SITE="${HAUL_SITE:-https://stackforge-pro.xyz}"
ACTION="${1:-install}"

say() { printf '%s\n' "$*"; }
fail() { say ""; say "Haul: $*" >&2; exit 1; }

case "$(uname -s)" in
  Darwin) build=mac ;;
  Linux)
    case "$(uname -m)" in
      x86_64 | amd64) build=linux-x64 ;;
      aarch64 | arm64) build=linux-arm64 ;;
      *) fail "This computer's processor ($(uname -m)) isn't supported yet." ;;
    esac ;;
  *) fail "This installer is for macOS and Linux. On Windows, download haul-helper.exe from $SITE/helper." ;;
esac

if command -v curl >/dev/null 2>&1; then
  fetch() { curl -fL --progress-bar -o "$2" "$1"; }
elif command -v wget >/dev/null 2>&1; then
  fetch() { wget -q --show-progress -O "$2" "$1"; }
else
  fail "Please install curl or wget first."
fi

if [ "$build" != mac ] && ! command -v tar >/dev/null 2>&1; then
  fail "Please install tar first. It's needed to unpack FFmpeg."
fi

tmp="$(mktemp -d 2>/dev/null || mktemp -d -t haul)"
trap 'rm -rf "$tmp"' EXIT INT TERM

say "Downloading the Haul helper for $build"
fetch "$SITE/get/helper/$build" "$tmp/haul-helper" || fail "Couldn't download the helper from $SITE. Check your internet connection and try again."
chmod +x "$tmp/haul-helper"

# The helper reads nothing from the terminal here, so the rest of this script stays out of its way.
HAUL_NO_PAUSE=1 "$tmp/haul-helper" "$ACTION" </dev/null
