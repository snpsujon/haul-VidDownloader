const $ = (id) => document.getElementById(id);

(async () => {
  const settings = await getSettings();
  $('helperLink').href = HELPER_DOWNLOAD_URL;

  for (const r of document.querySelectorAll('input[name="qrMode"]')) {
    r.checked = r.value === settings.qrMode;
    r.onchange = () => saveSettings({ qrMode: r.value });
  }

  $('folder').value = settings.folder;
  $('saveFolder').onclick = async () => {
    await saveSettings({ folder: $('folder').value.trim() });
    $('folderSaved').hidden = false;
    setTimeout(() => ($('folderSaved').hidden = true), 1500);
  };
  $('openFolder').onclick = () => chrome.runtime.sendMessage({ cmd: 'openFolder', folder: $('folder').value.trim() });
  $('recheck').onclick = check;
  $('update').onclick = async () => {
    $('update').disabled = true;
    $('update').textContent = 'Updating…';
    const r = await chrome.runtime.sendMessage({ cmd: 'updateTools' });
    $('update').disabled = false;
    $('update').textContent = 'Update yt-dlp';
    $('details').textContent = r.ok ? r.message : r.error;
  };

  // Download button on videos
  const toggle = $('inpageToggle');
  toggle.checked = settings.inpageButton && (await chrome.permissions.contains(ALL_SITES));
  toggle.onchange = async () => {
    if (toggle.checked) {
      const granted = await chrome.permissions.request(ALL_SITES);
      toggle.checked = granted;
      if (granted) await saveSettings({ inpageButton: true });
    } else {
      await saveSettings({ inpageButton: false });
      await chrome.permissions.remove(ALL_SITES);
    }
    syncYt();
  };
  const yt = $('youtubeToggle');
  yt.checked = settings.youtubeButton;
  yt.onchange = () => saveSettings({ youtubeButton: yt.checked });
  const syncYt = () => { yt.disabled = !toggle.checked; $('youtubeRow').style.opacity = toggle.checked ? 1 : 0.5; };
  syncYt();
  renderHidden(settings.hiddenSites);
  chrome.storage.onChanged.addListener((c, area) => {
    if (area === 'sync' && c.hiddenSites) renderHidden(c.hiddenSites.newValue || []);
  });

  // Shortcuts
  for (const c of await chrome.commands.getAll()) {
    if (c.name === '_execute_action') continue;
    const li = document.createElement('li');
    const name = document.createElement('span');
    name.textContent = c.description;
    const key = document.createElement('kbd');
    key.textContent = c.shortcut || 'Not set';
    li.append(name, key);
    $('shortcuts').append(li);
  }
  $('editShortcuts').onclick = () => chrome.tabs.create({ url: 'chrome://extensions/shortcuts' });

  // Subtitles and cover art
  $('subs').value = settings.subs;
  $('thumb').value = settings.thumb;
  $('subLangs').value = settings.subLangs;
  $('subs').onchange = () => saveSettings({ subs: $('subs').value });
  $('thumb').onchange = () => saveSettings({ thumb: $('thumb').value });
  $('subLangs').onchange = () => {
    const v = $('subLangs').value.replace(/\s+/g, '');
    const ok = /^[A-Za-z0-9,.*_-]{1,60}$/.test(v);
    $('subLangs').value = ok ? v : 'en';
    saveSettings({ subLangs: ok ? v : 'en' });
  };

  // Pairing
  $('pairStart').onclick = startPairing;
  $('pairCancel').onclick = stopPairing;
  $('unpair').onclick = async () => {
    await chrome.runtime.sendMessage({ cmd: 'unpair' });
    renderPairState();
  };
  renderPairState();

  check();
})();

let pairTimer = 0;

async function renderPairState() {
  const { pair } = await chrome.storage.local.get('pair');
  $('pairOff').hidden = !!pair;
  $('pairOn').hidden = !pair;
  $('pairing').hidden = true;
  if (pair) $('pairDevice').textContent = pair.device;
}

async function startPairing() {
  $('pairError').hidden = true;
  $('pairStart').disabled = true;
  const r = await chrome.runtime.sendMessage({ cmd: 'pairStart' });
  $('pairStart').disabled = false;
  if (!r.ok) {
    $('pairError').textContent = r.error;
    $('pairError').hidden = false;
    return;
  }
  drawQr($('pairQr'), r.qr, 220);
  $('pairOff').hidden = true;
  $('pairing').hidden = false;
  clearInterval(pairTimer);
  pairTimer = setInterval(async () => {
    const c = await chrome.runtime.sendMessage({ cmd: 'pairCheck' });
    if (c.claimed) {
      clearInterval(pairTimer);
      renderPairState();
    } else if (!c.ok) {
      clearInterval(pairTimer);
      renderPairState();
      $('pairError').textContent = 'The pairing code ran out. Try again.';
      $('pairError').hidden = false;
    }
  }, 2000);
}

function stopPairing() {
  clearInterval(pairTimer);
  chrome.storage.local.remove('pairPending');
  renderPairState();
}

// Shows "Get helper X" when the website has a newer helper than the installed one.
async function offerNewHelper(installed) {
  try {
    const r = await fetch(RELEASES_URL, { cache: 'no-store' });
    const { helper } = await r.json();
    if (!helper?.version || !newerVersion(helper.version, installed)) return;
    $('newHelper').href = new URL(helper.url || '/get/helper', SITE_URL).href;
    $('newHelper').textContent = `Get helper ${helper.version}`;
    $('newHelper').hidden = false;
  } catch {
    // The site isn't reachable or doesn't allow this request. Nothing to show.
  }
}

function renderHidden(sites) {
  $('hiddenBox').hidden = !sites.length;
  $('hiddenList').replaceChildren(...sites.map((site) => {
    const li = document.createElement('li');
    const name = document.createElement('span');
    name.textContent = site;
    const b = document.createElement('button');
    b.className = 'btn small';
    b.textContent = 'Show again';
    b.onclick = async () => {
      const { hiddenSites } = await getSettings();
      await saveSettings({ hiddenSites: hiddenSites.filter((s) => s !== site) });
    };
    li.append(name, b);
    return li;
  }));
}

async function check() {
  $('statusText').textContent = 'Checking…';
  $('dot').className = 'dot';
  const s = await chrome.runtime.sendMessage({ cmd: 'status' });
  const ready = s.ok && s.ready;
  $('dot').className = 'dot ' + (ready ? 'ok' : 'bad');
  $('setupSteps').hidden = ready;
  $('update').hidden = !s.ok;
  $('folderBox').hidden = !s.ok;

  if (ready) {
    $('statusText').textContent = 'Ready';
    $('details').textContent = `Helper ${s.version} · yt-dlp ${s.ytdlp} · FFmpeg ${s.ffmpeg ? 'found' : 'missing'}`;
    offerNewHelper(s.version);
  } else if (s.ok) {
    $('statusText').textContent = 'Helper found, but its tools are missing';
    $('details').textContent = 'Run haul-helper.exe again to finish downloading yt-dlp and FFmpeg.';
  } else if (s.error === 'missing') {
    $('statusText').textContent = 'Not set up yet';
    $('details').textContent = '';
  } else {
    $('statusText').textContent = 'The helper is installed but not answering';
    $('details').textContent = s.error;
  }
  if (s.folder) $('defaultFolder').textContent = s.folder;
}
