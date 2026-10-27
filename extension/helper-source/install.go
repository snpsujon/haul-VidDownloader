package main

import (
	"archive/zip"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"os"
	"path/filepath"
	"strings"
	"time"
)

func install() error {
	fmt.Println("Haul helper", version)
	fmt.Println("Setting up desktop downloads for the Haul browser extension.")
	fmt.Println()

	if err := os.MkdirAll(binDir(), 0o755); err != nil {
		return err
	}

	// 1. Put the helper somewhere stable, so the browser can find it after the download is deleted.
	self, err := os.Executable()
	if err != nil {
		return err
	}
	target := filepath.Join(baseDir(), exe("haul-helper"))
	if !samePath(self, target) {
		step("Copying the helper to " + baseDir())
		if err := copyFile(self, target); err != nil {
			return fmt.Errorf("couldn't copy the helper (is a download running? close the browser and try again): %w", err)
		}
	}

	// 2. Tell the browsers about it.
	step("Connecting to Chrome, Edge, Brave and Chromium")
	manifest := filepath.Join(baseDir(), hostName+".json")
	body, _ := json.MarshalIndent(map[string]any{
		"name":            hostName,
		"description":     "Haul desktop helper",
		"path":            target,
		"type":            "stdio",
		"allowed_origins": []string{"chrome-extension://" + extensionID + "/"},
	}, "", "  ")
	if err := os.WriteFile(manifest, body, 0o644); err != nil {
		return err
	}
	if err := registerHost(manifest); err != nil {
		return err
	}

	// 3. The download tools.
	step("Downloading yt-dlp")
	if err := download(ytdlpURL, ytdlpPath()); err != nil {
		return fmt.Errorf("couldn't download yt-dlp: %w", err)
	}
	if ffmpegZipURL != "" {
		if fileExists(ffmpegPath()) {
			step("FFmpeg is already here")
		} else {
			step("Downloading FFmpeg (the biggest part, about 140 MB)")
			if err := installFFmpeg(); err != nil {
				return fmt.Errorf("couldn't download FFmpeg: %w", err)
			}
		}
	}

	fmt.Println()
	fmt.Println("All done. Go back to the browser and click \"Check again\" in Haul's settings,")
	fmt.Println("or just open the Haul extension and pick \"On this PC\".")
	return nil
}

func uninstall() error {
	step("Disconnecting from the browsers")
	unregisterHost()
	step("Removing yt-dlp and FFmpeg")
	os.RemoveAll(binDir())
	os.Remove(filepath.Join(baseDir(), hostName+".json"))
	fmt.Println()
	fmt.Println("Done. You can delete", baseDir(), "once this window is closed.")
	return nil
}

func installFFmpeg() error {
	zipPath := filepath.Join(binDir(), "ffmpeg.zip")
	defer os.Remove(zipPath)
	if err := download(ffmpegZipURL, zipPath); err != nil {
		return err
	}
	r, err := zip.OpenReader(zipPath)
	if err != nil {
		return err
	}
	defer r.Close()
	found := 0
	for _, f := range r.File {
		name := filepath.Base(f.Name)
		if name != exe("ffmpeg") && name != exe("ffprobe") {
			continue
		}
		src, err := f.Open()
		if err != nil {
			return err
		}
		err = writeAtomic(filepath.Join(binDir(), name), src)
		src.Close()
		if err != nil {
			return err
		}
		found++
	}
	if found < 2 {
		return fmt.Errorf("ffmpeg wasn't in the download")
	}
	return nil
}

func download(url, dest string) error {
	client := &http.Client{Timeout: 30 * time.Minute}
	req, _ := http.NewRequest("GET", url, nil)
	req.Header.Set("User-Agent", "haul-helper/"+version)
	resp, err := client.Do(req)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	if resp.StatusCode != 200 {
		return fmt.Errorf("%s answered %s", url, resp.Status)
	}
	pr := &progressReader{r: resp.Body, total: resp.ContentLength}
	err = writeAtomic(dest, pr)
	fmt.Println()
	return err
}

type progressReader struct {
	r           io.Reader
	total, done int64
	last        time.Time
}

func (p *progressReader) Read(b []byte) (int, error) {
	n, err := p.r.Read(b)
	p.done += int64(n)
	if time.Since(p.last) > 200*time.Millisecond || err == io.EOF {
		p.last = time.Now()
		if p.total > 0 {
			fmt.Printf("\r   %5.1f MB of %.1f MB (%d%%)   ", mb(p.done), mb(p.total), p.done*100/p.total)
		} else {
			fmt.Printf("\r   %5.1f MB   ", mb(p.done))
		}
	}
	return n, err
}

func mb(n int64) float64 { return float64(n) / 1024 / 1024 }

func writeAtomic(dest string, r io.Reader) error {
	tmp := dest + ".part"
	f, err := os.OpenFile(tmp, os.O_CREATE|os.O_TRUNC|os.O_WRONLY, 0o755)
	if err != nil {
		return err
	}
	if _, err := io.Copy(f, r); err != nil {
		f.Close()
		os.Remove(tmp)
		return err
	}
	if err := f.Close(); err != nil {
		return err
	}
	os.Remove(dest)
	return os.Rename(tmp, dest)
}

func copyFile(src, dest string) error {
	f, err := os.Open(src)
	if err != nil {
		return err
	}
	defer f.Close()
	return writeAtomic(dest, f)
}

func samePath(a, b string) bool {
	a, _ = filepath.Abs(a)
	b, _ = filepath.Abs(b)
	return strings.EqualFold(a, b)
}

func fileExists(p string) bool {
	st, err := os.Stat(p)
	return err == nil && !st.IsDir()
}

func step(s string) { fmt.Println("•", s) }
