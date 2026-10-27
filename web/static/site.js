// Site-wide bits set on the admin page: the site's name, the announcement banner and the
// contact email. Loaded on every page.
(function () {
  function esc(s) { return String(s).replace(/[&<>"']/g, function (c) { return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]; }); }
  fetch('api/site').then(function (r) { return r.ok ? r.json() : null; }).then(function (s) {
    if (!s) return;
    var name = s.site_name || 'Haul';
    if (name !== 'Haul') {
      [].forEach.call(document.querySelectorAll('.mark'), function (m) { m.innerHTML = esc(name.toLowerCase()) + '<span>.</span>'; });
      document.title = document.title.replace(/\bHaul\b/g, name);
    }
    var box = document.getElementById('contactmail');
    if (box && s.contact_email) {
      var mail = s.contact_email.replace(/[<>&"\s]/g, '');
      box.innerHTML = 'Email: <a href="mailto:' + esc(mail) + '">' + esc(mail) + '</a>';
    }
    if (s.banner) {
      var seen = null;
      try { seen = localStorage.getItem('haul-banner-closed'); } catch (e) { }
      if (seen === s.banner) return;
      var b = document.createElement('div');
      b.className = 'sitebanner ' + (s.banner_level || 'info');
      b.setAttribute('role', s.banner_level === 'bad' ? 'alert' : 'status');
      b.innerHTML = '<span>' + esc(s.banner) + '</span><button type="button" aria-label="Hide this message">×</button>';
      b.querySelector('button').onclick = function () {
        b.remove();
        try { localStorage.setItem('haul-banner-closed', s.banner); } catch (e) { }
      };
      document.body.insertBefore(b, document.body.firstChild);
    }
  }).catch(function () { });
})();
