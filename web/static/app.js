(function () {
  'use strict';
  var $ = function (id) { return document.getElementById(id); };
  var state = { info: null, kind: document.body.getAttribute('data-kind') === 'audio' ? 'audio' : 'video', choice: null, url: '', list: null };
  var store = {
    get: function (k, d) { try { var v = localStorage.getItem('haul.' + k); return v == null ? d : JSON.parse(v); } catch (e) { return d; } },
    set: function (k, v) { try { localStorage.setItem('haul.' + k, JSON.stringify(v)); } catch (e) { } }
  };
  var CLIENT = 'web/2.0';

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
    if (s == null || s === '') return '';
    s = Math.round(s);
    var h = Math.floor(s / 3600), m = Math.floor(s % 3600 / 60), x = s % 60;
    return (h ? h + ':' + String(m).padStart(2, '0') : m) + ':' + String(x).padStart(2, '0');
  }
  function count(n) {
    if (n == null) return '';
    if (n >= 1e9) return (n / 1e9).toFixed(1).replace(/\.0$/, '') + 'B';
    if (n >= 1e6) return (n / 1e6).toFixed(1).replace(/\.0$/, '') + 'M';
    if (n >= 1e3) return (n / 1e3).toFixed(1).replace(/\.0$/, '') + 'K';
    return String(n);
  }
  function ymd(s) {
    if (!s || s.length !== 8) return '';
    var d = new Date(+s.slice(0, 4), +s.slice(4, 6) - 1, +s.slice(6, 8));
    return d.toLocaleDateString(undefined, { year: 'numeric', month: 'short', day: 'numeric' });
  }
  // "1:23", "1:02:03", "83" or "83.5" -> seconds; '' -> null; anything else -> NaN
  function parseTime(v) {
    v = (v || '').trim();
    if (!v) return null;
    if (/^\d+(\.\d+)?$/.test(v)) return parseFloat(v);
    var m = v.match(/^(?:(\d+):)?(\d{1,2}):(\d{1,2}(?:\.\d+)?)$/);
    if (!m) return NaN;
    return (+(m[1] || 0)) * 3600 + (+m[2]) * 60 + parseFloat(m[3]);
  }
  function hint(msg, bad) { $('hint').textContent = msg || ''; $('hint').className = 'hint' + (bad ? ' bad' : ''); }
  function show(id, on) { $(id).classList.toggle('hidden', !on); }
  function links(text) {
    var out = [], seen = {};
    (text.match(/https?:\/\/[^\s<>"']+/g) || []).forEach(function (u) { if (!seen[u]) { seen[u] = 1; out.push(u); } });
    return out;
  }

  function api(path, opts) {
    opts = opts || {};
    var headers = { 'Content-Type': 'application/json', 'X-Haul-Client': CLIENT };
    var key = store.get('key', '');
    if (key) headers['X-API-Key'] = key;
    return fetch('api/' + path, { method: opts.method || 'GET', headers: headers, body: opts.body ? JSON.stringify(opts.body) : undefined })
      .then(function (r) {
        return r.json().catch(function () { return {}; }).then(function (data) {
          if (r.status === 401) show('keycard', true);
          if (!r.ok) {
            var d = data.detail, msg;
            if (typeof d === 'string') msg = d;
            else if (d && d.message) msg = d.message;
            else if (Array.isArray(d) && d[0]) msg = String(d[0].msg || '').replace(/^Value error, /, '');
            var e = new Error(msg || 'Something went wrong (' + r.status + ').');
            e.status = r.status; e.cookie = !!(d && d.cookie_problem);
            throw e;
          }
          return data;
        });
      });
  }
  function withKey(path) {
    var key = store.get('key', '');
    return 'api/' + path + (key ? (path.indexOf('?') < 0 ? '?' : '&') + 'key=' + encodeURIComponent(key) : '');
  }

  // Input --------------------------------------------------------------------------------

  var input = $('url');
  function grow() { input.style.height = 'auto'; input.style.height = Math.min(input.scrollHeight + 2, 200) + 'px'; }
  input.addEventListener('input', grow);
  input.addEventListener('keydown', function (e) {
    // Enter looks the link up; Shift+Enter adds another line.
    if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); $('form').requestSubmit(); }
  });
  if (navigator.clipboard && navigator.clipboard.readText) {
    $('pastebtn').onclick = function () {
      navigator.clipboard.readText().then(function (t) {
        if (!t) return;
        input.value = t.trim(); grow();
        if (links(t).length) $('form').requestSubmit();
      }).catch(function () { input.focus(); hint('Press Ctrl+V (or long-press) to paste.'); });
    };
  } else show('pastebtn', false);

  $('form').addEventListener('submit', function (e) {
    e.preventDefault();
    var found = links(input.value);
    if (!found.length) { hint('Paste a full link that starts with http:// or https://.', true); return; }
    $('lang').innerHTML = '';
    if (found.length > 1) showBatch(found);
    else getInfo(found[0], false);
  });

  // Fetching info ----------------------------------------------------------------------

  // A watch link from inside a playlist or mix (…watch?v=X&list=Y) means that one video;
  // the whole list is one tap away.
  function inPlaylist(url) {
    try { var u = new URL(url); return !!(u.searchParams.get('v') && u.searchParams.get('list')); } catch (e) { return false; }
  }

  function getInfo(url, keepSelection, wholeList) {
    state.url = url;
    $('fetch').disabled = true;
    hint('Looking up the link…');
    var single = inPlaylist(url) && !wholeList;
    show('inlist', false);
    var body = { url: url, container: $('container').value, language: $('lang').value || null, playlist: !single };
    return api('info', { method: 'POST', body: body }).then(function (info) {
      hint('');
      if (info.kind === 'playlist') return showPlaylist(info);
      state.info = info;
      showMedia(info, keepSelection);
      show('inlist', single);
    }).catch(function (e) {
      hint(e.message, true);
    }).finally(function () { $('fetch').disabled = false; });
  }

  function showMedia(info, keepSelection) {
    show('listcard', false);
    show('mediacard', true);
    $('thumb').onerror = function () { this.classList.add('hidden'); };
    $('thumb').classList.toggle('hidden', !info.thumbnail);
    $('thumb').src = info.thumbnail || '';
    $('title').textContent = info.title || 'Untitled';
    var by = info.uploader ? (info.channel_url ? '<a href="' + esc(info.channel_url) + '" target="_blank" rel="noopener">' + esc(info.uploader) + '</a>' : esc(info.uploader)) : '';
    $('meta').innerHTML = [by, esc(dur(info.duration)), esc(info.extractor)].filter(Boolean).join(' · ');
    var st = info.stats || {};
    $('stats').textContent = [
      st.views != null ? count(st.views) + ' views' : '',
      st.likes != null ? count(st.likes) + ' likes' : '',
      ymd(st.uploaded)
    ].filter(Boolean).join(' · ');

    var langs = info.audio_languages || [];
    show('lang-wrap', langs.length > 1);
    if (langs.length > 1 && !keepSelection) {
      $('lang').innerHTML = '<option value="">Original</option>' + langs.map(function (l) {
        return '<option value="' + esc(l.code) + '">' + esc(l.name) + (l.original ? ' (original)' : '') + '</option>';
      }).join('');
    }
    if (!keepSelection) {
      state.choice = null;
      $('start').value = ''; $('end').value = '';
      $('direct').innerHTML = '';
      var subs = info.subtitles || [];
      $('subs').innerHTML = '<option value="">None</option>' + subs.map(function (s) {
        return '<option value="' + esc(s.code) + '">' + esc(s.name) + (s.auto ? ' (auto)' : '') + '</option>';
      }).join('');
      var ch = info.chapters || [];
      show('chapters', ch.length > 0);
      $('chapters').innerHTML = ch.length ? '<div class="label" style="margin:12px 0 6px">Chapters · tap to clip</div>' + ch.map(function (c, i) {
        return '<button type="button" class="chip" data-ch="' + i + '">' + esc(dur(c.start)) + ' ' + esc(c.title) + '</button>';
      }).join('') : '';
    }
    renderRows();
    moreSummary();
  }

  function renderRows() {
    var info = state.info, video = state.kind === 'video';
    $('tab-video').setAttribute('aria-selected', video);
    $('tab-audio').setAttribute('aria-selected', !video);
    show('container-wrap', video);
    show('cover-wrap', !video);
    show('subs-wrap', video && (info.subtitles || []).length > 0);
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

  function moreSummary() {
    var bits = [];
    if ($('subs').value && state.kind === 'video') bits.push('subtitles');
    if ($('start').value || $('end').value) bits.push('clip');
    $('moresum').textContent = bits.length ? '· ' + bits.join(', ') : '';
  }

  // Playlists and pasted batches --------------------------------------------------------

  function showPlaylist(info) {
    state.list = { kind: 'playlist', url: state.url, title: info.title, entries: info.entries };
    show('mediacard', false);
    show('listcard', true);
    $('listtitle').textContent = info.title || 'Playlist';
    $('listmeta').textContent = 'Playlist · ' + info.count + ' videos' + (info.uploader ? ' · ' + info.uploader : '');
    renderEntries();
  }

  function showBatch(urls) {
    state.list = { kind: 'batch', entries: urls.map(function (u, i) { return { index: i + 1, url: u, title: u }; }) };
    show('mediacard', false);
    show('listcard', true);
    $('listtitle').textContent = urls.length + ' links';
    $('listmeta').textContent = 'Several links · each one downloads on its own';
    hint('');
    renderEntries();
  }

  function renderEntries() {
    var L = state.list;
    $('entries').innerHTML = L.entries.map(function (e, i) {
      return '<div class="entry"><input type="checkbox" class="pick" data-i="' + i + '" checked aria-label="Include">' +
        '<div class="t"><b>' + esc(e.title || 'Video ' + (i + 1)) + '</b><span>' + esc([e.index && L.kind === 'playlist' ? '#' + e.index : '', dur(e.duration), e.uploader].filter(Boolean).join(' · ')) + '</span></div>' +
        '<button class="btn small ghost" type="button" data-url="' + esc(e.url) + '">Choose</button></div>';
    }).join('');
    $('selall').checked = true;
    updateListUi();
  }

  function picked() {
    return Array.prototype.filter.call(document.querySelectorAll('.pick'), function (c) { return c.checked; })
      .map(function (c) { return +c.getAttribute('data-i'); });
  }

  function updateListUi() {
    var n = picked().length, total = state.list ? state.list.entries.length : 0;
    var audio = $('lkind').value === 'audio';
    show('lheight-wrap', !audio); show('lcontainer-wrap', !audio); show('laudio-wrap', audio);
    $('selcount').textContent = n + ' of ' + total + ' selected';
    $('lgolabel').textContent = n === total ? 'Download all ' + total : 'Download ' + n;
    $('lgo').disabled = n === 0;
    $('lnote').textContent = onPc() ? 'Each one is saved on this PC, in the Haul extension\'s download folder.'
      : state.list && state.list.kind === 'playlist' ? 'Saved together as one ZIP file.' : 'Each link gets its own download below.';
  }

  function listOptions() {
    var audio = $('lkind').value === 'audio';
    return {
      kind: audio ? 'audio' : 'video',
      height: audio || !$('lheight').value ? null : +$('lheight').value,
      container: $('lcontainer').value,
      audio_format: $('laudio').value,
      embed_cover: true
    };
  }

  $('lgo').onclick = function () {
    var L = state.list, idx = picked(), o = listOptions();
    $('lgo').disabled = true;
    var done = function () { $('lgo').disabled = false; };
    if (onPc()) {
      // The helper saves each video on its own; one after another keeps the order.
      var chain = Promise.resolve(), failed = [];
      idx.forEach(function (i) {
        var e = L.entries[i];
        chain = chain.then(function () {
          return startPcJob(e.url, o.kind, pcQuality(o.height), o.audio_format, e.title !== e.url ? e.title : null)
            .catch(function (err) { failed.push(err.message); });
        });
      });
      chain.then(function () { hint(failed.length ? failed.length + ' failed: ' + failed[0] : '', failed.length > 0); }).finally(done);
      return;
    }
    if (L.kind === 'playlist') {
      var body = Object.assign({ url: L.url, playlist: true }, o);
      if (idx.length !== L.entries.length) body.playlist_items = idx.map(function (i) { return L.entries[i].index || i + 1; });
      api('jobs', { method: 'POST', body: body }).then(function (job) { job.autoSave = true; track(job); hint(''); })
        .catch(function (e) { hint(e.message, true); }).finally(done);
    } else {
      var urls = idx.map(function (i) { return L.entries[i].url; });
      api('jobs/batch', { method: 'POST', body: { urls: urls, options: Object.assign({ url: 'x' }, o) } }).then(function (r) {
        var bad = r.results.filter(function (x) { return x.error; });
        r.results.forEach(function (x) { if (x.job) { x.job.autoSave = true; track(x.job); } });
        hint(bad.length ? bad.length + ' link(s) failed: ' + bad.map(function (b) { return b.error; }).join(' ') : '', bad.length > 0);
      }).catch(function (e) { hint(e.message, true); }).finally(done);
    }
  };
  $('lkind').onchange = updateListUi;
  if (state.kind === 'audio') { $('lkind').value = 'audio'; show('lheight-wrap', false); show('lcontainer-wrap', false); show('laudio-wrap', true); }
  $('selall').onchange = function () {
    document.querySelectorAll('.pick').forEach(function (c) { c.checked = $('selall').checked; });
    updateListUi();
  };
  $('entries').addEventListener('change', updateListUi);
  $('entries').addEventListener('click', function (e) {
    var u = e.target.getAttribute('data-url');
    if (u) { input.value = u; grow(); $('lang').innerHTML = ''; getInfo(u, false); }
  });

  // Direct links -------------------------------------------------------------------------

  $('directbtn').onclick = function () {
    $('direct').innerHTML = '<p class="sub">Looking for direct links…</p>';
    api('direct-links?url=' + encodeURIComponent(state.url)).then(function (r) {
      $('direct').innerHTML = '<p class="sub">These open straight from the site. Some sites only allow them from this server, and they stop working after a few hours.</p>' +
        '<div class="rows">' + r.links.map(function (l) {
          return '<a class="row" href="' + esc(l.url) + '" target="_blank" rel="noopener noreferrer" download>' +
            '<span class="name">' + esc(l.kind === 'video' ? l.label : 'Audio') + '</span>' +
            '<span class="spec">' + esc([l.ext, l.kind === 'audio' ? l.label : '', l.language].filter(Boolean).join(' · ')) + '</span>' +
            '<span class="size">' + esc(bytes(l.size)) + '</span></a>';
        }).join('') + '</div>';
    }).catch(function (e) { $('direct').innerHTML = '<p class="sub bad">' + esc(e.message) + '</p>'; });
  };

  // Jobs ---------------------------------------------------------------------------------

  var jobs = {};   // id -> last known job

  function startJob() {
    var video = state.kind === 'video';
    var start = parseTime($('start').value), end = parseTime($('end').value);
    if (isNaN(start) || isNaN(end)) { hint('Write clip times like 1:30 or 90.', true); return; }
    if (onPc()) {
      var why = pcBlockers(start, end);
      if (!why.length) {
        $('go').disabled = true;
        return startPcJob(state.url, state.kind, pcQuality(video ? state.choice : null), video ? 'mp3' : state.choice, state.info && state.info.title)
          .then(function () { hint(''); }).catch(function (e) { hint(e.message, true); })
          .finally(function () { $('go').disabled = false; });
      }
      hint('The server is needed for ' + why.join(', ').replace(/, ([^,]*)$/, ' and $1') + ', so this one downloads there.');
    }
    var body = {
      url: state.url,
      kind: state.kind,
      height: video ? state.choice : null,
      container: $('container').value,
      audio_format: video ? 'mp3' : state.choice,
      audio_bitrate: parseInt($('bitrate').value, 10),
      language: $('lang').value || null,
      embed_cover: $('cover').checked,
      subtitles: video && $('subs').value ? [$('subs').value] : [],
      start: start,
      end: end
    };
    if (video && body.height === 0) body.height = null;
    $('go').disabled = true;
    api('jobs', { method: 'POST', body: body }).then(function (job) {
      if (!onPc()) hint('');
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
    if (jobs[id] && jobs[id].pc) return pcRemove(id);
    api('jobs/' + id, { method: 'DELETE' }).catch(function () { }).finally(function () { forget(id); });
  }

  var STAGES = { queued: 'Waiting', downloading: 'Downloading', merging: 'Merging', converting: 'Converting',
    subtitles: 'Adding subtitles', tagging: 'Adding tags', processing: 'Finishing', zipping: 'Zipping' };

  function statusText(j) {
    if (j.pc) return pcStatusText(j);
    if (j.status === 'done') return 'Ready · ' + bytes(j.size);
    if (j.status === 'error') return 'Failed';
    if (j.status === 'cancelled') return 'Cancelled';
    var s = STAGES[j.stage] || 'Working';
    if (j.playlist && j.stage !== 'zipping') s += ' ' + j.playlist.item + ' of ' + j.playlist.count;
    if (j.percent != null && (j.stage === 'downloading' || j.playlist)) s += ' · ' + Math.floor(j.percent) + '%';
    if (j.stage === 'downloading') {
      if (j.speed) s += ' · ' + bytes(j.speed) + '/s';
      if (j.eta && !j.playlist) s += ' · ' + dur(j.eta) + ' left';
    }
    return s;
  }

  function isMedia(name) { return /\.(mp4|webm|mkv|mp3|m4a|opus)$/i.test(name || ''); }

  function renderJobs() {
    var list = Object.keys(jobs).map(function (k) { return jobs[k]; }).sort(function (a, b) { return b.created - a.created; });
    show('jobscard', list.length > 0);
    $('jobs').innerHTML = list.map(function (j) {
      var live = j.status === 'queued' || j.status === 'running';
      var busy = live && (j.percent == null || (j.stage !== 'downloading' && !j.playlist));
      var cls = j.status === 'done' ? ' ok' : (j.status === 'error' ? ' bad' : '');
      var id = esc(j.id);
      var actions = j.pc ? pcActions(j, live) : j.status === 'done'
        ? '<button class="btn small" data-save="' + id + '">Save file</button>' +
          (isMedia(j.filename) && !/\.mkv$/i.test(j.filename) ? '<button class="btn small ghost" data-play="' + id + '">Play</button>' : '') +
          '<button class="btn small ghost" data-copy="' + id + '">Copy link</button>' +
          '<button class="btn small ghost" data-del="' + id + '">Delete</button>'
        : live ? '<button class="btn small ghost" data-del="' + id + '">Cancel</button>'
          : (j.status === 'error' ? '<button class="btn small ghost" data-retry="' + id + '">Look up again</button>' : '') +
            '<button class="btn small ghost" data-del="' + id + '">Clear</button>';
      var failed = j.playlist && j.playlist.failed && j.playlist.failed.length
        ? '<p class="err">' + j.playlist.failed.length + ' skipped: ' + j.playlist.failed.map(function (f) { return esc((f.title || '#' + f.index) + ' (' + f.error + ')'); }).join('; ') + '</p>' : '';
      var left = j.expires ? Math.max(0, Math.round((j.expires * 1000 - Date.now()) / 60000)) : null;
      return '<div class="job"><div class="head"><b>' + esc(j.title || j.url) + '</b><span class="status' + cls + '">' + esc(statusText(j)) + '</span></div>' +
        '<div class="label" style="margin-top:4px">' + esc(j.label) + (j.filename ? ' · ' + esc(j.filename) : '') + (left != null ? ' · deleted in ' + left + ' min' : '') + '</div>' +
        (live ? '<div class="bar' + (busy ? ' busy' : '') + '"><i style="width:' + (j.percent || 0) + '%"></i></div>' : '') +
        (j.error ? '<p class="err">' + esc(j.error) + '</p>' : '') + failed +
        '<div class="player" id="player-' + id + '"></div>' +
        '<div class="actions" style="margin-top:10px">' + actions + '</div></div>';
    }).join('');
  }

  function play(id) {
    var j = jobs[id], box = $('player-' + id);
    if (!j || !box) return;
    if (box.innerHTML) { box.innerHTML = ''; return; }
    var audio = j.kind === 'audio';
    var src = withKey('jobs/' + id + '/file?inline=1');
    box.innerHTML = audio ? '<audio controls autoplay src="' + esc(src) + '"></audio>' : '<video controls autoplay playsinline src="' + esc(src) + '"></video>';
  }

  function copyLink(id) {
    var url = new URL(withKey('jobs/' + id + '/file'), location.href).href;
    (navigator.clipboard ? navigator.clipboard.writeText(url) : Promise.reject()).then(function () {
      hint('Link copied. It works until the file is deleted.');
    }).catch(function () { window.prompt('Copy this link', url); });
  }

  function saveJobs() {
    var all = Object.keys(jobs).map(function (k) { return jobs[k]; });
    store.set('jobs', all.filter(function (j) { return !j.pc; }).map(function (j) { return j.id; }));
    store.set('pcjobs', all.filter(function (j) { return j.pc; }).map(function (j) { return j.id; }));
  }

  function restoreJobs() {
    store.get('jobs', []).forEach(function (id) {
      api('jobs/' + id).then(track).catch(function () { });
    });
  }

  // This PC: the Haul extension and its helper ---------------------------------------------
  // When the extension is installed, downloads run on the visitor's own computer through
  // haul-helper (yt-dlp + FFmpeg), the same way the extension's popup does it. The server is
  // only asked for link info. Anything the helper can't do yet goes to the server.

  var pc = { id: null, version: null, ready: false, error: null, timer: null };
  var STORE_URL = 'https://chromewebstore.google.com/detail/hnicogbjbfkjlkkeajepgjkkhojceeak';
  var helperUrl = null;
  var where = store.get('where', 'pc');
  var PC_HEIGHTS = [2160, 1440, 1080, 720, 480, 360];

  function extCall(msg, timeout, id) {
    id = id || pc.id;
    return new Promise(function (resolve, reject) {
      var rt = window.chrome && chrome.runtime;
      if (!rt || !rt.sendMessage || !id) return reject(new Error('The Haul extension is not installed.'));
      var t = setTimeout(function () { reject(new Error("The Haul extension didn't answer.")); }, timeout || 3000);
      try {
        rt.sendMessage(id, msg, function (r) {
          clearTimeout(t);
          var err = chrome.runtime.lastError;
          if (err || !r) reject(new Error((err && err.message) || 'No answer from the Haul extension.'));
          else resolve(r);
        });
      } catch (e) { clearTimeout(t); reject(e); }
    });
  }

  function detectPc(ids) {
    var tryOne = function (i) {
      if (i >= ids.length) { renderWhere(); return; }
      extCall({ cmd: 'hello' }, 1500, ids[i]).then(function (r) {
        pc.id = ids[i];
        pc.version = r.version || null;
        pc.ready = !!(r.helper && r.helper.ok !== false && r.helper.ready);
        pc.error = r.helper && r.helper.error;
        renderWhere();
        restorePcJobs();
      }).catch(function () { tryOne(i + 1); });
    };
    tryOne(0);
  }

  function onPc() { return !!pc.id && pc.ready && where === 'pc'; }

  function renderWhere() {
    show('wherewrap', !!pc.id && pc.ready);
    document.querySelectorAll('[data-where]').forEach(function (b) {
      b.setAttribute('aria-checked', String(b.getAttribute('data-where') === where));
    });
    var note = '';
    if (!pc.id) note = 'Downloads run on this server. <a href="' + STORE_URL + '" target="_blank" rel="noopener">Get the Haul extension</a> to download on your own PC instead.';
    else if (!pc.ready) note = pc.error === 'missing'
      ? "The Haul extension is installed, but its PC helper isn't set up yet. " + (helperUrl ? '<a href="helper">Download the PC helper</a>, or open the extension to finish setup.' : 'Open the extension to finish setup.') + ' Until then, downloads run on this server.'
      : "The Haul extension's PC helper isn't ready, so downloads run on this server for now.";
    else note = where === 'pc' ? 'Files are saved straight to this computer by the Haul extension. Nothing is kept on the server.'
      : 'Files are made on the server and deleted soon after you download them.';
    $('wherenote').innerHTML = note;
    if (state.list) updateListUi();
  }

  document.querySelectorAll('[data-where]').forEach(function (b) {
    b.onclick = function () { where = b.getAttribute('data-where'); store.set('where', where); renderWhere(); };
  });

  function pcBlockers(start, end) {
    var out = [];
    if (start != null || end != null) out.push('a clip');
    if (state.kind === 'video' && $('subs').value) out.push('subtitles');
    if ($('lang').value) out.push('picking the audio language');
    if (state.kind === 'video' && $('container').value !== 'mp4') out.push($('container').value.toUpperCase() + ' files');
    return out;
  }

  // The helper takes a height from its list, or "best".
  function pcQuality(h) {
    if (!h) return 'best';
    for (var i = 0; i < PC_HEIGHTS.length; i++) if (PC_HEIGHTS[i] <= h) return String(PC_HEIGHTS[i]);
    return '360';
  }

  function startPcJob(url, mode, quality, audioFormat, title) {
    return extCall({ cmd: 'download', url: url, mode: mode, title: title || undefined, quality: quality, audioFormat: audioFormat }, 10000)
      .then(function (r) {
        if (!r.ok) {
          if (r.error === 'notready' || r.error === 'missing') { pc.ready = false; pc.error = r.error; renderWhere(); }
          throw new Error(r.error === 'notready' ? "The Haul helper is still setting up. Try again in a minute."
            : r.error === 'missing' ? "The Haul helper isn't installed on this PC." : (r.error || "The Haul extension couldn't start this."));
        }
        r.job.quality = r.job.quality || quality;
        r.job.audioFormat = r.job.audioFormat || audioFormat;
        trackPc(r.job);
      });
  }

  var PC_STATE = { queued: ['queued', 'queued'], running: ['running', 'downloading'], processing: ['running', 'processing'],
    done: ['done', 'done'], error: ['error', 'error'], cancelled: ['cancelled', 'cancelled'] };

  function trackPc(ej) {
    var m = PC_STATE[ej.state] || ['running', 'downloading'];
    var prev = jobs[ej.id] || {};
    jobs[ej.id] = Object.assign(prev, {
      id: ej.id, pc: true, url: ej.url, title: ej.title, kind: ej.mode, status: m[0], stage: m[1],
      label: 'This PC · ' + (ej.mode === 'audio' ? String(ej.audioFormat || 'audio').toUpperCase() : (ej.quality && ej.quality !== 'best' ? ej.quality + 'p' : 'Best')),
      percent: ej.percent, speedText: ej.speed, etaText: ej.eta, file: ej.hasFile || ej.file, error: ej.error,
      created: (ej.created || Date.now()) / 1000
    });
    saveJobs();
    renderJobs();
    pcPoll();
  }

  function pcPoll() {
    if (pc.timer) return;
    pc.timer = setInterval(function () {
      var live = Object.keys(jobs).filter(function (k) { return jobs[k].pc && (jobs[k].status === 'queued' || jobs[k].status === 'running'); });
      if (!live.length) { clearInterval(pc.timer); pc.timer = null; return; }
      extCall({ cmd: 'jobs', ids: live }).then(function (r) {
        (r.jobs || []).forEach(function (ej) { if (live.indexOf(ej.id) >= 0) trackPc(ej); });
      }).catch(function () { });
    }, 1000);
  }

  function restorePcJobs() {
    var ids = store.get('pcjobs', []);
    if (!ids.length) return;
    extCall({ cmd: 'jobs', ids: ids }).then(function (r) {
      (r.jobs || []).forEach(function (ej) { if (ids.indexOf(ej.id) >= 0) trackPc(ej); });
    }).catch(function () { });
  }

  function pcStatusText(j) {
    if (j.status === 'done') return 'Saved on this PC';
    if (j.status === 'error') return 'Failed';
    if (j.status === 'cancelled') return 'Cancelled';
    if (j.status === 'queued') return 'Waiting';
    if (j.stage === 'processing') return 'Finishing';
    return ['Downloading', j.percent != null ? Math.floor(j.percent) + '%' : '', j.speedText, j.etaText ? j.etaText + ' left' : '']
      .filter(Boolean).join(' · ');
  }

  function pcActions(j, live) {
    var id = esc(j.id);
    if (j.status === 'done') return (j.file ? '<button class="btn small" data-reveal="' + id + '">Show in folder</button>' : '') +
      '<button class="btn small ghost" data-del="' + id + '">Clear</button>';
    if (live) return '<button class="btn small ghost" data-del="' + id + '">Cancel</button>';
    return (j.status === 'error' ? '<button class="btn small ghost" data-retry="' + id + '">Look up again</button>' : '') +
      '<button class="btn small ghost" data-del="' + id + '">Clear</button>';
  }

  function pcRemove(id) {
    var j = jobs[id];
    if (j && (j.status === 'queued' || j.status === 'running')) {
      extCall({ cmd: 'cancel', id: id }).catch(function () { });
      j.status = 'cancelled'; renderJobs();
      return;
    }
    forget(id);
  }

  function pcReveal(id) { extCall({ cmd: 'reveal', id: id }).catch(function (e) { hint(e.message, true); }); }

  // Wiring -------------------------------------------------------------------------------

  $('tab-video').onclick = function () { state.kind = 'video'; state.choice = null; renderRows(); moreSummary(); };
  $('tab-audio').onclick = function () { state.kind = 'audio'; state.choice = null; renderRows(); moreSummary(); };
  $('rows').addEventListener('change', function (e) {
    var v = e.target.value;
    state.choice = state.kind === 'video' ? parseInt(v, 10) : v;
    updateBitrate();
  });
  // Codec picks and sizes depend on the file type and audio track, so ask again.
  $('container').onchange = $('lang').onchange = function () { if (state.url) getInfo(state.url, true); };
  $('subs').onchange = $('start').oninput = $('end').oninput = moreSummary;
  $('chapters').addEventListener('click', function (e) {
    var i = e.target.getAttribute('data-ch');
    if (i == null) return;
    var c = state.info.chapters[+i];
    $('start').value = dur(c.start); $('end').value = dur(c.end);
    moreSummary();
  });
  $('go').onclick = startJob;
  $('showlist').onclick = function () { $('lang').innerHTML = ''; getInfo(state.url, false, true); };
  $('jobs').addEventListener('click', function (e) {
    var t = e.target, g = function (a) { return t.getAttribute(a); };
    if (g('data-save')) save(g('data-save'));
    if (g('data-del')) remove(g('data-del'));
    if (g('data-play')) play(g('data-play'));
    if (g('data-copy')) copyLink(g('data-copy'));
    if (g('data-reveal')) pcReveal(g('data-reveal'));
    if (g('data-retry')) { var j = jobs[g('data-retry')]; input.value = j.url; grow(); getInfo(j.url, false); window.scrollTo({ top: 0, behavior: 'smooth' }); }
  });
  $('clearjobs').onclick = function () {
    Object.keys(jobs).forEach(function (k) { if (['done', 'error', 'cancelled'].indexOf(jobs[k].status) >= 0) remove(k); });
  };
  $('keyform').addEventListener('submit', function (e) {
    e.preventDefault();
    store.set('key', $('key').value.trim());
    show('keycard', false);
    hint('Key saved.');
    restoreJobs();
  });

  api('health').then(function (h) {
    var t = h.limits.file_ttl_min;
    $('ttl').textContent = t >= 60 && t % 60 === 0 ? (t === 60 ? 'an hour' : t / 60 + ' hours') : t + ' minutes';
    if (h.auth && !store.get('key', '')) show('keycard', true);
    detectPc(h.extension_ids || []);
  }).catch(function () { renderWhere(); });

  restoreJobs();

  // Get Haul: the Android app, the extension and the PC helper -------------------------------
  fetch('api/releases').then(function (r) { return r.ok ? r.json() : null; }).then(function (r) {
    if (!r) return;
    var cards = [], a = r.android, x = r.extension, h = r.helper;
    function size(n) { return n ? ' · ' + bytes(n) : ''; }
    function ver(v) { return v ? 'Version ' + esc(v) : ''; }
    function notes(t) { return t ? '<p class="sub">' + esc(t) + '</p>' : ''; }
    if (a) {
      var main = a.apks.universal || a.apks[Object.keys(a.apks)[0]];
      var others = Object.keys(a.apks).filter(function (k) { return k !== 'universal'; });
      cards.push('<div class="ghcard"><h3>Android app</h3><p class="sub">' + ver(a.version) + size(main.size) + '</p>' +
        (a.changelog.length ? '<ul>' + a.changelog.slice(0, 4).map(function (c) { return '<li>' + esc(c) + '</li>'; }).join('') + '</ul>' : '') +
        '<a class="btn small" href="' + esc(a.url) + '" download>Download APK</a>' +
        (others.length && a.apks.universal ? '<div class="alt">Smaller builds: ' + others.map(function (k) { return '<a href="' + esc(a.apks[k].url) + '" download>' + esc(k) + '</a>'; }).join(' · ') + '</div>' : '') +
        '<div class="alt">Allow installs from your browser when Android asks.</div></div>');
    }
    if (x) {
      cards.push('<div class="ghcard"><h3>Browser extension</h3><p class="sub">' + [ver(x.version), 'Chrome, Edge, Brave'].filter(Boolean).join(' · ') + '</p>' + notes(x.notes) +
        (x.store_url ? '<a class="btn small" href="' + esc(x.store_url) + '" target="_blank" rel="noopener">Add to Chrome</a>' : '') +
        (x.url ? '<a class="btn small' + (x.store_url ? ' ghost' : '') + '" href="' + esc(x.url) + '" download>Download .zip' + size(x.size) + '</a>' : '') +
        (x.url && !x.store_url ? '<div class="alt">Unzip it, open chrome://extensions, turn on Developer mode and choose "Load unpacked".</div>' : '') + '</div>');
      if (x.store_url) STORE_URL = x.store_url;
    }
    if (h && (h.url || h.builds)) {
      helperUrl = 'helper';
      var b = h.builds || {}, systems = [b.windows && 'Windows', b.mac && 'Mac', (b['linux-x64'] || b['linux-arm64']) && 'Linux'].filter(Boolean);
      cards.push('<div class="ghcard"><h3>PC helper</h3><p class="sub">' + [ver(h.version), systems.join(', ') || 'Windows'].filter(Boolean).join(' · ') + '</p>' +
        (notes(h.notes) || '<p class="sub">Lets the extension download on this PC with full quality. Install it after the extension.</p>') +
        '<a class="btn small" href="helper">Download helper</a></div>');
    }
    $('ghgrid').innerHTML = cards.join('');
    $('ghfilelist').innerHTML = r.files.map(function (f) {
      return '<div class="ghfile"><a href="' + esc(f.url) + '" download>' + esc(f.title) + '</a><span>' + esc(bytes(f.size)) + '</span></div>';
    }).join('');
    show('ghfiles', r.files.length > 0);
    show('gethaul', cards.length > 0 || r.files.length > 0);
    renderWhere();
  }).catch(function () { });

  // Install as an app (Android/desktop Chrome). Sharing a link to it opens /?url=…
  var deferred = null;
  window.addEventListener('beforeinstallprompt', function (e) { e.preventDefault(); deferred = e; show('install', true); });
  $('install').onclick = function (e) { e.preventDefault(); if (deferred) { deferred.prompt(); deferred = null; show('install', false); } };
  if ('serviceWorker' in navigator) navigator.serviceWorker.register('sw.js').catch(function () { });

  // ?url=… (or a share's text/title) opens a link straight away. Used by the share sheet
  // and the browser extension.
  var q = new URLSearchParams(location.search);
  var shared = links([q.get('url'), q.get('text'), q.get('title')].filter(Boolean).join(' '));
  if (shared.length) {
    input.value = shared.join('\n'); grow();
    if (shared.length > 1) showBatch(shared); else getInfo(shared[0], false);
    history.replaceState(null, '', location.pathname);
  }
})();
