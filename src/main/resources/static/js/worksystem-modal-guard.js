/* [UX38] 모달 열림 동안 배경 화면 격리 — 스크롤 잠금(body.ws-modal-open) + 키보드 포커스가
   배경(.app)으로 새지 않도록 트랩. 클릭은 각 모달의 풀스크린 오버레이가 이미 차단한다.
   모달/피커 팝업은 body 직속(.app 밖)에 살기 때문에 ".app 안으로의 포커스 이동"만 막으면
   date-picker·time-picker 등 body 부착 팝업과 충돌하지 않는다. */
(() => {
  const SEL = [
    '.schedule-edit-modal', '.vf-modal', '.vf-common-modal', '.approval-confirm-modal',
    '.leave-cancel-modal', '.file-viewer-modal', '.pt-modal-bg', '.ws-pdf-modal', '.ws-dp-pop.open',
  ].join(',');
  const FOCUSABLE = 'a[href],button:not([disabled]),input:not([disabled]):not([type="hidden"]),select:not([disabled]),textarea:not([disabled]),[tabindex]:not([tabindex="-1"])';

  const isOpen = (el) => !el.hidden && el.getClientRects().length > 0;
  const zOf = (el) => Number(getComputedStyle(el).zIndex) || 0;
  let current = null;
  const ownedHelp = (modal, node) => {
    const panel = node?.closest?.('[data-help-popover]');
    return !!(panel && !panel.hidden && panel.__wsHelpTrigger && modal.contains(panel.__wsHelpTrigger));
  };

  const topModal = () => {
    const open = Array.from(document.querySelectorAll(SEL)).filter(isOpen);
    if (!open.length) return null;
    return open.reduce((a, b) => (zOf(b) >= zOf(a) ? b : a));
  };

  const focusFirst = (m) => {
    const f = m.querySelector(FOCUSABLE);
    if (f) { try { f.focus({ preventScroll: true }); } catch (e) { /* ignore */ } }
  };

  const sync = () => {
    const m = topModal();
    if (m === current) return;
    current = m;
    document.body.classList.toggle('ws-modal-open', !!m);
    if (m && !m.contains(document.activeElement) && !ownedHelp(m, document.activeElement)) focusFirst(m);
  };

  // Tab 순환 — 모달 안에서만 돌고, 배경에 있던 포커스는 모달로 끌어온다
  document.addEventListener('keydown', (event) => {
    sync();
    if (!current || event.key !== 'Tab') return;
    if (ownedHelp(current, event.target)) return;
    const items = Array.from(current.querySelectorAll(FOCUSABLE)).filter((el) => el.getClientRects().length > 0);
    if (!items.length) { event.preventDefault(); return; }
    const first = items[0];
    const last = items[items.length - 1];
    const inModal = current.contains(document.activeElement);
    if (!inModal) { event.preventDefault(); (event.shiftKey ? last : first).focus(); return; }
    if (event.shiftKey && document.activeElement === first) { event.preventDefault(); last.focus(); }
    else if (!event.shiftKey && document.activeElement === last) { event.preventDefault(); first.focus(); }
  }, true);

  // 포커스가 배경(.app)으로 새면 모달로 회수 — body 직속 팝업(피커 등)은 허용
  document.addEventListener('focusin', (event) => {
    sync();
    if (!current) return;
    if (event.target.closest && event.target.closest('.app') && !current.contains(event.target)) {
      focusFirst(current);
    }
  });

  let raf = 0;
  const schedule = () => { if (!raf) raf = requestAnimationFrame(() => { raf = 0; sync(); }); };
  new MutationObserver(schedule).observe(document.body, {
    subtree: true, childList: true, attributes: true, attributeFilter: ['hidden', 'style', 'class'],
  });
  window.addEventListener('worksystem:content-updated', schedule);
  sync();
})();
