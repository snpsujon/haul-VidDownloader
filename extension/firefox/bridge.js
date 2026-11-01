// Runs on the Haul website only. Firefox doesn't let a site message an add-on directly (Chrome's
// externally_connectable), so the site talks to this script with window.postMessage instead:
//
//   page -> add-on:  window.postMessage({ haul: 'request', id, msg }, location.origin)
//   add-on -> page:  window.postMessage({ haul: 'reply', id, reply }, location.origin)
//
// msg is the same object the site sends Chrome ({ cmd: 'hello' }, { cmd: 'download', … } and so on).
// When this script loads it posts { haul: 'ready', version } and sets data-haul-extension on <html>.

(() => {
  const version = browser.runtime.getManifest().version;
  const post = (data) => window.postMessage(data, location.origin);

  window.addEventListener('message', (e) => {
    const d = e.data;
    if (e.source !== window || e.origin !== location.origin || !d || d.haul !== 'request') return;
    browser.runtime.sendMessage({ cmd: 'site', msg: d.msg })
      .then((reply) => post({ haul: 'reply', id: d.id, reply }),
        (err) => post({ haul: 'reply', id: d.id, reply: { ok: false, error: String(err?.message || err) } }));
  });

  document.documentElement.dataset.haulExtension = version;
  post({ haul: 'ready', version });
})();
