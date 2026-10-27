//go:build !windows

package main

// Linux and macOS: enough to develop and test the extension. Uses FFmpeg from PATH.

import (
	"os"
	"os/exec"
	"path/filepath"
	"runtime"
)

var (
	ytdlpURL     = "https://github.com/yt-dlp/yt-dlp/releases/latest/download/yt-dlp_linux"
	ffmpegZipURL = ""
)

func init() {
	if runtime.GOOS == "darwin" {
		ytdlpURL = "https://github.com/yt-dlp/yt-dlp/releases/latest/download/yt-dlp_macos"
	}
}

func exe(name string) string { return name }

func hostDirs() []string {
	home, _ := os.UserHomeDir()
	if runtime.GOOS == "darwin" {
		s := filepath.Join(home, "Library", "Application Support")
		return []string{
			filepath.Join(s, "Google", "Chrome", "NativeMessagingHosts"),
			filepath.Join(s, "Microsoft Edge", "NativeMessagingHosts"),
			filepath.Join(s, "Chromium", "NativeMessagingHosts"),
		}
	}
	c := filepath.Join(home, ".config")
	return []string{
		filepath.Join(c, "google-chrome", "NativeMessagingHosts"),
		filepath.Join(c, "microsoft-edge", "NativeMessagingHosts"),
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

// FFmpeg comes from PATH here.
func init() {
	if p, err := exec.LookPath("ffmpeg"); err == nil {
		ffmpegOverride = p
	}
}
