/**
 * worksystem-time-picker.js
 *  - 페이지 안의 모든 <input type="time">을 [오전/오후 select][HH][MM] UI로 대체한다.
 *  - 원본 input은 hidden으로 유지하고, 사용자 입력을 24시간 HH:MM 형식으로 동기화한다.
 *  - data-time-skip 속성이 있는 input은 변환에서 제외한다.
 */
(() => {
  const pad2 = (n) => String(n).padStart(2, '0');

  // 24h "HH:MM" → { period, hour12, minute }
  const parse24 = (value) => {
    if (!value) return { period: 'AM', hour12: 9, minute: 0 };
    const m = /^(\d{1,2}):(\d{2})/.exec(value);
    if (!m) return { period: 'AM', hour12: 9, minute: 0 };
    let h = Number(m[1]);
    const min = Number(m[2]);
    const period = h >= 12 ? 'PM' : 'AM';
    let h12 = h % 12;
    if (h12 === 0) h12 = 12;
    return { period, hour12: h12, minute: min };
  };

  const to24 = (period, hour12, minute) => {
    let h = Number(hour12);
    const m = Number(minute);
    if (Number.isNaN(h) || Number.isNaN(m)) return '';
    if (h < 1 || h > 12 || m < 0 || m > 59) return '';
    if (period === 'PM' && h !== 12) h += 12;
    if (period === 'AM' && h === 12) h = 0;
    return `${pad2(h)}:${pad2(m)}`;
  };

  const enhance = (input) => {
    if (input.dataset.timeEnhanced === '1') return;
    if (input.hasAttribute('data-time-skip')) return;
    input.dataset.timeEnhanced = '1';

    const init = parse24(input.value || input.getAttribute('th:value') || '');
    const wrap = document.createElement('span');
    wrap.className = 'vf-time-picker';
    wrap.innerHTML = `
      <select class="vf-time-period" aria-label="오전/오후">
        <option value="AM">오전</option>
        <option value="PM">오후</option>
      </select>
      <input type="number" class="vf-time-h" min="1" max="12" step="1" inputmode="numeric" aria-label="시" placeholder="시">
      <span class="vf-time-colon">:</span>
      <input type="number" class="vf-time-m" min="0" max="59" step="1" inputmode="numeric" aria-label="분" placeholder="분">`;

    // hidden으로 원본 input을 유지 (name/required/value를 그대로 form submit에 사용)
    input.type = 'hidden';
    input.parentNode.insertBefore(wrap, input.nextSibling);

    const periodSel = wrap.querySelector('.vf-time-period');
    const hInput = wrap.querySelector('.vf-time-h');
    const mInput = wrap.querySelector('.vf-time-m');

    // 필수가 아닌 시각은 비워 둘 수 있어야 한다. 빈 값을 09:00으로 채워 보내면
    // '필요한 항목만 입력' 하는 폼(근태 정정 등)에서 입력하지 않은 시각까지 제출된다.
    const optional = !input.required;
    const startBlank = optional && !input.value;
    periodSel.value = init.period;
    hInput.value = startBlank ? '' : init.hour12;
    mInput.value = startBlank ? '' : pad2(init.minute);
    // 값이 아직 없는 선택 입력은 두 칸이 모두 비어 있는 동안 빈 값으로 둔다.
    const stayBlank = () => optional && input.value === '' && hInput.value.trim() === '' && mInput.value.trim() === '';

    let committedHour = init.hour12;
    let committedMinute = init.minute;
    const sync = () => {
      if (hInput.value.trim() === '' || mInput.value.trim() === '') return;
      const h = Number(hInput.value);
      const mn = Number(mInput.value);
      if (!Number.isInteger(h) || !Number.isInteger(mn)) return;
      const value = to24(periodSel.value, h, mn);
      if (!value) return;
      input.value = value;
      input.dispatchEvent(new CustomEvent('change', { bubbles: true, detail: { worksystemTimePickerSync: true } }));
    };

    // 편집 중 빈칸과 중간값을 유지하고, 확정할 때만 범위를 보정한다.
    const normalize = (field, min, max, fallback, padded) => {
      const value = field.value.trim() === '' ? fallback : Number(field.value);
      const bounded = Number.isFinite(value) ? Math.max(min, Math.min(max, Math.trunc(value))) : fallback;
      field.value = padded ? pad2(bounded) : String(bounded);
      return bounded;
    };
    const commitHour = () => {
      if (stayBlank()) return;
      committedHour = normalize(hInput, 1, 12, committedHour, false); sync();
    };
    const commitMinute = () => {
      if (stayBlank()) return;
      committedMinute = normalize(mInput, 0, 59, committedMinute, true); sync();
    };
    const commit = () => { commitHour(); commitMinute(); };

    // [UX10] 입력 즉시 hidden input에 반영되므로 '확인' 버튼 제거 — 저장은 폼의 저장 버튼으로.
    periodSel.addEventListener('change', commit);
    hInput.addEventListener('input', sync);
    mInput.addEventListener('input', sync);
    hInput.addEventListener('blur', commitHour);
    mInput.addEventListener('blur', commitMinute);
    wrap.addEventListener('keydown', (event) => {
      if (event.key === 'Enter') commit();
    });
    input.form?.addEventListener('submit', commit, true);
    input.addEventListener('change', (event) => {
      if (event.detail?.worksystemTimePickerSync) return;
      const external = parse24(input.value);
      committedHour = external.hour12;
      committedMinute = external.minute;
    });
    if (!startBlank) sync(); // 초기값 반영
  };

  const scan = (root = document) => {
    root.querySelectorAll('input[type="time"]').forEach(enhance);
  };

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', () => scan());
  } else {
    scan();
  }
  window.addEventListener('worksystem:content-updated', () => scan());
  document.addEventListener('content-updated', () => scan());
})();
