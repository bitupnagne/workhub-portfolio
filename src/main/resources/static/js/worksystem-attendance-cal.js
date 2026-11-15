/* [A2] 내 근태 월 캘린더 — 날짜 클릭 상세 모달. soft-nav 대응 document 위임. */
(() => {
  const setText = (modal, sel, value) => {
    const el = modal.querySelector(sel);
    if (el) el.textContent = value && value.trim() !== '' ? value : '-';
  };

  document.addEventListener('click', (event) => {
    const modal = document.querySelector('[data-acal-modal]');
    if (!modal) return;
    if (event.target.closest('[data-acal-close]') || event.target === modal) {
      event.preventDefault();
      modal.hidden = true;
      return;
    }
    const day = event.target.closest('[data-acal-date]');
    if (!day) return;
    event.preventDefault();
    setText(modal, '[data-acal-modal-date]', day.dataset.acalDate);
    const sched = day.dataset.acalSchednote && day.dataset.acalSchednote !== '기본'
      ? `${day.dataset.acalSched} (${day.dataset.acalSchednote})`
      : day.dataset.acalSched;
    setText(modal, '[data-acal-modal-status]', day.dataset.acalStatus);
    setText(modal, '[data-acal-modal-sched]', sched);
    setText(modal, '[data-acal-modal-in]', day.dataset.acalIn);
    setText(modal, '[data-acal-modal-out]', day.dataset.acalOut);
    setText(modal, '[data-acal-modal-note]', day.dataset.acalNote);
    const link = modal.querySelector('[data-acal-modal-correction]');
    if (link) link.href = '/hr/attendance/correction/new?date=' + encodeURIComponent(day.dataset.acalDate);
    modal.hidden = false;
  });

  document.addEventListener('keydown', (event) => {
    if (event.key !== 'Escape') return;
    const modal = document.querySelector('[data-acal-modal]');
    if (modal) modal.hidden = true;
  });
})();

/* [UX39] 휴가·근태 목록 탭 전환 — 콘텐츠 위치로 부드럽게 스크롤(0건→N건 높이 점프 완화) */
(() => {
  if (window.__wsHrTabScrollBound) return;
  window.__wsHrTabScrollBound = true;
  let pending = false;
  document.addEventListener('click', (event) => {
    if (event.target.closest && event.target.closest('.hr-tab')) pending = true;
  });
  window.addEventListener('worksystem:content-updated', () => {
    if (!pending) return;
    pending = false;
    requestAnimationFrame(() => {
      const tabs = document.querySelector('.hr-tabs');
      if (tabs) tabs.scrollIntoView({ behavior: 'smooth', block: 'start' });
    });
  });
})();
