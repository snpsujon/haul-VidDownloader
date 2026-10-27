//go:build !windows

package main

// macOS and Linux. install.sh downloads the right build and runs "haul-helper install".

import (
	"os"
	"os/exec"
	"path/filepath"
	"runtime"
)

var ytdlpURL, ffmpegArchives = downloads()

// downloads picks yt-dlp and FFmpeg for this system. yt-dlp_macos runs on Intel and Apple silicon.
// macOS FFmpeg comes from Martin Riedl's build server (one zip per program), Linux FFmpeg from
// yt-dlp's own builds.
func downloads() (string, []string) {
	const gh = "https://github.com/yt-dlp/"
	if runtime.GOOS == "darwin" {
		arch := "arm64"
		if runtime.GOARCH == "amd64" {
			arch = "amd64"
		}
		mr := "https://ffmpeg.martin-riedl.de/redirect/latest/macos/" + arch + "/release/"
		return gh + "yt-dlp/releases/latest/download/yt-dlp_macos", []string{mr + "ffmpeg.zip", mr + "ffprobe.zip"}
	}
	if runtime.GOARCH == "arm64" {
		return gh + "yt-dlp/releases/latest/download/yt-dlp_linux_aarch64",
			[]string{gh + "FFmpeg-Builds/releases/download/latest/ffmpeg-master-latest-linuxarm64-gpl.tar.xz"}
	}
	return gh + "yt-dlp/releases/latest/download/yt-dlp_linux",
		[]string{gh + "FFmpeg-Builds/releases/download/latest/ffmpeg-master-latest-linux64-gpl.tar.xz"}
}

func exe(name string) string { return name }

func hostDirs() []string {
	home, _ := os.UserHomeDir()
	if runtime.GOOS == "darwin" {
		s := filepath.Join(home, "Library", "Application Support")
		return []string{
			filepath.Join(s, "Google", "Chrome", "NativeMessagingHosts"),
			filepath.Join(s, "Microsoft Edge", "NativeMessagingHosts"),
			filepath.Join(s, "BraveSoftware", "Brave-Browser", "NativeMessagingHosts"),
			filepath.Join(s, "Chromium", "NativeMessagingHosts"),
		}
	}
	c := filepath.Join(home, ".config")
	return []string{
		filepath.Join(c, "google-chrome", "NativeMessagingHosts"),
		filepath.Join(c, "microsoft-edge", "NativeMessagingHosts"),
		filepath.Join(c, "BraveSoftware", "Brave-Browser", "NativeMessagingHosts"),
		filepath.Join(c, "chromium", "NativeMessagingHosts"),
	}
}

func registerHost(manifest string) error {
	body, err := os.ReadFile(manifest)
	if err != nil {
		return err
	}
	for _, d := range hostDirs() {
		if err := os.MkdirAll(d, 0o755); err != nil {
			return err
		}
		if err := os.WriteFile(filepath.Join(d, hostName+".json"), body, 0o644); err != nil {
			return err
		}
	}
	return nil
}

func unregisterHost() {
	for _, d := range hostDirs() {
		os.Remove(filepath.Join(d, hostName+".json"))
	}
}

func hide(c *exec.Cmd) {}

func reveal(path string) error { return openFolder(filepath.Dir(path)) }

func openPath(p string) error { return openFolder(p) }

func openFolder(dir string) error {
	if runtime.GOOS == "darwin" {
		return exec.Command("open", dir).Start()
	}
	return exec.Command("xdg-open", dir).Start()
}

// systemFFmpeg finds an FFmpeg that was already installed, for when the download failed. The browser
// starts the helper with a short PATH, so the usual Homebrew and package folders are checked too.
func systemFFmpeg() string {
	if p, err := exec.LookPath("ffmpeg"); err == nil {
		return p
	}
	for _, p := range []string{"/opt/homebrew/bin/ffmpeg", "/usr/local/bin/ffmpeg", "/usr/bin/ffmpeg"} {
		if fileExists(p) {
			return p
		}
	}
	return ""
}
