const $ = (id) => document.getElementById(id);

let settings;
let page = { url: '', title: '', tabId: null };
let scope = 'one'; // 'one' | 'all' (whole playlist)

init();

async function init() {
  settings = await getSettings();
  const [tab] = await chrome.tabs.query({ active: true, currentWindow: true });
  page = { url: tab?.url || '', title: tab?.title || '', tabId: tab?.id };

  $('settings').onclick = (e) => { e.preventDefault(); chrome.runtime.openOptionsPage(); };
  $('history').onclick = (e) => { e.preventDefault(); chrome.tabs.create({ url: 'history.html' }); };

  if (!isWebUrl(page.url)) {
    $('pageTitle').textContent = 'Nothing to save here';
    $('main').hidden = true;
    $('noPage').hidden = false;
    return;
  }
  $('pageTitle').textContent = page.title || page.url;
  $('pageHost').textContent = hostOf(page.url);

  document.querySelectorAll('[data-tab]').forEach((b) => (b.onclick = () => showTab(b.dataset.tab)));
  showTab(settings.tab);

  // Phone
  renderQr();
  renderPair();
  $('sendPhone').onclick = sendPhone;
  $('showQr').onclick = () => { $('qrBox').hidden = !$('qrBox').hidden; };
  $('pairLink').onclick = (e) => { e.preventDefault(); chrome.tabs.create({ url: 'options.html#pair' }); };
  $('copy').onclick = async () => {
    await navigator.clipboard.writeText(page.url);
    flash($('copy'), 'Copied');
  };
  $('switchMode').onclick = async () => {
    settings.qrMode = settings.qrMode === 'haul' ? 'plain' : 'haul';
    await saveSettings({ qrMode: settings.qrMode });
    renderQr();
  };

  // PC
  document.querySelectorAll('[data-mode]').forEach((b) => (b.onclick = () => setMode(b.dataset.mode)));
  $('quality').value = settings.quality;
  $('audioFormat').value = settings.audioFormat;
  $('quality').onchange = () => saveSettings({ quality: $('quality').value });
  $('audioFormat').onchange = () => saveSettings({ audioFormat: $('audioFormat').value });
  setMode(settings.mode);
  $('download').onclick = download;

  // Playlist and clip
  if (isPlaylistUrl(page.url)) {
    $('scopeRow').hidden = false;
    document.querySelectorAll('[data-scope]').forEach((b) => (b.onclick = () => setScope(b.dataset.scope)));
    setScope(isPlaylistOnly(page.url) ? 'all' : 'one');
  }
  $('clipOn').onchange = () => { $('clipBox').hidden = !$('clipOn').checked; };
  $('fromNow').onclick = async () => { const t = await videoTime(); if (t != null) $('clipFrom').value = fmtTime(t); };
  $('toNow').onclick = async () => { const t = await videoTime(); if (t != null) $('clipTo').value = fmtTime(t); };
  $('setup').onclick = () => chrome.tabs.create({ url: 'options.html#desktop' });
  $('getHelper').onclick = () => chrome.tabs.create({ url: HELPER_DOWNLOAD_URL });
  $('openFolder').onclick = () => chrome.runtime.sendMessage({ cmd: 'openFolder', folder: settings.folder });
  $('clear').onclick = () => chrome.runtime.sendMessage({ cmd: 'clearFinished' });

  renderJobs();

  if (!settings.inpageButton) {
    $('tip').hidden = false;
    $('tipOn').onclick = (e) => { e.preventDefault(); chrome.tabs.create({ url: 'options.html#inpage' }); };
  }
  chrome.storage.onChanged.addListener((changes, area) => {
    if (area === 'session' && changes.jobs) renderJobs(changes.jobs.newValue);
  });
}

function showTab(name) {
  document.querySelectorAll('[data-tab]').forEach((b) => b.setAttribute('aria-selected', String(b.dataset.tab === name)));
  $('phone').hidden = name !== 'phone';
  $('pc').hidden = name !== 'pc';
  saveSettings({ tab: name });
  if (name === 'pc') checkHelper();
}

function renderQr() {
  drawQr($('qr'), phoneLink(page.url, settings.qrMode), 220);
  if (settings.qrMode === 'haul') {
    $('qrHint').textContent = 'Scan with your phone camera. It opens straight in Haul.';
    $('switchMode').textContent = 'Not opening Haul?';
    $('switchMode').title = 'Use the plain page link instead, then share it to Haul on your phone';
  } else {
    $('qrHint').textContent = 'Scan with your phone camera, then tap Share and pick Haul.';
    $('switchMode').textContent = 'Open in Haul directly';
    $('switchMode').title = 'Needs the latest Haul app on your phone';
  }
}

let helperChecked = false;
async function checkHelper() {
  if (helperChecked) return;
  helperChecked = true;
  const s = await chrome.runtime.sendMessage({ cmd: 'status' });
  $('pcChecking').hidden = true;
  if (!s.ok || !s.ready) {
    $('pcMissing').hidden = false;
    return;
  }
  $('pcReady').hidden = false;
}

function setMode(mode) {
  settings.mode = mode;
  saveSettings({ mode });
  document.querySelectorAll('[data-mode]').forEach((b) => b.setAttribute('aria-selected', String(b.dataset.mode === mode)));
  $('quality').hidden = mode !== 'video';
  $('audioFormat').hidden = mode !== 'audio';
}

async function download() {
  $('pcError').hidden = true;
  const opts = { playlist: scope === 'all' };
  if (!opts.playlist && $('clipOn').checked) {
    const from = parseTime($('clipFrom').value);
    const to = parseTime($('clipTo').value);
    $('clipFrom').classList.toggle('bad', Number.isNaN(from));
    $('clipTo').classList.toggle('bad', Number.isNaN(to));
    if (Number.isNaN(from) || Number.isNaN(to)) return showError('Write times like 1:20 or 1:02:03.');
    if (to != null && to <= (from || 0)) return showError('The clip has to end after it starts.');
    opts.start = from || 0;
    if (to != null) opts.end = to;
  }
  $('download').disabled = true;
  const r = await chrome.runtime.sendMessage({ cmd: 'download', url: page.url, mode: settings.mode, title: page.title, opts });
  $('download').disabled = false;
  if (!r.ok) {
    if (r.error === 'missing' || r.error === 'notready') {
      $('pcReady').hidden = true;
      $('pcMissing').hidden = false;
    } else {
      showError(r.error);
    }
  }
}

function showError(text) {
  $('pcError').textContent = text;
  $('pcError').hidden = false;
}

function setScope(s) {
  scope = s;
  document.querySelectorAll('[data-scope]').forEach((b) => b.setAttribute('aria-selected', String(b.dataset.scope === s)));
  $('clipRow').hidden = s === 'all';
  $('clipBox').hidden = s === 'all' || !$('clipOn').checked;
  $('download').textContent = s === 'all' ? 'Download all' : 'Download';
}

// The current time of the biggest video on the page.
async function videoTime() {
  try {
    const [res] = await chrome.scripting.executeScript({
      target: { tabId: page.tabId },
      func: () => {
        const vids = [...document.querySelectorAll('video')].sort((a, b) => b.clientWidth * b.clientHeight - a.clientWidth * a.clientHeight);
        return vids[0] ? vids[0].currentTime : null;
      },
    });
    if (res?.result == null) showError('No video found on this page.');
    return res?.result ?? null;
  } catch {
    showError("Couldn't read the video's time on this page.");
    return null;
  }
}

// ---- Phone ----

async function renderPair() {
  const { pair } = await chrome.storage.local.get('pair');
  $('paired').hidden = !pair;
  $('qrBox').hidden = !!pair;
  $('showQr').hidden = !pair;
  $('switchMode').hidden = !!pair;
  $('pairTip').hidden = !!pair;
  if (pair) $('deviceName').textContent = pair.device;
}

async function sendPhone() {
  const b = $('sendPhone');
  b.disabled = true;
  $('sendStatus').textContent = 'Sending…';
  const r = await chrome.runtime.sendMessage({ cmd: 'phoneSend', url: page.url, title: page.title, mode: 'video' });
  b.disabled = false;
  $('sendStatus').textContent = r.ok ? `Sent. It'll open in Haul on ${r.device}.` : r.error;
  if (!r.ok) renderPair();
}

async function renderJobs(jobs) {
  if (!jobs) ({ jobs = [] } = await chrome.storage.session.get('jobs'));
  const list = $('jobs');
  list.replaceChildren();
  for (const j of jobs) {
    const li = document.createElement('li');
    li.className = j.state;
    const active = ['queued', 'running', 'processing'].includes(j.state);

    const top = document.createElement('div');
    top.className = 'top';
    const name = document.createElement('div');
    name.className = 'name';
    name.textContent = j.title;
    name.title = j.title;
    const kind = document.createElement('span');
    kind.className = 'kind';
    kind.textContent = jobKind(j);
    top.append(name, kind);
    if (active) {
      const x = document.createElement('button');
      x.className = 'x';
      x.textContent = '×';
      x.title = 'Cancel';
      x.onclick = () => chrome.runtime.sendMessage({ cmd: 'cancel', id: j.id });
      top.append(x);
    }
    li.append(top);

    if (active) {
      const bar = document.createElement('div');
      bar.className = 'bar' + (j.percent == null || j.state === 'processing' ? ' indeterminate' : '');
      const fill = document.createElement('i');
      if (j.percent != null && j.state !== 'processing') fill.style.width = j.percent + '%';
      bar.append(fill);
      li.append(bar);
    }

    const line = document.createElement('div');
    line.className = 'line';
    const text = document.createElement('span');
    text.textContent = jobLine(j);
    line.append(text);
    if (j.state === 'done' && j.file) {
      const show = document.createElement('button');
      show.className = 'link';
      show.textContent = j.playlist ? 'Open folder' : 'Show in folder';
      show.onclick = () => chrome.runtime.sendMessage({ cmd: j.playlist ? 'open' : 'reveal', path: j.file });
      line.append(show);
    }
    li.append(line);
    list.append(li);
  }
  $('jobsActions').hidden = jobs.length === 0;
}

function flash(btn, text) {
  const old = btn.textContent;
  btn.textContent = text;
  setTimeout(() => (btn.textContent = old), 1200);
}
