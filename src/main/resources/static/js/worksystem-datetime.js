/* 커스텀 일시 선택기. [data-dtp] 컨테이너 안의
   hidden(name=...) + readonly 표시 input + 팝오버(달력+시:분)를 제어한다.
   서버 제출 값은 yyyy-MM-ddTHH:mm 형식 유지. */
(function () {
  'use strict';
  function pad(n) { return String(n).padStart(2, '0'); }
  function fmtVal(d) {
    return d.getFullYear() + '-' + pad(d.getMonth() + 1) + '-' + pad(d.getDate())
      + 'T' + pad(d.getHours()) + ':' + pad(d.getMinutes());
  }
  function fmtLabel(d) {
    return d.getFullYear() + '. ' + pad(d.getMonth() + 1) + '. ' + pad(d.getDate())
      + ' (' + ['일','월','화','수','목','금','토'][d.getDay()] + ') '
      + pad(d.getHours()) + ':' + pad(d.getMinutes());
  }
  function parse(v) {
    var m = /^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2})/.exec(v || '');
    if (!m) return null;
    return new Date(+m[1], +m[2] - 1, +m[3], +m[4], +m[5]);
  }

  function build(box) {
    var hidden = box.querySelector('[data-dtp-value]');
    var display = box.querySelector('[data-dtp-display]');
    var pop = box.querySelector('[data-dtp-pop]');
    if (!hidden || !display || !pop) return;
    var cur = parse(hidden.value) || null;
    var view = cur ? new Date(cur.getTime()) : new Date();
    view.setDate(1);

    function sync() {
      display.value = cur ? fmtLabel(cur) : '';
      hidden.value = cur ? fmtVal(cur) : '';
    }
    function to12(h24) {
      var period = h24 >= 12 ? 'PM' : 'AM';
      var h12 = h24 % 12;
      if (h12 === 0) h12 = 12;
      return { period: period, hour: h12 };
    }
    function to24(period, h12) {
      var h = h12 % 12;
      if (period === 'PM') h += 12;
      return h;
    }
    function readClock() {
      var p = pop.querySelector('[data-p]').value;
      var h = +pop.querySelector('[data-h]').value;
      var m = +pop.querySelector('[data-m]').value;
      return { period: p, hour: h, minute: m, h24: to24(p, h) };
    }
    function render() {
      var y = view.getFullYear(), mo = view.getMonth();
      var first = new Date(y, mo, 1).getDay();
      var days = new Date(y, mo + 1, 0).getDate();
      var hh = cur ? cur.getHours() : 9;
      var mm = cur ? cur.getMinutes() : 0;
      var clock = to12(hh);
      var cells = '';
      for (var i = 0; i < first; i++) cells += '<span class="dtp-d empty"></span>';
      for (var d = 1; d <= days; d++) {
        var on = cur && cur.getFullYear() === y && cur.getMonth() === mo && cur.getDate() === d;
        cells += '<button type="button" class="dtp-d' + (on ? ' sel' : '') + '" data-d="' + d + '">' + d + '</button>';
      }
      var popt = '<option value="AM"' + (clock.period === 'AM' ? ' selected' : '') + '>오전</option>'
               + '<option value="PM"' + (clock.period === 'PM' ? ' selected' : '') + '>오후</option>';
      var hopt = '';
      for (var h = 1; h <= 12; h++) hopt += '<option value="' + h + '"' + (h === clock.hour ? ' selected' : '') + '>' + pad(h) + '</option>';
      var mopt = '';
      for (var mi = 0; mi < 60; mi += 5) mopt += '<option value="' + mi + '"' + (mi === mm ? ' selected' : '') + '>' + pad(mi) + '</option>';
      pop.innerHTML =
        '<div class="dtp-hd"><button type="button" class="dtp-nav" data-nav="-1">' + wsIcon('chevron_left') + '</button>'
        + '<b>' + y + '. ' + pad(mo + 1) + '</b>'
        + '<button type="button" class="dtp-nav" data-nav="1">' + wsIcon('chevron_right') + '</button></div>'
        + '<div class="dtp-dow"><span>일</span><span>월</span><span>화</span><span>수</span><span>목</span><span>금</span><span>토</span></div>'
        + '<div class="dtp-grid">' + cells + '</div>'
        + '<div class="dtp-time"><span>시간</span>'
        + '<select data-p>' + popt + '</select>'
        + '<select data-h>' + hopt + '</select> : <select data-m>' + mopt + '</select>'
        + '<button type="button" class="btn primary dtp-ok" data-ok>확인</button></div>';
    }
    function open() { render(); pop.hidden = false; box.classList.add('on'); }
    function close() { pop.hidden = true; box.classList.remove('on'); }

    display.addEventListener('click', function () { pop.hidden ? open() : close(); });
    pop.addEventListener('click', function (e) {
      var nav = e.target.closest('[data-nav]');
      if (nav) { view.setMonth(view.getMonth() + (+nav.dataset.nav)); render(); return; }
      var day = e.target.closest('[data-d]');
      if (day) {
        var ck = readClock();
        cur = new Date(view.getFullYear(), view.getMonth(), +day.dataset.d, ck.h24, ck.minute);
        render();
        return;
      }
      if (e.target.closest('[data-ok]')) {
        var ck2 = readClock();
        if (!cur) {
          cur = new Date(view.getFullYear(), view.getMonth(), 1, ck2.h24, ck2.minute);
        } else {
          cur.setHours(ck2.h24);
          cur.setMinutes(ck2.minute);
        }
        sync(); close();
      }
    });
    pop.addEventListener('change', function (e) {
      if (cur && (e.target.matches('[data-h]') || e.target.matches('[data-m]') || e.target.matches('[data-p]'))) {
        var ck3 = readClock();
        cur.setHours(ck3.h24);
        cur.setMinutes(ck3.minute);
      }
    });
    document.addEventListener('mousedown', function (e) {
      if (!pop.hidden && !box.contains(e.target)) close();
    });
    sync();
  }

  document.addEventListener('DOMContentLoaded', function () {
    document.querySelectorAll('[data-dtp]').forEach(build);
  });
})();
