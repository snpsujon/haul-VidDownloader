package main

import (
	"archive/zip"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"os"
	"os/exec"
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
	origins := []string{}
	for _, id := range extensionIDs {
		origins = append(origins, "chrome-extension://"+id+"/")
	}
	body, _ := json.MarshalIndent(map[string]any{
		"name":            hostName,
		"description":     "Haul desktop helper",
		"path":            target,
		"type":            "stdio",
		"allowed_origins": origins,
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
	if len(ffmpegArchives) > 0 {
		if fileExists(filepath.Join(binDir(), exe("ffmpeg"))) && fileExists(filepath.Join(binDir(), exe("ffprobe"))) {
			step("FFmpeg is already here")
		} else {
			step("Downloading FFmpeg (the biggest part, about 140 MB)")
			if err := installFFmpeg(); err != nil {
				if sys := systemFFmpeg(); sys != "" {
					step("Couldn't download FFmpeg, so Haul will use the one at " + sys)
				} else {
					return fmt.Errorf("couldn't download FFmpeg: %w", err)
				}
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

// installFFmpeg downloads each archive in ffmpegArchives and keeps ffmpeg and ffprobe from them.
// Windows gets both in one zip, macOS gets one zip each, Linux gets a .tar.xz.
func installFFmpeg() error {
	found := 0
	for i, url := range ffmpegArchives {
		archive := filepath.Join(binDir(), fmt.Sprintf("ffmpeg-%d.download", i))
		err := download(url, archive)
		if err == nil {
			if strings.HasSuffix(url, ".tar.xz") {
				found += extractTar(archive)
			} else {
				var n int
				n, err = extractZip(archive)
				found += n
			}
		}
		os.Remove(archive)
		if err != nil {
			return err
		}
	}
	if found < 2 {
		return fmt.Errorf("ffmpeg wasn't in the download")
	}
	return nil
}

// isFFmpeg tells whether an archive entry is one of the two programs we keep.
func isFFmpeg(name string) bool {
	return name == exe("ffmpeg") || name == exe("ffprobe")
}

func extractZip(path string) (int, error) {
	r, err := zip.OpenReader(path)
	if err != nil {
		return 0, err
	}
	defer r.Close()
	found := 0
	for _, f := range r.File {
		name := filepath.Base(f.Name)
		if !isFFmpeg(name) || f.FileInfo().IsDir() {
			continue
		}
		src, err := f.Open()
		if err != nil {
			return found, err
		}
		err = writeAtomic(filepath.Join(binDir(), name), src)
		src.Close()
		if err != nil {
			return found, err
		}
		found++
	}
	return found, nil
}

// extractTar unpacks a .tar.xz with the system tar, which handles xz on every Linux we support.
func extractTar(path string) int {
	tmp, err := os.MkdirTemp(binDir(), "ffmpeg-")
	if err != nil {
		return 0
	}
	defer os.RemoveAll(tmp)
	if out, err := exec.Command("tar", "-xJf", path, "-C", tmp).CombinedOutput(); err != nil {
		fmt.Println("   tar:", strings.TrimSpace(string(out)))
		return 0
	}
	found := 0
	filepath.WalkDir(tmp, func(p string, d os.DirEntry, err error) error {
		if err != nil || d.IsDir() || !isFFmpeg(d.Name()) {
			return nil
		}
		if copyFile(p, filepath.Join(binDir(), d.Name())) == nil {
			found++
		}
		return nil
	})
	return found
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
