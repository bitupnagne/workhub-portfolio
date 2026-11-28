/*
 * worksystem-tabs.js — 주소에 남는 세부 탭.
 *
 *   <div data-tabs="tab">                       ← 값은 주소 파라미터 이름(기본 'tab')
 *     <div class="ws-tabs" role="tablist">
 *       <button type="button" data-tab="info" role="tab">기본정보</button> …
 *     </div>
 *     <section data-tab-panel="info"> … </section> …
 *   </div>
 *
 *  - 모든 탭 내용은 서버가 한 번에 그려 두고, 전환은 보이기만 바꾼다(깜빡임·재요청 없음).
 *  - ?tab=값 으로 새로고침·뒤로가기·알림 링크가 정확한 탭을 연다. 탭을 바꾸면 주소에 남는다(pushState).
 *  - 첫 탭은 data-tab-default 가 있으면 그것, 없으면 첫 버튼.
 *  - 고른 탭이 가로 스크롤 밖이면 보이게 굴린다(모바일).
 */
(function () {
  'use strict';
  if (window.__wsTabsBound) return;
  window.__wsTabsBound = true;

  function param(box) {
    return box.getAttribute('data-tabs') || 'tab';
  }

  function activate(box, key, push) {
    var buttons = box.querySelectorAll(':scope [data-tab]');
    var panels = box.querySelectorAll(':scope [data-tab-panel]');
    var found = false;
    buttons.forEach(function (b) {
      if (b.closest('[data-tabs]') !== box) return;
      var on = b.getAttribute('data-tab') === key;
      if (on) found = true;
      b.classList.toggle('active', on);
      b.setAttribute('aria-selected', on ? 'true' : 'false');
      b.setAttribute('tabindex', on ? '0' : '-1');
      if (on && b.scrollIntoView) {
        var bar = b.parentElement;
        if (bar && bar.scrollWidth > bar.clientWidth) b.scrollIntoView({ block: 'nearest', inline: 'center' });
      }
    });
    if (!found) return false;
    panels.forEach(function (p) {
      if (p.closest('[data-tabs]') !== box) return;
      p.hidden = p.getAttribute('data-tab-panel') !== key;
    });
    if (push) {
      var url = new URL(window.location.href);
      url.searchParams.set(param(box), key);
      history.pushState({ wsTab: true }, '', url.pathname + url.search + url.hash);
    }
    box.dispatchEvent(new CustomEvent('worksystem:tab', { bubbles: true, detail: { tab: key } }));
    return true;
  }

  function init(box) {
    var key = new URL(window.location.href).searchParams.get(param(box));
    var first = box.querySelector(':scope [data-tab]');
    if (!key || !activate(box, key, false)) {
      var fallback = box.getAttribute('data-tab-default') || (first && first.getAttribute('data-tab'));
      if (fallback) activate(box, fallback, false);
    }
  }

  function scan() {
    document.querySelectorAll('[data-tabs]').forEach(init);
  }

  document.addEventListener('click', function (event) {
    var button = event.target.closest('[data-tab]');
    if (!button) return;
    var box = button.closest('[data-tabs]');
    if (!box) return;
    event.preventDefault();
    if (!button.classList.contains('active')) activate(box, button.getAttribute('data-tab'), true);
  });

  document.addEventListener('keydown', function (event) {
    var button = event.target.closest && event.target.closest('[data-tab]');
    if (!button || (event.key !== 'ArrowRight' && event.key !== 'ArrowLeft')) return;
    var list = Array.prototype.filter.call(button.parentElement.querySelectorAll('[data-tab]'), function (b) { return !b.hidden; });
    var index = list.indexOf(button) + (event.key === 'ArrowRight' ? 1 : -1);
    var next = list[(index + list.length) % list.length];
    if (next) { next.focus(); next.click(); }
  });

  window.addEventListener('popstate', scan);
  window.addEventListener('worksystem:content-updated', scan);
  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', scan);
  else scan();
  window.wsTabActivate = function (box, key) { return activate(box, key, true); };
})();
