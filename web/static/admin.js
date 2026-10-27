(function () {
  'use strict';
  var $ = function (id) { return document.getElementById(id); };
  var token = null, logOffset = 0, timer = null;
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
    show('logout', name === 'panel');
    clearInterval(timer);
    if (name === 'panel') { refresh(); loadLog(true); loadReleases(); loadCharts(); timer = setInterval(refresh, 4000); }
    if (name === 'login') setTimeout(function () { $('password').focus(); }, 0);
  }

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

  function renderJobs(r) {
    var rows = r.jobs.map(function (j) {
      var st = j.status === 'running' ? (STAGE[j.stage] || 'Working') + (j.percent != null ? ' ' + Math.floor(j.percent) + '%' : '')
        : j.status === 'done' ? 'Ready · ' + bytes(j.size) : j.status === 'error' ? 'Failed' : j.status === 'queued' ? 'Waiting' : 'Cancelled';
      var cls = j.status === 'done' ? 'ok' : j.status === 'error' ? 'bad' : '';
      return '<tr><td class="title"><b>' + esc(j.title || j.url) + '</b><a href="' + esc(j.url) + '" target="_blank" rel="noopener">' + esc(j.url) + '</a>' +
        (j.error ? '<span class="bad">' + esc(j.error) + '</span>' : '') + '</td>' +
        '<td class="mono">' + esc(j.source) + '<br>' + esc(j.client) + '</td><td class="mono">' + esc(j.label) + '</td>' +
        '<td class="mono ' + cls + '">' + esc(st) + '<br>' + esc(when(j.created)) + '</td>' +
        '<td><button class="linkbtn" data-kill="' + esc(j.id) + '">' + (j.status === 'running' || j.status === 'queued' ? 'Cancel' : 'Delete') + '</button></td></tr>';
    });
    $('jobs').tBodies[0].innerHTML = rows.join('') || '<tr><td colspan="5" class="empty">Nothing on the server right now.</td></tr>';
  }
  $('jobs').addEventListener('click', function (e) {
    var id = e.target.getAttribute('data-kill');
    if (id) api('jobs/' + encodeURIComponent(id), { method: 'DELETE' }).then(refresh);
  });
  $('clearfiles').onclick = function () {
    api('files/clear', { method: 'POST' }).then(function (r) { refresh(); hint('serverhint', 'Deleted ' + r.deleted + ' finished downloads.'); });
  };

  // Log -----------------------------------------------------------------------------------

  var searchTimer = null;
  function loadLog(reset) {
    if (reset) logOffset = 0;
    var q = $('search').value.trim();
    api('requests?limit=100&offset=' + logOffset + (q ? '&q=' + encodeURIComponent(q) : '')).then(function (r) {
      var html = r.requests.map(function (x) {
        var res = x.status === 'done' ? '<span class="ok">Done · ' + esc(bytes(x.size)) + '</span>' :
          x.status === 'error' ? '<span class="bad" title="' + esc(x.error) + '">Failed</span><br><small>' + esc((x.error || '').slice(0, 80)) + '</small>' : esc(x.status);
        return '<tr><td class="mono">' + esc(when(x.ts)) + '</td><td class="title"><b>' + esc(x.title || '—') + '</b><a href="' + esc(x.url) + '" target="_blank" rel="noopener">' + esc(x.url) + '</a></td>' +
          '<td class="mono">' + esc(x.site || '') + '</td><td class="mono">' + esc(x.source || '') + (x.client_version ? ' ' + esc(x.client_version) : '') + '</td>' +
          '<td class="mono">' + esc(x.label || '') + '</td><td class="mono">' + res + '</td><td class="mono" title="' + esc(x.user_agent) + '">' + esc(x.ip || '') + '</td></tr>';
      }).join('');
      var body = $('log').tBodies[0];
      if (reset) body.innerHTML = html || '<tr><td colspan="7" class="empty">No requests yet.</td></tr>';
      else body.insertAdjacentHTML('beforeend', html);
      logOffset += r.requests.length;
      show('moreLog', r.requests.length === 100);
    }).catch(function () { });
  }
  $('search').oninput = function () { clearTimeout(searchTimer); searchTimer = setTimeout(function () { loadLog(true); }, 300); };
  $('moreLog').onclick = function () { loadLog(false); };
  $('export').onclick = function (e) { e.preventDefault(); location.href = 'api/admin/requests/export?admin_token=' + encodeURIComponent(token); };
  $('clearlog').onclick = function () {
    if (!confirm('Clear the whole request log?')) return;
    api('requests', { method: 'DELETE' }).then(function () { loadLog(true); refresh(); });
  };

  // Server --------------------------------------------------------------------------------

  $('update').onclick = function () {
    $('update').disabled = true;
    hint('serverhint', 'Updating yt-dlp. This can take a minute…');
    api('ytdlp/update', { method: 'POST' }).then(function (r) {
      hint('serverhint', r.restart_needed ? 'Installed yt-dlp ' + r.installed + '. Restart the server to start using it.' : 'yt-dlp ' + r.installed + ' is already the newest.');
    }).catch(function (e) { hint('serverhint', e.message, true); }).finally(function () { $('update').disabled = false; });
  };
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
      $('uplimit').textContent = 'Up to ' + r.max_upload_mb + ' MB per file';
      var a = p.android;
      $('rel-android').innerHTML = a ? esc('Version ' + a.version + ' (' + a.version_code + ') · ' + Object.keys(a.apks).join(', ') + ' · ' + day(a.updated)) + ' · <a href="' + esc(a.url) + '">download</a>' : 'Not uploaded yet';
      ['extension', 'helper'].forEach(function (k) {
        var x = p[k];
        $('rel-' + k).innerHTML = x ? esc([x.version ? 'Version ' + x.version : '', x.size ? bytes(x.size) : '', x.store_url ? 'store link' : '', day(x.updated)].filter(Boolean).join(' · ')) +
          (x.url ? ' · <a href="' + esc(x.url) + '">download</a>' : '') : 'Not uploaded yet';
        var f = $('f-' + k);
        if (x) { if (x.version) f.version.value = x.version; if (f.notes) f.notes.value = x.notes || ''; if (f.store_url) f.store_url.value = x.store_url || ''; }
      });
      if (a) { $('f-android').min_supported.value = $('f-android').min_supported.value || 0; }
      $('files').tBodies[0].innerHTML = p.files.map(function (f) {
        return '<tr><td class="title"><b>' + esc(f.title) + '</b><a href="' + esc(f.url) + '">' + esc(f.name) + '</a></td><td class="mono">' + esc(bytes(f.size)) + '</td><td class="mono">' + esc(day(f.updated)) + '</td>' +
          '<td><button class="linkbtn" type="button" data-delfile="' + esc(f.name) + '">Remove</button></td></tr>';
      }).join('') || '<tr><td class="empty">No other files yet.</td></tr>';
    }).catch(function () { });
  }

  // XHR, not fetch, so big uploads can show progress.
  function upload(path, form, done) {
    var xhr = new XMLHttpRequest(), bar = $('upbar');
    xhr.open('POST', 'api/admin/releases/' + path);
    xhr.setRequestHeader('X-Admin-Token', token);
    show('upbar', true); bar.firstChild.style.width = '0';
    xhr.upload.onprogress = function (e) {
      if (e.lengthComputable) { bar.firstChild.style.width = (e.loaded / e.total * 100) + '%'; hint('relhint', 'Uploading… ' + Math.round(e.loaded / e.total * 100) + '%'); }
    };
    xhr.onload = function () {
      show('upbar', false);
      var d = {}; try { d = JSON.parse(xhr.responseText); } catch (e) { }
      if (xhr.status === 401) { setToken(null); screen('login'); return; }
      if (xhr.status >= 400) { hint('relhint', typeof d.detail === 'string' ? d.detail : 'Upload failed (' + xhr.status + ').', true); return; }
      hint('relhint', 'Published.'); form.reset(); loadReleases(); if (done) done();
    };
    xhr.onerror = function () { show('upbar', false); hint('relhint', 'The upload was cut off. Try again.', true); };
    xhr.send(new FormData(form));
  }

  ['android', 'extension', 'helper', 'files'].forEach(function (k) {
    $('f-' + k).addEventListener('submit', function (e) { e.preventDefault(); upload(k, this); });
  });
  $('releases').addEventListener('click', function (e) {
    var slot = e.target.getAttribute('data-unpublish'), file = e.target.getAttribute('data-delfile');
    if (slot && confirm('Stop offering this download on the page?')) api('releases/' + slot, { method: 'DELETE' }).then(loadReleases);
    if (file && confirm('Remove ' + file + '?')) api('releases/files/' + encodeURIComponent(file), { method: 'DELETE' }).then(loadReleases);
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
