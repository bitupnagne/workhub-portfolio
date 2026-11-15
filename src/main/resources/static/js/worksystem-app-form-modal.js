/* [UX23] 신청 폼 모달 — ＋휴가/＋시차출근/＋출장/＋근태정정 링크(data-app-form)를 가로채
   해당 페이지의 .content를 fetch해 모달에 주입. 바깥(백드롭) 클릭으로는 닫히지 않고
   × 버튼(또는 ESC)으로만 닫힌다. 폼은 body 직속 모달이라 soft-submit을 타지 않고 일반 POST.
   [UX28] 조회형 화면(직원 상세 등)도 겸용 — 모달 안 링크·GET 폼(페이징/필터)은
   페이지 이동 없이 모달 안에서 갱신한다. */
(() => {
  const modal = () => document.querySelector('[data-app-form-modal]');
  const picker = () => document.querySelector('[data-application-picker-modal]');
  let currentTitle = '';
  let pickerTrigger = null;
  let request = null;
  let sequence = 0;
  let stopLoading = () => {};

  const closePicker = ({ restoreFocus = true } = {}) => {
    const p = picker();
    if (!p || p.hidden) return;
    p.hidden = true;
    pickerTrigger?.setAttribute('aria-expanded', 'false');
    if (restoreFocus) pickerTrigger?.focus();
  };

  const openPicker = (trigger) => {
    const p = picker();
    if (!p) return;
    document.querySelectorAll('.schedule-edit-modal:not([hidden])').forEach((el) => { el.hidden = true; });
    pickerTrigger = trigger;
    pickerTrigger?.setAttribute('aria-expanded', 'true');
    p.hidden = false;
    requestAnimationFrame(() => p.querySelector('.application-picker-card')?.focus());
  };

  const closeModal = () => {
    sequence++;
    request?.abort();
    request = null;
    stopLoading();
    stopLoading = () => {};
    const m = modal();
    if (m) m.hidden = true;
  };

  const openForm = (url, title, retain = false) => {
    const m = modal();
    if (!m) { window.location.href = url; return; }
    // 다른 열림 모달(캘린더 상세 등)은 닫고 시작
    document.querySelectorAll('.schedule-edit-modal:not([hidden])').forEach((el) => { el.hidden = true; });
    const body = m.querySelector('[data-app-form-body]');
    const titleEl = m.querySelector('[data-app-form-title]');
    currentTitle = title || '신청';
    if (titleEl) titleEl.textContent = currentTitle;
    request?.abort();
    stopLoading();
    const current = ++sequence;
    const controller = new AbortController();
    request = controller;
    const position = { top: body.scrollTop, left: body.scrollLeft };
    if (!retain) body.replaceChildren();
    const finishLoading = window.wsLoading?.start(body, { variant: 'form', replace: !retain }) || (() => {});
    stopLoading = finishLoading;
    m.hidden = false;
    fetch(url, { credentials: 'same-origin', signal: controller.signal })
      .then((r) => {
        if (!r.ok || (r.url && new URL(r.url, window.location.href).pathname.startsWith('/login'))) {
          throw new Error('load-failed');
        }
        return r.text();
      })
      .then((html) => {
        if (current !== sequence || controller.signal.aborted || !m.isConnected || m.hidden) return;
        const doc = new DOMParser().parseFromString(html, 'text/html');
        const content = doc.querySelector('.content');
        if (!content) throw new Error('no-content');
        content.querySelectorAll('.banner').forEach((el) => el.remove());
        // 페이지 상단의 목록/뒤로 링크는 모달에선 불필요
        content.querySelectorAll('.form-head a.rbtn').forEach((el) => el.remove());
        // [UX28] 모달에서 제외할 요소(뒤로가기 줄·삭제 구역 등)
        content.querySelectorAll('[data-app-form-strip]').forEach((el) => el.remove());
        finishLoading();
        body.innerHTML = '';
        while (content.firstChild) body.appendChild(content.firstChild);
        // leave-preview / trip-expenses / att-correction 등 재초기화
        window.dispatchEvent(new CustomEvent('worksystem:content-updated'));
        if (retain) { body.scrollTop = position.top; body.scrollLeft = position.left; }
      })
      .catch((error) => {
        if (current !== sequence || controller.signal.aborted || error.name === 'AbortError' || !m.isConnected || m.hidden) return;
        window.location.href = url;
      }).finally(() => {
        finishLoading();
        if (current === sequence) { request = null; stopLoading = () => {}; }
      });
  };

  document.addEventListener('click', (event) => {
    const pickerOpen = event.target.closest('[data-application-picker-open]');
    if (pickerOpen) {
      event.preventDefault();
      openPicker(pickerOpen);
      return;
    }
    if (event.target.closest('[data-application-picker-close]')) {
      event.preventDefault();
      closePicker();
      return;
    }
    const link = event.target.closest('[data-app-form]');
    if (link) {
      event.preventDefault();
      const url = link.getAttribute('href') || link.getAttribute('data-app-form-url');
      if (!url) return;
      closePicker({ restoreFocus: false });
      openForm(url, (link.getAttribute('data-app-form-label') || link.textContent || '').replace(/^[＋+\s]+/, '').trim());
      return;
    }
    if (event.target.closest('[data-app-form-close]')) {
      event.preventDefault();
      closeModal();
      return;
    }
    // [UX28] 모달 안 일반 링크(페이징 등)는 페이지 이동 대신 모달 안에서 갱신
    const inModal = event.target.closest('[data-app-form-body]');
    if (!inModal) return;
    const navLink = event.target.closest('a[href]');
    if (!navLink) return;
    if (navLink.hasAttribute('data-partial-reload')) return; // 부분 리로드 JS가 처리
    if (navLink.target && navLink.target !== '_self') return;
    if (navLink.hasAttribute('download') || navLink.dataset.docPopup !== undefined
        || navLink.dataset.fileViewerUrl || navLink.dataset.receiptUrl) return;
    const navUrl = new URL(navLink.href, window.location.href);
    if (navUrl.origin !== window.location.origin) return;
    event.preventDefault();
    openForm(navUrl.href, currentTitle, true);
  });

  // [UX28] 모달 안 GET 폼(기간 조회 등)도 모달 안에서 갱신
  document.addEventListener('submit', (event) => {
    if (event.defaultPrevented) return;
    const form = event.target.closest('form');
    if (!form || !form.closest('[data-app-form-body]')) return;
    if ((form.method || '').toLowerCase() !== 'get') return;
    event.preventDefault();
    const url = new URL(form.action, window.location.href);
    url.search = '';
    new FormData(form).forEach((value, key) => {
      if (value !== null && String(value) !== '') url.searchParams.append(key, value);
    });
    openForm(url.href, currentTitle, true);
  });

  window.addEventListener('worksystem:navigation-start', (event) => {
    if (!['member-attendance', 'attendance-history'].includes(event.detail?.owner)) closeModal();
  });

  document.addEventListener('keydown', (event) => {
    const p = picker();
    if (event.key === 'Tab' && p && !p.hidden) {
      const focusable = [...p.querySelectorAll('a[href],button:not(:disabled)')];
      if (!focusable.length) return;
      const first = focusable[0];
      const last = focusable[focusable.length - 1];
      if (event.shiftKey && document.activeElement === first) {
        event.preventDefault();
        last.focus();
      } else if (!event.shiftKey && document.activeElement === last) {
        event.preventDefault();
        first.focus();
      }
      return;
    }
    if (event.key !== 'Escape') return;
    if (p && !p.hidden) closePicker();
    else closeModal();
  });
})();
