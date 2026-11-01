package main

import (
	"os/exec"
	"syscall"
)

const ytdlpURL = "https://github.com/yt-dlp/yt-dlp/releases/latest/download/yt-dlp.exe"

var ffmpegArchives = []string{"https://github.com/yt-dlp/FFmpeg-Builds/releases/download/latest/ffmpeg-master-latest-win64-gpl.zip"}

// Per-user registry keys each Chromium browser reads native messaging hosts from.
var chromeHostKeys = []string{
	`HKCU\Software\Google\Chrome\NativeMessagingHosts\`,
	`HKCU\Software\Microsoft\Edge\NativeMessagingHosts\`,
	`HKCU\Software\BraveSoftware\Brave-Browser\NativeMessagingHosts\`,
	`HKCU\Software\Chromium\NativeMessagingHosts\`,
}

// Firefox reads its own key, which points at a manifest with allowed_extensions.
const firefoxHostKey = `HKCU\Software\Mozilla\NativeMessagingHosts\`

func exe(name string) string { return name + ".exe" }

func registerHost(chromeManifest, firefoxManifest string) error {
	for _, k := range chromeHostKeys {
		if err := regAdd(k, chromeManifest); err != nil {
			return err
		}
	}
	return regAdd(firefoxHostKey, firefoxManifest)
}

func regAdd(key, manifest string) error {
	c := exec.Command("reg", "add", key+hostName, "/ve", "/t", "REG_SZ", "/d", manifest, "/f")
	hide(c)
	if out, err := c.CombinedOutput(); err != nil {
		return &regError{key: key, out: string(out), err: err}
	}
	return nil
}

func unregisterHost() {
	for _, k := range append(chromeHostKeys, firefoxHostKey) {
		c := exec.Command("reg", "delete", k+hostName, "/f")
		hide(c)
		c.Run()
	}
}

type regError struct {
	key, out string
	err      error
}

func (e *regError) Error() string { return "registry " + e.key + ": " + e.out }

// hide keeps child processes from flashing a console window.
func hide(c *exec.Cmd) {
	c.SysProcAttr = &syscall.SysProcAttr{HideWindow: true, CreationFlags: 0x08000000} // CREATE_NO_WINDOW
}

func reveal(path string) error {
	c := exec.Command("explorer")
	// explorer wants the quotes around the path only, which exec's own quoting can't produce.
	c.SysProcAttr = &syscall.SysProcAttr{CmdLine: `explorer /select,"` + path + `"`}
	return c.Start()
}

func openPath(p string) error {
	return exec.Command("explorer", p).Start()
}

func openFolder(dir string) error {
	return exec.Command("explorer", dir).Start()
}

// The Windows helper always uses the FFmpeg it downloaded.
func systemFFmpeg() string { return "" }
