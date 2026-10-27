// Service worker: talks to the desktop helper over native messaging and keeps the job list.
// An open native port keeps this worker alive, so downloads keep reporting while the popup is closed.

importScripts('common.js');

const MAX_JOBS = 30;

// Let the in-page button read the job list for its progress ring.
chrome.storage.session.setAccessLevel({ accessLevel: 'TRUSTED_AND_UNTRUSTED_CONTEXTS' });

let port = null;
let nextReq = 1;
const waiting = new Map(); // request id -> resolve

// ---- Helper connection ----

function connect() {
  if (port) return port;
  port = chrome.runtime.connectNative(HOST_NAME);
  port.onMessage.addListener(onHelperMessage);
  port.onDisconnect.addListener(() => {
    const error = chrome.runtime.lastError?.message || 'The Haul helper stopped.';
    port = null;
    for (const resolve of waiting.values()) resolve({ ok: false, error: friendlyError(error) });
    waiting.clear();
    failActiveJobs('The Haul helper stopped before this finished.');
  });
  return port;
}

function friendlyError(msg) {
  if (/not found/i.test(msg)) return 'missing';
  if (/forbidden/i.test(msg)) return 'The Haul helper is installed for a different copy of this extension.';
  return msg;
}

// Sends a message that expects one reply, matched by `req`.
function request(msg, timeoutMs = 5000) {
  return new Promise((resolve) => {
    const req = nextReq++;
    const timer = setTimeout(() => {
      waiting.delete(req);
      resolve({ ok: false, error: 'The Haul helper did not answer.' });
    }, timeoutMs);
    waiting.set(req, (reply) => { clearTimeout(timer); resolve(reply); });
    try {
      connect().postMessage({ ...msg, req });
    } catch (e) {
      waiting.delete(req);
      clearTimeout(timer);
      resolve({ ok: false, error: friendlyError(String(e.message || e)) });
    }
  });
}

async function helperStatus() {
  const reply = await request({ type: 'hello' });
  return reply.ok === false ? reply : { ok: true, ...reply };
}

function onHelperMessage(msg) {
  if (msg.req && waiting.has(msg.req)) {
    const resolve = waiting.get(msg.req);
    waiting.delete(msg.req);
    resolve(msg);
    return;
  }
  if (!msg.id) return;
  switch (msg.type) {
    case 'title': updateJob(msg.id, { title: msg.title }); break;
    case 'progress': {
      const patch = { state: 'running', percent: msg.percent, speed: msg.speed, eta: msg.eta };
      if (msg.item) patch.item = msg.item;
      if (msg.count) patch.count = msg.count;
      updateJob(msg.id, patch);
      break;
    }
    case 'processing': updateJob(msg.id, { state: 'processing', percent: 100, speed: '', eta: '' }); break;
    case 'done':
      updateJob(msg.id, { state: 'done', file: msg.file, percent: 100, saved: msg.saved, note: msg.note })
        .then((j) => { if (j) { notify(j); addHistory(j); } });
      break;
    case 'cancelled': updateJob(msg.id, { state: 'cancelled' }); break;
    case 'error':
      updateJob(msg.id, { state: 'error', error: msg.message }).then((j) => j && notify(j));
      break;
  }
}

// ---- Jobs (kept in session storage so the popup can read them after a restart) ----

let jobsLock = Promise.resolve();

function withJobs(fn) {
  // One failed update must not block the ones after it.
  jobsLock = jobsLock.catch(() => {}).then(async () => {
    const { jobs = [] } = await chrome.storage.session.get('jobs');
    const result = fn(jobs);
    await chrome.storage.session.set({ jobs: jobs.slice(0, MAX_JOBS) });
    return result;
  });
  return jobsLock;
}

function updateJob(id, patch) {
  return withJobs((jobs) => {
    const j = jobs.find((x) => x.id === id);
    if (j) Object.assign(j, patch);
    return j ? { ...j } : null;
  });
}

function failActiveJobs(message) {
  return withJobs((jobs) => {
    for (const j of jobs) {
      if (j.state === 'queued' || j.state === 'running' || j.state === 'processing') {
        j.state = 'error';
        j.error = message;
      }
    }
  });
}

const QUALITIES = ['best', '2160', '1440', '1080', '720', '480', '360'];
const AUDIO_FORMATS = ['mp3', 'm4a', 'opus'];

// `opts` can set quality / audioFormat for this one job, `playlist`, a clip (`start`/`end` in seconds),
// and `source` (the website origin that asked).
async function startDownload(url, mode, title, opts = {}) {
  if (!isWebUrl(url)) return { ok: false, error: 'This page has no link Haul can download.' };
  if (mode !== 'audio') mode = 'video';
  const status = await helperStatus();
  if (!status.ok) return status;
  if (!status.ready) return { ok: false, error: 'notready' };

  const s = await getSettings();
  const job = {
    id: crypto.randomUUID(),
    url,
    mode,
    title: title || hostOf(url),
    host: hostOf(url),
    quality: QUALITIES.includes(opts.quality) ? opts.quality : s.quality,
    audioFormat: AUDIO_FORMATS.includes(opts.audioFormat) ? opts.audioFormat : s.audioFormat,
    state: 'queued',
    percent: null,
    created: Date.now(),
  };
  if (opts.source) job.source = opts.source;
  if (opts.playlist) job.playlist = true;
  const start = Number(opts.start) > 0 ? Number(opts.start) : null;
  const end = Number(opts.end) > 0 ? Number(opts.end) : null;
  if (!job.playlist && (start != null || end != null)) {
    if (end != null && end <= (start || 0)) return { ok: false, error: 'The clip has to end after it starts.' };
    job.start = start || 0;
    if (end != null) job.end = end;
  }
  await withJobs((jobs) => { jobs.unshift(job); });
  connect().postMessage({
    type: 'download',
    id: job.id,
    url,
    mode,
    quality: job.quality,
    audioFormat: job.audioFormat,
    folder: s.folder,
    playlist: !!job.playlist,
    start: job.start || 0,
    end: job.end || 0,
    subs: s.subs === 'off' ? '' : s.subs,
    subLangs: s.subLangs,
    thumb: s.thumb === 'off' ? '' : s.thumb,
  });
  return { ok: true, job };
}

// ---- History (kept across restarts in local storage) ----

async function addHistory(j) {
  const { history = [] } = await chrome.storage.local.get('history');
  const entry = {
    id: j.id, url: j.url, title: j.title, host: j.host, mode: j.mode, quality: j.quality,
    audioFormat: j.audioFormat, file: j.file, finished: Date.now(),
  };
  for (const k of ['playlist', 'start', 'end', 'saved', 'note']) if (j[k] != null) entry[k] = j[k];
  history.unshift(entry);
  await chrome.storage.local.set({ history: history.slice(0, MAX_HISTORY) });
}

function notify(j) {
  const done = j.state === 'done';
  chrome.notifications.create('haul:' + j.id, {
    type: 'basic',
    iconUrl: 'icons/icon128.png',
    title: done ? 'Saved with Haul' : "Haul couldn't save this",
    message: done ? j.title + (j.playlist ? ` (${j.saved || 0} saved)` : '') : (j.title + '\n' + (j.error || '')),
    contextMessage: done ? 'Click to show it in its folder' : j.host,
  });
}

chrome.notifications.onClicked.addListener(async (nid) => {
  if (!nid.startsWith('haul:')) return;
  const id = nid.slice(5);
  const { jobs = [] } = await chrome.storage.session.get('jobs');
  const j = jobs.find((x) => x.id === id);
  if (j?.file) connect().postMessage({ type: 'reveal', path: j.file });
  chrome.notifications.clear(nid);
});

// ---- Messages from the popup and options page ----

chrome.runtime.onMessage.addListener((msg, _sender, reply) => {
  (async () => {
    switch (msg.cmd) {
      case 'status': return helperStatus();
      case 'download': return startDownload(msg.url, msg.mode, msg.title, msg.opts || {});
      case 'open':
        try { connect().postMessage({ type: 'open', path: msg.path }); } catch {}
        return { ok: true };
      case 'stat': return request({ type: 'stat', paths: msg.paths || [] });
      case 'phoneSend': return sendToPhone(msg.url, msg.title, msg.mode);
      case 'pairStart': return pairStart();
      case 'pairCheck': return pairCheck();
      case 'unpair': return unpair();
      case 'cancel':
        try { connect().postMessage({ type: 'cancel', id: msg.id }); } catch {}
        return { ok: true };
      case 'reveal':
        try { connect().postMessage({ type: 'reveal', path: msg.path }); } catch {}
        return { ok: true };
      case 'openOptions':
        chrome.runtime.openOptionsPage();
        return { ok: true };
      case 'openFolder': return request({ type: 'openFolder', folder: msg.folder });
      case 'updateTools': return request({ type: 'update' }, 120000);
      case 'clearFinished':
        await withJobs((jobs) => {
          const keep = jobs.filter((j) => ['queued', 'running', 'processing'].includes(j.state));
          jobs.length = 0;
          jobs.push(...keep);
        });
        return { ok: true };
      default: return { ok: false, error: 'Unknown command' };
    }
  })().then(reply);
  return true;
});

// ---- One-tap send to phone (paired through the Haul website) ----
// local storage: pair = { site, id, sendKey, device }, pairPending = { id, sendKey, token, expires }

async function api(path, body, base = SITE_URL) {
  const r = await fetch(base + path, body
    ? { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body), cache: 'no-store' }
    : { cache: 'no-store' });
  let data = {};
  try { data = await r.json(); } catch {}
  return { ...data, status: r.status };
}

const OFFLINE = "Couldn't reach the Haul website. Check your connection.";

async function pairStart() {
  try {
    const r = await api('/api/pair', {});
    if (!r.pair_id) return { ok: false, error: r.error || OFFLINE };
    const pending = {
      id: r.pair_id, sendKey: r.send_key, token: r.receive_token,
      expires: Date.now() + (r.expires_in || 600) * 1000,
    };
    await chrome.storage.local.set({ pairPending: pending });
    const qr = 'haul://pair?site=' + encodeURIComponent(SITE_URL) + '&id=' + encodeURIComponent(pending.id)
      + '&token=' + encodeURIComponent(pending.token);
    return { ok: true, qr, expires: pending.expires };
  } catch {
    return { ok: false, error: OFFLINE };
  }
}

async function pairCheck() {
  const { pairPending: p } = await chrome.storage.local.get('pairPending');
  if (!p || Date.now() > p.expires) {
    await chrome.storage.local.remove('pairPending');
    return { ok: false, error: 'expired' };
  }
  try {
    const r = await api(`/api/pair/status?id=${encodeURIComponent(p.id)}&key=${encodeURIComponent(p.sendKey)}`);
    if (!r.claimed) return { ok: true, claimed: false };
    const pair = { site: SITE_URL, id: p.id, sendKey: p.sendKey, device: r.device_name || 'your phone' };
    await chrome.storage.local.set({ pair });
    await chrome.storage.local.remove('pairPending');
    return { ok: true, claimed: true, device: pair.device };
  } catch {
    return { ok: true, claimed: false };
  }
}

async function unpair() {
  const { pair } = await chrome.storage.local.get('pair');
  if (pair) {
    try { await api('/api/pair/unpair', { pair_id: pair.id, send_key: pair.sendKey }, pair.site); } catch {}
  }
  await chrome.storage.local.remove('pair');
  return { ok: true };
}

async function sendToPhone(url, title, mode) {
  if (!isWebUrl(url)) return { ok: false, error: 'This page has no link Haul can send.' };
  const { pair } = await chrome.storage.local.get('pair');
  if (!pair) return { ok: false, error: 'notpaired' };
  try {
    const r = await api('/api/pair/send', {
      pair_id: pair.id, send_key: pair.sendKey, url,
      mode: mode === 'audio' ? 'audio' : 'video', title: String(title || '').slice(0, 300),
    }, pair.site);
    if (r.status === 404) {
      await chrome.storage.local.remove('pair');
      return { ok: false, error: 'Your phone was disconnected. Pair it again in Haul settings.' };
    }
    if (!r.ok) return { ok: false, error: r.error || "Couldn't send it." };
    return { ok: true, device: pair.device };
  } catch {
    return { ok: false, error: OFFLINE };
  }
}

// Right-click menu and shortcut: send straight to a paired phone, otherwise show the QR page.
async function phoneOrQr(url, title, tab) {
  const r = await sendToPhone(url, title, 'video');
  if (r.ok) {
    chrome.notifications.create({ type: 'basic', iconUrl: 'icons/icon128.png', title: 'Sent to ' + r.device, message: title || url });
  } else if (r.error === 'notpaired') {
    chrome.tabs.create({ url: 'qr.html?u=' + encodeURIComponent(url) + '&t=' + encodeURIComponent(title || ''), index: tab ? tab.index + 1 : undefined });
  } else {
    chrome.notifications.create({ type: 'basic', iconUrl: 'icons/icon128.png', title: "Couldn't send to your phone", message: r.error });
  }
}

// ---- Haul website bridge ----
// The site can ask for downloads on the visitor's own PC. It only sees and controls jobs it started,
// and never gets file paths.

const SITE_ORIGINS = [/^https:\/\/(www\.)?stackforge-pro\.xyz$/, /^http:\/\/(localhost|127\.0\.0\.1)(:\d+)?$/];

function siteJob(j) {
  const { id, url, mode, title, quality, audioFormat, state, percent, speed, eta, error } = j;
  return { id, url, mode, title, quality, audioFormat, state, percent, speed, eta, error, hasFile: !!j.file };
}

chrome.runtime.onMessageExternal.addListener((msg, sender, reply) => {
  const origin = sender.origin || (sender.url ? new URL(sender.url).origin : '');
  if (!SITE_ORIGINS.some((re) => re.test(origin)) || !msg || typeof msg !== 'object') return false;
  const own = async (id) => {
    const { jobs = [] } = await chrome.storage.session.get('jobs');
    return jobs.find((j) => j.id === id && j.source === origin);
  };
  (async () => {
    switch (msg.cmd) {
      case 'hello': {
        const h = await helperStatus();
        return {
          ok: true,
          version: chrome.runtime.getManifest().version,
          helper: h.ok ? { ok: true, ready: !!h.ready, version: h.version } : { ok: false, ready: false, error: h.error },
        };
      }
      case 'download': {
        const title = typeof msg.title === 'string' ? msg.title.slice(0, 300) : '';
        const r = await startDownload(String(msg.url || ''), msg.mode, title,
          { quality: msg.quality, audioFormat: msg.audioFormat, source: origin });
        return r.ok ? { ok: true, job: siteJob(r.job) } : r;
      }
      case 'jobs': {
        const { jobs = [] } = await chrome.storage.session.get('jobs');
        const ids = Array.isArray(msg.ids) ? msg.ids : null;
        return { ok: true, jobs: jobs.filter((j) => j.source === origin && (!ids || ids.includes(j.id))).map(siteJob) };
      }
      case 'cancel': {
        if (!(await own(msg.id))) return { ok: false, error: 'No such download.' };
        connect().postMessage({ type: 'cancel', id: msg.id });
        return { ok: true };
      }
      case 'reveal': {
        const j = await own(msg.id);
        if (!j) return { ok: false, error: 'No such download.' };
        if (!j.file) return { ok: false, error: 'Not saved yet.' };
        connect().postMessage({ type: 'reveal', path: j.file });
        return { ok: true };
      }
      default:
        return { ok: false, error: 'Unknown command' };
    }
  })().then(reply, (e) => reply({ ok: false, error: String(e?.message || e) }));
  return true;
});

// ---- In-page button (registered only while it's switched on and the site permission is granted) ----

const INPAGE_ID = 'haul-inpage';
const INPAGE_FILES = ['lib/qrcode.js', 'common.js', 'inpage.js'];

async function syncInpage() {
  const { inpageButton } = await getSettings();
  const allowed = await chrome.permissions.contains(ALL_SITES);
  let [current] = await chrome.scripting.getRegisteredContentScripts({ ids: [INPAGE_ID] });
  if (current?.excludeMatches?.length) {
    // Registered by an earlier version that kept it off YouTube; that's a setting now.
    await chrome.scripting.unregisterContentScripts({ ids: [INPAGE_ID] });
    current = undefined;
  }
  if (inpageButton && allowed && !current) {
    await chrome.scripting.registerContentScripts([{
      id: INPAGE_ID,
      matches: ['<all_urls>'],
      js: INPAGE_FILES,
      allFrames: true,
      runAt: 'document_idle',
    }]);
    // Pages that are already open get it too, without a reload.
    const tabs = await chrome.tabs.query({ url: ['http://*/*', 'https://*/*'] });
    for (const t of tabs) {
      chrome.scripting.executeScript({ target: { tabId: t.id, allFrames: true }, files: INPAGE_FILES }).catch(() => {});
    }
  } else if ((!inpageButton || !allowed) && current) {
    await chrome.scripting.unregisterContentScripts({ ids: [INPAGE_ID] });
  }
}

chrome.runtime.onStartup.addListener(syncInpage);
chrome.permissions.onAdded.addListener(syncInpage);
chrome.permissions.onRemoved.addListener(syncInpage);
chrome.storage.onChanged.addListener((changes, area) => {
  if (area === 'sync' && changes.inpageButton) syncInpage();
});

// ---- Keyboard shortcuts ----

chrome.commands.onCommand.addListener(async (command, tab) => {
  if (!tab || !isWebUrl(tab.url)) return;
  if (command === 'send-to-phone') return phoneOrQr(tab.url, tab.title, tab);
  const r = await startDownload(tab.url, command === 'download-audio' ? 'audio' : 'video', tab.title);
  if (r.ok) {
    chrome.notifications.create('haul-start:' + r.job.id, {
      type: 'basic', iconUrl: 'icons/icon128.png', title: 'Haul is downloading',
      message: r.job.title, contextMessage: command === 'download-audio' ? 'Audio' : 'Video',
    });
  } else if (r.error === 'missing' || r.error === 'notready') {
    chrome.runtime.openOptionsPage();
  } else {
    chrome.notifications.create({ type: 'basic', iconUrl: 'icons/icon128.png', title: "Haul couldn't start", message: r.error });
  }
});

// ---- Right-click menu ----

chrome.runtime.onInstalled.addListener(() => {
  syncInpage();
  chrome.contextMenus.removeAll(() => {
    const contexts = ['page', 'link', 'video', 'audio'];
    chrome.contextMenus.create({ id: 'haul-phone', title: 'Send to phone', contexts });
    chrome.contextMenus.create({ id: 'haul-video', title: 'Download video on this PC', contexts });
    chrome.contextMenus.create({ id: 'haul-audio', title: 'Download audio on this PC', contexts });
    const lists = ['*://*.youtube.com/*list=*', '*://*.youtube.com/playlist*'];
    chrome.contextMenus.create({ id: 'haul-playlist', title: 'Download the whole playlist on this PC', contexts: ['page'], documentUrlPatterns: lists });
    chrome.contextMenus.create({ id: 'haul-playlist-link', title: 'Download the whole playlist on this PC', contexts: ['link'], targetUrlPatterns: lists });
    chrome.contextMenus.create({ id: 'haul-history', title: 'Download history', contexts: ['action'] });
  });
});

chrome.contextMenus.onClicked.addListener(async (info, tab) => {
  // A video element's src is usually a stream or blob, so use the page it sits on.
  const url = info.linkUrl || info.pageUrl;
  const title = info.linkUrl ? '' : tab?.title;
  if (info.menuItemId === 'haul-phone') return phoneOrQr(url, title, tab);
  if (info.menuItemId === 'haul-history') return chrome.tabs.create({ url: 'history.html' });
  const mode = info.menuItemId === 'haul-audio' ? 'audio' : 'video';
  const playlist = info.menuItemId === 'haul-playlist' || info.menuItemId === 'haul-playlist-link';
  const r = await startDownload(url, mode, title, { playlist });
  if (!r.ok) {
    if (r.error === 'missing' || r.error === 'notready') chrome.runtime.openOptionsPage();
    else chrome.notifications.create({ type: 'basic', iconUrl: 'icons/icon128.png', title: "Haul couldn't start", message: r.error });
  }
});
