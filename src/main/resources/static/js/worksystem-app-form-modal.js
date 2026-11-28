/* [UX23] 신청 폼 모달 — ＋휴가/＋시차출근/＋출장/＋근태정정 링크(data-app-form)를 가로채
   해당 페이지의 .content를 fetch해 모달에 주입. 바깥(백드롭) 클릭으로는 닫히지 않고
   × 버튼(또는 ESC)으로만 닫힌다. 폼은 body 직속 모달이라 soft-submit을 타지 않고 일반 POST.
   [UX28] 조회형 화면(직원 상세 등)도 겸용 — 모달 안 링크·GET 폼(페이징/필터)은
   페이지 이동 없이 모달 안에서 갱신한다. */
(() => {
  if (globalThis.__wsAppFormModalBound) return;
  globalThis.__wsAppFormModalBound = true;
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
      const m = modal();
      if (m && m.dataset) {
        const size = link.getAttribute('data-app-form-size');
        if (size) m.dataset.modalSize = size; else delete m.dataset.modalSize;
      }
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

  /*
   * [UI] 모달 안 저장(POST)은 화면을 떠나지 않는다.
   *  - 응답 화면에 오류 배너가 있으면: 모달을 닫지 않고 입력값을 그대로 둔 채 오류만 위에 보인다.
   *  - 성공이면: 모달을 닫고, 돌아갈 화면이 지금 화면이면 그 내용(성공 안내 포함)으로 바꾸고, 아니면 그리로 이동한다.
   *  - 결재 비밀번호 확인 등 다른 처리기가 먼저 막은 제출(defaultPrevented)은 건드리지 않는다.
   */
  const showError = (body, message) => {
    body.querySelectorAll(':scope > .ws-modal-error').forEach((el) => el.remove());
    const banner = document.createElement('div');
    banner.className = 'banner err ws-modal-error';
    banner.setAttribute('role', 'alert');
    banner.textContent = message;
    body.prepend(banner);
    body.scrollTop = 0;
  };
  const swapContent = (doc) => {
    const next = doc.querySelector('.content');
    const current = document.querySelector('main .content') || document.querySelector('.content');
    if (!next || !current) return false;
    current.replaceWith(next);
    window.dispatchEvent(new CustomEvent('worksystem:content-updated'));
    return true;
  };
  document.addEventListener('submit', (event) => {
    if (event.defaultPrevented) return;
    const form = event.target.closest('form');
    const body = form?.closest('[data-app-form-body]');
    if (!form || !body) return;
    if ((form.getAttribute('method') || 'get').toLowerCase() !== 'post') return;
    if (form.target && form.target !== '_self') return;
    event.preventDefault();
    const submitter = event.submitter;
    const data = new FormData(form, submitter && submitter.name ? submitter : undefined);
    const buttons = [...form.querySelectorAll('button[type="submit"],input[type="submit"]')];
    buttons.forEach((b) => { b.disabled = true; });
    const action = form.getAttribute('action') || window.location.href;
    fetch(action, { method: 'POST', body: data, credentials: 'same-origin', headers: { 'X-Requested-With': 'fetch' } })
      .then((response) => response.text().then((html) => ({ response, html })))
      .then(({ response, html }) => {
        const finalUrl = new URL(response.url || action, window.location.href);
        if (finalUrl.pathname.startsWith('/login')) { window.location.href = finalUrl.href; return; }
        const doc = new DOMParser().parseFromString(html, 'text/html');
        const error = doc.querySelector('.content .banner.err');
        if (!response.ok || error) {
          showError(body, (error?.textContent || '저장하지 못했습니다. 입력값을 확인하세요.').trim());
          return;
        }
        closeModal();
        if (finalUrl.pathname === window.location.pathname && swapContent(doc)) {
          history.replaceState(history.state, '', finalUrl.pathname + finalUrl.search + finalUrl.hash);
          return;
        }
        if (window.worksystemSoftNavigate) window.worksystemSoftNavigate(finalUrl.href);
        else window.location.href = finalUrl.href;
      })
      .catch(() => showError(body, '연결이 끊겨 저장하지 못했습니다. 잠시 후 다시 시도하세요.'))
      .finally(() => { buttons.forEach((b) => { b.disabled = false; }); });
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
