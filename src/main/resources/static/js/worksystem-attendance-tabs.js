/* 하단 내역의 탭·페이지·조회만 서버 fragment로 바꾼다. 문서·업무 POST는 기존 처리에 맡긴다. */
(() => {
  if (window.__wsAttendanceTabsReady) return;
  window.__wsAttendanceTabsReady = true;
  const selector = '#attendance-history';
  const tabSelector = '[data-attendance-tab]';
  let sequence = 0;
  let controller = null;
  let loadingRoot = null;
  let retry = null;
  let stopLoading = () => {};
  let scrollIntent = 0;
  window.addEventListener('wheel', () => { scrollIntent++; }, { passive: true });
  window.addEventListener('touchmove', () => { scrollIntent++; }, { passive: true });
  const root = () => document.querySelector(selector);
  const state = () => typeof history.state === 'object' && history.state ? history.state : {};
  const context = (href) => {
    const url = new URL(href, window.location.href);
    ['tab', 'from', 'to', 'q', 'contractor', 'cq', 'mq', 'lq', 'lstatus', 'fq', 'fstatus', 'tq', 'tstatus',
      'page', 'cpage', 'mpage', 'lpage', 'fpage', 'tpage'].forEach(key => url.searchParams.delete(key));
    url.searchParams.sort();
    return url.pathname + url.search;
  };
  const cancel = () => {
    sequence += 1;
    controller?.abort();
    controller = null;
    if (loadingRoot) {
      loadingRoot.removeAttribute('aria-busy');
      announce(loadingRoot, '');
    }
    stopLoading();
    stopLoading = () => {};
    loadingRoot = null;
  };
  const announce = (element, message, error = false, href = '') => {
    const status = element.querySelector('[data-attendance-status]');
    if (!status) return;
    status.hidden = !message;
    status.classList.toggle('is-error', error);
    status.setAttribute('role', error ? 'alert' : 'status');
    status.querySelector('[data-attendance-message]').textContent = message;
    status.querySelector('[data-attendance-retry]').hidden = !error;
    const fallback = status.querySelector('[data-attendance-fallback]');
    fallback.hidden = !error;
    if (href) fallback.href = href;
  };
  const scrollState = (element) => {
    const table = element.querySelector('.table-scroll');
    return { x: window.scrollX || 0, y: window.scrollY || 0, tableTop: table?.scrollTop || 0, tableLeft: table?.scrollLeft || 0 };
  };
  const restoreScroll = (element, position) => {
    if (!position) return;
    const table = element.querySelector('.table-scroll');
    if (table) { table.scrollTop = position.tableTop; table.scrollLeft = position.tableLeft; }
    window.scrollTo(position.x, position.y);
  };
  const remember = (element) => {
    const href = new URL(element.dataset.historyUrl || window.location.href, window.location.href).href;
    history.replaceState({ ...state(), wsAttendanceContext: context(href), wsAttendanceUrl: href,
      wsAttendanceScroll: scrollState(element) }, '', href);
  };
  const mergeUrl = (element, href) => {
    const target = new URL(href, window.location.href);
    if (target.origin !== window.location.origin || target.pathname !== '/hr/attendance') return null;
    const url = new URL(element.dataset.historyUrl || window.location.href, window.location.href);
    target.searchParams.forEach((value, key) => url.searchParams.set(key, value));
    url.searchParams.set('tab', url.searchParams.get('tab') || element.dataset.currentTab || 'attendance');
    return url;
  };
  const init = () => {
    const element = root();
    if (loadingRoot && loadingRoot !== element) cancel();
    if (!element) return;
    element.querySelectorAll(tabSelector).forEach((tab) => {
      tab.tabIndex = tab.getAttribute('aria-selected') === 'true' ? 0 : -1;
    });
    remember(element);
  };
  const load = async (href, options = {}) => {
    const element = root();
    if (!element) return;
    const target = new URL(href, window.location.href);
    if (target.origin !== window.location.origin || target.pathname !== '/hr/attendance') return;
    const position = options.scroll || scrollState(element);
    const filterSnapshot = window.worksystemCaptureFragmentFilters?.(element);
    let documentHeight = Number(element.dataset.wsHistoryDocumentHeight) || document.documentElement?.scrollHeight || 0;
    if (options.push !== false) remember(element);
    cancel();
    window.dispatchEvent(new CustomEvent('worksystem:navigation-start', { detail: { owner: 'attendance-history' } }));
    const current = sequence;
    controller = new AbortController();
    loadingRoot = element;
    retry = { href, options };
    const finishLoading = window.wsLoading?.start(element, { variant: 'rows', surface: element.querySelector('.table-scroll') || element }) || (() => {});
    stopLoading = finishLoading;
    element.setAttribute('aria-busy', 'true');
    announce(element, '');
    try {
      const response = await fetch(href, {
        credentials: 'same-origin', signal: controller.signal,
        headers: { Accept: 'text/html', 'X-Worksystem-Fragment': 'attendance-history' },
      });
      if (current !== sequence || !element.isConnected) return;
      const finalUrl = new URL(response.url || href, window.location.href);
      if (finalUrl.pathname.startsWith('/login')) {
        window.location.assign(finalUrl.href);
        return;
      }
      if (!response.ok || finalUrl.pathname !== target.pathname) throw new Error('http');
      let fresh = new DOMParser().parseFromString(await response.text(), 'text/html').querySelector(selector);
      if (current !== sequence || !element.isConnected) return;
      if (!fresh || !fresh.querySelector('[role="tabpanel"]')) throw new Error('fragment');
      const canonical = new URL(fresh.dataset.historyUrl || href, window.location.href).href;
      if (new URL(canonical).origin !== window.location.origin || new URL(canonical).pathname !== target.pathname) throw new Error('fragment');
      loadingRoot = null;
      controller = null;
      finishLoading();
      stopLoading = () => {};
      // 빈 탭/마지막 페이지도 기존 문서의 최대 스크롤 높이를 줄이지 않는다.
      let naturalHeight = Number(element.dataset.wsHistoryMinHeight)
        || Math.max(element.getBoundingClientRect?.().height || 0, parseFloat(element.style?.minHeight) || 0);
      if (naturalHeight && fresh.style) fresh.style.minHeight = naturalHeight + 'px';
      const preserveDocumentHeight = () => {
        if (!fresh.style) return;
        // 직전 margin 보정값을 자연 높이로 누적하지 않고 매번 기준 높이에서 잰다.
        fresh.style.minHeight = naturalHeight + 'px';
        naturalHeight = Math.max(naturalHeight, fresh.getBoundingClientRect?.().height || 0);
        const currentDocumentHeight = document.documentElement?.scrollHeight || 0;
        documentHeight = Math.max(documentHeight, currentDocumentHeight);
        fresh.style.minHeight = (naturalHeight + Math.max(0, documentHeight - currentDocumentHeight)) + 'px';
        fresh.dataset.wsHistoryMinHeight = String(naturalHeight);
        fresh.dataset.wsHistoryDocumentHeight = String(documentHeight);
      };
      const previous = new URL(element.dataset.historyUrl || window.location.href, window.location.href).href;
      if (window.worksystemReconcileFragment) {
        fresh = window.worksystemReconcileFragment(element, fresh, {
          panelChanged: element.dataset.currentTab !== fresh.dataset.currentTab,
          preserveFilters: element.dataset.currentTab === fresh.dataset.currentTab,
          snapshot: filterSnapshot,
        });
      } else element.replaceWith(fresh);
      const nextState = { ...state(), wsAttendanceContext: context(canonical), wsAttendanceUrl: canonical, wsAttendanceScroll: position };
      if (options.push !== false && canonical !== previous) history.pushState(nextState, '', canonical);
      else history.replaceState(nextState, '', canonical);
      init();
      fresh.dispatchEvent(new CustomEvent('ws:partial-reloaded', { bubbles: true }));
      window.dispatchEvent(new CustomEvent('worksystem:content-updated', { detail: { root: fresh } }));
      if (options.focus) fresh.querySelector(options.focus === true ? '[aria-selected="true"]' : options.focus)?.focus({ preventScroll: true });
      announce(fresh, '');
      preserveDocumentHeight();
      restoreScroll(fresh, position);
      remember(fresh);
      const intent = scrollIntent;
      // 후속 위젯의 프레임 갱신/scroll anchoring 뒤 한 번만 복원한다.
      window.requestAnimationFrame?.(() => window.requestAnimationFrame?.(() => {
        if (current !== sequence || root() !== fresh || !fresh.isConnected || intent !== scrollIntent) return;
        preserveDocumentHeight();
        restoreScroll(fresh, position);
        remember(fresh);
      }));
      retry = null;
    } catch (error) {
      if (current !== sequence || error.name === 'AbortError' || !element.isConnected) return;
      announce(element, '내역을 불러오지 못했습니다. 현재 내역을 유지했습니다. 다시 시도해 주세요.', true, href);
    } finally {
      if (current === sequence) {
        element.removeAttribute('aria-busy');
        controller = null;
        loadingRoot = null;
        stopLoading = () => {};
      }
      finishLoading();
    }
  };
  // capture로 기존 soft navigation보다 먼저 탭을 소유하며, 수정키·새 창 동작은 유지한다.
  document.addEventListener('click', (event) => {
    if (event.defaultPrevented || event.button !== 0 || event.metaKey || event.ctrlKey || event.shiftKey || event.altKey) return;
    const element = root();
    const target = event.target.closest?.('a[href],[data-attendance-retry]');
    if (!element || !target || !element.contains(target)) {
      if (loadingRoot && event.target.closest?.('a[href]')) cancel();
      return;
    }
    if (target.hasAttribute('data-attendance-fallback')) {
      event.stopImmediatePropagation(); // 사용자가 선택한 명시적 전체 페이지 복구 링크
      return;
    }
    if (target.target && target.target !== '_self') return;
    if (target.hasAttribute('data-attendance-retry')) {
      event.preventDefault();
      event.stopImmediatePropagation();
      if (retry) load(retry.href, retry.options);
      return;
    }
    if (target.hasAttribute('download') || target.hasAttribute('data-app-form')) return;
    let href = mergeUrl(element, target.href);
    if (!href) return;
    event.preventDefault();
    event.stopImmediatePropagation();
    if (target.classList.contains('disabled') || target.getAttribute('href') === '#') return;
    if (target.hasAttribute('data-attendance-tab')) {
      href = new URL(element.dataset.historyUrl || window.location.href, window.location.href);
      href.searchParams.set('tab', target.dataset.attendanceTab);
    }
    load(href.href, { focus: target.hasAttribute('data-attendance-tab') ? true : '.pagination .active' });
  }, true);
  document.addEventListener('keydown', (event) => {
    const tab = event.target.closest?.(tabSelector);
    const element = root();
    if (!tab || !element?.contains(tab)) return;
    const tabs = [...element.querySelectorAll(tabSelector)];
    const index = tabs.indexOf(tab);
    let next;
    if (event.key === 'ArrowRight') next = (index + 1) % tabs.length;
    if (event.key === 'ArrowLeft') next = (index + tabs.length - 1) % tabs.length;
    if (event.key === 'Home') next = 0;
    if (event.key === 'End') next = tabs.length - 1;
    if (next !== undefined) {
      event.preventDefault();
      tabs.forEach((item, i) => { item.tabIndex = i === next ? 0 : -1; });
      tabs[next].focus();
    } else if (event.key === ' ') {
      event.preventDefault();
      tab.click();
    }
  });
  window.addEventListener('popstate', (event) => {
    const element = root();
    if (!element || window.location.pathname !== '/hr/attendance' || !event.state?.wsAttendanceUrl) {
      cancel();
      return;
    }
    const currentUrl = element.dataset.historyUrl || window.location.href;
    if (event.state.wsAttendanceContext !== context(currentUrl)) {
      cancel();
      return; // 다른 필터/화면 문맥은 기존 soft navigation으로 복원
    }
    event.stopImmediatePropagation();
    load(event.state.wsAttendanceUrl, { push: false, scroll: event.state.wsAttendanceScroll });
  }, true);
  document.addEventListener('submit', (event) => {
    const form = event.target;
    const element = root();
    if (!element?.contains(form) || (form.method || '').toLowerCase() !== 'get' || (form.target && form.target !== '_self')) {
      if (loadingRoot) cancel();
      return;
    }
    const href = mergeUrl(element, form.action || window.location.href);
    if (!href) { if (loadingRoot) cancel(); return; }
    const data = new FormData(form);
    data.forEach((value, key) => { if (typeof value === 'string') href.searchParams.set(key, value); });
    const tab = href.searchParams.get('tab') || element.dataset.currentTab || 'attendance';
    href.searchParams.set(({ attendance: 'page', leave: 'lpage', flex: 'fpage', trip: 'tpage', correction: 'cpage' })[tab] || 'page', '1');
    event.preventDefault();
    event.stopImmediatePropagation();
    load(href.href);
  }, true);
  window.addEventListener('worksystem:navigation-start', (event) => {
    if (event.detail?.owner !== 'attendance-history') cancel();
  });
  window.addEventListener('worksystem:content-updated', init);
  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', init, { once: true });
  else init();
})();
