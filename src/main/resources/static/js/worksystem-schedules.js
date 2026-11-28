/* [UX21] 일정관리 — 모달 참조를 클릭 시점에 조회(soft-nav로 .content/모달이 교체돼도 동작).
   종전엔 로드 시점에 querySelector로 캐싱해 cross-path soft-nav 진입 시 stale null → 버튼 무동작. */
(() => {
  const q = (sel) => document.querySelector(sel);
  let originalSchedule = null;

  const setScheduleEditing = (editing) => {
    const form = q('[data-schedule-form]');
    if (!form) return;
    const existing = form.dataset.scheduleMode === 'existing';
    const canManage = form.dataset.canManage === 'true';
    const editable = canManage && editing;
    form.dataset.scheduleEditing = String(editable);
    form.dataset.editState = editable ? 'editing' : 'view';
    form.querySelectorAll('.schedule-edit-body input,.schedule-edit-body textarea,.schedule-edit-body select').forEach((field) => {
      field.readOnly = !editable;
      // The custom date picker keeps its original input hidden; its display is locked separately.
      if (field.type !== 'hidden') field.disabled = !editable;
    });
    form.querySelectorAll('.schedule-edit-body button').forEach((button) => { button.disabled = !editable; });
    q('[data-schedule-edit]').hidden = !existing || editable || !canManage;
    q('[data-schedule-edit-cancel]').hidden = !existing || !editable;
    const submit = q('[data-schedule-submit]');
    submit.hidden = !editable;
    submit.textContent = existing ? '저장' : '등록';
    form.dispatchEvent(new CustomEvent('worksystem:edit-state', { bubbles: true, detail: { editing: editable } }));
  };

  const restoreSchedule = () => {
    const form = q('[data-schedule-form]');
    if (!form || !originalSchedule) return;
    for (const [selector, value] of Object.entries(originalSchedule)) {
      const input = form.querySelector(selector);
      input.value = value;
      input.dispatchEvent(new Event('change', { bubbles: true }));
    }
  };

  const closeSchedule = () => { const m = q('[data-schedule-modal]'); if (m) m.hidden = true; };
  const closeHoliday = () => { const m = q('[data-holiday-modal]'); if (m) m.hidden = true; };

  const openSchedule = (mode, data = {}) => {
    const scheduleModal = q('[data-schedule-modal]');
    const scheduleForm = q('[data-schedule-form]');
    if (!scheduleModal || !scheduleForm) return;
    const isEdit = mode === 'edit';
    scheduleForm.action = isEdit
      ? `/hr/schedules/${encodeURIComponent(data.id)}/update`
      : scheduleForm.dataset.createAction;
    scheduleForm.querySelector('[data-schedule-title]').value = data.title || '';
    scheduleForm.querySelector('[data-schedule-description]').value = data.description || '';
    scheduleForm.querySelector('[data-schedule-start]').value = data.start || '';
    scheduleForm.querySelector('[data-schedule-end]').value = data.end || '';
    scheduleForm.querySelector('[data-schedule-color]').value = data.color || '#2563eb';
    scheduleForm.dataset.scheduleMode = isEdit ? 'existing' : 'new';
    originalSchedule = Object.fromEntries(['[data-schedule-title]', '[data-schedule-description]', '[data-schedule-start]', '[data-schedule-end]', '[data-schedule-color]']
      .map((selector) => [selector, scheduleForm.querySelector(selector).value]));
    restoreSchedule();
    setScheduleEditing(!isEdit || scheduleForm.dataset.canManage === 'true');
    scheduleModal.querySelector('[data-schedule-modal-title]').textContent = isEdit ? '일정 상세' : '일정 등록';
    scheduleModal.hidden = false;
    (isEdit ? scheduleModal.querySelector('[data-schedule-edit]:not([hidden]),[data-schedule-close]') : scheduleForm.querySelector('[data-schedule-title]'))?.focus();
  };

  const openHoliday = (isoDate, data = null) => {
    const holidayModal = q('[data-holiday-modal]');
    if (!holidayModal) return;
    holidayModal.hidden = false;
    const dateInput = holidayModal.querySelector('input[name="holidayDate"]');
    const form = holidayModal.querySelector('form');
    const existing = !!data;
    form.querySelector('input[name="name"]').value = data?.name || '';
    form.querySelector('select[name="category"]').value = data?.category || 'COMPANY';
    dateInput.readOnly = existing;
    const dateWrap = dateInput.closest('.ws-dp-wrap');
    dateWrap?.classList.toggle('ws-control-locked', existing);
    dateWrap?.querySelectorAll('.ws-dp-display').forEach(button => { button.disabled = existing; });
    holidayModal.querySelector('#holidayModalTitle').textContent = existing ? '휴무일 수정' : '휴무일 추가';
    q('[data-holiday-submit]').textContent = existing ? '저장' : '등록';
    form.dataset.editState = 'editing';
    if (dateInput && isoDate) {
      dateInput.value = isoDate;
      dateInput.dispatchEvent(new Event('change', { bubbles: true }));
    }
    form.dispatchEvent(new CustomEvent('worksystem:edit-state', { bubbles: true, detail: { editing: true } }));
    (isoDate ? holidayModal.querySelector('input[name="name"]') : dateInput)?.focus();
  };

  // [UX26] 날짜 클릭 → 일정/휴무 선택 모달
  const closePick = () => { const m = q('[data-cal-pick-modal]'); if (m) m.hidden = true; };
  const openPick = (isoDate) => {
    const pickModal = q('[data-cal-pick-modal]');
    if (!pickModal) return;
    pickModal.dataset.pickDate = isoDate;
    const label = pickModal.querySelector('[data-cal-pick-date]');
    if (label) label.textContent = isoDate;
    pickModal.hidden = false;
  };

  document.addEventListener('click', (event) => {
    const holidayEdit = event.target.closest('[data-holiday-edit]');
    if (holidayEdit) {
      event.preventDefault();
      openHoliday(holidayEdit.dataset.date, holidayEdit.dataset);
      return;
    }
    if (event.target.closest('[data-schedule-edit]')) {
      event.preventDefault();
      setScheduleEditing(true);
      q('[data-schedule-title]')?.focus();
      return;
    }
    if (event.target.closest('[data-schedule-edit-cancel]')) {
      event.preventDefault();
      restoreSchedule();
      setScheduleEditing(false);
      q('[data-schedule-edit]')?.focus();
      return;
    }
    const viewButton = event.target.closest('[data-schedule-view]');
    if (viewButton) {
      event.preventDefault();
      openSchedule('edit', viewButton.dataset);
      return;
    }
    // [UX26] 선택 모달 내부 버튼 — 캘린더 클릭보다 먼저 처리
    if (event.target.closest('[data-cal-pick-schedule]')) {
      event.preventDefault();
      const iso = q('[data-cal-pick-modal]')?.dataset.pickDate;
      closePick();
      openSchedule('new', iso ? { start: iso + 'T09:00', end: iso + 'T18:00' } : {});
      return;
    }
    if (event.target.closest('[data-cal-pick-holiday]')) {
      event.preventDefault();
      const iso = q('[data-cal-pick-modal]')?.dataset.pickDate;
      closePick();
      openHoliday(iso || null);
      return;
    }
    if (event.target.closest('[data-cal-pick-close]')) {
      event.preventDefault();
      closePick();
      return;
    }
    // [UX26] 연간 캘린더 날짜 클릭 → 추가 항목 선택 모달
    const calDay = event.target.closest('[data-cal-date]');
    if (calDay) {
      event.preventDefault();
      openPick(calDay.getAttribute('data-cal-date'));
      return;
    }
    if (event.target.closest('[data-schedule-close]')) { // [UX24]
      event.preventDefault();
      closeSchedule();
      return;
    }
    if (event.target.closest('[data-holiday-close]')) { // [UX24]
      event.preventDefault();
      closeHoliday();
    }
  });

  document.addEventListener('submit', (event) => {
    if (event.target.matches('[data-schedule-form]') && event.target.dataset.scheduleEditing !== 'true') {
      event.preventDefault();
    }
  }, true);

  document.addEventListener('keydown', (event) => {
    if (event.key !== 'Escape') return;
    closeSchedule();
    closeHoliday();
    closePick();
  });
})();

/* [UX48] 공휴일 업로드 — 점선 드롭존, 파일명 표시, 업로드 로딩, ⓘ 도움말 */
(() => {
  const init = () => {
    const form = document.querySelector('[data-holiday-upload]');
    if (!form || form.dataset.hzInit) return;
    form.dataset.hzInit = '1';
    const zone = form.querySelector('[data-hz-zone]');
    const input = form.querySelector('[data-hz-input]');
    const text = form.querySelector('[data-hz-text]');
    const submit = form.querySelector('[data-hz-submit]');
    const DEFAULT_TEXT = 'CSV 파일을 끌어다 놓거나 클릭해서 선택';

    const sync = () => {
      const f = input.files && input.files[0];
      text.textContent = f ? `선택됨: ${f.name}` : DEFAULT_TEXT;
      zone.classList.toggle('has-file', !!f);
      submit.disabled = !f;
    };
    input.addEventListener('change', sync);
    ['dragenter', 'dragover'].forEach((t) => zone.addEventListener(t, (e) => {
      e.preventDefault();
      zone.classList.add('is-drag');
    }));
    ['dragleave', 'drop'].forEach((t) => zone.addEventListener(t, (e) => {
      e.preventDefault();
      zone.classList.remove('is-drag');
    }));
    zone.addEventListener('drop', (e) => {
      const files = e.dataTransfer && e.dataTransfer.files;
      if (files && files.length) {
        input.files = files;
        sync();
      }
    });
    // multipart 폼은 soft-submit 이 건드리지 않으므로 로딩 오버레이를 직접 띄운다
    form.addEventListener('submit', () => {
      submit.disabled = true;
      if (!document.getElementById('wsBusyOverlay')) {
        const ov = document.createElement('div');
        ov.id = 'wsBusyOverlay';
        ov.innerHTML = '<div class="ws-busy-card" aria-busy="true">' + (window.wsLoading?.markup('form') || '') + '</div>';
        document.body.appendChild(ov);
      }
    });
    sync();
  };

  if (!window.__hzHelpBound) {
    window.__hzHelpBound = true;
    document.addEventListener('click', (e) => {
      const pop = document.querySelector('[data-hz-help-pop]');
      if (!pop || pop.hasAttribute('data-help-popover')) return;
      if (e.target.closest('[data-hz-help]')) {
        e.preventDefault();
        pop.hidden = !pop.hidden;
        return;
      }
      if (!e.target.closest('.hz-help-wrap')) pop.hidden = true;
    });
  }

  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', init);
  else init();
  window.addEventListener('worksystem:content-updated', init);
})();
