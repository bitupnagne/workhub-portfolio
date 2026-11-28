/* [UX19/UX20] 출퇴근 확인 모달(현재시각·판정 미리보기) + 진입 미체크 알림. soft-nav 대응 위임. */
(() => {
  let liveTimer = null;
  let pendingForm = null;
  let request = null;
  let stopLoading = () => {};

  const q = (sel) => document.querySelector(sel);
  const fmt = (d) => String(d.getHours()).padStart(2, '0') + ':' +
    String(d.getMinutes()).padStart(2, '0') + ':' + String(d.getSeconds()).padStart(2, '0');

  const closeCheck = () => {
    request?.abort();
    request = null;
    stopLoading();
    stopLoading = () => {};
    const m = q('[data-check-modal]');
    if (m) m.hidden = true;
    if (liveTimer) { clearInterval(liveTimer); liveTimer = null; }
    pendingForm = null;
  };

  const openCheck = async (form) => {
    request?.abort();
    stopLoading();
    const controller = new AbortController();
    request = controller;
    pendingForm = form;
    const kind = form.getAttribute('data-check-kind');
    const modal = q('[data-check-modal]');
    if (!modal) { form.submit(); return; }
    q('[data-check-title]').textContent = kind === 'out' ? '퇴근 확인' : '출근 확인';
    const nowEl = q('[data-check-now]');
    nowEl.textContent = fmt(new Date());
    if (liveTimer) clearInterval(liveTimer);
    liveTimer = setInterval(() => { nowEl.textContent = fmt(new Date()); }, 1000);
    const judge = q('[data-check-judge]');
    judge.replaceChildren();
    const finishLoading = window.wsLoading?.start(judge, { variant: 'text', replace: true }) || (() => {});
    stopLoading = finishLoading;
    q('[data-check-confirm]').disabled = false;
    modal.hidden = false;
    try {
      const res = await fetch('/hr/attendance/check-preview?kind=' + encodeURIComponent(kind), {
        credentials: 'same-origin', signal: controller.signal, headers: { 'X-Requested-With': 'fetch' }
      });
      if (!res.ok) throw new Error('preview');
      const data = await res.json();
      if (controller !== request || controller.signal.aborted || modal.hidden || q('[data-check-modal]') !== modal) return;
      if (data.allowed) {
        judge.innerHTML = '지금 ' + (kind === 'out' ? '퇴근' : '출근') +
          '하면 <b class="att-judge-label">' + data.label + '</b> 처리됩니다.';
      } else {
        judge.innerHTML = '<b class="att-judge-block">' + data.label + '</b>';
        q('[data-check-confirm]').disabled = true;
      }
    } catch (e) {
      if (controller !== request || controller.signal.aborted || modal.hidden) return;
      judge.textContent = '판정을 확인하지 못했습니다. 그대로 진행할 수 있습니다.';
    } finally {
      finishLoading();
      if (controller === request) { request = null; stopLoading = () => {}; }
    }
  };

  document.addEventListener('submit', (event) => {
    if (event.defaultPrevented) return;
    const form = event.target.closest('[data-check-form]');
    if (!form || form.dataset.checkConfirmed === 'true') return;
    event.preventDefault();
    openCheck(form);
  }, true);

  document.addEventListener('click', (event) => {
    if (event.target.closest('[data-check-close]')) { event.preventDefault(); closeCheck(); return; }
    if (event.target.closest('[data-check-confirm]')) {
      event.preventDefault();
      if (!pendingForm) return;
      const f = pendingForm;
      f.dataset.checkConfirmed = 'true';
      closeCheck();
      f.requestSubmit ? f.requestSubmit() : f.submit();
      return;
    }
    // 진입 알림 모달
    if (event.target.closest('[data-reminder-close]')) { event.preventDefault(); const m = q('[data-reminder-modal]'); if (m) m.hidden = true; return; }
    if (event.target.closest('[data-reminder-act]')) {
      event.preventDefault();
      const m = q('[data-reminder-modal]');
      if (m) m.hidden = true;
      const kind = m ? m.getAttribute('data-kind') : '';
      const form = document.querySelector('[data-check-form][data-check-kind="' + (kind === 'checkout' ? 'out' : 'in') + '"]');
      if (form) openCheck(form);
    }
  });

  // [UX19] 진입 시 미체크 알림 — soft-nav로도 재실행되도록 content-updated에서도 처리(세션 1회 억제)
  const showReminder = () => {
    const flag = document.querySelector('[data-reminder-kind]');
    if (!flag) return;
    const kind = flag.getAttribute('data-reminder-kind');
    const msg = flag.getAttribute('data-reminder-msg');
    if (sessionStorage.getItem('attReminderShown') === kind + '|' + msg) return;
    const modal = q('[data-reminder-modal]');
    if (!modal) return;
    // 신청 완료 안내·대리승인자 안내를 먼저 읽고 닫은 뒤 근태 알림을 표시한다.
    const anotherDialog = [...document.querySelectorAll('[role="dialog"][aria-modal="true"]')]
      .some((dialog) => !modal.contains(dialog) && dialog.getClientRects().length > 0);
    if (anotherDialog) return;
    sessionStorage.setItem('attReminderShown', kind + '|' + msg);
    modal.setAttribute('data-kind', kind);
    q('[data-reminder-text]').textContent = msg;
    modal.hidden = false;
  };
  document.addEventListener('DOMContentLoaded', showReminder);
  window.addEventListener('worksystem:content-updated', () => {
    if (pendingForm && !pendingForm.isConnected) closeCheck();
    showReminder();
  });
  let reminderFrame = 0;
  new MutationObserver(() => {
    if (reminderFrame) return;
    reminderFrame = requestAnimationFrame(() => { reminderFrame = 0; showReminder(); });
  }).observe(document.body, { subtree: true, childList: true, attributes: true, attributeFilter: ['hidden'] });
  if (document.readyState !== 'loading') showReminder();

  document.addEventListener('keydown', (event) => {
    if (event.key !== 'Escape') return;
    closeCheck();
    const rm = q('[data-reminder-modal]');
    if (rm) rm.hidden = true;
  });
})();
