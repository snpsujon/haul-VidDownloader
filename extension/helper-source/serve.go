package main

import (
	"bufio"
	"encoding/binary"
	"encoding/json"
	"fmt"
	"io"
	"log"
	"os"
	"os/exec"
	"path/filepath"
	"regexp"
	"strconv"
	"strings"
	"sync"
	"time"
)

// Native messaging: each message is a 32-bit native-endian length, then that many bytes of JSON.

type message struct {
	Type        string `json:"type"`
	Req         int    `json:"req,omitempty"`
	ID          string `json:"id,omitempty"`
	URL         string `json:"url,omitempty"`
	Mode        string `json:"mode,omitempty"`
	Quality     string `json:"quality,omitempty"`
	AudioFormat string `json:"audioFormat,omitempty"`
	Folder      string `json:"folder,omitempty"`
	Path        string `json:"path,omitempty"`
}

type out map[string]any

var (
	writeMu sync.Mutex
	jobsMu  sync.Mutex
	jobs    = map[string]*job{}
)

type job struct {
	cmd       *exec.Cmd
	cancelled bool
}

func serve() {
	if f, err := os.OpenFile(filepath.Join(baseDir(), "helper.log"), os.O_CREATE|os.O_APPEND|os.O_WRONLY, 0o644); err == nil {
		log.SetOutput(f)
	} else {
		log.SetOutput(io.Discard)
	}
	log.Printf("started %s", version)

	in := bufio.NewReader(os.Stdin)
	for {
		var n uint32
		if err := binary.Read(in, binary.LittleEndian, &n); err != nil {
			break // the browser closed the port
		}
		if n > 1<<20 {
			log.Printf("message too large: %d", n)
			break
		}
		buf := make([]byte, n)
		if _, err := io.ReadFull(in, buf); err != nil {
			break
		}
		var m message
		if err := json.Unmarshal(buf, &m); err != nil {
			log.Printf("bad message: %v", err)
			continue
		}
		handle(m)
	}
	cancelAll()
}

func send(v out) {
	b, err := json.Marshal(v)
	if err != nil {
		return
	}
	writeMu.Lock()
	defer writeMu.Unlock()
	binary.Write(os.Stdout, binary.LittleEndian, uint32(len(b)))
	os.Stdout.Write(b)
}

func handle(m message) {
	switch m.Type {
	case "hello":
		send(out{"req": m.Req, "type": "hello", "version": version, "ready": fileExists(ytdlpPath()) && fileExists(ffmpegPath()),
			"ytdlp": ytdlpVersion(), "ffmpeg": fileExists(ffmpegPath()), "folder": defaultFolder()})
	case "download":
		go runDownload(m)
	case "cancel":
		jobsMu.Lock()
		if j := jobs[m.ID]; j != nil && j.cmd.Process != nil {
			j.cancelled = true
			j.cmd.Process.Kill()
		}
		jobsMu.Unlock()
	case "reveal":
		if fileExists(m.Path) {
			reveal(m.Path)
		}
	case "openFolder":
		dir, err := folderFor(m.Folder)
		if err == nil {
			err = openFolder(dir)
		}
		send(out{"req": m.Req, "ok": err == nil, "error": errText(err)})
	case "update":
		go func() {
			c := exec.Command(ytdlpPath(), "-U")
			hide(c)
			b, err := c.CombinedOutput()
			cachedVersion = ""
			lines := strings.Split(strings.TrimSpace(string(b)), "\n")
			msg := strings.TrimSpace(lines[len(lines)-1])
			send(out{"req": m.Req, "ok": err == nil, "message": msg, "error": msg})
		}()
	default:
		if m.Req != 0 {
			send(out{"req": m.Req, "ok": false, "error": "unknown message " + m.Type})
		}
	}
}

var cachedVersion string

func ytdlpVersion() string {
	if cachedVersion == "" && fileExists(ytdlpPath()) {
		c := exec.Command(ytdlpPath(), "--version")
		hide(c)
		if b, err := c.Output(); err == nil {
			cachedVersion = strings.TrimSpace(string(b))
		}
	}
	return cachedVersion
}

func folderFor(custom string) (string, error) {
	dir := strings.TrimSpace(custom)
	if dir == "" {
		dir = defaultFolder()
	}
	if !filepath.IsAbs(dir) {
		return "", fmt.Errorf("the save folder must be a full path, like C:\\Users\\you\\Videos")
	}
	return dir, os.MkdirAll(dir, 0o755)
}

func ytdlpArgs(m message, dir string) []string {
	a := []string{
		"--newline", "--progress", "--no-simulate", "--no-colors", "--encoding", "utf-8",
		"--no-playlist", "--no-mtime", "--windows-filenames",
		"--ffmpeg-location", ffmpegPath(),
		"-P", dir,
		"-P", "temp:" + filepath.Join(dir, ".haul-tmp", m.ID),
		"-o", "%(title).150B [%(id)s]" + audioTag(m) + ".%(ext)s",
		"--progress-template", "download:HAULP %(progress._percent_str)s|%(progress._speed_str)s|%(progress._eta_str)s",
		"--progress-template", "postprocess:HAULPP %(progress.postprocessor)s",
		"--print", "before_dl:HAULT %(title)s",
		"--print", "after_move:HAULF %(filepath)s",
	}
	if m.Mode == "audio" {
		f := m.AudioFormat
		if f != "mp3" && f != "m4a" && f != "opus" {
			f = "mp3"
		}
		a = append(a, "-f", "ba/b", "-x", "--audio-format", f, "--audio-quality", "0", "--embed-metadata")
	} else {
		sort := "res,vcodec:h264,acodec:aac"
		if _, err := strconv.Atoi(m.Quality); err == nil {
			sort = "res:" + m.Quality + ",vcodec:h264,acodec:aac"
		}
		a = append(a, "-f", "bv*+ba/b", "-S", sort, "--merge-output-format", "mp4", "--embed-metadata")
	}
	return append(a, "--", m.URL)
}

// Audio jobs save under a tagged name first. Otherwise yt-dlp sees a video saved earlier under the
// same name as "already downloaded", converts it, and deletes it.
func audioTag(m message) string {
	if m.Mode == "audio" {
		return ".haul-audio"
	}
	return ""
}

var (
	errorPrefix = regexp.MustCompile(`^ERROR:\s*(\[[^\]]+\]\s*)?([A-Za-z0-9_-]+:\s+)?`)
	percentRe   = regexp.MustCompile(`([\d.]+)%`)
)

func runDownload(m message) {
	fail := func(msg string) { send(out{"type": "error", "id": m.ID, "message": msg}) }
	if !strings.HasPrefix(m.URL, "http://") && !strings.HasPrefix(m.URL, "https://") {
		fail("That isn't a web link.")
		return
	}
	dir, err := folderFor(m.Folder)
	if err != nil {
		fail("Can't use the save folder: " + err.Error())
		return
	}

	c := exec.Command(ytdlpPath(), ytdlpArgs(m, dir)...)
	c.Env = append(os.Environ(), "PYTHONIOENCODING=utf-8", "PYTHONUTF8=1")
	hide(c)
	stdout, _ := c.StdoutPipe()
	stderr, _ := c.StderrPipe()
	if err := c.Start(); err != nil {
		fail("Couldn't start yt-dlp: " + err.Error())
		return
	}
	j := &job{cmd: c}
	jobsMu.Lock()
	jobs[m.ID] = j
	jobsMu.Unlock()
	log.Printf("download %s %s %s", m.ID, m.Mode, m.URL)

	var lastErr string
	var errWG sync.WaitGroup
	errWG.Add(1)
	go func() {
		defer errWG.Done()
		sc := bufio.NewScanner(stderr)
		for sc.Scan() {
			line := strings.TrimSpace(sc.Text())
			if strings.HasPrefix(line, "ERROR:") {
				lastErr = strings.TrimSpace(errorPrefix.ReplaceAllString(line, ""))
				log.Printf("%s %s", m.ID, line)
			}
		}
	}()

	var file string
	var lastSent time.Time
	sc := bufio.NewScanner(stdout)
	sc.Buffer(make([]byte, 64*1024), 1024*1024)
	for sc.Scan() {
		line := strings.TrimSpace(sc.Text())
		switch {
		case strings.HasPrefix(line, "HAULT "):
			send(out{"type": "title", "id": m.ID, "title": strings.TrimPrefix(line, "HAULT ")})
		case strings.HasPrefix(line, "HAULPP "):
			send(out{"type": "processing", "id": m.ID})
		case strings.HasPrefix(line, "HAULP "):
			parts := strings.Split(strings.TrimPrefix(line, "HAULP "), "|")
			if time.Since(lastSent) < 300*time.Millisecond {
				continue
			}
			lastSent = time.Now()
			p := out{"type": "progress", "id": m.ID}
			if mm := percentRe.FindStringSubmatch(parts[0]); mm != nil {
				if f, err := strconv.ParseFloat(mm[1], 64); err == nil {
					p["percent"] = f
				}
			}
			if len(parts) > 1 {
				p["speed"] = clean(parts[1])
			}
			if len(parts) > 2 {
				p["eta"] = clean(parts[2])
			}
			send(p)
		case strings.HasPrefix(line, "HAULF "):
			file = strings.TrimPrefix(line, "HAULF ")
		}
	}
	errWG.Wait()
	err = c.Wait()
	os.RemoveAll(filepath.Join(dir, ".haul-tmp", m.ID))
	os.Remove(filepath.Join(dir, ".haul-tmp")) // only succeeds once empty
	if tag := audioTag(m); tag != "" && strings.Contains(filepath.Base(file), tag+".") {
		final := filepath.Join(filepath.Dir(file), strings.Replace(filepath.Base(file), tag+".", ".", 1))
		if os.Rename(file, final) == nil {
			file = final
		}
	}

	jobsMu.Lock()
	delete(jobs, m.ID)
	cancelled := j.cancelled
	jobsMu.Unlock()

	switch {
	case cancelled:
		send(out{"type": "cancelled", "id": m.ID})
	case err != nil || file == "":
		if lastErr == "" {
			lastErr = "yt-dlp stopped without saving anything."
		}
		fail(friendly(lastErr))
	default:
		log.Printf("done %s %s", m.ID, file)
		send(out{"type": "done", "id": m.ID, "file": file})
	}
}

// clean drops yt-dlp's placeholders for unknown values.
func clean(s string) string {
	s = strings.TrimSpace(s)
	if s == "NA" || s == "Unknown" || strings.HasPrefix(s, "Unknown") {
		return ""
	}
	return s
}

func friendly(e string) string {
	l := strings.ToLower(e)
	switch {
	case strings.Contains(l, "unsupported url"):
		return "Haul can't find a video on this page."
	case strings.Contains(l, "private video"), strings.Contains(l, "sign in"), strings.Contains(l, "login required"):
		return "This video needs a sign-in, so Haul can't get it."
	case strings.Contains(l, "not available in your country"), strings.Contains(l, "geo"):
		return "This video isn't available in your country."
	case strings.Contains(l, "unable to download webpage"), strings.Contains(l, "getaddrinfo"):
		return "Couldn't reach the site. Check your connection."
	}
	return e
}

func cancelAll() {
	jobsMu.Lock()
	defer jobsMu.Unlock()
	for _, j := range jobs {
		if j.cmd.Process != nil {
			j.cancelled = true
			j.cmd.Process.Kill()
		}
	}
}

func errText(err error) string {
	if err == nil {
		return ""
	}
	return err.Error()
}
