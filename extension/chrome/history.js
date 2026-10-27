const $ = (id) => document.getElementById(id);

let history = [];
let filter = 'all';
let missing = new Set(); // files that were moved or deleted

(async () => {
  ({ history = [] } = await chrome.storage.local.get('history'));
  render();
  checkFiles();

  $('q').oninput = render;
  document.querySelectorAll('[data-f]').forEach((b) => (b.onclick = () => {
    filter = b.dataset.f;
    document.querySelectorAll('[data-f]').forEach((x) => x.setAttribute('aria-selected', String(x === b)));
    render();
  }));
  $('clearAll').onclick = async () => {
    if (!confirm('Clear the whole download history? The files themselves stay where they are.')) return;
    await chrome.storage.local.set({ history: [] });
  };
  chrome.storage.onChanged.addListener((c, area) => {
    if (area === 'local' && c.history) {
      history = c.history.newValue || [];
      render();
    }
  });
})();

// Asks the helper which saved files still exist.
async function checkFiles() {
  const paths = history.map((h) => h.file).filter(Boolean);
  if (!paths.length) return;
  const r = await chrome.runtime.sendMessage({ cmd: 'stat', paths });
  if (!r?.ok || !Array.isArray(r.exists)) return;
  missing = new Set(paths.filter((_, i) => !r.exists[i]));
  render();
}

function render() {
  const q = $('q').value.trim().toLowerCase();
  const items = history.filter((h) =>
    (filter === 'all' || h.mode === filter)
    && (!q || (h.title || '').toLowerCase().includes(q) || (h.host || '').toLowerCase().includes(q)));
  $('empty').hidden = items.length > 0;
  if (!history.length) $('empty').textContent = 'Nothing here yet. Things you save on this PC with Haul show up here.';
  else if (!items.length) $('empty').textContent = 'Nothing matches.';

  const groups = new Map();
  for (const h of items) {
    const label = dayLabel(h.finished);
    if (!groups.has(label)) groups.set(label, []);
    groups.get(label).push(h);
  }
  const out = [];
  for (const [label, list] of groups) {
    const h2 = document.createElement('h2');
    h2.textContent = label;
    const ul = document.createElement('ul');
    ul.append(...list.map(row));
    out.push(h2, ul);
  }
  $('list').replaceChildren(...out);
}

function row(h) {
  const li = document.createElement('li');
  const name = document.createElement('div');
  name.className = 'name';
  name.textContent = h.title || h.url;
  name.title = h.title || h.url;

  const meta = document.createElement('div');
  meta.className = 'meta';
  const time = new Date(h.finished).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
  const fmt = h.mode === 'audio' ? (h.audioFormat || 'mp3').toUpperCase() : (h.quality === 'best' ? 'Best' : (h.quality || '') + 'p');
  meta.textContent = [h.host, jobKind(h), fmt, h.playlist ? `${h.saved || 0} saved` : '', time].filter(Boolean).join(' · ');
  const gone = h.file && missing.has(h.file);
  if (gone) {
    const g = document.createElement('span');
    g.className = 'gone';
    g.textContent = ' · File moved or deleted';
    meta.append(g);
  }

  const acts = document.createElement('div');
  acts.className = 'acts';
  if (h.file && !gone) {
    acts.append(
      button(h.playlist ? 'Open folder' : 'Open', () => chrome.runtime.sendMessage({ cmd: 'open', path: h.file }), true),
    );
    if (!h.playlist) acts.append(button('Show in folder', () => chrome.runtime.sendMessage({ cmd: 'reveal', path: h.file })));
  }
  acts.append(
    button('Download again', async (b) => {
      b.disabled = true;
      const opts = { quality: h.quality, audioFormat: h.audioFormat, playlist: h.playlist, start: h.start, end: h.end };
      const r = await chrome.runtime.sendMessage({ cmd: 'download', url: h.url, mode: h.mode, title: h.title, opts });
      b.textContent = r.ok ? 'Started' : (r.error === 'missing' || r.error === 'notready' ? 'Helper not set up' : 'Failed');
      if (!r.ok) b.title = r.error;
    }),
    button('Copy link', async (b) => {
      await navigator.clipboard.writeText(h.url);
      b.textContent = 'Copied';
      setTimeout(() => (b.textContent = 'Copy link'), 1200);
    }),
  );
  const x = document.createElement('button');
  x.className = 'x';
  x.textContent = '×';
  x.title = 'Remove from history';
  x.onclick = async () => {
    const { history: all = [] } = await chrome.storage.local.get('history');
    await chrome.storage.local.set({ history: all.filter((e) => e.id !== h.id) });
  };
  acts.append(x);

  li.append(name, acts, meta);
  return li;
}

function button(text, onclick, primary) {
  const b = document.createElement('button');
  b.className = 'btn small' + (primary ? ' primary' : '');
  b.textContent = text;
  b.onclick = () => onclick(b);
  return b;
}

function dayLabel(ts) {
  const d = new Date(ts);
  const today = new Date();
  const start = (x) => new Date(x.getFullYear(), x.getMonth(), x.getDate()).getTime();
  const diff = Math.round((start(today) - start(d)) / 86400000);
  if (diff === 0) return 'Today';
  if (diff === 1) return 'Yesterday';
  return d.toLocaleDateString([], { weekday: diff < 7 ? 'long' : undefined, day: 'numeric', month: 'long', year: d.getFullYear() === today.getFullYear() ? undefined : 'numeric' });
}
