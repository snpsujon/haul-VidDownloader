// haul-helper lets the Haul browser extension save video and audio on this computer.
//
// Double-click it to install: it copies itself to %LOCALAPPDATA%\Haul\helper, downloads yt-dlp and
// FFmpeg, and registers itself as a native messaging host for Chrome, Edge, Brave and Chromium.
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
	version     = "0.1.0"
	hostName    = "app.haul.helper"
	extensionID = "pjolfkooklpnndljgomfbnnhigaoecoe"
)

// Paths under %LOCALAPPDATA%\Haul\helper.
func baseDir() string {
	dir := os.Getenv("LOCALAPPDATA")
	if dir == "" {
		dir, _ = os.UserConfigDir()
	}
	return filepath.Join(dir, "Haul", "helper")
}

func binDir() string    { return filepath.Join(baseDir(), "bin") }
func ytdlpPath() string { return filepath.Join(binDir(), exe("yt-dlp")) }
func ffmpegPath() string {
	if ffmpegOverride != "" {
		return ffmpegOverride
	}
	return filepath.Join(binDir(), exe("ffmpeg"))
}

// Set on platforms that use the system FFmpeg.
var ffmpegOverride string

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
}

func pause() {
	fmt.Println()
	fmt.Print("Press Enter to close this window.")
	fmt.Scanln()
}
