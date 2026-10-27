const $ = (id) => document.getElementById(id);

(async () => {
  const settings = await getSettings();

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

  check();
})();

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
