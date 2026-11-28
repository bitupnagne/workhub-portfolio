/* [A3] 직원×일자 근태 매트릭스 — 셀 클릭 상세/직권 정정 모달. soft-nav 대응 document 위임. */
(() => {
  const setText = (modal, sel, value) => {
    const el = modal.querySelector(sel);
    if (el) el.textContent = value && value.trim() !== '' ? value : '-';
  };

  document.addEventListener('click', (event) => {
    const modal = document.querySelector('[data-mx-modal]');
    if (!modal) return;
    if (event.target.closest('[data-mx-close]')) { // [UX24] backdrop 닫힘 제거(입력형)
      event.preventDefault();
      modal.hidden = true;
      return;
    }
    const cell = event.target.closest('[data-mx-date]');
    if (!cell) return;
    setText(modal, '[data-mx-modal-name]', cell.dataset.mxName);
    setText(modal, '[data-mx-modal-date]', cell.dataset.mxDate);
    setText(modal, '[data-mx-modal-status]', cell.dataset.mxStatuslabel);
    setText(modal, '[data-mx-modal-sched]', cell.dataset.mxSched);
    const inout = [cell.dataset.mxIn, cell.dataset.mxOut].filter(Boolean).join(' ~ ');
    setText(modal, '[data-mx-modal-inout]', inout);
    setText(modal, '[data-mx-modal-note]', cell.dataset.mxNote);
    const setVal = (sel, value) => {
      const el = modal.querySelector(sel);
      if (el) {
        el.value = value || '';
        el.dispatchEvent(new Event('change', { bubbles: true }));
      }
    };
    setVal('[data-mx-form-user]', cell.dataset.mxUser);
    setVal('[data-mx-form-date]', cell.dataset.mxDate);
    setVal('[data-mx-form-in]', cell.dataset.mxIn);
    setVal('[data-mx-form-out]', cell.dataset.mxOut);
    const statusSel = modal.querySelector('[data-mx-form-status]');
    if (statusSel) {
      const current = cell.dataset.mxStatus;
      statusSel.value = ['NORMAL', 'LATE', 'EARLY_LEAVE', 'ABSENT', 'REMOTE', 'OUTSIDE'].includes(current)
        ? current : 'NORMAL';
    }
    const reason = modal.querySelector('textarea[name="reason"]');
    if (reason) reason.value = '';
    modal.hidden = false;
  });

  document.addEventListener('keydown', (event) => {
    if (event.key !== 'Escape') return;
    const modal = document.querySelector('[data-mx-modal]');
    if (modal) modal.hidden = true;
  });
})();
