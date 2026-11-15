/*
 * worksystem-input-tooltip.js
 *
 * 입력칸에 적은 값이 칸보다 길면, 지금까지는 캐럿을 끌거나 가로 스크롤해야 뒷부분을 볼 수 있었다.
 * 마우스를 올렸을 때 전체 값을 말풍선으로 띄워 준다.
 *
 * 설계 메모
 *  - 넘칠 때만 뜬다(scrollWidth > clientWidth). 안 넘치는 칸에까지 말풍선이 붙으면 성가시다.
 *  - 편집 중에는 뜨지 않는다(focus 상태). 캐럿 위치를 가리면 오히려 방해가 된다.
 *  - title 속성을 쓰지 않는다. 브라우저 기본 툴팁은 1초 가까이 지연되고 서식을 맞출 수 없다.
 *  - 위치는 position:fixed 로 잡는다. .table-scroll 같은 overflow 컨테이너 안에서도 잘리지 않는다.
 */
(function () {
  'use strict';

  var SELECTOR = 'input[type="text"], input:not([type]), input[type="search"], input[type="number"], textarea';
  var tip = null;
  var current = null;

  function ensureTip() {
    if (!tip) {
      tip = document.createElement('div');
      tip.className = 'ws-input-tip';
      tip.setAttribute('role', 'tooltip');
      document.body.appendChild(tip);
    }
    return tip;
  }

  function overflows(el) {
    if (el.tagName === 'TEXTAREA') {
      return el.scrollHeight > el.clientHeight + 1;
    }
    return el.scrollWidth > el.clientWidth + 1;
  }

  function show(el) {
    var value = el.value;
    if (!value || !value.trim()) { return; }
    if (!overflows(el)) { return; }

    var t = ensureTip();
    t.textContent = value;
    t.classList.add('open');
    current = el;

    // 먼저 보이게 한 뒤 크기를 재야 실제 폭이 나온다.
    var rect = el.getBoundingClientRect();
    var tw = t.offsetWidth;
    var th = t.offsetHeight;
    var gap = 6;

    var left = rect.left;
    if (left + tw > window.innerWidth - 8) { left = window.innerWidth - tw - 8; }
    if (left < 8) { left = 8; }

    // 위쪽에 자리가 없으면 아래로 내린다.
    var top = rect.top - th - gap;
    t.classList.toggle('below', top < 8);
    if (top < 8) { top = rect.bottom + gap; }

    t.style.left = left + 'px';
    t.style.top = top + 'px';
  }

  function hide() {
    if (tip) { tip.classList.remove('open'); }
    current = null;
  }

  document.addEventListener('mouseover', function (e) {
    var el = e.target.closest ? e.target.closest(SELECTOR) : null;
    if (!el || el === current) { return; }
    if (el === document.activeElement) { return; }
    show(el);
  });

  document.addEventListener('mouseout', function (e) {
    var el = e.target.closest ? e.target.closest(SELECTOR) : null;
    if (el && el === current) { hide(); }
  });

  document.addEventListener('focusin', function (e) {
    if (current && e.target === current) { hide(); }
  });

  // 스크롤·리사이즈로 입력칸이 움직이면 말풍선만 남는다.
  window.addEventListener('scroll', hide, true);
  window.addEventListener('resize', hide);
})();
