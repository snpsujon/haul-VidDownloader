// Service worker: talks to the desktop helper over native messaging and keeps the job list.
// An open native port keeps this worker alive, so downloads keep reporting while the popup is closed.

importScripts('common.js');

const MAX_JOBS = 30;

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
    case 'progress':
      updateJob(msg.id, { state: 'running', percent: msg.percent, speed: msg.speed, eta: msg.eta });
      break;
    case 'processing': updateJob(msg.id, { state: 'processing', percent: 100, speed: '', eta: '' }); break;
    case 'done':
      updateJob(msg.id, { state: 'done', file: msg.file, percent: 100 }).then((j) => j && notify(j));
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
  jobsLock = jobsLock.then(async () => {
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

async function startDownload(url, mode, title) {
  if (!isWebUrl(url)) return { ok: false, error: 'This page has no link Haul can download.' };
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
    state: 'queued',
    percent: null,
    created: Date.now(),
  };
  await withJobs((jobs) => { jobs.unshift(job); });
  connect().postMessage({
    type: 'download',
    id: job.id,
    url,
    mode,
    quality: s.quality,
    audioFormat: s.audioFormat,
    folder: s.folder,
  });
  return { ok: true, job };
}

function notify(j) {
  const done = j.state === 'done';
  chrome.notifications.create('haul:' + j.id, {
    type: 'basic',
    iconUrl: 'icons/icon128.png',
    title: done ? 'Saved with Haul' : "Haul couldn't save this",
    message: done ? j.title : (j.title + '\n' + (j.error || '')),
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
      case 'download': return startDownload(msg.url, msg.mode, msg.title);
      case 'cancel':
        try { connect().postMessage({ type: 'cancel', id: msg.id }); } catch {}
        return { ok: true };
      case 'reveal':
        try { connect().postMessage({ type: 'reveal', path: msg.path }); } catch {}
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

// ---- Right-click menu ----

chrome.runtime.onInstalled.addListener(() => {
  chrome.contextMenus.removeAll(() => {
    const contexts = ['page', 'link', 'video', 'audio'];
    chrome.contextMenus.create({ id: 'haul-phone', title: 'Send to phone (QR code)', contexts });
    chrome.contextMenus.create({ id: 'haul-video', title: 'Download video on this PC', contexts });
    chrome.contextMenus.create({ id: 'haul-audio', title: 'Download audio on this PC', contexts });
  });
});

chrome.contextMenus.onClicked.addListener(async (info, tab) => {
  // A video element's src is usually a stream or blob, so use the page it sits on.
  const url = info.linkUrl || info.pageUrl;
  const title = info.linkUrl ? '' : tab?.title;
  if (info.menuItemId === 'haul-phone') {
    chrome.tabs.create({ url: 'qr.html?u=' + encodeURIComponent(url) + '&t=' + encodeURIComponent(title || '') });
    return;
  }
  const mode = info.menuItemId === 'haul-audio' ? 'audio' : 'video';
  const r = await startDownload(url, mode, title);
  if (!r.ok) {
    if (r.error === 'missing' || r.error === 'notready') chrome.runtime.openOptionsPage();
    else chrome.notifications.create({ type: 'basic', iconUrl: 'icons/icon128.png', title: "Haul couldn't start", message: r.error });
  }
});
