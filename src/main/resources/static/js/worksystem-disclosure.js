/*
 * worksystem-disclosure.js — 접기/펼치기 공통 동작.
 *
 * 모든 <details>(data-no-disclosure 제외)와 [data-disclosure] 묶음이 같은 모양·같은 움직임을 갖는다.
 *  - 머리 줄 맨 오른쪽에 꺾쇠 아이콘 하나(.ws-chev) — 펼치면 아래로 돈다. 다른 곳에 꺾쇠·글자를 두지 않는다.
 *  - 약 0.2초 높이 애니메이션. 기기에서 '동작 줄이기'를 켜면 바로 열고 닫는다.
 *  - 머리 줄 안의 버튼·링크·입력을 누르면 접히지 않는다.
 *
 * [data-disclosure] 묶음: 머리 [data-disclosure-head], 본문 [data-disclosure-body]. 열림 상태는 .is-open.
 * 요소는 누른 시점에 찾는다(화면 전환 뒤에도 동작).
 */
(function () {
  'use strict';
  if (window.__wsDisclosureBound) return;
  window.__wsDisclosureBound = true;

  var DURATION = 200;
  var reduced = function () {
    return window.matchMedia && window.matchMedia('(prefers-reduced-motion: reduce)').matches;
  };
  var interactive = 'a,button,input,select,textarea,label,[data-no-toggle]';

  function chevron() {
    return window.wsIconEl ? window.wsIconEl('expand_more', 'ws-chev') : null;
  }

  function ensureChevron(head) {
    if (!head || head.hasAttribute('data-no-chev') || head.querySelector(':scope > .ws-chev, :scope .ws-chev-slot > .ws-chev')) return;
    var icon = chevron();
    if (!icon) return;
    var slot = head.querySelector('.ws-chev-slot');
    (slot || head).appendChild(icon);
  }

  function scan(root) {
    (root || document).querySelectorAll('details:not([data-no-disclosure]) > summary').forEach(ensureChevron);
    (root || document).querySelectorAll('[data-disclosure] > [data-disclosure-head]').forEach(function (head) {
      ensureChevron(head);
      var box = head.parentElement;
      head.setAttribute('role', 'button');
      if (!head.hasAttribute('tabindex')) head.setAttribute('tabindex', '0');
      head.setAttribute('aria-expanded', box.classList.contains('is-open') ? 'true' : 'false');
    });
  }

  /* ── <details> ── */
  function animateDetails(details, open) {
    var summary = details.querySelector(':scope > summary');
    if (!summary || reduced() || details.hasAttribute('data-no-anim')) {
      details.open = open;
      return;
    }
    if (details._wsAnim) details._wsAnim.cancel();
    var start = details.offsetHeight;
    if (open) details.open = true;
    var end = open ? details.scrollHeight : summary.offsetHeight + (details.offsetHeight - details.clientHeight);
    details.classList.toggle('is-closing', !open);
    details.style.overflow = 'hidden';
    var anim = details.animate({ height: [start + 'px', end + 'px'] }, { duration: DURATION, easing: 'cubic-bezier(.2,.7,.3,1)' });
    details._wsAnim = anim;
    anim.onfinish = anim.oncancel = function () {
      details._wsAnim = null;
      details.style.overflow = '';
      details.classList.remove('is-closing');
      if (!open && anim.playState === 'finished') details.open = false;
    };
  }

  document.addEventListener('click', function (event) {
    var summary = event.target.closest('details:not([data-no-disclosure]) > summary');
    if (summary) {
      if (event.target !== summary && event.target.closest(interactive) && summary.contains(event.target.closest(interactive))) return;
      var details = summary.parentElement;
      event.preventDefault();
      animateDetails(details, !details.open || details.classList.contains('is-closing'));
      return;
    }
    var head = event.target.closest('[data-disclosure] > [data-disclosure-head]');
    if (head) {
      var hit = event.target.closest(interactive);
      if (hit && head.contains(hit) && hit !== head) return;
      toggleBox(head.parentElement);
    }
  });

  document.addEventListener('keydown', function (event) {
    if (event.key !== 'Enter' && event.key !== ' ') return;
    var head = event.target.closest && event.target.closest('[data-disclosure] > [data-disclosure-head]');
    if (!head || event.target !== head) return;
    event.preventDefault();
    toggleBox(head.parentElement);
  });

  /* ── [data-disclosure] 묶음: 본문은 CSS(grid-template-rows 0fr→1fr)로 움직인다 ── */
  function toggleBox(box) {
    var open = !box.classList.contains('is-open');
    box.classList.toggle('is-open', open);
    var head = box.querySelector(':scope > [data-disclosure-head]');
    if (head) head.setAttribute('aria-expanded', open ? 'true' : 'false');
    box.dispatchEvent(new CustomEvent('worksystem:disclosure', { bubbles: true, detail: { open: open } }));
  }
  window.wsDisclosureToggle = toggleBox;

  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', function () { scan(); });
  else scan();
  window.addEventListener('worksystem:content-updated', function () { scan(); });
  document.addEventListener('worksystem:content-updated', function () { scan(); });
  if (window.MutationObserver) {
    var pending = false;
    new MutationObserver(function () {
      if (pending) return;
      pending = true;
      requestAnimationFrame(function () { pending = false; scan(); });
    }).observe(document.documentElement, { childList: true, subtree: true });
  }
})();
