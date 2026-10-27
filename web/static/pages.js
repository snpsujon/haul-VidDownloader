// Fills in the contact email (set with HAUL_CONTACT_EMAIL on the server) on the info pages.
(function () {
  fetch('api/health').then(function (r) { return r.json(); }).then(function (h) {
    var mail = h.contact_email, box = document.getElementById('contactmail');
    if (!mail) return;
    var a = '<a href="mailto:' + encodeURIComponent(mail).replace('%40', '@') + '">' + mail.replace(/[<>&"]/g, '') + '</a>';
    if (box) box.innerHTML = 'Email: ' + a;
  }).catch(function () { });
})();
