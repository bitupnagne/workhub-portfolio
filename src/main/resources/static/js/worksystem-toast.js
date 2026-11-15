(function () {
  function dismiss(t) {
    t.classList.add('leaving');
    setTimeout(function () { t.remove(); }, 260);
  }
  function build() {
    var wrap = document.getElementById('vfToasts');
    if (!wrap) return;
    var banners = document.querySelectorAll('.content .banner');
    for (var i = 0; i < banners.length; i++) {
      (function (bn) {
        var txt = (bn.textContent || '').trim();
        if (!txt) { bn.remove(); return; }
        var kind = bn.classList.contains('err') ? 'err'
          : (bn.classList.contains('warn') ? 'warn' : 'ok');
        var t = document.createElement('div');
        t.className = 'vf-toast ' + kind;
        t.setAttribute('role', 'status');
        t.textContent = txt;
        t.addEventListener('click', function () { dismiss(t); });
        wrap.appendChild(t);
        setTimeout(function () { if (t.isConnected) dismiss(t); }, 4200);
        bn.remove();
      })(banners[i]);
    }
  }
  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', build);
  } else {
    build();
  }
  window.addEventListener('load', build);
  document.addEventListener('worksystem:content-updated', build);
})();
