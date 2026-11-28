/* worksystem-mobile.js
 * 모바일(≤980px) 전면 재구성 보조 스크립트.
 * - 데이터 표(table.rt)를 헤더 기준으로 "라벨 카드"로 변환할 수 있도록 각 td에 data-label을 주입.
 *   (실제 렌더된 thead를 읽으므로 th:if로 조건부 표시되는 컬럼도 정확히 정렬됨)
 * - 카드 모양 자체는 CSS(@media max-width:980)에서 처리. 이 스크립트는 라벨만 채운다.
 * - 데스크톱(≥981)에서는 아무 것도 하지 않는다(matchMedia 가드).
 * - 부분 갱신(partial-reload)·동적 추가 행에도 대응(MutationObserver + 이벤트).
 */
(function () {
  'use strict';
  var MQ = window.matchMedia('(max-width: 980px)');

  function labelTable(tbl) {
    if (tbl.classList.contains('no-mcard')) return;          // 카드화 제외 opt-out
    var ths = tbl.querySelectorAll(':scope > thead > tr > th');
    if (!ths.length) return;
    var labels = Array.prototype.map.call(ths, function (th) {
      return (th.textContent || '').replace(/\s+/g, ' ').trim();
    });
    var rows = tbl.querySelectorAll(':scope > tbody > tr');
    Array.prototype.forEach.call(rows, function (tr) {
      var tds = tr.children;
      // 빈 상태 행(colspan 단일 셀)은 라벨 없이 그대로 둠
      if (tds.length === 1 && tds[0].hasAttribute('colspan')) {
        tr.classList.add('m-empty-row');
        return;
      }
      for (var i = 0; i < tds.length; i++) {
        if (tds[i].hasAttribute('data-label')) continue;
        var lbl = labels[i];
        if (lbl) tds[i].setAttribute('data-label', lbl);
        else tds[i].setAttribute('data-label', '');           // 라벨 없는 컬럼(#, 관리 등 빈 헤더)도 표시 정리
      }
    });
  }

  function labelAll(root) {
    (root || document).querySelectorAll('table.rt').forEach(labelTable);
  }

  function run() {
    if (!MQ.matches) return;
    labelAll(document);
  }

  // 초기 실행
  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', run);
  } else {
    run();
  }

  // 뷰포트가 모바일 폭으로 바뀔 때(회전 등)
  if (MQ.addEventListener) MQ.addEventListener('change', run);
  else if (MQ.addListener) MQ.addListener(run);

  // 부분 갱신(목록 페이징/필터 ajax) 후 재적용
  document.addEventListener('ws:partial-reloaded', function (e) {
    if (!MQ.matches) return;
    labelAll((e && e.target && e.target.nodeType === 1) ? e.target : document);
  });

  // 동적으로 행/표가 추가되는 경우 대응(가벼운 관찰)
  if ('MutationObserver' in window) {
    var pending = false;
    var mo = new MutationObserver(function () {
      if (pending || !MQ.matches) return;
      pending = true;
      (window.requestAnimationFrame || window.setTimeout)(function () {
        pending = false;
        labelAll(document);
      });
    });
    var start = function () { mo.observe(document.body, { childList: true, subtree: true }); };
    if (document.body) start();
    else document.addEventListener('DOMContentLoaded', start);
  }

  // ── 우측 패널/도크가 열리면 배경(body) 스크롤 잠금 ──
  // 모바일에서 패널이 화면을 완전히 덮을 때, 뒷화면이 같이 스크롤되거나 동작하는 것을 막는다.
  // 패널은 .open 클래스, 채팅/자료전송 도크는 [hidden] 속성으로 토글되므로 둘 다 관찰한다.
  function setupOverlayScrollLock() {
    var panel = document.getElementById('vfRightPanel');
    var chatDock = document.getElementById('vfChatDock');
    var wmDock = document.getElementById('vfWorkMemoDock');
    if (!panel && !chatDock && !wmDock) return;
    function sync() {
      var open = (panel && panel.classList.contains('open'))
        || (chatDock && !chatDock.hasAttribute('hidden'))
        || (wmDock && !wmDock.hasAttribute('hidden'));
      document.body.classList.toggle('m-overlay-open', !!(MQ.matches && open));
    }
    if ('MutationObserver' in window) {
      var ob = new MutationObserver(sync);
      if (panel) ob.observe(panel, { attributes: true, attributeFilter: ['class'] });
      if (chatDock) ob.observe(chatDock, { attributes: true, attributeFilter: ['hidden'] });
      if (wmDock) ob.observe(wmDock, { attributes: true, attributeFilter: ['hidden'] });
    }
    if (MQ.addEventListener) MQ.addEventListener('change', sync);
    else if (MQ.addListener) MQ.addListener(sync);
    sync();
  }
  if (document.body) setupOverlayScrollLock();
  else document.addEventListener('DOMContentLoaded', setupOverlayScrollLock);

  // ── 풀스크린 패널: 상단 × 대신 화면 맨 아래 고정 '닫기' 바(앱 스타일) ──
  // 기존 [data-panel-close](상단 ×)는 CSS로 숨기고, 하단 큰 버튼이 같은 닫기를 호출.
  function setupPanelBottomClose() {
    var panel = document.getElementById('vfRightPanel');
    if (!panel || panel.querySelector('.m-sheet-close')) return;
    var bar = document.createElement('div');
    bar.className = 'm-sheet-close';
    var btn = document.createElement('button');
    btn.type = 'button';
    btn.className = 'm-sheet-close-btn';
    btn.textContent = '닫기';
    btn.addEventListener('click', function () {
      var x = panel.querySelector('[data-panel-close]');
      if (x) x.click();
      else panel.classList.remove('open');
    });
    bar.appendChild(btn);
    panel.appendChild(bar);   // flex column의 마지막 child → 본문 아래 고정
  }
  if (document.body) setupPanelBottomClose();
  else document.addEventListener('DOMContentLoaded', setupPanelBottomClose);

  // ── 모바일 섹션 아코디언(.section-block.m-acc) — 헤더 탭으로 펼침/접힘(기본 접힘) ──
  function setupMobileAccordions(root) {
    if (!MQ.matches) return;
    (root || document).querySelectorAll('.section-block.m-acc').forEach(function (sec) {
      if (sec.__accInit) return;
      sec.__accInit = true;
      var head = sec.querySelector(':scope > .section-header');
      if (!head) return;
      head.addEventListener('click', function (e) {
        if (e.target.closest('a, button, input, select, label')) return; // 헤더 내 컨트롤은 통과
        sec.classList.toggle('m-open');
      });
    });
  }
  if (document.body) setupMobileAccordions();
  else document.addEventListener('DOMContentLoaded', setupMobileAccordions);
  if (MQ.addEventListener) MQ.addEventListener('change', function () { setupMobileAccordions(); });
  window.addEventListener('worksystem:content-updated', function () { setupMobileAccordions(document); });
  document.addEventListener('ws:partial-reloaded', function (e) {
    if (!MQ.matches) return;
    setupMobileAccordions(document);
    // 부분 갱신된 섹션이 아코디언이면 펼친 상태 유지(사용자가 그 안에서 조작 중)
    var t = e && e.target;
    if (t && t.classList && t.classList.contains('m-acc')) t.classList.add('m-open');
  });
})();
