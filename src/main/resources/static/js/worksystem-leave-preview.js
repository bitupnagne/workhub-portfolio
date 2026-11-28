// 휴가 신청 폼 — 차감 예정일수 실시간 미리보기 + 반차 종료일 잠금.
// soft-nav(.content 교체)에서도 동작하도록 document 위임 + worksystem:content-updated 재초기화.
(() => {
  const HALF_TYPES = ['AM_HALF', 'PM_HALF'];
  let timer = null;

  const esc = (s) => String(s ?? '').replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));

  const fmtDays = (v) => {
    if (v === null || v === undefined) return '-';
    const n = Number(v);
    if (Number.isNaN(n)) return '-';
    return (Number.isInteger(n) ? String(n) : n.toFixed(1)) + '일';
  };

  const applyHalfDayLock = (form) => {
    const type = form.querySelector('select[name="type"]');
    const start = form.querySelector('input[name="startDate"]');
    const end = form.querySelector('input[name="endDate"]');
    if (!type || !start || !end) return;
    const half = HALF_TYPES.includes(type.value);
    let changed = false;
    if (half) {
      if (start.value && end.value !== start.value) {
        end.value = start.value;
        changed = true;
      }
      changed = changed || !end.readOnly;
      end.readOnly = true;
      end.classList.add('is-locked');
      end.title = '반차는 하루만 신청할 수 있습니다.';
    } else if (end.classList.contains('is-locked')) {
      end.readOnly = false;
      end.classList.remove('is-locked');
      end.title = '';
      changed = true;
    }
    if (changed) end.dispatchEvent(new Event('change', { bubbles: true }));
  };

  // 결재선 미비(routeDisabled)로 이미 비활성인 버튼은 그대로 두고, 미리보기 사유로만 on/off.
  const setSubmitBlocked = (form, blocked) => {
    const btn = form.querySelector('button[type="submit"]');
    if (!btn) return;
    if (btn.dataset.lpRouteDisabled === undefined) {
      btn.dataset.lpRouteDisabled = btn.disabled ? '1' : '0';
    }
    btn.disabled = blocked || btn.dataset.lpRouteDisabled === '1';
  };

  // 상단 카드(잔여·발생)를 신청 날짜의 연도 값으로 갈아끼운다.
  // 연차는 연도별로 부여·집계되므로, 내년 날짜를 고른 채 올해 잔여가 떠 있으면
  // 화면의 두 숫자가 서로 다른 해를 가리키게 된다.
  const renderYearCards = (data) => {
    if (!data.year) return;
    document.querySelectorAll('[data-lp-year]').forEach((el) => { el.textContent = data.year; });
    const remainEl = document.querySelector('[data-lp-remaining]');
    if (remainEl && data.remaining !== null && data.remaining !== undefined) {
      remainEl.textContent = Number(data.remaining);
    }
    const entEl = document.querySelector('[data-lp-entitlement]');
    if (entEl && data.entitlement !== null && data.entitlement !== undefined) {
      entEl.textContent = Number(data.entitlement);
    }
    const noteEl = document.querySelector('[data-lp-accrual-note]');
    if (noteEl) {
      noteEl.textContent = data.accrualNote || '';
      noteEl.hidden = !data.accrualNote;
    }
  };

  const render = (form, data) => {
    renderYearCards(data);
    const box = form.querySelector('[data-lp-box]');
    if (!box) return;
    const days = Number(data.days || 0);
    box.hidden = false;
    const daysEl = box.querySelector('[data-lp-days]');
    if (daysEl) daysEl.textContent = fmtDays(days);
    const afterWrap = box.querySelector('[data-lp-after-wrap]');
    const afterEl = box.querySelector('[data-lp-after]');
    if (afterWrap) afterWrap.hidden = !data.annualCounted;
    if (afterEl && data.annualCounted) afterEl.textContent = fmtDays(data.remainingAfter);
    const warns = box.querySelector('[data-lp-warns]');
    if (warns) warns.innerHTML = (data.warnings || []).map((w) => `<li>${esc(w)}</li>`).join('');
    const insufficient = data.annualCounted && data.remainingAfter !== null && Number(data.remainingAfter) < 0;
    const blocked = insufficient || days <= 0;
    box.classList.toggle('is-bad', blocked);
    setSubmitBlocked(form, blocked);
  };

  const refresh = (form) => {
    applyHalfDayLock(form);
    const type = form.querySelector('select[name="type"]');
    const start = form.querySelector('input[name="startDate"]');
    const end = form.querySelector('input[name="endDate"]');
    if (!type || !start || !end || !type.value || !start.value || !end.value) {
      const box = form.querySelector('[data-lp-box]');
      if (box) box.hidden = true;
      setSubmitBlocked(form, false);
      return;
    }
    const base = form.dataset.previewUrl || '/hr/leave/preview-days';
    const url = `${base}?type=${encodeURIComponent(type.value)}&startDate=${encodeURIComponent(start.value)}&endDate=${encodeURIComponent(end.value)}`;
    fetch(url, { credentials: 'same-origin', headers: { Accept: 'application/json' } })
      .then((r) => (r.ok ? r.json() : null))
      .then((data) => {
        // 응답 도착 시점에 입력이 바뀌었으면 버린다.
        if (!data) return;
        if (form.querySelector('select[name="type"]').value !== type.value
          || form.querySelector('input[name="startDate"]').value !== start.value
          || form.querySelector('input[name="endDate"]').value !== end.value) return;
        render(form, data);
      })
      .catch(() => {});
  };

  const schedule = (form) => {
    window.clearTimeout(timer);
    timer = window.setTimeout(() => refresh(form), 250);
  };

  ['change', 'input'].forEach((evt) => {
    document.addEventListener(evt, (e) => {
      const target = e.target;
      if (!target || !target.matches) return;
      if (!target.matches('select[name="type"], input[name="startDate"], input[name="endDate"]')) return;
      const form = target.closest('form[data-leave-preview]');
      if (form) schedule(form);
    });
  });

  const initAll = () => document.querySelectorAll('form[data-leave-preview]').forEach((form) => refresh(form));
  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', initAll);
  else initAll();
  window.addEventListener('worksystem:content-updated', initAll);
})();
