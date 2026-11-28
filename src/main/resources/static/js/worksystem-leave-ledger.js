/* [A5] 연차 원장 — 조정 모달. soft-nav 대응 document 위임. */
(() => {
  document.addEventListener('click', (event) => {
    const modal = document.querySelector('[data-ll-modal]');
    if (!modal) return;
    if (event.target.closest('[data-ll-close]')) { // [UX24] backdrop 닫힘 제거(입력형)
      event.preventDefault();
      modal.hidden = true;
      return;
    }
    const btn = event.target.closest('[data-ll-adjust]');
    if (!btn) return;
    event.preventDefault();
    const nameEl = modal.querySelector('[data-ll-modal-name]');
    if (nameEl) nameEl.textContent = btn.dataset.userName || '';
    const remainEl = modal.querySelector('[data-ll-modal-remaining]');
    if (remainEl) remainEl.textContent = btn.dataset.remaining || '0';
    const userInput = modal.querySelector('[data-ll-form-user]');
    if (userInput) userInput.value = btn.dataset.userId || '';
    const delta = modal.querySelector('input[name="deltaDays"]');
    if (delta) delta.value = '';
    const reason = modal.querySelector('textarea[name="reason"]');
    if (reason) reason.value = '';
    modal.hidden = false;
    if (delta) delta.focus();
  });

  document.addEventListener('keydown', (event) => {
    if (event.key !== 'Escape') return;
    const modal = document.querySelector('[data-ll-modal]');
    if (modal) modal.hidden = true;
  });
})();
