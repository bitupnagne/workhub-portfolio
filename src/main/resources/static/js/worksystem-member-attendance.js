/* 직원 인사카드의 근태 표·건수·필터·페이지 컨트롤만 갱신한다. */
(() => {
  if (window.__wsMemberAttendanceReady) return;
  window.__wsMemberAttendanceReady = true;
  const selector = '#member-attendance-history';
  const owner = 'member-attendance';
  let sequence = 0;
  let request = null;
  let loading = null;
  let retry = null;
  let stopLoading = () => {};
  const state = () => history.state && typeof history.state === 'object' ? history.state : {};
  const root = () => [...document.querySelectorAll(selector)].find(element => !element.closest('[data-app-form-modal][hidden]'));
  const urlOf = element => new URL(element.dataset.memberAttendanceUrl, location.href);
  const modalOf = element => element.closest('[data-app-form-body]');
  const context = href => {
    const url = new URL(href, location.href);
    url.searchParams.delete('apage');
    url.searchParams.delete('astatus');
    url.searchParams.sort();
    return url.pathname + url.search;
  };
  const announce = (element, message, failed = false, href = '') => {
    const status = element.querySelector('[data-member-attendance-status]');
    status.hidden = !message;
    status.classList.toggle('is-error', failed);
    status.setAttribute('role', failed ? 'alert' : 'status');
    status.querySelector('[data-member-attendance-message]').textContent = message;
    status.querySelector('[data-member-attendance-retry]').hidden = !failed;
    const fallback = status.querySelector('[data-member-attendance-fallback]');
    fallback.hidden = !failed;
    if (href) fallback.href = href;
  };
  const cancel = () => {
    sequence++;
    request?.abort();
    request = null;
    if (loading) {
      loading.removeAttribute('aria-busy');
      announce(loading, '');
    }
    stopLoading();
    stopLoading = () => {};
    loading = null;
  };
  const scrollState = element => {
    const modal = modalOf(element);
    const table = element.querySelector('.table-scroll');
    return { x: window.scrollX, y: window.scrollY, modalTop: modal?.scrollTop || 0, modalLeft: modal?.scrollLeft || 0,
      tableTop: table?.scrollTop || 0, tableLeft: table?.scrollLeft || 0 };
  };
  const restoreScroll = (element, position) => {
    if (!position) return;
    const modal = modalOf(element);
    if (modal) { modal.scrollTop = position.modalTop; modal.scrollLeft = position.modalLeft; }
    const table = element.querySelector('.table-scroll');
    if (table) { table.scrollTop = position.tableTop || 0; table.scrollLeft = position.tableLeft || 0; }
    window.scrollTo(position.x, position.y);
  };
  const entry = (element, url, scroll = scrollState(element)) => ({
    url, context: context(url), modal: !!modalOf(element), hostPath: location.pathname, scroll,
  });
  const remember = element => history.replaceState({ ...state(), wsMemberAttendance: entry(element, urlOf(element).href) }, '', location.href);
  const init = () => {
    const element = root();
    if (loading && loading !== element) cancel();
    if (element) remember(element);
  };
  const load = async (href, options = {}) => {
    const element = root();
    if (!element) return;
    const target = new URL(href, location.href);
    if (target.origin !== location.origin || target.pathname !== urlOf(element).pathname) return;
    cancel();
    window.dispatchEvent(new CustomEvent('worksystem:navigation-start', { detail: { owner } }));
    const current = sequence;
    const controller = new AbortController();
    request = controller;
    loading = element;
    retry = { href, options };
    const position = options.scroll || scrollState(element);
    const filterSnapshot = window.worksystemCaptureFragmentFilters?.(element);
    // Record the entry being left, including modal and page scroll, before a new history entry.
    if (options.push !== false) remember(element);
    const finishLoading = window.wsLoading?.start(element, { variant: 'rows', surface: element.querySelector('.table-scroll') || element }) || (() => {});
    stopLoading = finishLoading;
    element.setAttribute('aria-busy', 'true');
    announce(element, '');
    try {
      const response = await fetch(target.href, {
        credentials: 'same-origin', signal: controller.signal,
        headers: { Accept: 'text/html', 'X-Worksystem-Fragment': owner },
      });
      if (current !== sequence || root() !== element) return;
      const finalUrl = new URL(response.url || target.href, location.href);
      if (finalUrl.pathname.startsWith('/login') || finalUrl.pathname.includes('/login')) {
        window.location.assign(finalUrl.href);
        return;
      }
      if (!response.ok || finalUrl.pathname !== target.pathname) throw new Error('response');
      const doc = new DOMParser().parseFromString(await response.text(), 'text/html');
      if (current !== sequence || root() !== element) return;
      let fresh = doc.querySelector(selector);
      if (!fresh || !fresh.querySelector('table') || !fresh.dataset.memberAttendanceUrl) throw new Error('fragment');
      const canonical = urlOf(fresh);
      if (canonical.origin !== location.origin || canonical.pathname !== target.pathname) throw new Error('context');
      loading = null;
      finishLoading();
      stopLoading = () => {};
      const previousCanonical = urlOf(element).href;
      if (window.worksystemReconcileFragment) fresh = window.worksystemReconcileFragment(element, fresh, { preserveFilters: true, snapshot: filterSnapshot });
      else element.replaceWith(fresh);
      const next = { ...state(), wsMemberAttendance: entry(fresh, canonical.href, position) };
      // A card opened inside the list's modal keeps the list URL; history.state stores its own deep link.
      const visibleUrl = modalOf(fresh) ? location.href : canonical.href;
      if (options.push !== false && (canonical.href !== previousCanonical)) history.pushState(next, '', visibleUrl);
      else history.replaceState(next, '', visibleUrl);
      if (options.focus) fresh.querySelector(options.focus)?.focus({ preventScroll: true });
      announce(fresh, '');
      restoreScroll(fresh, position);
      retry = null;
      fresh.dispatchEvent(new CustomEvent('worksystem:member-attendance-updated', { bubbles: true }));
    } catch (error) {
      if (current !== sequence || error.name === 'AbortError' || root() !== element) return;
      announce(element, '근태 내역을 불러오지 못했습니다. 현재 내역을 유지했습니다.', true, target.href);
    } finally {
      if (current === sequence) {
        element.removeAttribute('aria-busy');
        loading = null;
        request = null;
        stopLoading = () => {};
      }
      finishLoading();
    }
  };
  document.addEventListener('click', event => {
    if (event.defaultPrevented || event.button !== 0 || event.metaKey || event.ctrlKey || event.shiftKey || event.altKey) return;
    const target = event.target.closest?.('a[href],[data-member-attendance-retry]');
    const element = target?.closest(selector);
    if (!target || !element || element !== root()) {
      if (loading && event.target.closest?.('a[href]')) cancel();
      return;
    }
    if (target.hasAttribute('data-member-attendance-fallback')) {
      event.stopImmediatePropagation();
      return; // Explicit recovery uses native navigation, including when the card is in a modal.
    }
    if (target.target && target.target !== '_self') return;
    if (!target.hasAttribute('data-member-attendance-retry') && !target.closest('.pagination,.document-tabs')) return;
    event.preventDefault();
    event.stopImmediatePropagation();
    if (target.hasAttribute('data-member-attendance-retry')) {
      if (retry) load(retry.href, retry.options);
      return;
    }
    if (target.classList.contains('disabled') || target.getAttribute('href') === '#') return;
    load(target.href, { focus: target.closest('.pagination') ? '.pagination .active' : '.document-tabs .active' });
  }, true);
  window.addEventListener('popstate', event => {
    const element = root();
    const saved = event.state?.wsMemberAttendance;
    if (!element || !saved || saved.hostPath !== location.pathname || saved.modal !== !!modalOf(element)
        || saved.context !== context(urlOf(element).href)) {
      cancel();
      return;
    }
    event.stopImmediatePropagation();
    load(saved.url, { push: false, scroll: saved.scroll });
  }, true);
  document.addEventListener('submit', () => { if (loading) cancel(); }, true);
  window.addEventListener('worksystem:navigation-start', event => { if (event.detail?.owner !== owner) cancel(); });
  window.addEventListener('worksystem:content-updated', init);
  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', init, { once: true });
  else init();
})();
