(function () {
  'use strict';
  var $ = function (id) { return document.getElementById(id); };
  var state = { info: null, kind: 'video', choice: null, url: '' };
  var store = {
    get: function (k, d) { try { var v = localStorage.getItem('haul.' + k); return v == null ? d : JSON.parse(v); } catch (e) { return d; } },
    set: function (k, v) { try { localStorage.setItem('haul.' + k, JSON.stringify(v)); } catch (e) { } }
  };

  // Helpers -----------------------------------------------------------------------------

  function esc(s) {
    return String(s == null ? '' : s).replace(/[&<>"']/g, function (c) {
      return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c];
    });
  }
  function bytes(n) {
    if (!n) return '';
    if (n >= 1e9) return (n / 1e9).toFixed(1) + ' GB';
    if (n >= 1e6) return Math.round(n / 1e6) + ' MB';
    return Math.max(1, Math.round(n / 1e3)) + ' KB';
  }
  function dur(s) {
    if (!s) return '';
    s = Math.round(s);
    var h = Math.floor(s / 3600), m = Math.floor(s % 3600 / 60), x = s % 60;
    return (h ? h + ':' + String(m).padStart(2, '0') : m) + ':' + String(x).padStart(2, '0');
  }
  function hint(msg, bad) { $('hint').textContent = msg || ''; $('hint').className = 'hint' + (bad ? ' bad' : ''); }
  function show(id, on) { $(id).classList.toggle('hidden', !on); }

  function api(path, opts) {
    opts = opts || {};
    var headers = { 'Content-Type': 'application/json' };
    var key = store.get('key', '');
    if (key) headers['X-API-Key'] = key;
    return fetch('api/' + path, { method: opts.method || 'GET', headers: headers, body: opts.body ? JSON.stringify(opts.body) : undefined })
      .then(function (r) {
        return r.json().catch(function () { return {}; }).then(function (data) {
          if (r.status === 401) { show('keycard', true); }
          if (!r.ok) { var e = new Error(typeof data.detail === 'string' ? data.detail : 'Something went wrong (' + r.status + ').'); e.status = r.status; throw e; }
          return data;
        });
      });
  }
  function withKey(path) {
    var key = store.get('key', '');
    return 'api/' + path + (key ? (path.indexOf('?') < 0 ? '?' : '&') + 'key=' + encodeURIComponent(key) : '');
  }

  // Fetching info ----------------------------------------------------------------------

  function getInfo(url, keepSelection) {
    state.url = url;
    $('fetch').disabled = true;
    hint('Looking up the link…');
    var body = { url: url, container: $('container').value, language: $('lang').value || null };
    return api('info', { method: 'POST', body: body }).then(function (info) {
      hint('');
      if (info.kind === 'playlist') return showPlaylist(info);
      state.info = info;
      showMedia(info, keepSelection);
    }).catch(function (e) {
      hint(e.message, true);
    }).finally(function () { $('fetch').disabled = false; });
  }

  function showPlaylist(info) {
    show('mediacard', false);
    show('listcard', true);
    $('listtitle').textContent = info.title || 'Playlist';
    $('listmeta').textContent = 'Playlist · ' + info.count + ' videos' + (info.uploader ? ' · ' + info.uploader : '');
    $('entries').innerHTML = info.entries.map(function (e, i) {
      return '<div class="entry"><div class="t"><b>' + esc(e.title || 'Video ' + (i + 1)) + '</b><span>' + esc(dur(e.duration)) + '</span></div>' +
        '<button class="btn small ghost" type="button" data-url="' + esc(e.url) + '">Choose</button></div>';
    }).join('');
  }

  function showMedia(info, keepSelection) {
    show('listcard', false);
    show('mediacard', true);
    $('thumb').onerror = function () { this.classList.add('hidden'); };
    $('thumb').src = info.thumbnail || '';
    $('thumb').classList.toggle('hidden', !info.thumbnail);
    $('title').textContent = info.title || 'Untitled';
    $('meta').textContent = [info.uploader, dur(info.duration), info.extractor].filter(Boolean).join(' · ');

    var langs = info.audio_languages || [];
    show('lang-wrap', langs.length > 1);
    if (langs.length > 1 && !keepSelection) {
      $('lang').innerHTML = '<option value="">Original</option>' + langs.map(function (l) {
        return '<option value="' + esc(l.code) + '">' + esc(l.name) + (l.original ? ' (original)' : '') + '</option>';
      }).join('');
    }
    if (!keepSelection) state.choice = null;
    renderRows();
  }

  function renderRows() {
    var info = state.info, video = state.kind === 'video';
    $('tab-video').setAttribute('aria-selected', video);
    $('tab-audio').setAttribute('aria-selected', !video);
    show('container-wrap', video);
    show('cover-wrap', !video);
    var rows = video ? info.video : info.audio;
    if (!rows.length) {
      $('rows').innerHTML = '<div class="row"><span class="spec">No ' + (video ? 'video' : 'audio') + ' formats found</span></div>';
      $('go').disabled = true;
      return;
    }
    $('go').disabled = false;
    var keyOf = function (r) { return video ? r.height : r.format; };
    var keys = rows.map(keyOf);
    if (keys.indexOf(state.choice) < 0) state.choice = video ? (info.recommended_height || keys[0]) : keys[0];
    $('rows').innerHTML = rows.map(function (r) {
      var k = keyOf(r), on = k === state.choice;
      var badge = video ? (k === info.recommended_height ? 'Recommended' : '') : (r.badge || '');
      return '<label class="row"><input type="radio" name="q" value="' + esc(k) + '"' + (on ? ' checked' : '') + '>' +
        '<span class="name">' + esc(r.label) + (badge ? '<span class="badge">' + esc(badge) + '</span>' : '') + '</span>' +
        '<span class="spec">' + esc(video ? [r.codecs, r.spec].filter(Boolean).join(' · ') : r.spec) + '</span>' +
        '<span class="size">' + esc(r.size ? '~' + bytes(r.size) : '') + '</span></label>';
    }).join('');
    updateBitrate();
  }

  function updateBitrate() { show('bitrate-wrap', state.kind === 'audio' && state.choice === 'mp3'); }

  // Jobs ---------------------------------------------------------------------------------

  var jobs = {};   // id -> last known job

  function startJob() {
    var video = state.kind === 'video';
    var body = {
      url: state.url,
      kind: state.kind,
      height: video ? state.choice : null,
      container: $('container').value,
      audio_format: video ? 'mp3' : state.choice,
      audio_bitrate: parseInt($('bitrate').value, 10),
      language: $('lang').value || null,
      embed_cover: $('cover').checked
    };
    $('go').disabled = true;
    api('jobs', { method: 'POST', body: body }).then(function (job) {
      job.autoSave = true;
      track(job);
    }).catch(function (e) { hint(e.message, true); })
      .finally(function () { $('go').disabled = false; });
  }

  function track(job) {
    jobs[job.id] = Object.assign(jobs[job.id] || {}, job);
    saveJobs();
    renderJobs();
    if (job.status === 'queued' || job.status === 'running') follow(job.id);
  }

  function follow(id) {
    if (window.EventSource) {
      var es = new EventSource(withKey('jobs/' + id + '/events'));
      es.addEventListener('progress', function (ev) { update(JSON.parse(ev.data)); });
      es.onerror = function () { es.close(); poll(id); };
      jobs[id]._es = es;
    } else poll(id);
  }

  function poll(id) {
    var j = jobs[id];
    if (!j || ['done', 'error', 'cancelled'].indexOf(j.status) >= 0) return;
    setTimeout(function () {
      api('jobs/' + id).then(function (job) { update(job); poll(id); })
        .catch(function (e) { if (e.status === 404) { forget(id); } else poll(id); });
    }, 1000);
  }

  function update(job) {
    var prev = jobs[job.id] || {};
    jobs[job.id] = Object.assign(prev, job);
    if (job.status !== 'queued' && job.status !== 'running' && prev._es) { prev._es.close(); prev._es = null; }
    if (job.status === 'done' && prev.autoSave) { prev.autoSave = false; save(job.id); }
    saveJobs();
    renderJobs();
  }

  function save(id) {
    var a = document.createElement('a');
    a.href = withKey('jobs/' + id + '/file');
    a.download = '';
    document.body.appendChild(a); a.click(); a.remove();
  }

  function forget(id) {
    var j = jobs[id];
    if (j && j._es) j._es.close();
    delete jobs[id];
    saveJobs();
    renderJobs();
  }

  function remove(id) {
    api('jobs/' + id, { method: 'DELETE' }).catch(function () { }).finally(function () { forget(id); });
  }

  var STAGES = { queued: 'Waiting', downloading: 'Downloading', merging: 'Merging', converting: 'Converting', tagging: 'Adding tags', processing: 'Finishing' };

  function statusText(j) {
    if (j.status === 'done') return 'Ready · ' + bytes(j.size);
    if (j.status === 'error') return 'Failed';
    if (j.status === 'cancelled') return 'Cancelled';
    var s = STAGES[j.stage] || 'Working';
    if (j.stage === 'downloading') {
      if (j.percent != null) s += ' ' + Math.floor(j.percent) + '%';
      if (j.speed) s += ' · ' + bytes(j.speed) + '/s';
      if (j.eta) s += ' · ' + dur(j.eta) + ' left';
    }
    return s;
  }

  function renderJobs() {
    var list = Object.keys(jobs).map(function (k) { return jobs[k]; }).sort(function (a, b) { return b.created - a.created; });
    show('jobscard', list.length > 0);
    $('jobs').innerHTML = list.map(function (j) {
      var live = j.status === 'queued' || j.status === 'running';
      var busy = live && (j.percent == null || j.stage !== 'downloading');
      var cls = j.status === 'done' ? ' ok' : (j.status === 'error' ? ' bad' : '');
      var actions = j.status === 'done'
        ? '<button class="btn small" data-save="' + esc(j.id) + '">Save file</button><button class="btn small ghost" data-del="' + esc(j.id) + '">Delete</button>'
        : live ? '<button class="btn small ghost" data-del="' + esc(j.id) + '">Cancel</button>'
          : '<button class="btn small ghost" data-del="' + esc(j.id) + '">Clear</button>';
      return '<div class="job"><div class="head"><b>' + esc(j.title || j.url) + '</b><span class="status' + cls + '">' + esc(statusText(j)) + '</span></div>' +
        '<div class="label" style="margin-top:4px">' + esc(j.label) + (j.filename ? ' · ' + esc(j.filename) : '') + '</div>' +
        (live ? '<div class="bar' + (busy ? ' busy' : '') + '"><i style="width:' + (j.percent || 0) + '%"></i></div>' : '') +
        (j.error ? '<p class="err">' + esc(j.error) + '</p>' : '') +
        '<div class="actions" style="margin-top:10px">' + actions + '</div></div>';
    }).join('');
  }

  function saveJobs() {
    store.set('jobs', Object.keys(jobs).map(function (k) { return jobs[k].id; }));
  }

  function restoreJobs() {
    store.get('jobs', []).forEach(function (id) {
      api('jobs/' + id).then(track).catch(function () { });
    });
  }

  // Wiring -------------------------------------------------------------------------------

  $('form').addEventListener('submit', function (e) {
    e.preventDefault();
    var url = $('url').value.trim();
    if (!url) return;
    $('lang').innerHTML = '';
    getInfo(url, false);
  });
  $('tab-video').onclick = function () { state.kind = 'video'; state.choice = null; renderRows(); };
  $('tab-audio').onclick = function () { state.kind = 'audio'; state.choice = null; renderRows(); };
  $('rows').addEventListener('change', function (e) {
    var v = e.target.value;
    state.choice = state.kind === 'video' ? parseInt(v, 10) : v;
    updateBitrate();
  });
  // Codec picks and sizes depend on the file type and audio track, so ask again.
  $('container').onchange = $('lang').onchange = function () { if (state.url) getInfo(state.url, true); };
  $('go').onclick = startJob;
  $('entries').addEventListener('click', function (e) {
    var u = e.target.getAttribute('data-url');
    if (u) { $('url').value = u; $('lang').innerHTML = ''; getInfo(u, false); }
  });
  $('jobs').addEventListener('click', function (e) {
    var s = e.target.getAttribute('data-save'), d = e.target.getAttribute('data-del');
    if (s) save(s);
    if (d) remove(d);
  });
  $('keyform').addEventListener('submit', function (e) {
    e.preventDefault();
    store.set('key', $('key').value.trim());
    show('keycard', false);
    hint('Key saved.');
    restoreJobs();
  });

  document.querySelectorAll('.origin').forEach(function (el) { el.textContent = location.origin; });
  api('health').then(function (h) {
    $('versions').textContent = 'yt-dlp ' + h.yt_dlp + (h.ffmpeg ? ' · FFmpeg ' + h.ffmpeg : '');
    var t = h.limits.file_ttl_min;
    $('ttl').textContent = t >= 60 && t % 60 === 0 ? (t === 60 ? 'an hour' : t / 60 + ' hours') : t + ' minutes';
    if (h.auth && !store.get('key', '')) show('keycard', true);
  }).catch(function () { });

  restoreJobs();

  // ?url=… opens a link straight away (used by the share sheet and the browser extension).
  var shared = new URLSearchParams(location.search).get('url') || new URLSearchParams(location.search).get('text');
  if (shared) {
    var m = shared.match(/https?:\/\/\S+/);
    if (m) { $('url').value = m[0]; getInfo(m[0], false); }
  }
})();
