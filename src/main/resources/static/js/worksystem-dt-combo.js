/**
 * worksystem-dt-combo.js
 *  - [data-dtcombo] 컨테이너 안의 (hidden value) + (date input) + (time input)를 묶어준다.
 *  - 두 입력 어느 쪽이든 변하면 hidden 값을 "yyyy-MM-ddTHH:mm" 형식으로 갱신한다.
 *  - 빈 값이면 hidden도 비운다.
 *  - <input type="time">은 다른 스크립트(worksystem-time-picker.js)가 오전/오후 + 시:분 위젯으로 변환한다.
 */
(() => {
  const pad2 = (n) => String(n).padStart(2, '0');

  const sync = (combo) => {
    const hidden = combo.querySelector('[data-dtcombo-value]');
    const dateInput = combo.querySelector('[data-dtcombo-date]');
    const timeInput = combo.querySelector('[data-dtcombo-time]');
    if (!hidden || !dateInput || !timeInput) return;
    const d = (dateInput.value || '').trim();
    const t = (timeInput.value || '').trim();
    if (!d) { hidden.value = ''; return; }
    const tt = t || '00:00';
    hidden.value = d + 'T' + (tt.length === 5 ? tt : tt.slice(0, 5));
  };

  const initialFill = (combo) => {
    const hidden = combo.querySelector('[data-dtcombo-value]');
    const dateInput = combo.querySelector('[data-dtcombo-date]');
    const timeInput = combo.querySelector('[data-dtcombo-time]');
    if (!hidden || !dateInput || !timeInput) return;
    const v = (hidden.value || '').trim();
    const m = /^(\d{4}-\d{2}-\d{2})T(\d{2}:\d{2})/.exec(v);
    if (m) {
      dateInput.value = m[1];
      timeInput.value = m[2];
      // Soft-rendered dates may already have picker UI when this combo is filled.
      dateInput.dispatchEvent(new Event('change', { bubbles: true }));
      timeInput.dispatchEvent(new Event('change', { bubbles: true }));
    }
  };

  const enhance = (combo) => {
    if (combo.dataset.dtcomboEnhanced === '1') return;
    combo.dataset.dtcomboEnhanced = '1';
    initialFill(combo);
    const dateInput = combo.querySelector('[data-dtcombo-date]');
    const timeInput = combo.querySelector('[data-dtcombo-time]');
    if (dateInput) dateInput.addEventListener('change', () => sync(combo));
    if (timeInput) {
      timeInput.addEventListener('change', () => sync(combo));
      timeInput.addEventListener('input', () => sync(combo));
    }
    sync(combo);
  };

  const scan = (root = document) => {
    root.querySelectorAll('[data-dtcombo]').forEach(enhance);
  };

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', () => scan());
  } else {
    scan();
  }
  window.addEventListener('worksystem:content-updated', () => scan());
  document.addEventListener('content-updated', () => scan());
})();
