/**
 * worksystem-partial-reload.js
 *  - `<a data-partial-reload="#selector">` 클릭 시 페이지 전체 새로고침 대신 fetch + 해당 영역만 교체.
 *  - `<form data-partial-toggle>` submit 시 fetch POST + 버튼/UI 즉시 토글 (URL/검색어 유지).
 */
(() => {
  // 부분 리로드: 링크 클릭 → fetch HTML → 지정 selector만 교체 + URL 동기화
  document.addEventListener('click', async (event) => {
    const link = event.target.closest('a[data-partial-reload]');
    if (!link) return;
    event.preventDefault();
    const url = link.href;
    const selector = link.dataset.partialReload;
    if (!selector) return;
    try {
      const res = await fetch(url, { headers: { Accept: 'text/html' }, credentials: 'same-origin' });
      if (!res.ok) { window.location.href = url; return; }
      const html = await res.text();
      const newDoc = new DOMParser().parseFromString(html, 'text/html');
      const fresh = newDoc.querySelector(selector);
      const current = document.querySelector(selector);
      if (fresh && current) {
        current.replaceWith(fresh);
        // [UX30] 검색 파라미터 URL 노출 금지 — pathname만. [UX28] 모달 안에서는 주소 유지.
        if (!link.closest('[data-app-form-modal]')) {
          try {
            const clean = new URL(url, window.location.href);
            clean.search = '';
            clean.hash = '';
            history.pushState(null, '', clean.href);
          } catch { /* ignore */ }
        }
      } else {
        window.location.href = url;
      }
    } catch {
      window.location.href = url;
    }
  });

  // 부분 토글: form[data-partial-toggle] submit → fetch POST → 응답 OK면 버튼 텍스트/클래스 즉시 토글
  document.addEventListener('submit', async (event) => {
    // 확인 모달(worksystem-modal.js)이 먼저 preventDefault 했으면(미확인 1차 제출) 건너뜀.
    // 확인 후 재제출(requestSubmit) 때는 defaultPrevented=false라 여기서 처리.
    if (event.defaultPrevented) return;
    const form = event.target.closest('form[data-partial-toggle]');
    if (!form) return;
    event.preventDefault();
    const grantInput = form.querySelector('input[name="grant"]');
    const button = form.querySelector('button[type="submit"]');
    if (!button || button.disabled) return;
    button.disabled = true;
    try {
      const fd = new FormData(form);
      const res = await fetch(form.action, { method: 'POST', body: fd, credentials: 'same-origin', redirect: 'follow' });
      if (!res.ok && !res.redirected) { button.disabled = false; return; }
      // 슈퍼관리자 등 연동(cascade) 케이스: 한 클릭이 같은 행의 다른 권한까지 바꾸므로
      // 버튼만 토글하면 안 되고, 현재 URL(검색어 포함)로 다시 받아 '직원 권한' 영역만 교체.
      if (form.hasAttribute('data-cascade')) {
        try {
          const fresh = await fetch(window.location.href, { headers: { Accept: 'text/html' }, credentials: 'same-origin' });
          if (fresh.ok) {
            const doc = new DOMParser().parseFromString(await fresh.text(), 'text/html');
            const a = doc.querySelector('#rolesSection');
            const b = document.querySelector('#rolesSection');
            if (a && b) { b.replaceWith(a); return; }
          }
        } catch { /* ignore → 아래 버튼 토글로 폴백 */ }
      }
      // 일반 케이스: grant 값 반전 + 버튼 텍스트/클래스 즉시 토글
      if (grantInput) {
        const wasGranting = grantInput.value === 'true';
        grantInput.value = wasGranting ? 'false' : 'true';
        if (wasGranting) {
          button.textContent = '해제';
          button.className = 'rbtn del';
        } else {
          button.textContent = '지정';
          button.className = 'rbtn acc';
        }
      }
    } finally {
      button.disabled = false;
    }
  });
})();
