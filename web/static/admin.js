(function () {
  'use strict';
  var $ = function (id) { return document.getElementById(id); };
  var token = null, timer = null;
  try { token = sessionStorage.getItem('haul.admin'); } catch (e) { }

  function esc(s) {
    return String(s == null ? '' : s).replace(/[&<>"']/g, function (c) {
      return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c];
    });
  }
  function bytes(n) {
    if (!n) return '0 MB';
    if (n >= 1e12) return (n / 1e12).toFixed(1) + ' TB';
    if (n >= 1e9) return (n / 1e9).toFixed(1) + ' GB';
    if (n >= 1e6) return Math.round(n / 1e6) + ' MB';
    return Math.max(1, Math.round(n / 1e3)) + ' KB';
  }
  function when(ts) {
    if (!ts) return '';
    var d = new Date(ts * 1000), s = (Date.now() - d) / 1000;
    if (s < 60) return 'just now';
    if (s < 3600) return Math.floor(s / 60) + ' min ago';
    if (s < 86400) return Math.floor(s / 3600) + ' h ago';
    return d.toLocaleString(undefined, { month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit' });
  }
  function date(ts) { return ts ? new Date(ts * 1000).toLocaleDateString(undefined, { year: 'numeric', month: 'short', day: 'numeric' }) : ''; }
  function show(id, on) { $(id).classList.toggle('hidden', !on); }
  function hint(id, msg, bad) { $(id).textContent = msg || ''; $(id).className = 'hint' + (bad ? ' bad' : ''); }
  function setToken(t) { token = t; try { t ? sessionStorage.setItem('haul.admin', t) : sessionStorage.removeItem('haul.admin'); } catch (e) { } }

  function api(path, opts) {
    opts = opts || {};
    var headers = { 'Content-Type': 'application/json' };
    if (token) headers['X-Admin-Token'] = token;
    return fetch('api/admin/' + path, { method: opts.method || 'GET', headers: headers, body: opts.body ? JSON.stringify(opts.body) : undefined })
      .then(function (r) {
        return r.json().catch(function () { return {}; }).then(function (d) {
          if (r.status === 401 && path !== 'login') { setToken(null); screen('login'); }
          if (r.status === 404 && /admin page is off/.test(d.detail || '')) screen('off');
          if (!r.ok) { var e = new Error(typeof d.detail === 'string' ? d.detail : 'Something went wrong (' + r.status + ').'); e.status = r.status; throw e; }
          return d;
        });
      });
  }

  function screen(name) {
    show('login', name === 'login'); show('off', name === 'off'); show('panel', name === 'panel');
    show('outer', name !== 'panel');
    if (name === 'panel') route();
    clearInterval(timer);
    if (name === 'panel') { refresh(); loadLog(true); loadReleases(); loadCharts(); loadSettings(); timer = setInterval(refresh, 4000); }
    if (name === 'login') setTimeout(function () { $('password').focus(); }, 0);
  }

  // Pages: the sidebar picks one by the address's #hash ------------------------------------

  var PAGES = ['dashboard', 'stats', 'jobs', 'log', 'app', 'extension', 'helper', 'files', 'site', 'blocking', 'alerts', 'cookies', 'server'];
  function route() {
    var page = location.hash.slice(1);
    if (PAGES.indexOf(page) < 0) page = 'dashboard';
    [].forEach.call(document.querySelectorAll('[data-page]'), function (s) { s.classList.toggle('hidden', s.getAttribute('data-page') !== page); });
    [].forEach.call(document.querySelectorAll('[data-nav]'), function (a) {
      var on = a.getAttribute('data-nav') === page;
      a.classList.toggle('on', on);
      if (on) a.setAttribute('aria-current', 'page'); else a.removeAttribute('aria-current');
    });
    if (token) {
      if (page === 'stats') loadStats();
      if (page === 'site' || page === 'blocking' || page === 'alerts') loadSettings();
    }
    document.title = (document.querySelector('[data-nav="' + page + '"] span') || {}).textContent + ' · Haul admin';
    window.scrollTo(0, 0);
  }
  window.addEventListener('hashchange', route);

  function badge(id, text) { var b = $(id); b.textContent = text || ''; b.classList.toggle('hidden', !text); }

  // Overview ----------------------------------------------------------------------------

  function refresh() {
    api('overview').then(renderOverview).catch(function () { });
    api('jobs').then(renderJobs).catch(function () { });
  }

  function tile(label, value, warn) {
    return '<div class="tile' + (warn ? ' warn' : '') + '"><div class="label">' + esc(label) + '</div><b>' + esc(value) + '</b></div>';
  }

  // Charts ------------------------------------------------------------------------------

  var days = 14;
  function loadCharts() {
    api('charts?days=' + days).then(function (c) {
      var d = c.daily, max = Math.max(1, Math.max.apply(null, d.map(function (x) { return x.total; })));
      var W = 700, H = 160, gap = 3, bw = W / d.length - gap, tot = 0, failed = 0;
      var bars = d.map(function (x, i) {
        tot += x.total; failed += x.failed || 0;
        var hOk = (x.done || 0) / max * (H - 20), hBad = (x.failed || 0) / max * (H - 20), px = i * (bw + gap);
        var other = Math.max(0, x.total - (x.done || 0) - (x.failed || 0)) / max * (H - 20);
        var label = x.day.slice(5).replace('-', '/');
        return '<g><title>' + esc(x.day + ': ' + x.total + ' requests, ' + (x.done || 0) + ' worked, ' + (x.failed || 0) + ' failed, ' + x.people + ' people, ' + bytes(x.bytes)) + '</title>' +
          '<rect x="' + px + '" y="' + (H - 20 - hOk - hBad - other) + '" width="' + bw + '" height="' + other + '" class="b-other"/>' +
          '<rect x="' + px + '" y="' + (H - 20 - hOk - hBad) + '" width="' + bw + '" height="' + hBad + '" class="b-bad"/>' +
          '<rect x="' + px + '" y="' + (H - 20 - hOk) + '" width="' + bw + '" height="' + hOk + '" class="b-ok"/>' +
          ((d.length <= 14 || i % 3 === 0) ? '<text x="' + (px + bw / 2) + '" y="' + (H - 4) + '">' + label + '</text>' : '') + '</g>';
      }).join('');
      $('chart').innerHTML = '<svg viewBox="0 0 ' + W + ' ' + H + '" preserveAspectRatio="none" role="img" aria-label="Downloads per day">' +
        '<text x="0" y="10" class="ymax">' + max + '</text>' + bars + '</svg>';
      $('chartsum').textContent = tot + ' requests · ' + (tot ? Math.round(failed / tot * 100) : 0) + '% failed';
      $('sites').tBodies[0].innerHTML = c.sites.map(function (s) {
        var pct = s.total ? Math.round(s.failed / s.total * 100) : 0;
        return '<tr><td>' + esc(s.site) + '</td><td class="mono">' + s.total + '</td><td class="mono">' + s.failed + '</td>' +
          '<td><div class="rate"><i class="' + (pct >= 50 ? 'bad' : pct >= 20 ? 'warn' : 'ok') + '" style="width:' + Math.max(pct, 1) + '%"></i></div><span class="mono">' + pct + '%</span></td></tr>';
      }).join('') || '<tr><td class="empty" colspan="4">No downloads yet.</td></tr>';
    }).catch(function () { });
  }
  $('range').addEventListener('click', function (e) {
    var n = +e.target.getAttribute('data-days'); if (!n) return;
    days = n;
    [].forEach.call(this.children, function (b) { b.setAttribute('aria-checked', String(b === e.target)); });
    loadCharts();
  });

  function renderAlerts(list) {
    $('alerts').innerHTML = (list || []).map(function (a) {
      return '<div class="alert ' + (a.level === 'bad' ? 'bad' : 'warn') + '">' + esc(a.text) + '</div>';
    }).join('');
  }

  function renderOverview(o) {
    renderAlerts(o.alerts);
    badge('nav-alerts', (o.alerts || []).length ? String(o.alerts.length) : '');
    badge('nav-jobs', o.active_jobs ? String(o.active_jobs) : '');
    $('nav-cookies').classList.toggle('hidden', !(o.cookies && o.cookies.present && (o.cookies.ok === false || o.cookies.expired)));
    var s = o.stats || {};
    $('tiles').innerHTML =
      tile('Downloads today', s.today || 0) +
      tile('Failed today', s.failed_today || 0, s.failed_today > 0) +
      tile('People today', s.visitors_today || 0) +
      tile('Sent today', bytes(s.bytes_today)) +
      tile('Running now', o.active_jobs) +
      tile('Disk free', bytes(o.disk.free), o.disk.free < 5e9);
    $('filesize').textContent = bytes(o.files_bytes) + ' on disk';
    renderCookies(o.cookies);
    var st = o.settings;
    var sites = (s.top_sites_week || []).map(function (x) { return (x.site || '?') + ' ' + x.n; }).join(', ');
    var srcs = (s.sources_week || []).map(function (x) { return (x.source || '?') + ' ' + x.n; }).join(', ');
    $('server').innerHTML = [
      ['yt-dlp', o.yt_dlp], ['FFmpeg', o.ffmpeg || 'not found'],
      ['Downloads at once', st.max_concurrent + ' (' + st.max_active_per_client + ' per person)'],
      ['Files kept for', st.file_ttl_min + ' min'], ['Size limit', st.max_filesize_mb + ' MB'],
      ['Length limit', st.max_duration_min + ' min'], ['Playlist limit', st.max_playlist_items + ' videos'],
      ['API keys', st.api_keys ? st.api_keys + ' set' : 'off (open to everyone)'],
      ['Old extension API', st.legacy_api ? 'on' : 'off'], ['Proxy', st.proxy ? 'on' : 'off'],
      ['YouTube fallbacks', (st.youtube_clients || []).join(', ')],
      ['Top sites, 7 days', sites || '—'], ['Where from, 7 days', srcs || '—'],
      ['All-time requests', s.total || 0],
      ['Auto-update', o.upkeep.auto_update ? 'every night at ' + o.upkeep.hour + ':00' + (o.upkeep.auto_restart ? ', restarts when idle' : '') : 'off'],
      ['Last auto-update', !o.upkeep.last ? 'not yet' : new Date(o.upkeep.last.at * 1000).toLocaleString() + ' · ' +
        (o.upkeep.last.error ? 'failed' : o.upkeep.last.restart_needed ? 'updated to ' + o.upkeep.last.installed : 'already up to date')]
    ].map(function (kv) { return '<span>' + esc(kv[0]) + '</span><span>' + esc(kv[1]) + '</span>'; }).join('');
  }

  // Cookies -----------------------------------------------------------------------------

  function renderCookies(c) {
    var pill = $('cookiepill'), lines = [];
    if (!c.present) {
      pill.textContent = 'None'; pill.className = 'pill bad';
      lines.push('No cookies saved. Most sites work without them, but YouTube often blocks servers until you add some.');
    } else if (c.valid === false) {
      pill.textContent = 'Broken'; pill.className = 'pill bad';
      lines.push(c.error);
    } else {
      var bad = c.ok === false || c.expired, good = c.ok === true && !c.expired;
      pill.textContent = good ? 'Working' : bad ? 'Needs new cookies' : 'Not checked';
      pill.className = 'pill' + (good ? ' ok' : bad ? ' bad' : '');
      lines.push(c.count + ' cookies for ' + c.domains.slice(0, 4).join(', ') + (c.domains.length > 4 ? ' and ' + (c.domains.length - 4) + ' more' : '') + '. Saved ' + when(c.updated) + '.');
      if (!c.youtube_signed_in) lines.push('There are no signed-in YouTube cookies in this file.');
      if (c.expires) lines.push((c.expired ? 'The sign-in cookie expired on ' : 'The sign-in cookie lasts until ') + date(c.expires) + '. YouTube often ends the session sooner.');
    }
    if (c.message) lines.push('Last check ' + when(c.checked) + ': ' + c.message);
    $('cookiestate').innerHTML = lines.map(esc).join('<br>');
    $('cookiehelp').open = !c.present;
  }

  $('cookiefile').onchange = function () {
    var f = this.files[0];
    if (!f) return;
    f.text().then(function (t) { $('cookietext').value = t; saveCookies(); });
    this.value = '';
  };
  function saveCookies() {
    var text = $('cookietext').value.trim();
    if (!text) { hint('cookiehint', 'Paste the cookies.txt text or upload the file first.', true); return; }
    hint('cookiehint', 'Saving and checking with YouTube…');
    api('cookies', { method: 'PUT', body: { text: text } }).then(function (c) {
      $('cookietext').value = '';
      renderCookies(c);
      hint('cookiehint', c.ok ? 'Saved. YouTube accepts these cookies.' : 'Saved, but ' + (c.message || 'the check failed.'), !c.ok);
    }).catch(function (e) { hint('cookiehint', e.message, true); });
  }
  $('cookiesave').onclick = saveCookies;
  $('cookiecheck').onclick = function () {
    hint('cookiehint', 'Checking with YouTube…');
    api('cookies/check', { method: 'POST' }).then(function (c) { renderCookies(c); hint('cookiehint', c.message, !c.ok); })
      .catch(function (e) { hint('cookiehint', e.message, true); });
  };
  $('cookiedel').onclick = function () {
    if (!confirm('Remove the saved cookies? YouTube downloads may stop working.')) return;
    api('cookies', { method: 'DELETE' }).then(function (c) { renderCookies(c); hint('cookiehint', 'Removed.'); });
  };

  // Jobs ----------------------------------------------------------------------------------

  var STAGE = { downloading: 'Downloading', merging: 'Merging', converting: 'Converting', subtitles: 'Subtitles', tagging: 'Tagging', processing: 'Finishing', zipping: 'Zipping', queued: 'Waiting' };

  // Page numbers under a table: "Showing 51–100 of 386", Prev 1 2 … 8 Next.
  function pager(id, page, per, total, go) {
    var pages = Math.max(1, Math.ceil(total / per)), el = $(id);
    if (total <= per) { el.innerHTML = total ? '<span class="pinfo">' + total + ' in all</span>' : ''; return; }
    var nums = [], i;
    for (i = 1; i <= pages; i++) if (i === 1 || i === pages || Math.abs(i - page) <= 1) nums.push(i); else if (nums[nums.length - 1] !== '…') nums.push('…');
    el.innerHTML = '<span class="pinfo">Showing ' + ((page - 1) * per + 1) + '–' + Math.min(page * per, total) + ' of ' + total + '</span><span class="pbtns">' +
      '<button type="button" data-p="' + (page - 1) + '"' + (page === 1 ? ' disabled' : '') + '>‹ Prev</button>' +
      nums.map(function (n) { return n === '…' ? '<span>…</span>' : '<button type="button" data-p="' + n + '"' + (n === page ? ' class="on" aria-current="page"' : '') + '>' + n + '</button>'; }).join('') +
      '<button type="button" data-p="' + (page + 1) + '"' + (page === pages ? ' disabled' : '') + '>Next ›</button></span>';
    el.onclick = function (e) { var p = +e.target.getAttribute('data-p'); if (p >= 1 && p <= pages && p !== page) go(p); };
  }

  var JOBS_PER = 20, jobPage = 1, lastJobs = [];
  function isMedia(name) { return /\.(mp4|webm|mkv|mov|m4v|mp3|m4a|opus|ogg|wav|flac|aac)$/i.test(name || ''); }
  function renderJobs(r) {
    if (r) lastJobs = r.jobs;
    var total = lastJobs.length, pages = Math.max(1, Math.ceil(total / JOBS_PER));
    if (jobPage > pages) jobPage = pages;
    var rows = lastJobs.slice((jobPage - 1) * JOBS_PER, jobPage * JOBS_PER).map(function (j) {
      var st = j.status === 'running' ? (STAGE[j.stage] || 'Working') + (j.percent != null ? ' ' + Math.floor(j.percent) + '%' : '')
        : j.status === 'done' ? 'Ready · ' + bytes(j.size) : j.status === 'error' ? 'Failed' : j.status === 'queued' ? 'Waiting' : 'Cancelled';
      var cls = j.status === 'done' ? 'ok' : j.status === 'error' ? 'bad' : '';
      var ready = j.status === 'done' && j.filename;
      var acts = (ready && isMedia(j.filename) ? '<button class="abtn primary" data-play="' + esc(j.id) + '" data-title="' + esc(j.title || j.filename) + '" data-name="' + esc(j.filename) + '">▶ Play</button>' : '') +
        (ready ? '<a class="abtn" href="api/admin/jobs/' + encodeURIComponent(j.id) + '/file?admin_token=' + encodeURIComponent(token) + '" target="_blank" rel="noopener">Open</a>' : '') +
        '<button class="abtn danger" data-kill="' + esc(j.id) + '">' + (j.status === 'running' || j.status === 'queued' ? 'Cancel' : 'Delete') + '</button>';
      return '<tr><td class="title"><b>' + esc(j.title || j.url) + '</b><a href="' + esc(j.url) + '" target="_blank" rel="noopener">' + esc(j.url) + '</a>' +
        (j.error ? '<span class="bad">' + esc(j.error) + '</span>' : '') + '</td>' +
        '<td class="mono">' + esc(j.source) + '<br>' + esc(j.client) + '</td><td class="mono">' + esc(j.label) + '</td>' +
        '<td class="mono ' + cls + '">' + esc(st) + '<br>' + esc(when(j.created)) + '</td>' +
        '<td><div class="rowacts">' + acts + '</div></td></tr>';
    });
    $('jobs').tBodies[0].innerHTML = rows.join('') || '<tr><td colspan="5" class="empty">Nothing on the server right now.</td></tr>';
    pager('jobpager', jobPage, JOBS_PER, total, function (p) { jobPage = p; renderJobs(); });
  }
  $('jobs').addEventListener('click', function (e) {
    var id = e.target.getAttribute('data-kill');
    if (id) api('jobs/' + encodeURIComponent(id), { method: 'DELETE' }).then(refresh);
    var play = e.target.getAttribute('data-play');
    if (play) openPlayer(play, e.target.getAttribute('data-title'), e.target.getAttribute('data-name'));
  });

  function openPlayer(id, title, name) {
    var src = 'api/admin/jobs/' + encodeURIComponent(id) + '/file?admin_token=' + encodeURIComponent(token);
    var audio = /\.(mp3|m4a|opus|ogg|wav|flac|aac)$/i.test(name);
    $('playertitle').textContent = title;
    $('playerbody').innerHTML = audio ? '<audio controls autoplay preload="metadata"></audio>' : '<video controls autoplay playsinline preload="metadata"></video>';
    var m = $('playerbody').firstChild;
    m.src = src;
    m.onerror = function () { $('playerbody').insertAdjacentHTML('beforeend', '<p class="sub">This browser can\'t play ' + esc(name.split('.').pop().toUpperCase()) + ' files. Use Open to download it instead.</p>'); };
    show('player', true);
  }
  function closePlayer() { $('playerbody').innerHTML = ''; show('player', false); }
  $('playerclose').onclick = closePlayer;
  $('player').addEventListener('click', function (e) { if (e.target === this) closePlayer(); });
  document.addEventListener('keydown', function (e) { if (e.key === 'Escape' && !$('player').classList.contains('hidden')) closePlayer(); });
  $('clearfiles').onclick = function () {
    if (!confirm('Delete every finished download on the server? People who haven\'t saved theirs yet will lose them.')) return;
    api('files/clear', { method: 'POST' }).then(function (r) { refresh(); hint('serverhint', 'Deleted ' + r.deleted + ' finished downloads.'); });
  };

  // Log -----------------------------------------------------------------------------------

  var searchTimer = null, LOG_PER = 50, logPage = 1;
  function loadLog(reset) {
    if (reset) logPage = 1;
    var q = $('search').value.trim();
    api('requests?limit=' + LOG_PER + '&offset=' + (logPage - 1) * LOG_PER + (q ? '&q=' + encodeURIComponent(q) : '')).then(function (r) {
      var html = r.requests.map(function (x) {
        var res = x.status === 'done' ? '<span class="ok">Done · ' + esc(bytes(x.size)) + '</span>' :
          x.status === 'error' ? '<span class="bad" title="' + esc(x.error) + '">Failed</span><br><small>' + esc((x.error || '').slice(0, 80)) + '</small>' : esc(x.status);
        return '<tr><td class="mono">' + esc(when(x.ts)) + '</td><td class="title"><b>' + esc(x.title || '—') + '</b><a href="' + esc(x.url) + '" target="_blank" rel="noopener">' + esc(x.url) + '</a></td>' +
          '<td class="mono">' + esc(x.site || '') + '</td><td class="mono">' + esc(x.source || '') + (x.client_version ? ' ' + esc(x.client_version) : '') + '</td>' +
          '<td class="mono">' + esc(x.label || '') + '</td><td class="mono">' + res + '</td><td class="mono" title="' + esc(x.user_agent) + '"><div class="ipcell">' + (x.country ? '<span title="' + esc(country(x.country)) + '">' + flag(x.country) + '</span>' : '') + esc(x.ip || '') +
          (x.ip ? (x.banned ? '<button type="button" class="abtn small ok" data-unban="' + esc(x.ip) + '">Blocked · undo</button>' : '<button type="button" class="abtn small danger" data-ban="' + esc(x.ip) + '">Block</button>') : '') + '</div></td></tr>';
      }).join('');
      $('log').tBodies[0].innerHTML = html || '<tr><td colspan="7" class="empty">' + (q ? 'Nothing matches that search.' : 'No requests yet.') + '</td></tr>';
      pager('logpager', logPage, LOG_PER, r.total || 0, function (p) { logPage = p; loadLog(false); $('log').scrollIntoView({ block: 'start' }); });
    }).catch(function () { });
  }
  $('search').oninput = function () { clearTimeout(searchTimer); searchTimer = setTimeout(function () { loadLog(true); }, 300); };
  $('export').onclick = function (e) { e.preventDefault(); location.href = 'api/admin/requests/export?admin_token=' + encodeURIComponent(token); };
  $('clearlog').onclick = function () {
    if (!confirm('Clear the whole request log?')) return;
    api('requests', { method: 'DELETE' }).then(function () { loadLog(true); refresh(); });
  };

  // Server --------------------------------------------------------------------------------

  function waitForServer() {
    var tries = 0, wait = setInterval(function () {
      fetch('api/health').then(function (r) { return r.ok ? r.json() : null; }).then(function (h) {
        if (h && tries > 1) { clearInterval(wait); hint('serverhint', 'Done. The server is running yt-dlp ' + h.yt_dlp + '.'); refresh(); }
      }).catch(function () { });
      if (++tries > 60) { clearInterval(wait); hint('serverhint', "The server didn't come back. Check that Docker restarts it.", true); }
    }, 2000);
  }
  $('update').onclick = function () {
    $('update').disabled = true;
    hint('serverhint', 'Updating yt-dlp. This can take a minute…');
    api('ytdlp/update?restart=true', { method: 'POST' }).then(function (r) {
      if (!r.restart_needed) hint('serverhint', 'yt-dlp ' + r.installed + ' is already the newest. Nothing to do.');
      else if (r.restart === 'now') { hint('serverhint', 'Installed yt-dlp ' + r.installed + '. Restarting to load it…'); waitForServer(); }
      else hint('serverhint', 'Installed yt-dlp ' + r.installed + '. Downloads are running, so press Restart server when they finish (or it restarts tonight).');
    }).catch(function (e) { hint('serverhint', e.message, true); }).finally(function () { $('update').disabled = false; });
  };
  $('f-backup').addEventListener('submit', function (e) {
    e.preventDefault();
    var a = document.createElement('a');
    a.href = 'api/admin/backup?releases=' + ($('backuprel').checked ? 'true' : 'false') + '&admin_token=' + encodeURIComponent(token);
    a.download = '';
    document.body.appendChild(a); a.click(); a.remove();
  });
  $('restart').onclick = function () {
    var go = function (force) {
      return api('restart' + (force ? '?force=true' : ''), { method: 'POST' }).then(function () {
        hint('serverhint', 'Restarting…');
        var tries = 0, wait = setInterval(function () {
          fetch('api/health').then(function (r) { if (r.ok) { clearInterval(wait); hint('serverhint', 'Back up.'); refresh(); } }).catch(function () { });
          if (++tries > 60) { clearInterval(wait); hint('serverhint', "The server didn't come back. Check that Docker restarts it.", true); }
        }, 2000);
      });
    };
    go(false).catch(function (e) {
      if (e.status === 409 && confirm(e.message + '\n\nRestart anyway? Running downloads will stop.')) go(true);
      else if (e.status !== 409) hint('serverhint', e.message, true);
    });
  };

  // Downloads offered on the page -------------------------------------------------------

  function day(ts) { return ts ? new Date(ts * 1000).toLocaleDateString(undefined, { month: 'short', day: 'numeric', year: 'numeric' }) : ''; }

  function loadReleases() {
    api('releases').then(function (r) {
      var p = r.public;
      $('uplimit').textContent = 'Up to ' + r.max_upload_mb + ' MB per file.';
      var a = p.android;
      renderApks(a);
      renderSystems(p.helper);
      renderExtension(p.extension);
      $('filecount').textContent = p.files.length ? p.files.length + (p.files.length === 1 ? ' file' : ' files') : '';
      $('files').tBodies[0].innerHTML = p.files.map(function (f) {
        return '<tr><td class="title"><b>' + esc(f.title) + '</b><a href="' + esc(f.url) + '">' + esc(f.name) + '</a></td><td class="mono">' + esc(bytes(f.size)) + '</td><td class="mono">' + esc(day(f.updated)) + '</td>' +
          '<td><div class="rowacts"><button class="abtn" type="button" data-copy="' + esc(f.url) + '">Copy link</button>' +
          '<button class="abtn" type="button" data-retitle="' + esc(f.name) + '" data-title="' + esc(f.title) + '">Rename</button>' +
          '<a class="abtn" href="' + esc(f.url) + '" target="_blank" rel="noopener">Open</a>' +
          '<button class="abtn danger" type="button" data-delfile="' + esc(f.name) + '">Remove</button></div></td></tr>';
      }).join('') || '<tr><td colspan="4" class="empty">No other files yet. Drop some below.</td></tr>';
    }).catch(function () { });
  }

  // XHR, not fetch, so big uploads can show progress.
  // XHR, not fetch, so big uploads can show progress. The progress box sits under the form's
  // buttons: bytes sent, speed and time left, then "checking" while the server works.
  var busy = false;
  function upload(path, form, done, data) {
    if (busy) return;
    var box = form.querySelector('.progress');
    if (!box) { box = document.createElement('div'); box.className = 'progress'; form.appendChild(box); }
    box.className = 'progress';
    box.innerHTML = '<div class="upbar"><i></i></div><div class="ptext"></div>';
    var bar = box.querySelector('i'), text = box.querySelector('.ptext');
    var btn = form.querySelector('button[type=submit]'), label = btn.textContent;
    var started = Date.now();
    function setText(msg, cls) { text.textContent = msg; box.className = 'progress' + (cls ? ' ' + cls : ''); }
    function finish() { busy = false; btn.disabled = false; btn.textContent = label; }
    busy = true; btn.disabled = true; btn.textContent = 'Publishing…';
    hint('relhint', '');
    setText('Starting upload…');

    // Files go up first in pieces (so a proxy's size limit never blocks them), then the form
    // is published with references to them.
    var fd = data || new FormData(form), files = [], rest = [];
    fd.forEach(function (v, k) { if (v instanceof File && v.name && v.size > 0) files.push([k, v]); else if (!(v instanceof File)) rest.push([k, v]); });
    var total = files.reduce(function (n, f) { return n + f[1].size; }, 0), sent = 0;

    function fail(msg) { finish(); box.classList.remove('working'); setText(msg, 'bad'); }
    function req(method, url, body, cb, onprog) {
      var x = new XMLHttpRequest();
      x.open(method, url);
      x.setRequestHeader('X-Admin-Token', token);
      if (onprog) x.upload.onprogress = onprog;
      x.onload = function () { var d = {}; try { d = JSON.parse(x.responseText); } catch (e) { } cb(x.status, d); };
      x.onerror = function () { cb(0, {}); };
      x.send(body);
    }
    function progress(done) {
      var pct = total ? done / total * 100 : 100, secs = (Date.now() - started) / 1000, speed = done / Math.max(secs, 0.1);
      var left = speed > 0 ? (total - done) / speed : 0;
      bar.style.width = pct + '%';
      setText('Step 1 of 2 · Uploading ' + Math.floor(pct) + '% · ' + bytes(done) + ' of ' + bytes(total) + ' · ' +
        bytes(speed) + '/s' + (left > 1 ? ' · about ' + (left > 90 ? Math.round(left / 60) + ' min' : Math.round(left) + ' s') + ' left' : ''));
    }

    var chunk = 8 * 1024 * 1024, MIN = 256 * 1024, staged = [];
    function sendFile(i) {
      if (i >= files.length) return publish();
      var field = files[i][0], file = files[i][1];
      req('POST', 'api/admin/releases/uploads', null, function (st, d) {
        if (st === 401) { setToken(null); screen('login'); return; }
        if (st !== 200 || !d.id) return fail('Couldn\'t start the upload (error ' + st + ').');
        var offset = 0, tries = 0;
        (function piece() {
          if (offset >= file.size) { staged.push([field, JSON.stringify({ id: d.id, name: file.name, field: field })]); return sendFile(i + 1); }
          var end = Math.min(offset + chunk, file.size);
          req('PUT', 'api/admin/releases/uploads/' + d.id + '?offset=' + offset, file.slice(offset, end), function (st2, r) {
            if (st2 === 200) { sent += end - offset; offset = end; tries = 0; return piece(); }
            if (st2 === 413 && chunk > MIN) { chunk = Math.max(MIN, chunk / 4); return piece(); }   // the proxy wants smaller pieces
            if (st2 === 409 && typeof r.size === 'number') { sent += r.size - offset; offset = r.size; return piece(); }
            if ((st2 === 0 || st2 >= 500) && tries++ < 5) return setTimeout(piece, 1000 * tries);   // brief network trouble
            if (st2 === 401) { setToken(null); screen('login'); return; }
            fail(typeof r.detail === 'string' ? r.detail : st2 === 413 ? 'The proxy in front of the server refuses even small uploads.' : 'The upload stopped (error ' + st2 + '). Try again.');
          }, function (e) { if (e.lengthComputable) progress(sent + e.loaded); });
        })();
      });
    }

    function publish() {
      bar.style.width = '100%'; box.classList.add('working');
      setText(path === 'android' ? 'Step 2 of 2 · Checking the APK and publishing…' : 'Step 2 of 2 · Saving and publishing…');
      var body = new FormData();
      rest.forEach(function (kv) { body.append(kv[0], kv[1]); });
      staged.forEach(function (kv) { body.append('staged', kv[1]); });
      req('POST', 'api/admin/releases/' + path, body, function (st, d) {
        finish(); box.classList.remove('working');
        if (st === 401) { setToken(null); screen('login'); return; }
        if (st === 0) { setText('Lost the connection while publishing. Reload to see if it went through.', 'bad'); return; }
        if (st >= 400) { setText(typeof d.detail === 'string' ? d.detail : 'Publishing failed (error ' + st + ').', 'bad'); return; }
        setText('✓ ' + (d.apks ? 'Published version ' + d.version + ' (' + d.version_code + ') for ' + d.apks.join(', ') + '. Phones will see the update.'
        : d.names ? 'Uploaded ' + d.names.join(', ') + (d.names.length > 1 ? '. They\'re' : '. It\'s') + ' on the main page now.'
        : d.packages ? 'Saved extension' + (d.version ? ' ' + d.version : '') + '. ' + (d.stores.length ? 'Store links: ' + d.stores.join(', ') + '. ' : 'No store links. ') + (d.packages.length ? 'New files: ' + d.packages.join(', ') + '.' : '')
        : d.systems ? 'Published helper ' + (d.version || '') + (d.systems.length ? ' for ' + d.systems.join(', ') : '') + '.' : 'Published.'), 'ok');
        form.reset(); loadReleases(); if (done) done();
      });
    }

    if (files.length) { progress(0); sendFile(0); } else publish();
  }

  // PC helper: one box for every system's file. The page guesses each file's system from its
  // first bytes (the server checks again); the version is read on the server.
  var SYSTEMS = [['windows', 'Windows'], ['mac', 'Mac'], ['linux-x64', 'Linux x64'], ['linux-arm64', 'Linux ARM64']];
  var ABIS = [['universal', 'Universal', 'Works on every phone'], ['arm64-v8a', 'arm64-v8a', 'Most phones'],
    ['armeabi-v7a', 'armeabi-v7a', 'Older phones'], ['x86_64', 'x86_64', 'Emulators, some tablets']];
  function renderApks(a) {
    var apks = (a && a.apks) || {};
    $('appver').innerHTML = a ? esc('Version ' + a.version + ' · code ' + a.version_code + ' · ' + day(a.updated)) +
      ' · <a href="app/version.json" target="_blank">version.json</a>' : 'Nothing published yet';
    $('apkgrid').innerHTML = ABIS.map(function (x) {
      var f = apks[x[0]];
      return '<div class="sys' + (f ? ' have' : '') + '"><b>' + x[1] + '</b><span class="hintline">' + x[2] + '</span><span>' +
        (f ? esc('Version ' + a.version + ' · ' + bytes(f.size)) : 'Not uploaded') + '</span>' +
        (f ? '<a href="' + esc(f.url) + '">download</a>' : '') + '</div>';
    }).join('');
    var bits = [];
    if (a && a.changelog.length) bits.push('<div class="label" style="margin:16px 0 6px">What\'s new</div><ul class="changes">' + a.changelog.map(function (c) { return '<li>' + esc(c) + '</li>'; }).join('') + '</ul>');
    if (a && a.min_supported) bits.push('<p class="sub" style="margin:12px 0 0">Phones older than version code ' + esc(a.min_supported) + ' have to update.</p>');
    if (a && !apks.universal) bits.push('<p class="sub" style="margin:12px 0 0">Tip: also publish <code>app-universal-release.apk</code>. The site\'s Download APK button gives that one, so every phone can install it.</p>');
    $('appnotes').innerHTML = bits.join('');
  }

  function renderSystems(h) {
    var b = (h && h.builds) || {};
    $('sysgrid').innerHTML = SYSTEMS.map(function (s) {
      var x = b[s[0]];
      return '<div class="sys' + (x ? ' have' : '') + '"><b>' + s[1] + '</b><span>' +
        (x ? esc((h.version ? 'Version ' + h.version + ' · ' : '') + bytes(x.size)) : 'Not uploaded') + '</span>' +
        (x ? '<a href="' + esc(x.url) + '">download</a>' : '') + '</div>';
    }).join('');
    if (h) { $('f-helper').notes.value = h.notes || ''; }
  }
  function sniff(file, cb) {
    var r = new FileReader();
    r.onload = function () {
      var b = new Uint8Array(r.result), hex = Array.prototype.map.call(b.slice(0, 4), function (x) { return ('0' + x.toString(16)).slice(-2); }).join('');
      if (b[0] === 0x4d && b[1] === 0x5a) return cb('Windows');
      if (['cafebabe', 'feedface', 'feedfacf', 'cefaedfe', 'cffaedfe'].indexOf(hex) >= 0) return cb('Mac');
      if (hex === '7f454c46') { var m = b[18] | (b[19] << 8); return cb(m === 0x3e ? 'Linux x64' : m === 0xb7 ? 'Linux ARM64' : null); }
      cb(null);
    };
    r.onerror = function () { cb(null); };
    r.readAsArrayBuffer(file.slice(0, 64));
  }
  var helperFiles = [];
  function showHelpers() {
    $('helperlist').innerHTML = helperFiles.map(function (f, i) {
      var tag = f.sys ? '<span class="systag">' + esc(f.sys) + '</span>' : '<span class="systag bad">not a helper file</span>';
      return '<li><span>' + esc(f.file.name) + '</span>' + tag + '<span class="mono">' + esc(bytes(f.file.size)) + '</span><button type="button" class="abtn danger" data-unhelper="' + i + '">Remove</button></li>';
    }).join('');
  }
  function addHelpers(list) {
    [].forEach.call(list, function (file) {
      if (helperFiles.some(function (g) { return g.file.name === file.name; })) return;
      var row = { file: file, sys: '…' };
      helperFiles.push(row);
      sniff(file, function (sys) { row.sys = sys; showHelpers(); });
    });
    showHelpers();
  }
  $('helperfiles').addEventListener('change', function () { addHelpers(this.files); this.value = ''; });
  (function (drop) {
    ['dragenter', 'dragover'].forEach(function (t) { drop.addEventListener(t, function (e) { e.preventDefault(); drop.classList.add('over'); }); });
    ['dragleave', 'drop'].forEach(function (t) { drop.addEventListener(t, function () { drop.classList.remove('over'); }); });
    drop.addEventListener('drop', function (e) { e.preventDefault(); addHelpers(e.dataTransfer.files); });
  })($('helperdrop'));
  $('helperlist').addEventListener('click', function (e) {
    var i = e.target.getAttribute('data-unhelper'); if (i === null) return;
    helperFiles.splice(+i, 1); showHelpers();
  });
  $('f-helper').addEventListener('submit', function (e) {
    e.preventDefault();
    var bad = helperFiles.filter(function (f) { return !f.sys; });
    if (bad.length) { alert(bad[0].file.name + " isn't a helper file. Remove it first."); return; }
    var data = new FormData(this);
    helperFiles.forEach(function (f) { data.append('helpers', f.file); });
    if (!helperFiles.length && !confirm('No new files picked. Only save the version and note?')) return;
    upload('helper', this, function () { helperFiles = []; showHelpers(); }, data);
  });

  // Android: drop APKs (and optionally the version.json from package_release.py).
  var apkFiles = [];
  function showApks() {
    $('apklist').innerHTML = apkFiles.map(function (f, i) {
      return '<li><span>' + esc(f.name) + '</span><span class="mono">' + esc(bytes(f.size)) + '</span><button type="button" class="abtn danger" data-unpick="' + i + '">Remove</button></li>';
    }).join('');
  }
  function addApks(list) {
    [].forEach.call(list, function (f) {
      if (/\.(apk|json)$/i.test(f.name) && !apkFiles.some(function (g) { return g.name === f.name; })) apkFiles.push(f);
    });
    showApks();
  }
  $('apkfiles').addEventListener('change', function () { addApks(this.files); this.value = ''; });
  var drop = $('apkdrop');
  ['dragenter', 'dragover'].forEach(function (t) { drop.addEventListener(t, function (e) { e.preventDefault(); drop.classList.add('over'); }); });
  ['dragleave', 'drop'].forEach(function (t) { drop.addEventListener(t, function () { drop.classList.remove('over'); }); });
  drop.addEventListener('drop', function (e) { e.preventDefault(); addApks(e.dataTransfer.files); });
  $('apklist').addEventListener('click', function (e) {
    var i = e.target.getAttribute('data-unpick'); if (i === null) return;
    apkFiles.splice(+i, 1); showApks();
  });
  $('f-android').addEventListener('submit', function (e) {
    e.preventDefault();
    if (!apkFiles.some(function (f) { return /\.apk$/i.test(f.name); })) { hint('relhint', 'Pick the APK first.', true); return; }
    var data = new FormData(this);
    apkFiles.forEach(function (f) { data.append(/\.json$/i.test(f.name) ? 'version_file' : 'apks', f); });
    upload('android', this, function () { apkFiles = []; showApks(); }, data);
  });

  // Extension: a store link per browser, plus the files to install by hand.
  var STORES = [['chrome', 'Chrome Web Store', 'Chrome, Brave, Vivaldi'], ['edge', 'Edge Add-ons', 'Microsoft Edge'],
    ['firefox', 'Firefox Add-ons', 'Firefox'], ['opera', 'Opera Add-ons', 'Opera']];
  var PACKAGES = [['chromium', 'Chrome .zip', 'Chrome, Edge, Brave, Opera'], ['firefox', 'Firefox file', 'Firefox']];
  function renderExtension(x) {
    var st = (x && x.stores) || {}, b = (x && x.builds) || {}, f = $('f-extension');
    $('extver').textContent = x ? [x.version ? 'Version ' + x.version : '', day(x.updated)].filter(Boolean).join(' · ') : 'Nothing published yet';
    $('storegrid').innerHTML = STORES.map(function (s) {
      var u = st[s[0]];
      return '<div class="sys' + (u ? ' have' : '') + '"><b><i class="bdot ' + s[0] + '"></i>' + s[1] + '</b><span class="hintline">' + s[2] + '</span><span>' + (u ? 'Linked' : 'No link') + '</span>' +
        (u ? '<div class="sysacts"><a href="' + esc(u) + '" target="_blank" rel="noopener">open</a><button type="button" class="abtn danger" data-extpart="store/' + s[0] + '">Remove</button></div>' : '') + '</div>';
    }).join('');
    $('pkggrid').innerHTML = PACKAGES.map(function (s) {
      var p = b[s[0]];
      return '<div class="sys' + (p ? ' have' : '') + '"><b>' + s[1] + '</b><span class="hintline">' + s[2] + '</span><span>' +
        (p ? esc((p.version ? 'Version ' + p.version + ' · ' : '') + bytes(p.size)) : s[0] === 'firefox' ? 'Not available yet' : 'Not uploaded') + '</span>' +
        (p ? '<div class="sysacts"><a href="' + esc(p.url) + '">download</a><button type="button" class="abtn danger" data-extpart="package/' + s[0] + '">Remove</button></div>' : '') + '</div>';
    }).join('');
    STORES.forEach(function (s) { f['store_' + s[0]].value = st[s[0]] || ''; });
    f.notes.value = (x && x.notes) || '';
    f.version.value = '';
  }
  document.addEventListener('click', function (e) {
    var part = e.target.getAttribute('data-extpart');
    if (part && confirm('Remove this from the extension downloads?')) api('releases/extension/' + part, { method: 'DELETE' }).then(loadReleases);
  });
  var extFiles = [];
  function extTag(f) { return /\.xpi$/i.test(f.name) ? 'Firefox' : /\.(zip|crx)$/i.test(f.name) ? 'checked on publish' : null; }
  function showExt() {
    $('extlist').innerHTML = extFiles.map(function (f, i) {
      var t = extTag(f);
      return '<li><span>' + esc(f.name) + '</span>' + (t ? '<span class="systag">' + esc(t) + '</span>' : '<span class="systag bad">not an extension file</span>') +
        '<span class="mono">' + esc(bytes(f.size)) + '</span><button type="button" class="abtn danger" data-unext="' + i + '">Remove</button></li>';
    }).join('');
  }
  function addExt(list) {
    [].forEach.call(list, function (f) { if (!extFiles.some(function (g) { return g.name === f.name; })) extFiles.push(f); });
    showExt();
  }
  $('extfiles').addEventListener('change', function () { addExt(this.files); this.value = ''; });
  (function (drop) {
    ['dragenter', 'dragover'].forEach(function (t) { drop.addEventListener(t, function (e) { e.preventDefault(); drop.classList.add('over'); }); });
    ['dragleave', 'drop'].forEach(function (t) { drop.addEventListener(t, function () { drop.classList.remove('over'); }); });
    drop.addEventListener('drop', function (e) { e.preventDefault(); addExt(e.dataTransfer.files); });
  })($('extdrop'));
  $('extlist').addEventListener('click', function (e) {
    var i = e.target.getAttribute('data-unext'); if (i === null) return;
    extFiles.splice(+i, 1); showExt();
  });
  $('f-extension').addEventListener('submit', function (e) {
    e.preventDefault();
    var bad = extFiles.filter(function (f) { return !extTag(f); });
    if (bad.length) { alert(bad[0].name + " isn't a .zip, .crx or .xpi. Remove it first."); return; }
    var data = new FormData(this);
    extFiles.forEach(function (f) { data.append('packages', f); });
    upload('extension', this, function () { extFiles = []; showExt(); }, data);
  });

  // Other files: drop any files, each gets a title from its name that can be edited first.
  var otherFiles = [];
  function niceTitle(name) {
    var t = name.replace(/\.[^.]+$/, '').replace(/[-_]+/g, ' ').trim();
    return t ? t.charAt(0).toUpperCase() + t.slice(1) : name;
  }
  function showOther() {
    $('otherlist').innerHTML = otherFiles.map(function (f, i) {
      return '<li><input class="ftitle" data-i="' + i + '" value="' + esc(f.title) + '" aria-label="Title for ' + esc(f.file.name) + '" maxlength="120">' +
        '<span class="fname">' + esc(f.file.name) + '</span><span class="mono">' + esc(bytes(f.file.size)) + '</span>' +
        '<button type="button" class="abtn danger" data-unother="' + i + '">Remove</button></li>';
    }).join('');
  }
  function addOther(list) {
    [].forEach.call(list, function (file) {
      if (!otherFiles.some(function (g) { return g.file.name === file.name; })) otherFiles.push({ file: file, title: niceTitle(file.name) });
    });
    showOther();
  }
  $('otherfiles').addEventListener('change', function () { addOther(this.files); this.value = ''; });
  (function (drop) {
    ['dragenter', 'dragover'].forEach(function (t) { drop.addEventListener(t, function (e) { e.preventDefault(); drop.classList.add('over'); }); });
    ['dragleave', 'drop'].forEach(function (t) { drop.addEventListener(t, function () { drop.classList.remove('over'); }); });
    drop.addEventListener('drop', function (e) { e.preventDefault(); addOther(e.dataTransfer.files); });
  })($('otherdrop'));
  $('otherlist').addEventListener('input', function (e) { var i = e.target.getAttribute('data-i'); if (i !== null) otherFiles[+i].title = e.target.value; });
  $('otherlist').addEventListener('click', function (e) {
    var i = e.target.getAttribute('data-unother'); if (i === null) return;
    otherFiles.splice(+i, 1); showOther();
  });
  $('f-files').addEventListener('submit', function (e) {
    e.preventDefault();
    if (!otherFiles.length) { alert('Pick a file first.'); return; }
    var data = new FormData();
    otherFiles.forEach(function (f) { data.append('files', f.file); data.append('titles', f.title); });
    upload('files', this, function () { otherFiles = []; showOther(); }, data);
  });
  $('files').addEventListener('click', function (e) {
    var t = e.target, url = t.getAttribute('data-copy'), name = t.getAttribute('data-retitle');
    if (url) {
      var full = new URL(url, location.href).href;
      (navigator.clipboard ? navigator.clipboard.writeText(full) : Promise.reject()).then(function () {
        t.textContent = 'Copied'; setTimeout(function () { t.textContent = 'Copy link'; }, 1500);
      }, function () { prompt('Copy this link:', full); });
    }
    if (name) {
      var title = prompt('Title shown on the main page:', t.getAttribute('data-title') || '');
      if (title === null) return;
      api('releases/files/' + encodeURIComponent(name), { method: 'PATCH', body: { title: title } }).then(loadReleases);
    }
  });
  document.addEventListener('click', function (e) {
    var slot = e.target.getAttribute('data-unpublish'), file = e.target.getAttribute('data-delfile');
    if (slot && confirm('Stop offering this download on the page?')) api('releases/' + slot, { method: 'DELETE' }).then(loadReleases);
    if (file && confirm('Remove ' + file + '?')) api('releases/files/' + encodeURIComponent(file), { method: 'DELETE' }).then(loadReleases);
  });

  // Countries -----------------------------------------------------------------------------

  var regionNames = null;
  try { regionNames = new Intl.DisplayNames(['en'], { type: 'region' }); } catch (e) { }
  function country(code) { if (!code) return 'Unknown'; try { return (regionNames && regionNames.of(code)) || code; } catch (e) { return code; } }
  function flag(code) {
    if (!code || !/^[A-Z]{2}$/.test(code)) return '🌐';
    return String.fromCodePoint(0x1f1e6 + code.charCodeAt(0) - 65, 0x1f1e6 + code.charCodeAt(1) - 65);
  }

  // Stats ---------------------------------------------------------------------------------

  var sdays = 30;
  var SOURCES = { web: 'Website', app: 'Android app', extension: 'Extension', 'legacy-extension': 'Old extension', 'legacy-web': 'Old website', api: 'API' };
  function barlist(id, rows, label, empty) {
    var max = Math.max(1, Math.max.apply(null, rows.map(function (r) { return r.n; })));
    $(id).innerHTML = rows.map(function (r) {
      return '<div class="brow"><span class="bname">' + label(r) + '</span><span class="btrack"><i style="width:' + Math.max(2, r.n / max * 100) + '%"></i></span><span class="mono">' + r.n + '</span></div>';
    }).join('') || '<p class="empty">' + esc(empty || 'Nothing yet.') + '</p>';
  }
  function loadStats() {
    api('stats?days=' + sdays).then(function (r) {
      var t = r.installs.totals || {}, total = r.sources.reduce(function (a, x) { return a + x.n; }, 0);
      var known = r.countries.filter(function (c) { return c.country; });
      $('stiles').innerHTML = tile('Downloads', total) + tile('App downloads', t.android || 0) + tile('Extension installs', t.extension || 0) +
        tile('Helper downloads', t.helper || 0) + tile('Countries', known.length);
      // Stacked bars per day.
      var d = r.installs.daily, max = Math.max(1, Math.max.apply(null, d.map(function (x) { return x.android + x.extension + x.helper; })));
      var W = 700, H = 160, gap = d.length > 40 ? 1 : 3, bw = W / d.length - gap, sum = 0;
      var bars = d.map(function (x, i) {
        var px = i * (bw + gap), y = H - 20, parts = '';
        ['android', 'extension', 'helper'].forEach(function (k) {
          var h = x[k] / max * (H - 20); y -= h; sum += x[k];
          if (h) parts += '<rect x="' + px + '" y="' + y + '" width="' + bw + '" height="' + h + '" class="c-' + k + '"/>';
        });
        var every = d.length <= 14 ? 1 : d.length <= 31 ? 3 : 10;
        return '<g><title>' + esc(x.day + ': ' + x.android + ' app, ' + x.extension + ' extension, ' + x.helper + ' helper') + '</title>' +
          '<rect x="' + px + '" y="0" width="' + bw + '" height="' + (H - 20) + '" class="hit"/>' + parts +
          (i % every === 0 ? '<text x="' + (px + bw / 2) + '" y="' + (H - 4) + '">' + x.day.slice(5).replace('-', '/') + '</text>' : '') + '</g>';
      }).join('');
      $('ichart').innerHTML = '<svg viewBox="0 0 ' + W + ' ' + H + '" preserveAspectRatio="none" role="img" aria-label="Installs per day"><text x="0" y="10" class="ymax">' + max + '</text>' + bars + '</svg>';
      $('isum').textContent = sum + ' in ' + sdays + ' days';
      var DETAIL = { universal: 'APK universal', 'arm64-v8a': 'APK arm64', 'armeabi-v7a': 'APK armv7', x86_64: 'APK x86_64', zip: 'Extension .zip', 'firefox-file': 'Firefox file',
        chrome: 'Chrome Web Store', edge: 'Edge Add-ons', firefox: 'Firefox Add-ons', opera: 'Opera Add-ons', store: 'Store link', windows: 'Helper Windows', mac: 'Helper Mac',
        'linux-x64': 'Helper Linux x64', 'linux-arm64': 'Helper Linux ARM64' };
      $('idetail').innerHTML = r.installs.detail.map(function (x) { return '<span class="chip">' + esc(DETAIL[x.detail] || x.kind + ' ' + x.detail) + ' <b>' + x.n + '</b></span>'; }).join('');
      barlist('ssites', r.sites, function (x) { return esc(x.site) + (x.failed ? ' <small class="bad">' + x.failed + ' failed</small>' : ''); }, 'No downloads yet.');
      barlist('scountries', r.countries, function (x) { return flag(x.country) + ' ' + esc(country(x.country)); }, 'No downloads yet.');
      $('cnote').textContent = r.countries.length && !known.length ? 'Countries come from Cloudflare (the CF-IPCountry header). Downloads from before this update, or without Cloudflare in front, show as Unknown.' : '';
      barlist('ssources', r.sources, function (x) { return esc(SOURCES[x.source] || x.source); });
      barlist('skinds', r.kinds, function (x) { return esc(x.kind.charAt(0).toUpperCase() + x.kind.slice(1)); });
    }).catch(function () { });
  }
  $('srange').addEventListener('click', function (e) {
    var n = +e.target.getAttribute('data-days'); if (!n) return;
    sdays = n;
    [].forEach.call(this.children, function (b) { b.setAttribute('aria-checked', String(b === e.target)); });
    loadStats();
  });

  // Site settings, blocking and alerts ------------------------------------------------------

  var current = null, level = 'info';
  function setLevel(l) {
    level = l;
    [].forEach.call($('blevel').children, function (b) { b.setAttribute('aria-checked', String(b.getAttribute('data-level') === l)); });
    preview();
  }
  function preview() {
    var t = $('f-site').banner.value.trim();
    $('bannerprev').innerHTML = t ? '<div class="sitebanner ' + level + '"><span>' + esc(t) + '</span><button type="button" tabindex="-1" aria-hidden="true">×</button></div>' : '<p class="empty">No banner. Visitors see nothing extra.</p>';
  }
  function loadSettings() {
    api('settings').then(function (s) {
      current = s;
      var f = $('f-site');
      f.site_name.value = s.site_name || 'Haul';
      f.contact_email.value = s.contact_email || '';
      f.contact_email.placeholder = s.contact_email_env || 'hello@example.com';
      $('contacthint').textContent = s.contact_email_env ? 'Leave empty to use ' + s.contact_email_env + ' from docker-compose.yml.' : 'Shown on the Contact page.';
      f.banner.value = s.banner || '';
      setLevel(s.banner_level || 'info');
      $('f-limit').daily_limit.value = s.daily_limit || 0;
      show('proxywarn', !s.trust_proxy);
      renderBans(s.banned);
      var a = s.alerts, g = $('f-alerts');
      ['telegram_chat', 'smtp_host', 'smtp_port', 'smtp_user', 'email_from', 'email_name', 'email_to'].forEach(function (k) { g[k].value = a[k] || ''; });
      g.smtp_security.value = a.smtp_security || 'starttls';
      ['cookies', 'disk', 'failures', 'updates'].forEach(function (k) { g[k].checked = !!a[k]; });
      g.telegram_token.value = ''; g.smtp_password.value = '';
      g.telegram_token.placeholder = a.telegram_token ? 'Leave blank to keep current (' + a.telegram_token_hint + ')' : '123456:ABC…';
      g.smtp_password.placeholder = a.smtp_password ? 'Leave blank to keep current (' + a.smtp_password_hint + ')' : '';
      var on = (a.telegram_token && a.telegram_chat) || (a.smtp_host && a.email_to);
      $('nav-alertsetup').classList.toggle('hidden', !!on);
      if (!on) hint('alerthint', 'Not set up yet: alerts only show on the Dashboard.');
      else if (/^Not set up/.test($('alerthint').textContent)) hint('alerthint', '');
    }).catch(function () { });
    api('overview').then(function (o) {
      $('alertsnow').innerHTML = (o.alerts || []).map(function (a) { return '<div class="alert ' + (a.level === 'bad' ? 'bad' : 'warn') + '">' + esc(a.text) + '</div>'; }).join('') ||
        '<p class="empty" style="text-align:left;padding:4px 0">Everything looks fine.</p>';
    }).catch(function () { });
  }
  function renderBans(list) {
    $('bancount').textContent = list.length ? list.length + ' blocked' : '';
    badge('nav-bans', list.length ? String(list.length) : '');
    $('bans').tBodies[0].innerHTML = list.map(function (b) {
      return '<tr><td class="mono">' + esc(b.ip) + '</td><td>' + esc(b.note || '—') + '</td><td class="mono">' + esc(when(b.at)) + '</td>' +
        '<td><div class="rowacts"><button type="button" class="abtn" data-unban="' + esc(b.ip) + '">Unblock</button></div></td></tr>';
    }).join('') || '<tr><td colspan="4" class="empty">Nobody is blocked.</td></tr>';
  }
  $('blevel').addEventListener('click', function (e) { var l = e.target.getAttribute('data-level'); if (l) setLevel(l); });
  $('f-site').banner.addEventListener('input', preview);
  $('f-site').addEventListener('submit', function (e) {
    e.preventDefault();
    var f = this;
    api('settings', { method: 'PUT', body: { site_name: f.site_name.value, contact_email: f.contact_email.value, banner: f.banner.value,
      banner_level: level, daily_limit: (current && current.daily_limit) || 0 } })
      .then(function (s) { current = s; hint('sitehint', '✓ Saved. Visitors see it on their next page load.'); })
      .catch(function (err) { hint('sitehint', err.message, true); });
  });
  $('f-limit').addEventListener('submit', function (e) {
    e.preventDefault();
    var n = Math.max(0, parseInt(this.daily_limit.value, 10) || 0), c = current || {};
    api('settings', { method: 'PUT', body: { site_name: c.site_name || 'Haul', contact_email: c.contact_email || '', banner: c.banner || '',
      banner_level: c.banner_level || 'info', daily_limit: n } })
      .then(function (s) { current = s; hint('limithint', n ? '✓ Each visitor can start ' + n + ' downloads a day.' : '✓ No daily limit.'); })
      .catch(function (err) { hint('limithint', err.message, true); });
  });
  function ban(ip, note, hintId) {
    return api('bans', { method: 'POST', body: { ip: ip, note: note || '' } }).then(function () {
      loadSettings(); loadLog(false);
      if (hintId) hint(hintId, '✓ Blocked ' + ip + '.');
    }).catch(function (err) { if (hintId) hint(hintId, err.message, true); else alert(err.message); });
  }
  function unban(ip) {
    return api('bans/' + encodeURIComponent(ip), { method: 'DELETE' }).then(function () { loadSettings(); loadLog(false); }).catch(function (err) { alert(err.message); });
  }
  $('f-ban').addEventListener('submit', function (e) {
    e.preventDefault();
    var f = this;
    ban(f.ip.value.trim(), f.note.value, 'banhint').then(function () { f.reset(); });
  });
  document.addEventListener('click', function (e) {
    var b = e.target.getAttribute('data-ban'), u = e.target.getAttribute('data-unban');
    if (b) { var note = prompt('Block ' + b + '? They won\'t be able to download anything.\n\nWhy (optional):', ''); if (note !== null) ban(b, note); }
    if (u && confirm('Unblock ' + u + '?')) unban(u);
  });
  function alertBody(g) {
    var body = {};
    ['telegram_chat', 'smtp_host', 'smtp_user', 'email_from', 'email_name', 'email_to', 'smtp_security'].forEach(function (k) { body[k] = g[k].value; });
    body.smtp_port = parseInt(g.smtp_port.value, 10) || ({ ssl: 465, none: 25 }[g.smtp_security.value] || 587);
    ['cookies', 'disk', 'failures', 'updates'].forEach(function (k) { body[k] = g[k].checked; });
    if (g.telegram_token.value) body.telegram_token = g.telegram_token.value;
    if (g.smtp_password.value) body.smtp_password = g.smtp_password.value;
    return body;
  }
  $('f-alerts').addEventListener('submit', function (e) {
    e.preventDefault();
    api('settings/alerts', { method: 'PUT', body: alertBody(this) }).then(function () { hint('alerthint', '✓ Saved. Press "Send a test message" to check it works.'); loadSettings(); })
      .catch(function (err) { hint('alerthint', err.message, true); });
  });
  function saveAlerts(hintId) {
    return api('settings/alerts', { method: 'PUT', body: alertBody($('f-alerts')) }).then(function (r) { loadSettings(); return r; })
      .catch(function (err) { hint(hintId, err.message, true); throw err; });
  }
  // Port follows the security choice while it's still one of the usual ports.
  $('f-alerts').smtp_security.addEventListener('change', function () {
    var port = $('f-alerts').smtp_port;
    if (!port.value || ['25', '465', '587'].indexOf(port.value) >= 0) port.value = { ssl: 465, none: 25 }[this.value] || 587;
  });
  $('f-alerts').addEventListener('click', function (e) {
    var saveTo = e.target.getAttribute('data-save'), channel = e.target.getAttribute('data-test');
    if (saveTo) saveAlerts(saveTo).then(function () { hint(saveTo, '✓ Saved.'); }, function () { });
    if (channel) {
      var btn = e.target, to = channel === 'email' ? 'smtphint' : 'tghint';
      btn.disabled = true;
      hint(to, channel === 'email' ? 'Saving and sending a test email…' : 'Saving and sending a test message…');
      saveAlerts(to).then(function () {
        return api('settings/alerts/test?channel=' + channel, { method: 'POST' }).then(function (r) {
          var x = r.results[0] || { ok: false, error: 'Nothing was sent.' };
          hint(to, x.ok ? '✓ Sent. Check ' + (channel === 'email' ? 'the inbox (and the spam folder).' : 'Telegram.') : '✗ ' + x.error, !x.ok);
        }, function (err) { hint(to, err.message, true); });
      }, function () { }).finally(function () { btn.disabled = false; });
    }
  });

  // Sign in -------------------------------------------------------------------------------

  $('loginform').addEventListener('submit', function (e) {
    e.preventDefault();
    hint('loginhint', '');
    api('login', { method: 'POST', body: { password: $('password').value } }).then(function (r) {
      setToken(r.token); $('password').value = ''; screen('panel');
    }).catch(function (err) { hint('loginhint', err.message, true); });
  });
  $('logout').onclick = function (e) {
    e.preventDefault();
    api('logout', { method: 'POST' }).catch(function () { }).finally(function () { setToken(null); screen('login'); });
  };

  fetch('api/health').then(function (r) { return r.json(); }).then(function (h) {
    if (!h.admin) return screen('off');
    if (!token) return screen('login');
    api('overview').then(function () { screen('panel'); }).catch(function () { });
  }).catch(function () { screen('login'); });
})();
