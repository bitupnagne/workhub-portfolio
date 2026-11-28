/*
 * worksystem-view-transition.js — 페이지 이동 화면 전환(@view-transition navigation:auto)의 콘솔 오류 정리.
 *
 * 폼 POST 로 떠나는 이동(로그인·저장·승인 등)은 서버가 302 로 다른 주소를 돌려준다. 이때 이전 페이지가
 * 시작한 전환을 새 페이지가 이어받지 못해 브라우저가 전환을 버리고, 아무도 받지 않은 거부가
 * "Uncaught (in promise) InvalidStateError / Transition was skipped" 로 콘솔에 찍혔다(실측 20회 중 11회).
 *  - POST 로 떠날 때는 전환을 처음부터 건너뛴다(어차피 리다이렉트로 깨진다).
 *  - 그 밖의 이동에서 전환이 중단돼도 거부를 여기서 받아 콘솔에 남지 않게 한다.
 * 링크 클릭 같은 일반 이동의 페이드 효과는 그대로다.
 */
(function () {
  'use strict';
  if (typeof window === 'undefined' || !('onpageswap' in window)) return;
  if (window.__wsViewTransitionBound) return;
  window.__wsViewTransitionBound = true;

  var leavingByPost = false;

  function quiet(vt) {
    if (!vt) return;
    ['ready', 'finished', 'updateCallbackDone'].forEach(function (key) {
      var p = vt[key];
      if (p && typeof p.catch === 'function') p.catch(function () {});
    });
  }

  document.addEventListener('submit', function (event) {
    var form = event.target;
    if (!form || event.defaultPrevented) return;
    var method = (event.submitter && event.submitter.getAttribute('formmethod')) || form.getAttribute('method') || 'get';
    leavingByPost = method.toLowerCase() === 'post';
  }, true);

  window.addEventListener('pageswap', function (event) {
    quiet(event.viewTransition);
    if (event.viewTransition && leavingByPost) event.viewTransition.skipTransition();
  });

  window.addEventListener('pagereveal', function (event) {
    quiet(event.viewTransition);
  });

  // 뒤로 가기(bfcache)로 돌아오면 이전 제출 표시는 지운다
  window.addEventListener('pageshow', function () { leavingByPost = false; });
})();
