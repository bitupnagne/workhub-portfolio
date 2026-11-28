/* [UX47] 출장보고서 — 경비 '신청 시와 동일'/'금액 변동' 토글.
   동일 선택 시 편집 영역을 숨기고 입력을 disabled 처리해 제출에서 제외한다. */
(() => {
  const init = () => {
    const form = document.querySelector('[data-report-form]');
    if (!form || form.dataset.reportInit) return;
    form.dataset.reportInit = '1';
    const editor = form.querySelector('[data-report-expense-editor]');
    if (!editor) return;
    const sync = () => {
      const changedRadio = form.querySelector('[data-exp-mode][value="true"]');
      const changed = !!(changedRadio && changedRadio.checked);
      editor.hidden = !changed;
      editor.querySelectorAll('input, select, button').forEach((el) => { el.disabled = !changed; });
    };
    form.querySelectorAll('[data-exp-mode]').forEach((r) => r.addEventListener('change', sync));
    sync();
  };
  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', init);
  else init();
  window.addEventListener('worksystem:content-updated', init);
})();
