(function () {
  function pad(value) { return String(value).padStart(2, '0'); }
  function isoDate(date) { return date.getFullYear() + '-' + pad(date.getMonth() + 1) + '-' + pad(date.getDate()); }
  function mondayOfWeek(date) {
    var d = new Date(date.getFullYear(), date.getMonth(), date.getDate());
    var day = d.getDay() || 7;
    d.setDate(d.getDate() - day + 1);
    return d;
  }
  function getWeekKey(date) {
    var d = mondayOfWeek(date);
    d.setDate(d.getDate() + 3);
    var firstThursday = new Date(d.getFullYear(), 0, 4);
    firstThursday = mondayOfWeek(firstThursday);
    var week = 1 + Math.round((d - firstThursday) / 604800000);
    return d.getFullYear() + '-W' + pad(week);
  }
  function getWeekStart(weekKey) {
    var parts = weekKey.split('-W');
    var year = Number(parts[0]);
    var week = Number(parts[1]);
    var jan4 = new Date(year, 0, 4);
    var monday = mondayOfWeek(jan4);
    monday.setDate(monday.getDate() + (week - 1) * 7);
    return monday;
  }
  function labelMonth(date) { return date.getFullYear() + '년 ' + (date.getMonth() + 1) + '월'; }
  function goToWeek(path, weekKey) {
    var url = new URL(path, window.location.origin);
    url.searchParams.set('week', weekKey);
    var target = url.pathname + url.search;
    if (typeof window.worksystemSoftNavigate === 'function') {
      window.worksystemSoftNavigate(target);
      return;
    }
    window.location.href = target;
  }
  if (window.__worksystemWeekPicker) return;
  window.__worksystemWeekPicker = true;
  var active = null;
  function close(restoreFocus) {
    if (!active) return;
    var owner = active.btn;
    active.overlay.classList.remove('open');
    owner.classList.remove('open');
    owner.setAttribute('aria-expanded', 'false');
    window.removeEventListener('resize', positionPopup);
    window.removeEventListener('scroll', positionPopup, true);
    active = null;
    if (restoreFocus && owner.isConnected) owner.focus({ preventScroll: true });
  }
  function positionPopup() {
    if (!active) return;
    var btn = active.btn, overlay = active.overlay;
    if (!btn.isConnected || overlay !== document.getElementById('weekCalOverlay')) { close(false); return; }
    var popup = overlay.querySelector('.cal-popup'), anchor = btn.getBoundingClientRect();
    var top = Math.max(8, anchor.bottom + 6);
    var edge = document.documentElement.clientWidth - popup.offsetWidth - 8;
    popup.style.position = 'fixed';
    popup.style.left = Math.max(8, Math.min(anchor.left, edge)) + 'px';
    popup.style.top = top + 'px';
    popup.style.maxHeight = Math.max(0, window.innerHeight - top - 8) + 'px';
    popup.style.overflowY = 'auto';
  }
  function render() {
    if (!active) return;
    var monthLabel = active.overlay.querySelector('#weekCalMonth'), grid = active.overlay.querySelector('#weekCalGrid');
    var month = active.month;
    monthLabel.textContent = labelMonth(month);
    var cursor = mondayOfWeek(new Date(month.getFullYear(), month.getMonth(), 1));
    var end = new Date(month.getFullYear(), month.getMonth() + 1, 0), html = '';
    while (cursor <= end) {
      var monday = new Date(cursor.getFullYear(), cursor.getMonth(), cursor.getDate());
      var weekKey = getWeekKey(monday), classes = ['cal-week-row'];
      if (weekKey === active.btn.dataset.week) classes.push('cal-selected');
      if (weekKey === active.btn.dataset.currentWeek) classes.push('cal-current-week');
      var weeksWithData = new Set((active.btn.dataset.weeks || '').split(',').filter(Boolean));
      html += '<button type="button" class="' + classes.join(' ') + '" data-week="' + weekKey + '">';
      for (var i = 0; i < 7; i++) {
        var day = new Date(monday.getFullYear(), monday.getMonth(), monday.getDate() + i), cells = ['cal-cell'];
        if (day.getMonth() !== month.getMonth()) cells.push('cal-other');
        if (i >= 5) cells.push('cal-weekend');
        if (isoDate(day) === isoDate(new Date())) cells.push('cal-today');
        html += '<span class="' + cells.join(' ') + '">' + day.getDate() + '</span>';
      }
      html += '<span class="cal-data-indicator">' + (weeksWithData.has(weekKey) ? '<span class="cal-dot"></span>' : '') + '</span></button>';
      cursor.setDate(cursor.getDate() + 7);
    }
    grid.innerHTML = html;
  }
  function prepare() {
    var btn = document.getElementById('weekPickerBtn');
    if (!btn) return;
    btn.setAttribute('aria-haspopup', 'dialog');
    btn.setAttribute('aria-controls', 'weekCalOverlay');
    btn.setAttribute('aria-expanded', 'false');
  }
  // 버튼/overlay가 조건부로 바뀌어도 document 위임은 한 번만 연결한다.
  // 주차·대상 경로·점 표시는 매번 현재 버튼 dataset에서 읽는다.
  document.addEventListener('click', function (event) {
    var btn = event.target.closest('#weekPickerBtn');
    if (btn) {
      var overlay = document.getElementById('weekCalOverlay');
      if (!overlay) return;
      event.preventDefault();
      if (active && active.btn === btn) { close(true); return; }
      close(false);
      var start = getWeekStart(btn.dataset.week || btn.dataset.currentWeek);
      active = { btn: btn, overlay: overlay, month: new Date(start.getFullYear(), start.getMonth(), 1) };
      render(); overlay.classList.add('open'); btn.classList.add('open'); btn.setAttribute('aria-expanded', 'true');
      positionPopup(); window.addEventListener('resize', positionPopup); window.addEventListener('scroll', positionPopup, true);
      return;
    }
    if (!active) return;
    if (event.target === active.overlay) { event.preventDefault(); close(true); return; }
    if (!active.overlay.contains(event.target)) return;
    if (event.target.closest('[data-cal-prev]') || event.target.closest('[data-cal-next]')) {
      event.preventDefault();
      active.month = new Date(active.month.getFullYear(), active.month.getMonth() + (event.target.closest('[data-cal-prev]') ? -1 : 1), 1);
      render(); return;
    }
    var row = event.target.closest('#weekCalGrid [data-week]');
    if (row) {
      event.preventDefault();
      var owner = active.btn, week = row.dataset.week;
      close(true);
      var url = new URL(owner.dataset.currentPath || window.location.pathname, window.location.origin);
      url.searchParams.set('week', week);
      if (typeof window.worksystemSoftNavigate === 'function') window.worksystemSoftNavigate(url.pathname + url.search, { source: owner });
      else goToWeek(url.pathname, week);
    }
  });
  document.addEventListener('keydown', function (event) { if (event.key === 'Escape' && active) { event.preventDefault(); close(true); } });
  window.addEventListener('worksystem:navigation-start', function () { close(false); });
  window.addEventListener('worksystem:content-updated', function () { close(false); prepare(); });
  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', prepare, { once: true });
  else prepare();

})();
