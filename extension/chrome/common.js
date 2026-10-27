// Shared by the popup, options page, QR page and service worker.

const HOST_NAME = 'app.haul.helper';

const DEFAULTS = {
  qrMode: 'haul',       // 'haul': haul://save?url=… opens Haul directly. 'plain': the page link itself
  tab: 'phone',         // last popup tab: 'phone' | 'pc'
  mode: 'video',        // 'video' | 'audio'
  quality: '1080',      // 'best' | '2160' | '1440' | '1080' | '720' | '480'
  audioFormat: 'mp3',   // 'mp3' | 'm4a' | 'opus'
  folder: '',           // empty: the helper's default (Downloads\Haul)
};

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
      if (j.percent != null) bits.push(Math.floor(j.percent) + '%');
      if (j.speed) bits.push(j.speed);
      if (j.eta) bits.push(j.eta + ' left');
      return bits.length ? bits.join(' · ') : 'Getting ready…';
    }
    case 'processing': return j.mode === 'audio' ? 'Converting audio…' : 'Joining video and audio…';
    case 'done': return 'Saved';
    case 'cancelled': return 'Cancelled';
    case 'error': return j.error || 'Something went wrong';
    default: return '';
  }
}
