// The Haul button that appears on videos. Injected only while the user has switched it on.
// Everything lives in a closed shadow root, so the site's styles can't reach it and ours can't leak.

(() => {
  if (window.__haulInpage) return;
  window.__haulInpage = true;

  const MIN_W = 200;
  const MIN_H = 112;
  const FAB = 38;
  // Links that point at one post or video, used to tell feed videos apart.
  const PERMALINK = /\/(videos?|reels?|watch|status|p|shorts|tv|clips?)\/|[?&]v=/i;

  let settings = { ...DEFAULTS };
  let host, root, fab, ring, panel;
  let video = null;     // the video the button is attached to
  let pinned = null;    // the video the open panel belongs to
  let hideTimer = 0;
  let frame = 0;
  let lastPoint = null;
  let jobId = null;
  let flashTimer = 0;
  let pair = null; // paired phone, for one-tap sending

  chrome.storage.local.get('pair').then((r) => { pair = r.pair || null; });

  getSettings().then((s) => {
    settings = s;
    document.addEventListener('pointermove', onMove, { passive: true, capture: true });
    document.addEventListener('scroll', place, { passive: true, capture: true });
    window.addEventListener('resize', place, { passive: true });
    document.addEventListener('fullscreenchange', onFullscreen);
    document.addEventListener('keydown', (e) => { if (e.key === 'Escape' && pinned) closePanel(); }, true);
    document.addEventListener('pointerdown', (e) => { if (pinned && host && !e.composedPath().includes(host)) closePanel(); }, true);
  });

  chrome.storage.onChanged.addListener((changes, area) => {
    if (area === 'sync') {
      for (const k in changes) settings[k] = changes[k].newValue ?? DEFAULTS[k];
      if (!enabled()) hide(true);
      else if (pinned) renderMenu();
    }
    if (area === 'session' && changes.jobs) onJobs(changes.jobs.newValue || []);
    if (area === 'local' && changes.pair) pair = changes.pair.newValue || null;
  });

  function enabled() {
    if (YOUTUBE_HOSTS.test(location.hostname) && !settings.youtubeButton) return false;
    return settings.inpageButton && !(settings.hiddenSites || []).includes(location.hostname);
  }

  // ---- Finding the video under the pointer ----

  function onMove(e) {
    lastPoint = { x: e.clientX, y: e.clientY, path: e.composedPath() };
    if (!frame) frame = requestAnimationFrame(track);
  }

  function track() {
    frame = 0;
    if (!enabled() || !lastPoint) return;
    if (host && lastPoint.path.includes(host)) return keep();
    const v = videoAt(lastPoint.x, lastPoint.y);
    if (v) {
      keep();
      if (v !== video && !pinned) attach(v);
    } else if (!pinned) {
      scheduleHide();
    }
  }

  function videoAt(x, y) {
    let best = null;
    let bestArea = Infinity;
    for (const v of document.querySelectorAll('video')) {
      const r = v.getBoundingClientRect();
      if (r.width < MIN_W || r.height < MIN_H) continue;
      if (x < r.left || x > r.right || y < r.top || y > r.bottom) continue;
      const area = r.width * r.height;
      if (area < bestArea) { best = v; bestArea = area; }
    }
    return best;
  }

  // The link to save for this video. In feeds (Facebook, Instagram, X…) the page URL is the feed,
  // so look for the post's own link near the video.
  function linkFor(v) {
    if (window.top !== window) return location.href;
    // YouTube's main and Shorts players hold links to other videos (end screens), so use the page itself.
    if (v.closest('#movie_player, #shorts-player, ytd-player')) return location.href;
    if (document.querySelectorAll('video').length > 1) {
      let el = v.parentElement;
      for (let depth = 0; el && el !== document.body && depth < 12; depth++, el = el.parentElement) {
        for (const a of el.querySelectorAll('a[href]')) {
          try {
            const u = new URL(a.href, location.href);
            if (!/^https?:$/.test(u.protocol) || !sameSite(u.hostname)) continue;
            if (PERMALINK.test(u.pathname + u.search) && u.href !== location.href) return u.href;
          } catch {}
        }
      }
    }
    return location.href;
  }

  function sameSite(h) {
    const tail = (s) => s.split('.').slice(-2).join('.');
    return tail(h) === tail(location.hostname);
  }

  // ---- Showing and placing the button ----

  function attach(v) {
    ensureUi();
    video = v;
    host.hidden = false;
    fab.classList.add('show');
    place();
  }

  function keep() {
    clearTimeout(hideTimer);
    hideTimer = 0;
  }

  function scheduleHide() {
    if (hideTimer || !video) return;
    hideTimer = setTimeout(() => { hideTimer = 0; hide(); }, 900);
  }

  function hide(force) {
    if (!host) return;
    if (pinned && !force) return;
    closePanel();
    fab.classList.remove('show');
    video = null;
  }

  function place() {
    if (!host || !video) return;
    if (!video.isConnected) return hide(true);
    const r = video.getBoundingClientRect();
    const vw = innerWidth;
    const vh = innerHeight;
    if (r.bottom < 0 || r.top > vh) return hide(true);
    const top = Math.max(r.top, 0) + 10;
    const left = Math.min(r.right, vw) - FAB - 10;
    fab.style.top = top + 'px';
    fab.style.left = left + 'px';
    if (pinned) placePanel(top, left);
  }

  function placePanel(fabTop, fabLeft) {
    const w = panel.offsetWidth;
    const h = panel.offsetHeight;
    let left = Math.min(fabLeft + FAB - w, innerWidth - w - 8);
    left = Math.max(8, left);
    let top = fabTop + FAB + 8;
    if (top + h > innerHeight - 8) top = Math.max(8, fabTop - h - 8);
    panel.style.left = left + 'px';
    panel.style.top = top + 'px';
  }

  function onFullscreen() {
    if (!host) return;
    const fs = document.fullscreenElement;
    // A fullscreen <video> can't hold other elements, so the button only follows custom players.
    const parent = fs && !(fs instanceof HTMLVideoElement) ? fs : document.documentElement;
    if (host.parentNode !== parent) parent.appendChild(host);
    place();
  }

  // ---- UI ----

  function ensureUi() {
    if (host) return;
    host = document.createElement('haul-button');
    host.style.cssText = 'all:initial;position:fixed;top:0;left:0;width:0;height:0;z-index:2147483647;';
    root = host.attachShadow({ mode: 'closed' });
    root.innerHTML = `<style>${CSS}</style>
      <button class="fab" title="Haul" aria-label="Save with Haul">
        <svg class="ring" viewBox="0 0 40 40"><circle cx="20" cy="20" r="18"/></svg>
        <svg class="arrow" viewBox="0 0 24 24"><path d="M12 4v11M7 10.5l5 5 5-5M5 20h14"/></svg>
        <svg class="check" viewBox="0 0 24 24"><path d="M5 12.5l4.5 4.5L19 7.5"/></svg>
      </button>
      <div class="panel" role="dialog" aria-label="Haul" hidden></div>`;
    fab = root.querySelector('.fab');
    ring = root.querySelector('.ring circle');
    panel = root.querySelector('.panel');
    fab.addEventListener('click', (e) => {
      e.preventDefault();
      e.stopPropagation();
      pinned ? closePanel() : openPanel();
    });
    // Keep clicks inside the panel from reaching the player underneath.
    // Typing in the clip fields must not trigger the site's own keyboard shortcuts either.
    for (const t of ['click', 'pointerdown', 'mousedown', 'pointerup', 'mouseup', 'dblclick', 'keydown', 'keyup', 'keypress']) {
      panel.addEventListener(t, (e) => e.stopPropagation());
    }
    (document.fullscreenElement && !(document.fullscreenElement instanceof HTMLVideoElement)
      ? document.fullscreenElement : document.documentElement).appendChild(host);
  }

  function openPanel() {
    pinned = video;
    renderMenu();
    panel.hidden = false;
    place();
  }

  function closePanel() {
    if (!panel) return;
    pinned = null;
    panel.hidden = true;
  }

  function el(tag, cls, text) {
    const n = document.createElement(tag);
    if (cls) n.className = cls;
    if (text != null) n.textContent = text;
    return n;
  }

  function head(title, back) {
    const h = el('div', 'head');
    if (back) {
      const b = el('button', 'back', '‹');
      b.title = 'Back';
      b.onclick = renderMenu;
      h.append(b);
    } else {
      const logo = el('span', 'logo');
      logo.innerHTML = '<svg viewBox="0 0 24 24"><path d="M12 4v11M7 10.5l5 5 5-5M5 20h14"/></svg>';
      h.append(logo);
    }
    h.append(el('span', 'htitle', title));
    return h;
  }

  function renderMenu() {
    if (!pinned) return;
    panel.replaceChildren(head('Haul'));

    const phone = pair
      ? item('phone', 'Send to ' + pair.device, 'One tap, it opens in Haul')
      : item('phone', 'Send to phone', 'Scan a QR code, it opens in Haul');
    phone.onclick = pair ? sendPhone : renderQr;

    const vid = item('video', 'Download video', qualityLabel());
    vid.onclick = () => download('video');
    const vChips = chips(
      [['best', 'Best'], ['2160', '4K'], ['1080', '1080p'], ['720', '720p'], ['480', '480p']],
      settings.quality, (q) => saveSettings({ quality: q }),
    );

    const aud = item('audio', 'Download audio', settings.audioFormat.toUpperCase());
    aud.onclick = () => download('audio');
    const aChips = chips([['mp3', 'MP3'], ['m4a', 'M4A'], ['opus', 'Opus']],
      settings.audioFormat, (f) => saveSettings({ audioFormat: f }));

    const extra = el('div', 'extra');
    const clip = el('button', 'mini', 'Save a clip');
    clip.onclick = renderClip;
    extra.append(clip);
    if (window.top === window && isPlaylistUrl(linkFor(pinned))) {
      const list = el('button', 'mini', 'Whole playlist');
      list.onclick = renderPlaylist;
      extra.append(list);
    }

    const status = el('div', 'status');
    status.hidden = true;

    const foot = el('div', 'foot');
    const hideBtn = el('button', 'link', 'Hide on this site');
    hideBtn.onclick = async () => {
      const list = new Set(settings.hiddenSites || []);
      list.add(location.hostname);
      await saveSettings({ hiddenSites: [...list] });
    };
    const more = el('button', 'link', 'Settings');
    more.onclick = () => send({ cmd: 'openOptions' });
    foot.append(hideBtn, more);

    panel.append(phone, vid, vChips, aud, aChips, extra, status, foot);
    renderStatus();
    requestAnimationFrame(place);
  }

  function qualityLabel() {
    return settings.quality === 'best' ? 'Best quality · MP4' : 'Up to ' + (settings.quality === '2160' ? '4K' : settings.quality + 'p') + ' · MP4';
  }

  const ICONS = {
    phone: '<path d="M8 3h8a1 1 0 0 1 1 1v16a1 1 0 0 1-1 1H8a1 1 0 0 1-1-1V4a1 1 0 0 1 1-1zM11 18h2"/>',
    video: '<path d="M4 6h11v12H4zM15 10l5-3v10l-5-3"/>',
    audio: '<path d="M9 18V6l10-2v12M9 18a2 2 0 1 1-4 0 2 2 0 0 1 4 0zM19 16a2 2 0 1 1-4 0 2 2 0 0 1 4 0z"/>',
  };

  function item(icon, title, sub) {
    const b = el('button', 'item');
    const i = el('span', 'icon');
    i.innerHTML = `<svg viewBox="0 0 24 24">${ICONS[icon]}</svg>`;
    const t = el('span', 'text');
    t.append(el('span', 't1', title), el('span', 't2', sub));
    b.append(i, t);
    return b;
  }

  function chips(options, value, onPick) {
    const row = el('div', 'chips');
    for (const [v, label] of options) {
      const c = el('button', 'chip' + (v === value ? ' on' : ''), label);
      c.onclick = () => onPick(v);
      row.append(c);
    }
    return row;
  }

  function renderQr() {
    const url = linkFor(pinned);
    panel.replaceChildren(head('Send to phone', true));
    const box = el('div', 'qr');
    const canvas = el('canvas');
    box.append(canvas);
    const hint = el('p', 'hint', settings.qrMode === 'haul'
      ? 'Scan with your phone camera. It opens straight in Haul.'
      : 'Scan with your phone camera, then share it to Haul.');
    const link = el('p', 'url', hostOf(url) + new URL(url).pathname);
    panel.append(box, hint, link);
    drawQr(canvas, phoneLink(url, settings.qrMode), 200);
    requestAnimationFrame(place);
  }

  function renderSetup() {
    panel.replaceChildren(head('Download on this PC', true));
    panel.append(
      el('p', 'hint', 'Saving on this computer needs the small Haul helper app. It takes about a minute to set up.'),
    );
    const b = el('button', 'primary', 'Download the PC helper');
    b.onclick = () => { window.open(HELPER_DOWNLOAD_URL, '_blank', 'noopener'); };
    const how = el('button', 'link', 'How to set it up');
    how.style.cssText = 'display:block;margin:10px auto 2px';
    how.onclick = () => { send({ cmd: 'openOptions' }); closePanel(); };
    panel.append(b, how);
    requestAnimationFrame(place);
  }

  // ---- Downloads ----

  function renderClip() {
    const v = pinned;
    panel.replaceChildren(head('Save a clip', true));
    const now = v.currentTime || 0;
    const dur = Number.isFinite(v.duration) ? v.duration : 0;
    const from = timeField('From', fmtTime(now), () => v.currentTime);
    const to = timeField('To', dur ? fmtTime(Math.min(now + 30, dur)) : '', () => v.currentTime);
    const err = el('p', 'err');
    err.hidden = true;
    const go = (mode) => {
      const a = parseTime(from.input.value);
      const b = parseTime(to.input.value);
      if (Number.isNaN(a) || Number.isNaN(b)) return showErr('Write times like 1:20 or 1:02:03.');
      if (b != null && b <= (a || 0)) return showErr('The clip has to end after it starts.');
      download(mode, { start: a || 0, end: b ?? undefined });
      renderMenu();
    };
    const showErr = (t) => { err.textContent = t; err.hidden = false; };
    const row = el('div', 'buttons');
    const bv = el('button', 'primary', 'Video clip');
    bv.onclick = () => go('video');
    const ba = el('button', 'secondary', 'Audio clip');
    ba.onclick = () => go('audio');
    row.append(bv, ba);
    panel.append(el('p', 'hint', 'Pick the part to save. "Now" uses where the video is playing.'), from.row, to.row, err, row);
    requestAnimationFrame(place);
    from.input.focus();
  }

  function timeField(label, value, now) {
    const row = el('div', 'tfield');
    const input = el('input');
    input.value = value;
    input.placeholder = label === 'To' ? 'end' : '0:00';
    input.spellcheck = false;
    const b = el('button', 'mini', 'Now');
    b.onclick = () => { input.value = fmtTime(now()); };
    row.append(el('span', '', label), input, b);
    return { row, input };
  }

  function renderPlaylist() {
    panel.replaceChildren(head('Whole playlist', true));
    panel.append(el('p', 'hint', 'Saves every item in this playlist or channel into its own folder.'));
    const row = el('div', 'buttons');
    const bv = el('button', 'primary', 'All as video');
    bv.onclick = () => { download('video', { playlist: true }); renderMenu(); };
    const ba = el('button', 'secondary', 'All as audio');
    ba.onclick = () => { download('audio', { playlist: true }); renderMenu(); };
    row.append(bv, ba);
    panel.append(row);
    requestAnimationFrame(place);
  }

  async function sendPhone() {
    const url = linkFor(pinned);
    const r = await send({ cmd: 'phoneSend', url, title: document.title, mode: 'video' });
    if (!r) return;
    if (r.ok) return showStatus({ state: 'done', title: 'Sent to ' + r.device, phone: true });
    if (r.error === 'notpaired') { pair = null; return renderQr(); }
    showStatus({ state: 'error', error: r.error, title: document.title });
  }

  async function download(mode, opts = {}) {
    const url = linkFor(pinned);
    const r = await send({ cmd: 'download', url, mode, title: document.title, opts });
    if (!r) return;
    if (!r.ok) {
      if (r.error === 'missing' || r.error === 'notready') return renderSetup();
      jobId = null;
      return showStatus({ state: 'error', error: r.error, title: document.title });
    }
    jobId = r.job.id;
    showStatus(r.job);
    setRing(0, true);
  }

  async function renderStatus() {
    if (!jobId) return;
    const { jobs = [] } = await chrome.storage.session.get('jobs');
    const j = jobs.find((x) => x.id === jobId);
    if (j) showStatus(j);
  }

  function showStatus(j) {
    const box = panel?.querySelector('.status');
    if (!box) return;
    box.hidden = false;
    box.className = 'status ' + j.state;
    const active = ['queued', 'running', 'processing'].includes(j.state);
    const bar = el('div', 'bar' + (active && (j.percent == null || j.state === 'processing') ? ' busy' : ''));
    const fill = el('i');
    fill.style.width = (j.state === 'done' ? 100 : j.percent || 0) + '%';
    bar.append(fill);
    const line = el('div', 'line');
    line.append(el('span', '', j.phone ? "It'll open in Haul on your phone." : jobLine(j)));
    if (j.state === 'done' && j.file) {
      const show = el('button', 'link', j.playlist ? 'Open folder' : 'Show in folder');
      show.onclick = () => send({ cmd: j.playlist ? 'open' : 'reveal', path: j.file });
      line.append(show);
    } else if (active) {
      const x = el('button', 'link', 'Cancel');
      x.onclick = () => send({ cmd: 'cancel', id: j.id });
      line.append(x);
    }
    box.replaceChildren(el('div', 'stitle', j.title || ''), bar, line);
  }

  function onJobs(jobs) {
    if (!jobId) return;
    const j = jobs.find((x) => x.id === jobId);
    if (!j) return;
    showStatus(j);
    if (j.state === 'running' || j.state === 'queued') setRing(j.percent || 0, true);
    else if (j.state === 'processing') setRing(100, true);
    else {
      setRing(0, false);
      if (j.state === 'done') flash();
    }
  }

  function setRing(pct, on) {
    if (!fab) return;
    fab.classList.toggle('busy', on);
    const c = 2 * Math.PI * 18;
    ring.style.strokeDasharray = c;
    ring.style.strokeDashoffset = c * (1 - Math.min(100, pct) / 100);
  }

  function flash() {
    fab.classList.add('done');
    clearTimeout(flashTimer);
    flashTimer = setTimeout(() => fab.classList.remove('done'), 2500);
  }

  async function send(msg) {
    try {
      return await chrome.runtime.sendMessage(msg);
    } catch {
      // The extension was updated or removed. Take the button away until the page reloads.
      host?.remove();
      host = null;
      window.__haulInpage = false;
      return null;
    }
  }

  const CSS = `
    :host { all: initial; }
    * { box-sizing: border-box; font-family: system-ui, -apple-system, "Segoe UI", Roboto, sans-serif; }
    [hidden] { display: none !important; }
    button { font: inherit; color: inherit; cursor: pointer; }
    svg { fill: none; stroke: currentColor; stroke-width: 2.4; stroke-linecap: square; }

    .fab {
      position: fixed; width: ${FAB}px; height: ${FAB}px; border-radius: 12px; border: 0; padding: 0;
      background: #ff7a3d; color: #1a0e06; display: grid; place-items: center;
      box-shadow: 0 4px 14px rgba(0,0,0,.35); opacity: 0; transform: scale(.85); pointer-events: none;
      transition: opacity .15s, transform .15s;
    }
    .fab.show { opacity: 1; transform: none; pointer-events: auto; }
    .fab:hover { filter: brightness(1.06); }
    .fab > svg { grid-area: 1 / 1; }
    .fab .arrow, .fab .check { width: 20px; height: 20px; }
    .fab .check { display: none; }
    .fab.done .arrow { display: none; }
    .fab.done .check { display: block; }
    .fab .ring { width: ${FAB + 8}px; height: ${FAB + 8}px; transform: rotate(-90deg); display: none; }
    .fab .ring circle { stroke: #1a0e06; stroke-width: 3; opacity: .55; transition: stroke-dashoffset .3s; }
    .fab.busy .ring { display: block; width: ${FAB - 6}px; height: ${FAB - 6}px; }

    .panel {
      position: fixed; width: 280px; background: #1e1c19; color: #f4f1ea; border: 1px solid #2f2c27;
      border-radius: 14px; padding: 10px; box-shadow: 0 12px 40px rgba(0,0,0,.45); font-size: 13px; line-height: 1.4;
    }
    .head { display: flex; align-items: center; gap: 8px; padding: 2px 4px 8px; }
    .logo { width: 22px; height: 22px; border-radius: 6px; background: #ff7a3d; color: #1a0e06; display: grid; place-items: center; }
    .logo svg { width: 14px; height: 14px; }
    .htitle { font-weight: 700; font-size: 14px; }
    .back { border: 0; background: none; color: #a39d92; font-size: 22px; line-height: 1; padding: 0 4px; }

    .item { display: flex; gap: 10px; align-items: center; width: 100%; text-align: left; border: 0; background: none; border-radius: 10px; padding: 8px; }
    .item:hover { background: #2a2722; }
    .icon { width: 32px; height: 32px; border-radius: 9px; background: #2a2722; display: grid; place-items: center; color: #ff7a3d; flex: none; }
    .item:hover .icon { background: #34302a; }
    .icon svg { width: 18px; height: 18px; stroke-width: 2; }
    .text { display: flex; flex-direction: column; min-width: 0; }
    .t1 { font-weight: 600; }
    .t2 { color: #a39d92; font-size: 12px; }

    .chips { display: flex; flex-wrap: wrap; gap: 4px; padding: 0 8px 6px 50px; }
    .chip { border: 1px solid #3a362f; background: none; border-radius: 999px; padding: 1px 8px; font-size: 11px; color: #a39d92; }
    .chip.on { border-color: #ff7a3d; color: #ff7a3d; }

    .status { margin: 6px 4px 2px; padding: 8px; border-radius: 10px; background: #2a2722; }
    .stitle { font-weight: 600; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
    .bar { height: 4px; border-radius: 2px; background: #3a362f; overflow: hidden; margin: 6px 0 4px; }
    .bar i { display: block; height: 100%; background: #ff7a3d; transition: width .3s; }
    .bar.busy i { width: 30% !important; animation: slide 1.1s ease-in-out infinite; }
    @keyframes slide { from { margin-left: -30%; } to { margin-left: 100%; } }
    .line { display: flex; justify-content: space-between; gap: 8px; font-size: 12px; color: #a39d92; }
    .status.done .line span { color: #7fd0a0; }
    .status.error .line span { color: #ff8a70; }
    .status.error .bar { display: none; }

    .foot { display: flex; justify-content: space-between; padding: 8px 6px 0; margin-top: 6px; border-top: 1px solid #2f2c27; }
    .link { border: 0; background: none; padding: 0; color: #ff7a3d; font-size: 12px; font-weight: 600; }
    .foot .link { color: #a39d92; font-weight: 500; }
    .foot .link:hover { color: #f4f1ea; }

    .qr { display: flex; justify-content: center; padding: 4px 0; }
    .qr canvas { border-radius: 10px; background: #fff; }
    .hint { color: #a39d92; text-align: center; margin: 8px 6px; }
    .url { color: #6b665d; font-size: 11px; text-align: center; margin: 0 6px 4px; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
    .primary { display: block; width: 100%; height: 34px; border: 0; border-radius: 10px; background: #ff7a3d; color: #1a0e06; font-weight: 700; }
    .secondary { display: block; width: 100%; height: 34px; border: 1px solid #3a362f; border-radius: 10px; background: none; color: #f4f1ea; font-weight: 600; }
    .buttons { display: grid; grid-template-columns: 1fr 1fr; gap: 8px; margin-top: 10px; }
    .extra { display: flex; gap: 6px; padding: 4px 8px 2px; }
    .mini { border: 1px solid #3a362f; background: none; border-radius: 8px; padding: 4px 10px; font-size: 12px; color: #f4f1ea; }
    .mini:hover { border-color: #ff7a3d; color: #ff7a3d; }
    .tfield { display: grid; grid-template-columns: 40px 1fr auto; gap: 8px; align-items: center; margin: 6px 4px; }
    .tfield span { color: #a39d92; font-size: 12px; }
    .tfield input { height: 32px; border-radius: 8px; border: 1px solid #3a362f; background: #141311; color: #f4f1ea; padding: 0 10px; font: inherit; outline: none; }
    .tfield input:focus { border-color: #ff7a3d; }
    .err { color: #ff8a70; font-size: 12px; margin: 6px 4px 0; }
  `;
})();
