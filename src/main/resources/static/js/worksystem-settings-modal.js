/* [UX3] 설정 대시보드 공용 모달 — data-modal-open="#id" / data-modal-close. soft-nav 대응 document 위임. */
(() => {
  const closeAll = () => {
    document.querySelectorAll('.schedule-edit-modal[data-settings-modal]').forEach((m) => { m.hidden = true; });
  };

  document.addEventListener('click', (event) => {
    const opener = event.target.closest('[data-modal-open]');
    if (opener) {
      event.preventDefault();
      const modal = document.querySelector(opener.getAttribute('data-modal-open'));
      if (modal) {
        closeAll();
        modal.hidden = false;
        modal.querySelector('input,select,textarea,button')?.focus();
      }
      return;
    }
    if (event.target.closest('[data-modal-close]')) {
      event.preventDefault();
      closeAll();
      return;
    }
    // [UX24] 입력형 모달 - backdrop 클릭으로 닫지 않음(닫기/취소 버튼만)
  });

  /* [UX11] 주간보고식 '+ 행 추가' — data-add-row 버튼이 인접 template을 복제해
     __IDX__ 를 다음 인덱스로 치환하여 자기 행 앞 또는 같은 폼의 명시된 tbody 끝에 삽입한다. */
  document.addEventListener('click', (event) => {
    const btn = event.target.closest('[data-add-row]');
    if (!btn) return;
    event.preventDefault();
    const anchorRow = btn.closest('tr');
    const target = btn.dataset.addTarget ? btn.form?.querySelector(btn.dataset.addTarget) : anchorRow?.parentElement;
    const tpl = document.querySelector(btn.getAttribute('data-add-row'));
    if (!target || target.tagName !== 'TBODY' || !tpl) return;
    const next = Number(btn.dataset.nextIndex || tpl.dataset.startIndex || '0');
    btn.dataset.nextIndex = String(next + 1);
    const holder = document.createElement('tbody');
    holder.innerHTML = tpl.innerHTML.replaceAll('__IDX__', String(next)).trim();
    const row = holder.querySelector('tr');
    if (row) {
      if (anchorRow) anchorRow.before(row);
      else target.append(row);
      row.querySelector('input,select')?.focus();
    }
  });

  document.addEventListener('click', (event) => {
    const rm = event.target.closest('[data-remove-row]');
    if (!rm) return;
    event.preventDefault();
    rm.closest('tr')?.remove();
  });

  document.addEventListener('keydown', (event) => {
    if (event.key === 'Escape') closeAll();
  });
})();
