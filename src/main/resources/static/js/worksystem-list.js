/*
 * worksystem-list.js — 목록 화면 표준: 필터는 바꾸면 바로 반영, '조회' 버튼 없음.
 *
 *  - [data-ws-query-filter] GET 폼: 선택·날짜·체크를 바꾸면 바로, 글자 검색은 입력을 멈추면(0.45초) 반영한다.
 *    반영 자체는 기존 화면 부분 갱신(worksystem-soft-submit.js)이 맡는다 — 깜빡임 없이 목록만 다시 그린다.
 *  - '초기화'는 필터가 걸려 있을 때만 보인다.
 */
(function () {
  'use strict';
  if (window.__wsListBound) return;
  window.__wsListBound = true;

  var SELECTOR = 'form[data-ws-query-filter]';
  var timers = new WeakMap();

  function submit(form) {
    if (form.requestSubmit) form.requestSubmit();
    else form.submit();
  }

  function isText(el) {
    return el.matches('input[type="search"], input[type="text"], input:not([type])');
  }

  function filtered(form) {
    return Array.prototype.some.call(form.elements, function (el) {
      if (!el.name || el.type === 'hidden' || el.type === 'submit' || el.type === 'button') return false;
      if (el.tagName === 'SELECT') return el.selectedIndex > 0;
      if (el.type === 'checkbox' || el.type === 'radio') return el.checked !== el.defaultChecked;
      return String(el.value || '').trim() !== '';
    });
  }

  function syncReset(form) {
    form.querySelectorAll('[data-ws-query-reset]').forEach(function (link) {
      if ((link.textContent || '').trim() !== '초기화') return;
      link.hidden = !filtered(form);
    });
  }

  function scan() {
    document.querySelectorAll(SELECTOR).forEach(syncReset);
  }

  document.addEventListener('change', function (event) {
    var form = event.target.closest && event.target.closest(SELECTOR);
    if (!form || isText(event.target)) return;
    syncReset(form);
    submit(form);
  });

  document.addEventListener('input', function (event) {
    var form = event.target.closest && event.target.closest(SELECTOR);
    if (!form || !isText(event.target)) return;
    clearTimeout(timers.get(form));
    timers.set(form, setTimeout(function () { syncReset(form); submit(form); }, 450));
  });

  document.addEventListener('keydown', function (event) {
    if (event.key !== 'Enter') return;
    var form = event.target.closest && event.target.closest(SELECTOR);
    if (!form || !isText(event.target)) return;
    event.preventDefault();
    clearTimeout(timers.get(form));
    submit(form);
  });

  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', scan);
  else scan();
  window.addEventListener('worksystem:content-updated', scan);
  document.addEventListener('worksystem:query-filter-sync', scan);
})();
