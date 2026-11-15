/* 출장 경비 행 편집 — [UX23] 신청 모달 주입에서도 동작하도록 폼 단위 init 가드 +
   worksystem:content-updated 재초기화. */
(() => {
  const initForm = (form) => {
    if (!form || form.dataset.tripInit) return;
    form.dataset.tripInit = '1';
    const rows = form.querySelector('[data-expense-rows]');
    const addButton = form.querySelector('[data-expense-add]');
    if (!rows) return;
    const money = new Intl.NumberFormat('ko-KR');

    const rowTemplate = () => {
      const first = rows.querySelector('[data-expense-row]');
      const clone = first.cloneNode(true);
      clone.querySelectorAll('input').forEach((input) => { input.value = ''; });
      clone.querySelector('[data-expense-type]').selectedIndex = 0;
      return clone;
    };

    const amountOf = (row) => Number(row.querySelector('[data-expense-amount]')?.value || 0);

    const updateNames = () => {
      [...rows.querySelectorAll('[data-expense-row]')].forEach((row, index) => {
        row.querySelector('[data-expense-type]').name = `expenses[${index}].expenseType`;
        row.querySelector('[data-expense-description]').name = `expenses[${index}].description`;
        row.querySelector('[data-expense-amount]').name = `expenses[${index}].amount`;
        row.querySelector('[data-expense-note]').name = `expenses[${index}].note`;
      });
    };

    const updateTotals = () => {
      const totals = { TRANSPORT: 0, LODGING: 0, MEAL: 0, DAILY: 0, ETC: 0 };
      rows.querySelectorAll('[data-expense-row]').forEach((row) => {
        const type = row.querySelector('[data-expense-type]')?.value;
        if (type && Object.prototype.hasOwnProperty.call(totals, type)) {
          totals[type] += amountOf(row);
        }
      });
      let grandTotal = 0;
      Object.entries(totals).forEach(([type, total]) => {
        grandTotal += total;
        const target = form.querySelector(`[data-expense-total="${type}"]`);
        if (target) target.textContent = `${money.format(total)}원`;
      });
      const grandTarget = form.querySelector('[data-expense-grand-total]');
      if (grandTarget) grandTarget.textContent = `${money.format(grandTotal)}원`;
    };

    addButton?.addEventListener('click', () => {
      rows.appendChild(rowTemplate());
      updateNames();
      updateTotals();
    });

    rows.addEventListener('click', (event) => {
      const removeButton = event.target.closest('[data-expense-remove]');
      if (!removeButton) return;
      const allRows = rows.querySelectorAll('[data-expense-row]');
      if (allRows.length === 1) {
        allRows[0].querySelectorAll('input').forEach((input) => { input.value = ''; });
        allRows[0].querySelector('[data-expense-type]').selectedIndex = 0;
      } else {
        removeButton.closest('[data-expense-row]')?.remove();
      }
      updateNames();
      updateTotals();
    });

    rows.addEventListener('input', updateTotals);
    rows.addEventListener('change', updateTotals);
    form.addEventListener('submit', updateNames);
    updateNames();
    updateTotals();
  };

  const initAll = () => document.querySelectorAll('[data-trip-expense-form]').forEach(initForm);
  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', initAll);
  else initAll();
  window.addEventListener('worksystem:content-updated', initAll);
})();
