/**
 * worksystem-date-apply.js
 *  - 모든 <input type="date"> / <input type="datetime-local"> 옆에 '확인' 버튼을 붙인다.
 *  - 네이티브 날짜/시간 선택은 change 이벤트가 blur 전까지 발생하지 않아(브라우저별)
 *    "다른 영역을 클릭해야 적용"되는 문제가 있다 → '확인' 버튼이 값을 즉시 커밋한다.
 *  - 클릭 시: change 이벤트 발생(자동 제출/콤보 동기화 트리거) + blur(피커 닫기/커밋).
 *  - data-date-apply-skip 속성이 있으면 제외.
 */
(() => {
  const decorate = (input) => {
    if (!input || input.dataset.dateApplyReady === '1') return;
    if (input.hasAttribute('data-date-apply-skip')) return;
    if (input.type !== 'date' && input.type !== 'datetime-local') return;
    input.dataset.dateApplyReady = '1';

    const btn = document.createElement('button');
    btn.type = 'button';
    btn.className = 'ws-date-apply';
    btn.textContent = '확인';
    btn.title = '날짜 적용';
    btn.addEventListener('click', () => {
      // 값 커밋 + 변경 전파(필터 자동제출·dt-combo 동기화 등) + 포커스 해제
      input.dispatchEvent(new Event('change', { bubbles: true }));
      input.blur();
    });
    input.insertAdjacentElement('afterend', btn);
  };

  const scan = (root) => {
    (root || document).querySelectorAll('input[type="date"], input[type="datetime-local"]').forEach(decorate);
  };

  const run = () => scan(document);
  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', run);
  } else {
    run();
  }
  // soft-nav 콘텐츠 교체 후 재적용
  document.addEventListener('content-updated', run);
  // 동적 추가(주간보고 행 등) 대응
  if (document.body) {
    new MutationObserver((mutations) => {
      for (const m of mutations) {
        for (const node of m.addedNodes) {
          if (node.nodeType !== 1) continue;
          if (node.matches && (node.matches('input[type="date"]') || node.matches('input[type="datetime-local"]'))) {
            decorate(node);
          }
          scan(node);
        }
      }
    }).observe(document.body, { childList: true, subtree: true });
  }
})();
