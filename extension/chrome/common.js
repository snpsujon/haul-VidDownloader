// Shared by the popup, options page, QR page and service worker.

const HOST_NAME = 'app.haul.helper';

// The Haul website hosts the desktop helper (uploaded on its admin page).
const SITE_URL = 'https://stackforge-pro.xyz';
const HELPER_DOWNLOAD_URL = 'https://stackforge-pro.xyz/helper'; // helper download page with setup steps
const RELEASES_URL = SITE_URL + '/api/releases';

const DEFAULTS = {
  qrMode: 'haul',       // 'haul': haul://save?url=… opens Haul directly. 'plain': the page link itself
  tab: 'phone',         // last popup tab: 'phone' | 'pc'
  mode: 'video',        // 'video' | 'audio'
  quality: '1080',      // 'best' | '2160' | '1440' | '1080' | '720' | '480'
  audioFormat: 'mp3',   // 'mp3' | 'm4a' | 'opus'
  folder: '',           // empty: the helper's default (Downloads\Haul)
  inpageButton: false,  // Haul button on videos; also needs the optional <all_urls> host permission
  hiddenSites: [],      // hostnames where the button is turned off
  youtubeButton: false, // the button on YouTube too (off by default: the Web Store is strict about YouTube)
  subs: 'off',          // 'off' | 'embed' | 'file' (video only)
  subLangs: 'en',       // yt-dlp language list, like 'en' or 'bn,en'
  thumb: 'off',         // cover art: 'off' | 'embed' | 'file'
};

const MAX_HISTORY = 500;

const YOUTUBE_HOSTS = /(^|\.)(youtube\.com|youtube-nocookie\.com|youtu\.be)$/i;
const ALL_SITES = { origins: ['<all_urls>'] };

function getSettings() {
  return chrome.storage.sync.get(DEFAULTS);
}

function saveSettings(patch) {
  return chrome.storage.sync.set(patch);
}

function isWebUrl(url) {
  return typeof url === 'string' && /^https?:\/\//i.test(url);
}

function phoneLink(url, qrMode) {
  return qrMode === 'haul' ? 'haul://save?url=' + encodeURIComponent(url) : url;
}

// Playlist and channel pages. A YouTube watch link with list= is both a video and a playlist.
function isPlaylistUrl(url) {
  try {
    const u = new URL(url);
    const h = u.hostname.replace(/^(www|m|music)\./, '');
    if (/(^|\.)youtube\.com$/.test(h)) {
      return u.searchParams.has('list') || /^\/(playlist|channel\/|c\/|user\/|@)/.test(u.pathname);
    }
    return /\/(playlists?|sets|showcase|album|channel)s?\b/i.test(u.pathname);
  } catch {
    return false;
  }
}

// True when the link is only a playlist (no single video to pick instead).
function isPlaylistOnly(url) {
  try {
    const u = new URL(url);
    return isPlaylistUrl(url) && !u.searchParams.has('v');
  } catch {
    return false;
  }
}

// "1:20", "01:02:03", "80" or "1m20s" -> seconds. Empty -> null. Invalid -> NaN.
function parseTime(text) {
  const t = String(text || '').trim();
  if (!t) return null;
  let m = t.match(/^(?:(\d+)h)?\s*(?:(\d+)m)?\s*(?:(\d+(?:\.\d+)?)s)?$/i);
  if (m && (m[1] || m[2] || m[3])) return (+m[1] || 0) * 3600 + (+m[2] || 0) * 60 + (+m[3] || 0);
  m = t.match(/^(\d+(?:\.\d+)?)$|^(\d+):(\d{1,2}(?:\.\d+)?)$|^(\d+):(\d{1,2}):(\d{1,2}(?:\.\d+)?)$/);
  if (!m) return NaN;
  if (m[1]) return +m[1];
  if (m[2]) return +m[2] * 60 + +m[3];
  return +m[4] * 3600 + +m[5] * 60 + +m[6];
}

// 80 -> "1:20", 3723 -> "1:02:03".
function fmtTime(sec) {
  sec = Math.max(0, Math.floor(sec || 0));
  const h = Math.floor(sec / 3600);
  const m = Math.floor((sec % 3600) / 60);
  const s = String(sec % 60).padStart(2, '0');
  return h ? `${h}:${String(m).padStart(2, '0')}:${s}` : `${m}:${s}`;
}

// Short label for what a job saves, like "Clip 1:20–2:05" or "Playlist".
function jobKind(j) {
  const what = j.mode === 'audio' ? 'Audio' : 'Video';
  if (j.playlist) return 'Playlist · ' + what;
  if (j.start != null || j.end != null) return `Clip ${fmtTime(j.start || 0)}–${j.end ? fmtTime(j.end) : 'end'} · ${what}`;
  return what;
}

// True when version a is newer than b ("1.2.0" vs "1.10.0" style).
function newerVersion(a, b) {
  const pa = String(a).split('.').map((n) => parseInt(n, 10) || 0);
  const pb = String(b).split('.').map((n) => parseInt(n, 10) || 0);
  for (let i = 0; i < Math.max(pa.length, pb.length); i++) {
    if ((pa[i] || 0) !== (pb[i] || 0)) return (pa[i] || 0) > (pb[i] || 0);
  }
  return false;
}

function hostOf(url) {
  try { return new URL(url).hostname.replace(/^www\./, ''); } catch { return ''; }
}

// Draws a QR code for `text` onto `canvas`, with a quiet zone, at `px` CSS pixels.
function drawQr(canvas, text, px) {
  qrcode.stringToBytes = qrcode.stringToBytesFuncs['UTF-8'];
  const qr = qrcode(0, text.length > 600 ? 'L' : 'M');
  qr.addData(text, 'Byte');
  qr.make();
  const n = qr.getModuleCount();
  const quiet = 3;
  const dpr = window.devicePixelRatio || 1;
  const cell = Math.max(1, Math.floor((px * dpr) / (n + quiet * 2)));
  const size = cell * (n + quiet * 2);
  canvas.width = size;
  canvas.height = size;
  canvas.style.width = canvas.style.height = Math.round(size / dpr) + 'px';
  const g = canvas.getContext('2d');
  g.fillStyle = '#ffffff';
  g.fillRect(0, 0, size, size);
  g.fillStyle = '#141311';
  for (let r = 0; r < n; r++) {
    for (let c = 0; c < n; c++) {
      if (qr.isDark(r, c)) g.fillRect((c + quiet) * cell, (r + quiet) * cell, cell, cell);
    }
  }
}

// One-line progress text for a download job.
function jobLine(j) {
  switch (j.state) {
    case 'queued': return 'Starting…';
    case 'running': {
      const bits = [];
      if (j.playlist && j.item) bits.push(`${j.mode === 'audio' ? 'Track' : 'Video'} ${j.item}${j.count ? ' of ' + j.count : ''}`);
      if (j.percent != null) bits.push(Math.floor(j.percent) + '%');
      if (j.speed) bits.push(j.speed);
      if (j.eta) bits.push(j.eta + ' left');
      return bits.length ? bits.join(' · ') : 'Getting ready…';
    }
    case 'processing': return j.mode === 'audio' ? 'Converting audio…' : 'Joining video and audio…';
    case 'done':
      if (j.playlist) return `Saved ${j.saved || 0}${j.note ? ' · ' + j.note : ''}`;
      return 'Saved';
    case 'cancelled': return 'Cancelled';
    case 'error': return j.error || 'Something went wrong';
    default: return '';
  }
}
