// haul-helper lets the Haul browser extension save video and audio on this computer.
//
// Double-click it to install (on macOS and Linux, install.sh runs it): it copies itself to
// %LOCALAPPDATA%\Haul\helper (~/Library/Application Support/Haul/helper on macOS, ~/.config/Haul/helper
// on Linux), downloads yt-dlp and FFmpeg, and registers itself as a native messaging host for Chrome,
// Edge, Brave and Chromium.
// The browser then starts it with the extension's origin as the first argument and talks to it
// over stdin/stdout.
package main

import (
	"fmt"
	"os"
	"path/filepath"
	"strings"
)

const (
	version  = "0.4.0"
	hostName = "app.haul.helper"
)

// The extensions allowed to talk to the helper.
var extensionIDs = []string{
	"hnicogbjbfkjlkkeajepgjkkhojceeak", // Chrome Web Store
	"pjolfkooklpnndljgomfbnnhigaoecoe", // unpacked copy loaded from the chrome folder (fixed by the manifest key)
}

// Paths under %LOCALAPPDATA%\Haul\helper, or the user config folder outside Windows.
func baseDir() string {
	dir := os.Getenv("LOCALAPPDATA")
	if dir == "" {
		dir, _ = os.UserConfigDir()
	}
	return filepath.Join(dir, "Haul", "helper")
}

func binDir() string    { return filepath.Join(baseDir(), "bin") }
func ytdlpPath() string { return filepath.Join(binDir(), exe("yt-dlp")) }

// ffmpegPath is the FFmpeg the helper downloaded, or one already on the system if that download failed.
func ffmpegPath() string {
	own := filepath.Join(binDir(), exe("ffmpeg"))
	if fileExists(own) {
		return own
	}
	if p := systemFFmpeg(); p != "" {
		return p
	}
	return own
}

func defaultFolder() string {
	home, _ := os.UserHomeDir()
	return filepath.Join(home, "Downloads", "Haul")
}

func main() {
	args := os.Args[1:]
	for _, a := range args {
		if strings.HasPrefix(a, "chrome-extension://") {
			serve()
			return
		}
	}
	cmd := ""
	if len(args) > 0 {
		cmd = strings.TrimLeft(args[0], "-/")
	}
	var err error
	switch cmd {
	case "", "install":
		err = install()
	case "uninstall":
		err = uninstall()
	case "version":
		fmt.Println("haul-helper", version)
		return
	default:
		fmt.Println("Usage: haul-helper [install | uninstall | version]")
		return
	}
	if err != nil {
		fmt.Println()
		fmt.Println("Something went wrong:", err)
	}
	pause()
	if err != nil {
		os.Exit(1)
	}
}

// pause keeps a double-clicked window open. It does nothing when run from a script or a pipe,
// such as the macOS and Linux one-line installer.
func pause() {
	if fi, err := os.Stdin.Stat(); err != nil || fi.Mode()&os.ModeCharDevice == 0 || os.Getenv("HAUL_NO_PAUSE") != "" {
		return
	}
	fmt.Println()
	fmt.Print("Press Enter to close this window.")
	fmt.Scanln()
}
