/*
 * worksystem-icons.js — 스크립트가 그리는 아이콘도 템플릿과 같은 Material Icons 스프라이트에서 가져온다.
 *   window.wsIcon('close')            → '<svg class="i i-close" …><use href="…/icons.svg#close"/></svg>'
 *   window.wsIconEl('close')          → 같은 SVG 요소
 * 스프라이트 주소는 사이드바의 [data-icon-sprite](내용 해시가 붙은 주소)에서 읽는다.
 * Google Material Icons, Apache-2.0 — META-INF/licenses/google-material-icons-LICENSE.txt
 */
(function () {
  'use strict';
  function sprite() {
    var holder = document.querySelector('[data-icon-sprite]');
    return (holder && holder.getAttribute('data-href')) || '/img/icons.svg';
  }
  function safe(name) {
    return String(name).replace(/[^a-z_]/g, '');
  }
  window.wsIcon = function (name, extraClass) {
    var n = safe(name);
    return '<svg class="i i-' + n + (extraClass ? ' ' + extraClass : '') + '" viewBox="0 0 24 24" aria-hidden="true" focusable="false">'
      + '<use href="' + sprite() + '#' + n + '"></use></svg>';
  };
  window.wsIconEl = function (name, extraClass) {
    var wrap = document.createElement('span');
    wrap.innerHTML = window.wsIcon(name, extraClass);
    return wrap.firstChild;
  };
})();
