// Full-page QR code, opened from the right-click menu.
(async () => {
  const q = new URLSearchParams(location.search);
  const url = q.get('u') || '';
  const settings = await getSettings();

  document.getElementById('title').textContent = q.get('t') || url;
  document.getElementById('host').textContent = hostOf(url);

  const render = () => {
    drawQr(document.getElementById('qr'), phoneLink(url, settings.qrMode), 300);
    const haul = settings.qrMode === 'haul';
    document.getElementById('hint').textContent = haul
      ? 'Scan with your phone camera. It opens straight in Haul.'
      : 'Scan with your phone camera, then tap Share and pick Haul.';
    document.getElementById('switchMode').textContent = haul ? 'Not opening Haul?' : 'Open in Haul directly';
  };
  render();

  document.getElementById('switchMode').onclick = async () => {
    settings.qrMode = settings.qrMode === 'haul' ? 'plain' : 'haul';
    await saveSettings({ qrMode: settings.qrMode });
    render();
  };
  document.getElementById('copy').onclick = async (e) => {
    await navigator.clipboard.writeText(url);
    e.target.textContent = 'Copied';
    setTimeout(() => (e.target.textContent = 'Copy link'), 1200);
  };
})();
