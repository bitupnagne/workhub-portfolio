(() => {
  const contentSelector = '.content';
  const topbarSelector = '.topbar';
  const excludedPrefixes = ['/login', '/logout', '/report/', '/messages/'];
  const reportViews = new Set(['/report/my', '/report/team', '/report/admin', '/report/search']);
  const blockedGet = path => excludedPrefixes.some(prefix => path.startsWith(prefix)) && !reportViews.has(path);
  const pageOwnedSelector = '.schedule-edit-modal,[data-acal-modal],[data-mx-modal],[data-ll-modal],#releaseConfirmDialog,#releasePatchDialog,#stepTpl,#receiverModal,#weekCalOverlay';
  let committedHref = window.location.href;
  let getSequence = 0;
  let getController = null;
  let getLoading = [];
  const stylesheetLoads = new Map();
  const inlineInstalled = new Set(Array.from(document.querySelectorAll('body script:not([src])')).map(script => script.textContent.trim()));
  const executableInline = (doc) => Array.from(doc.querySelectorAll('body script:not([src])'))
    .filter(script => (!script.type || /^(?:text|application)\/javascript$/.test(script.type)) && script.textContent.trim());
  const inlineKind = (code) => {
    if (/var pickerPeople/.test(code) && /function submitMemo/.test(code)) return 'compose';
    if (/initApprovalChain|dash-cal-agenda|data-cal-agenda|data-emp-card|data-accrual-scope/.test(code)) return 'once';
    if (/data-theme-color|data-release-detail|function zoneSync/.test(code)) return 'mount';
    if (/modalEmpEdit|modalMonthLock/.test(code) && /DOMContentLoaded/.test(code)) return 'ready';
    return inlineInstalled.has(code) ? 'shared' : null;
  };
  const runInline = (scripts) => {
    scripts.forEach(({ code, kind }) => {
      if (kind === 'shared' || (kind === 'once' && inlineInstalled.has(code))) return;
      const script = document.createElement('script');
      // 페이지 전용 const/let은 별도 scope에 두고 새 DOM에만 직접 바인딩한다.
      const source = kind === 'ready' ? code.replace(/document\.addEventListener\(\s*['"]DOMContentLoaded['"]\s*,/g, '(function (ready) { ready(); })(') : code;
      const exports = kind === 'compose' ? '\nwindow.submitMemo = submitMemo;' : '';
      script.textContent = '(() => {\n' + source + exports + '\n})();';
      document.body.appendChild(script);
      script.remove();
      inlineInstalled.add(code);
    });
  };
  document.addEventListener('DOMContentLoaded', () => {
    executableInline(document).forEach(script => inlineInstalled.add(script.textContent.trim()));
  }, { once: true });
  const cancelGet = () => {
    getSequence += 1;
    getController?.abort();
    getController = null;
    getLoading.forEach(finish => finish());
    getLoading = [];
  };
  window.addEventListener('worksystem:navigation-start', (event) => {
    if (event.detail?.owner !== 'soft') cancelGet();
  });
  window.addEventListener('worksystem:content-updated', event => {
    if (event.detail?.root) committedHref = window.location.href;
  });
  document.addEventListener('submit', event => {
    cancelGet();
    if ((event.target.method || '').toLowerCase() === 'post') {
      // 이미 편집 중인 이전 문맥으로 제출하는 폼의 숨은 값을 뒤늦게 바꾸지 않는다.
      deferredStates.forEach((fresh, node) => { if (event.target.contains(node)) deferredStates.delete(node); });
    }
  }, true);

  const canSoftSubmit = (form) => {
    if (!form) return false;
    if ((form.method || '').toLowerCase() !== 'post') return false;
    if ((form.enctype || '').toLowerCase() === 'multipart/form-data'
        && !form.matches('[data-registration-form],[data-edit-form]')) return false;
    if (form.target && form.target !== '_self') return false;
    if (!form.closest(contentSelector)) return false;
    const action = new URL(form.action, window.location.href);
    if (action.origin !== window.location.origin) return false;
    return !excludedPrefixes.some((prefix) => action.pathname.startsWith(prefix));
  };

  // data-busy 폼 제출 시 로딩 오버레이(soft-submit은 preventDefault를 먼저 하므로 여기서 직접 표시).
  const showBusy = (form) => {
    if (!form || !form.hasAttribute('data-busy') || document.getElementById('wsBusyOverlay')) return;
    const ov = document.createElement('div');
    ov.id = 'wsBusyOverlay';
    ov.innerHTML = '<div class="ws-busy-card" aria-busy="true">' + (window.wsLoading?.markup('form') || '') + '</div>';
    document.body.appendChild(ov);
  };
  const hideBusy = () => { const ov = document.getElementById('wsBusyOverlay'); if (ov) ov.remove(); };

  const prependBanner = (className, message) => {
    const content = document.querySelector(contentSelector);
    if (!content) return;
    const banner = document.createElement('div');
    banner.className = className;
    banner.textContent = message;
    content.prepend(banner);
  };

  // 세션 만료/CSRF 거부/연결 단절 시: 안내 후 로그아웃(재로그인 유도). 서버 재시작으로 인메모리 세션이 날아간 경우 등.
  let loggingOut = false;
  const forceLogout = () => {
    if (loggingOut) return;
    loggingOut = true;
    try { document.cookie = 'OFFICESESSION=; Max-Age=0; path=/'; } catch (e) { /* ignore */ }
    const mask = document.createElement('div');
    mask.className = 'ws-relogin-mask';
    mask.innerHTML = '<div class="ws-relogin-card">서버와의 연결이 불안정하여, 로그아웃 됩니다.</div>';
    document.body.appendChild(mask);
    setTimeout(() => { window.location.href = '/login?relogin=1'; }, 1600);
  };
  const isLoginUrl = (u) => {
    try { return new URL(u, window.location.href).pathname.startsWith('/login'); } catch (e) { return false; }
  };

  // 서버가 명시한 owner만 부분 갱신한다. 요청 키/결과/문맥 슬롯이 맞지 않으면
  // 추정해서 표를 갱신하지 않고 전체 서버 문맥으로 복구한다.
  const queryOwners = root => {
    if (!root) return [];
    const external = Array.from((root.ownerDocument || document).querySelectorAll?.('[data-ws-query-contract][data-ws-query-detached-owner]') || []);
    return [...new Set([...(root.hasAttribute?.('data-ws-query-contract') ? [root] : []), ...Array.from(root.querySelectorAll?.('[data-ws-query-contract]') || []), ...external])]
      .filter(owner => {
        if (owner.closest?.('[data-app-form-body]')) return false;
        const view = owner.closest?.('.content');
        return !view || view === root;
      });
  };
  const queryKeys = owner => new Set((owner?.getAttribute('data-ws-query-params') || '').split(/\s+/).filter(Boolean));
  const changedQueryKeys = (before, after) => [...new Set([...before.searchParams.keys(), ...after.searchParams.keys()])]
    .filter(key => JSON.stringify(before.searchParams.getAll(key)) !== JSON.stringify(after.searchParams.getAll(key)));
  const requestOwner = (source, root) => {
    if (source?.closest?.('[data-app-form-body]')) return null;
    const direct = source?.closest?.('[data-ws-query-contract]');
    if (direct) return direct;
    const ref = source?.closest?.('[data-ws-query-owner]')?.getAttribute('data-ws-query-owner');
    return ref ? queryOwners(root).find(owner => owner.getAttribute('data-ws-query-contract') === ref) : null;
  };
  const inferredOwner = (root, target) => {
    const previous = new URL(committedHref || window.location.href);
    if (previous.pathname !== target.pathname) return null;
    const differences = changedQueryKeys(previous, target);
    if (!differences.length) return null;
    const owners = queryOwners(root).filter(owner => differences.every(key => queryKeys(owner).has(key)));
    return owners.length === 1 ? owners[0] : null;
  };
  const ownerRequestUrl = (href, source, owner, formRequest = false) => {
    const target = new URL(href, window.location.href), previous = new URL(committedHref || window.location.href);
    if (!owner || target.origin !== previous.origin || target.pathname !== previous.pathname) return target;
    const merged = new URL(previous.href), keys = queryKeys(owner);
    const reset = source?.hasAttribute?.('data-ws-query-reset');
    keys.forEach(key => {
      if (formRequest || reset || target.searchParams.has(key)) {
        merged.searchParams.delete(key);
        target.searchParams.getAll(key).forEach(value => merged.searchParams.append(key, value));
      }
    });
    merged.hash = target.hash;
    return merged;
  };
  const keyedNodes = (root, attribute, externalDocument) => {
    const contract = root.getAttribute('data-ws-query-contract');
    const local = [...(root.hasAttribute(attribute) ? [root] : []), ...Array.from(root.querySelectorAll('[' + attribute + ']'))]
      .filter(node => {
        const nearest = node.closest?.('[data-ws-query-contract]'), reference = node.getAttribute('data-ws-query-owner');
        return (!reference || reference === contract) && (!nearest || nearest === root);
      });
    const external = Array.from(externalDocument?.querySelectorAll?.('[' + attribute + '][data-ws-query-owner]') || [])
      .filter(node => node.getAttribute('data-ws-query-owner') === contract && !node.closest?.('[data-app-form-body]'));
    const nodes = [...new Set([...local, ...external])], keys = nodes.map(node => node.getAttribute(attribute));
    return keys.every(Boolean) && new Set(keys).size === keys.length ? new Map(nodes.map((node, i) => [keys[i], node])) : null;
  };
  const keyedGlobalStates = root => new Map(Array.from(root.querySelectorAll('[data-ws-query-global-state]'))
    .map(node => [node.getAttribute('data-ws-query-global-state'), node]));
  const queryPlan = (before, after, oldUrl, nextUrl, ownerHint = '', nextDocument = null) => {
    if (oldUrl.pathname !== nextUrl.pathname) return null;
    const differences = changedQueryKeys(oldUrl, nextUrl), results = [];
    const incoming = queryOwners(after);
    queryOwners(before).forEach(scope => {
      const contract = scope.getAttribute('data-ws-query-contract');
      if (!contract || ownerHint && contract !== ownerHint) return;
      const matches = incoming.filter(node => node.getAttribute('data-ws-query-contract') === contract);
      if (matches.length !== 1) return;
      const fresh = matches[0], path = scope.getAttribute('data-ws-query-path');
      if ((path !== 'self' && path !== oldUrl.pathname) || fresh.getAttribute('data-ws-query-path') !== path
          || scope.getAttribute('data-ws-query-context') !== fresh.getAttribute('data-ws-query-context')
          || scope.getAttribute('data-ws-query-params') !== fresh.getAttribute('data-ws-query-params')
          || differences.some(key => !queryKeys(scope).has(key))) return;
      const pairs = attribute => {
        const oldNodes = keyedNodes(scope, attribute, document), newNodes = keyedNodes(fresh, attribute, nextDocument || after.ownerDocument);
        if (!oldNodes || !newNodes || oldNodes.size !== newNodes.size) return null;
        return [...oldNodes].every(([key, node]) => newNodes.has(key) && node.tagName === newNodes.get(key).tagName)
          ? [...oldNodes].map(([key, node]) => [node, newNodes.get(key)]) : null;
      };
      const regions = pairs('data-ws-query-region'), state = pairs('data-ws-query-state'), filters = pairs('data-ws-query-filter'), meta = pairs('data-ws-query-meta');
      if (regions?.length && state && filters && meta) results.push({ beforeRoot: scope, afterRoot: fresh, regions, state, filters, meta });
    });
    return results.length === 1 ? results[0] : null;
  };
  const filterValue = field => /^(checkbox|radio)$/.test(field.type) ? field.checked : field.value;
  const captureFilters = root => {
    const filters = root?.hasAttribute?.('data-ws-query-contract')
      ? [...keyedNodes(root, 'data-ws-query-filter', document)?.values() || []]
      : Array.from(root?.querySelectorAll?.('[data-ws-query-filter]') || []);
    return new Map(filters.flatMap(form => Array.from(form.querySelectorAll('[name]')).map(field => [field, filterValue(field)])));
  };
  const copyAttributes = (before, after) => {
    Array.from(before.attributes).forEach(attribute => before.removeAttribute(attribute.name));
    Array.from(after.attributes).forEach(attribute => before.setAttribute(attribute.name, attribute.value));
  };
  // 명시된 작은 상태 슬롯만 동기화한다. 클릭 중인 월/주 버튼과 indicator는
  // 분리하지 않고 href/ARIA/data/텍스트를 최신 서버 값으로 바꾼다.
  const syncState = (before, after, options = {}) => {
    if (options.keep?.has(before)) return;
    if (before.nodeType === 3 || before.nodeType === 8) { before.nodeValue = after.nodeValue; return; }
    const nextValue = after.value;
    const left = before.scrollLeft;
    const islandHeight = before.matches?.('.table-scroll') ? Math.max(before.clientHeight || 0, parseFloat(before.style.minHeight) || 0) : 0;
    copyAttributes(before, after);
    if (options.island?.(before)) {
      if (islandHeight) before.style.minHeight = islandHeight + 'px';
      before.replaceChildren(...Array.from(after.childNodes));
      before.scrollLeft = left;
      Array.from(before.children).forEach(child => child.classList?.add('ws-query-enter'));
      return;
    }
    if (before.tagName === 'INPUT') {
      if (before.type !== 'file') before.value = after.value;
      before.checked = after.checked;
      return;
    }
    const owned = node => !(node.nodeType === 1 && node.classList.contains('ws-choice-indicator'));
    const oldChildren = Array.from(before.childNodes).filter(owned), newChildren = Array.from(after.childNodes).filter(owned);
    for (let i = 0; i < Math.max(oldChildren.length, newChildren.length); i++) {
      const node = oldChildren[i], fresh = newChildren[i];
      if (!node) before.appendChild(fresh);
      else if (!fresh) node.remove();
      else if (node.nodeType === fresh.nodeType && node.nodeName === fresh.nodeName) syncState(node, fresh, options);
      else node.replaceWith(fresh);
    }
    if (before.tagName === 'SELECT' || before.tagName === 'TEXTAREA') before.value = nextValue;
  };
  const deferredStates = new Map();
  const stateHasDraft = node => {
    const form = node.closest?.('form'), dialog = node.closest?.('.schedule-edit-modal,.ws-registration-modal,[role="dialog"]');
    if (!form || !dialog || dialog.hidden || dialog.closest('[hidden]')) return false;
    return form.getAttribute('data-edit-state') === 'editing' || form.hasAttribute('data-registration-form')
      || (form.method || '').toLowerCase() === 'post' && !form.hasAttribute('data-edit-form');
  };
  const applyState = (node, fresh) => {
    if (node.isConnected === false) { deferredStates.delete(node); return; }
    if (stateHasDraft(node)) {
      deferredStates.set(node, fresh.cloneNode(true));
      if (deferredStates.size > 100) deferredStates.delete(deferredStates.keys().next().value);
      return;
    }
    deferredStates.delete(node);
    syncState(node, fresh);
  };
  const flushDeferredStates = () => {
    [...deferredStates].forEach(([node, fresh]) => {
      if (node.isConnected === false) deferredStates.delete(node);
      else if (!stateHasDraft(node)) applyState(node, fresh);
    });
  };
  let deferredScheduled = false;
  const scheduleDeferredStates = () => {
    if (deferredScheduled || !deferredStates.size) return;
    deferredScheduled = true;
    queueMicrotask(() => { deferredScheduled = false; flushDeferredStates(); });
  };
  const watchDeferredStates = () => {
    if (typeof MutationObserver !== 'function') return;
    new MutationObserver(scheduleDeferredStates).observe(document.body, { subtree: true, attributes: true, attributeFilter: ['hidden', 'data-edit-state'] });
  };
  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', watchDeferredStates, { once: true });
  else watchDeferredStates();
  window.addEventListener('worksystem:content-updated', scheduleDeferredStates);
  window.addEventListener('pagehide', () => deferredStates.clear());
  const syncFilters = (pairs, snapshot) => pairs.forEach(([before, after]) => {
    if (before.isConnected === false) return;
    const fields = Array.from(before.querySelectorAll('[name]')), nextFields = Array.from(after.querySelectorAll('[name]'));
    const sameSchema = fields.length === nextFields.length && fields.every((field, i) => field.name === nextFields[i].name && field.tagName === nextFields[i].tagName);
    copyAttributes(before, after);
    if (!sameSchema) { before.replaceChildren(...Array.from(after.childNodes)); return; }
    fields.forEach((field, index) => {
      const fresh = nextFields[index];
      if (/^(file|password|submit|button)$/.test(field.type)) return;
      if (snapshot?.has(field) && snapshot.get(field) !== filterValue(field)) return;
      const nextValue = fresh.value, nextChecked = fresh.checked;
      if (field.tagName === 'SELECT') field.replaceChildren(...Array.from(fresh.childNodes));
      if (/^(checkbox|radio)$/.test(field.type)) field.checked = nextChecked;
      else field.value = nextValue;
      field.disabled = fresh.disabled;
      field.dispatchEvent(new CustomEvent('worksystem:query-filter-sync'));
    });
  });
  const syncMeta = (pairs, canonical) => pairs.forEach(([node, fresh]) => {
    const attributes = (fresh.getAttribute('data-ws-query-meta-attrs') || '').split(/\s+/).filter(Boolean);
    attributes.forEach(name => {
      if (!name.startsWith('data-')) return;
      if (fresh.hasAttribute(name)) node.setAttribute(name, fresh.getAttribute(name));
      else node.removeAttribute(name);
    });
    const linkKeys = (fresh.getAttribute('data-ws-query-link-params') || '').split(/\s+/).filter(Boolean);
    if (canonical && linkKeys.length) node.querySelectorAll('a[href]').forEach(link => {
      const href = new URL(link.getAttribute('href'), canonical);
      if (href.origin !== canonical.origin || href.pathname !== canonical.pathname) return;
      linkKeys.forEach(key => {
        href.searchParams.delete(key);
        canonical.searchParams.getAll(key).forEach(value => href.searchParams.append(key, value));
      });
      link.setAttribute('href', href.pathname + href.search + href.hash);
    });
  });
  // 전용 fragment fetch도 같은 DOM 갱신 원칙을 쓴다. 작은 shell/nav는 그대로 두고
  // 표/페이지 또는 탭 스키마가 바뀌는 panel만 새 응답으로 바꾼다.
  const captureFragmentFilters = root => new Map(Array.from(root.querySelectorAll('form[method="get"] [name]'))
    .map(field => [field, filterValue(field)]));
  const reconcileFragment = (before, after, options = {}) => {
    const keep = new Set();
    if (options.preserveFilters !== false) {
      const oldForms = Array.from(before.querySelectorAll('form[method="get"]'));
      const newForms = Array.from(after.querySelectorAll('form[method="get"]'));
      if (oldForms.length === newForms.length) oldForms.forEach((form, index) => {
        const fields = Array.from(form.querySelectorAll('[name]')), fresh = newForms[index];
        const nextFields = Array.from(fresh.querySelectorAll('[name]'));
        if (fields.length !== nextFields.length || fields.some((field, i) => field.name !== nextFields[i].name || field.tagName !== nextFields[i].tagName)) return;
        syncFilters([[form, fresh]], options.snapshot);
        keep.add(form);
      });
    }
    const selector = options.panelChanged ? '[role="tabpanel"]' : '.table-scroll,.pagination,.table-footer';
    syncState(before, after, { keep, island: node => node.matches?.(selector) });
    return before;
  };
  window.worksystemCaptureFragmentFilters = captureFragmentFilters;
  window.worksystemReconcileFragment = reconcileFragment;
  // GET 탭의 기존 indicator/포커스 노드를 유지하고 선택 상태는 서버 응답에서 가져온다.
  // 직접 바인딩한 버튼 그룹은 재사용하지 않아 이전 페이지의 클로저를 남기지 않는다.
  const retainNavigation = (before, after, base) => {
    const selector = 'nav.ws-segmented,nav.ws-section-tabs';
    const groups = root => Array.from(root.querySelectorAll(selector));
    const links = group => Array.from(group.children).filter(child => !child.classList.contains('ws-choice-indicator'));
    const signature = group => {
      const items = links(group);
      return items.length && items.every(item => item.tagName === 'A' && item.hasAttribute('href'))
        ? (group.id || group.getAttribute('aria-label') || '') + '|' + items.map(item => new URL(item.getAttribute('href'), base).href).join('|') : null;
    };
    const old = groups(before), incoming = groups(after);
    incoming.forEach(group => {
      const key = signature(group), matches = key ? old.filter(candidate => signature(candidate) === key) : [];
      if (matches.length !== 1) return;
      const kept = matches[0], currentLinks = links(kept), freshLinks = links(group);
      copyAttributes(kept, group);
      currentLinks.forEach((link, i) => { copyAttributes(link, freshLinks[i]); link.innerHTML = freshLinks[i].innerHTML; });
      group.replaceWith(kept);
    });
  };

  const replaceContentFromResponse = async (response, updateHistory = true, isCurrent = () => true, queryOnly = false, filterSnapshot = null, ownerHint = '') => {
    if (!isCurrent()) return false;
    // 세션 만료로 로그인 페이지로 리다이렉트된 경우 → 콘텐츠 교체 대신 로그아웃 안내
    if (response && response.url && isLoginUrl(response.url) && !isLoginUrl(window.location.href)) {
      forceLogout();
      return false;
    }
    const html = await response.text();
    if (!isCurrent()) return false;
    if (!response.ok) throw new Error(`HTTP ${response.status}`);
    const doc = new DOMParser().parseFromString(html, 'text/html');
    const nextContent = doc.querySelector(contentSelector);
    const currentContent = document.querySelector(contentSelector);
    if (!nextContent || !currentContent) {
      window.location.assign(response.url || window.location.href);
      return false;
    }
    const nextUrl = new URL(response.url || window.location.href, window.location.href);
    const oldUrl = new URL(committedHref || window.location.href);
    const crossPath = nextUrl.pathname !== oldUrl.pathname;
    const scripts = executableInline(doc).map(script => {
      const code = script.textContent.trim();
      return { code, kind: inlineKind(code) };
    });
    if (scripts.some(script => !script.kind)) {
      const error = new Error('이 화면의 초기화 방식은 확인되지 않았습니다. 현재 화면을 유지합니다.');
      error.viewUpdate = true;
      throw error;
    }
    if (crossPath) {
      // Page-specific CSS must be ready before showing its UI, in the same cascade order as a hard load.
      const styleLinks = Array.from(doc.querySelectorAll('head link[rel="stylesheet"][href]'));
      const presentStyles = new Map(Array.from(document.querySelectorAll('link[rel="stylesheet"][href]'))
        .map(link => [new URL(link.getAttribute('href'), window.location.href).href, link]));
      for (let index = 0; index < styleLinks.length; index++) {
        const source = styleLinks[index];
        const url = new URL(source.getAttribute('href'), nextUrl);
        if (url.origin !== window.location.origin) continue;
        if (!presentStyles.has(url.href) && !stylesheetLoads.has(url.href)) {
          const link = document.createElement('link');
          link.rel = 'stylesheet'; link.href = url.href;
          ['media', 'integrity', 'crossorigin', 'referrerpolicy'].forEach(name => {
            if (source.hasAttribute(name)) link.setAttribute(name, source.getAttribute(name));
          });
          const following = styleLinks.slice(index + 1)
            .map(next => presentStyles.get(new URL(next.getAttribute('href'), nextUrl).href))
            .find(next => next?.parentNode === document.head);
          const loaded = new Promise((resolve, reject) => {
            link.onload = resolve;
            link.onerror = () => {
              link.remove(); stylesheetLoads.delete(url.href);
              const error = new Error('화면 스타일을 불러오지 못했습니다.'); error.viewUpdate = true;
              reject(error);
            };
            document.head.insertBefore(link, following || null);
          });
          stylesheetLoads.set(url.href, loaded);
          presentStyles.set(url.href, link);
        }
        if (stylesheetLoads.has(url.href)) await stylesheetLoads.get(url.href);
        if (!isCurrent()) return false;
      }
      const loadedSrcs = new Set(Array.from(document.querySelectorAll('script[src]'))
        .map((sc) => new URL(sc.getAttribute('src'), window.location.href).pathname));
      const pending = Array.from(doc.querySelectorAll('body script[src]'))
        .map((sc) => new URL(sc.getAttribute('src'), window.location.href))
        .filter((u) => u.origin === window.location.origin && !loadedSrcs.has(u.pathname));
      for (const u of pending) {
        await new Promise((resolve, reject) => {
          const el = document.createElement('script');
          el.src = u.pathname + u.search;
          el.onload = resolve;
          el.onerror = () => {
            el.remove();
            const error = new Error('화면 스크립트를 불러오지 못했습니다.'); error.viewUpdate = true;
            reject(error);
          };
          document.body.appendChild(el);
        });
        if (!isCurrent()) return false;
      }
    }
    if (!isCurrent()) return false;
    const oldY = window.scrollY;
    let partial = false;
    const plan = !crossPath && queryOnly ? queryPlan(currentContent, nextContent, oldUrl, nextUrl, ownerHint, doc) : null;
    if (plan) {
      const candidates = (root, selector) => Array.from(root.querySelectorAll(selector))
        .filter(node => !node.parentElement?.closest(selector) && !node.closest('.schedule-edit-modal,[role="dialog"],form[data-edit-form]'));
      const footers = (root, pairIndex) => candidates(root, '.table-footer,.pagination')
        .filter(node => !plan.regions.some(pair => pair[pairIndex] === node || pair[pairIndex].contains?.(node)));
      const oldFooters = footers(plan.beforeRoot, 0), newFooters = footers(plan.afterRoot, 1);
      let footerAnchor = plan.regions.map(pair => pair[0]).filter(node => !plan.beforeRoot.contains || plan.beforeRoot.contains(node)).at(-1);
      getLoading.forEach(finish => finish());
      plan.regions.forEach(([node, fresh]) => {
        const left = node.scrollLeft, height = Math.max(parseFloat(node.style.minHeight) || 0, node.clientHeight || 0);
        if (node.getAttribute('data-ws-query-reconcile') === 'member-attendance') {
          reconcileFragment(node, fresh, { preserveFilters: true, snapshot: filterSnapshot });
          if (height) node.style.minHeight = height + 'px';
          return;
        }
        copyAttributes(node, fresh);
        if (height) node.style.minHeight = height + 'px';
        node.replaceChildren(...Array.from(fresh.childNodes));
        node.scrollLeft = left;
        Array.from(node.children).forEach(child => child.classList?.add('ws-query-enter'));
      });
      for (let i = 0; i < Math.max(oldFooters.length, newFooters.length); i++) {
        const node = oldFooters[i], fresh = newFooters[i];
        const height = node ? Math.max(parseFloat(node.style.minHeight) || 0, node.clientHeight || 0) : 0;
        if (node && fresh) syncState(node, fresh);
        else if (node) node.replaceChildren();
        else if (fresh && footerAnchor) { footerAnchor.insertAdjacentElement('afterend', fresh); footerAnchor = fresh; }
        if (node && height) node.style.minHeight = height + 'px';
      }
      plan.state.forEach(([node, fresh]) => applyState(node, fresh));
      syncFilters(plan.filters, filterSnapshot);
      syncMeta(plan.meta, nextUrl);
      const globalState = keyedGlobalStates(document), incomingState = keyedGlobalStates(doc);
      globalState.forEach((node, key) => { const fresh = incomingState.get(key); if (fresh && node.tagName === fresh.tagName) applyState(node, fresh); });
      partial = true;
    }
    const nextTopbar = doc.querySelector(topbarSelector);
    const currentTopbar = document.querySelector(topbarSelector);
    // 이 조건부 fragment는 body 직접 자식이 아니라 main 안 anonymous wrapper에도 놓인다.
    const nextWeekOverlay = !partial ? doc.querySelector('#weekCalOverlay') : null;
    if (nextWeekOverlay) nextWeekOverlay.remove();
    if (!partial && nextTopbar && currentTopbar) {
      currentTopbar.replaceWith(nextTopbar);
    }
    if (!partial) {
      if (!crossPath) retainNavigation(currentContent, nextContent, nextUrl.href);
      if (!crossPath && queryOnly && currentContent.clientHeight) {
        nextContent.style.minHeight = Math.max(currentContent.clientHeight, parseFloat(currentContent.style.minHeight) || 0) + 'px';
      }
      // 결과가 어느 owner인지 확인되지 않은 요청은 서버 문맥 전체를 반영하되 root를 fade하지 않는다.
      Array.from(nextContent.querySelectorAll('.table-scroll,.m-emp-list,[role="tabpanel"]')).filter(node => !node.parentElement?.closest('.table-scroll,.m-emp-list,[role="tabpanel"]'))
        .forEach(node => node.classList.add('ws-query-enter'));
      currentContent.replaceWith(nextContent);
    }
    if (doc.title) document.title = doc.title;
    if (updateHistory && response.url) {
      const cleanHref = new URL(response.url, window.location.href);
      cleanHref.hash = '';
      if (cleanHref.href !== window.location.href) window.history.pushState({}, '', cleanHref.href);
    }
    committedHref = nextUrl.href;
    if (window.worksystemSyncMenu) window.worksystemSyncMenu(doc);
    if (!partial) {
      const modalRoot = doc.body;
      // 최초 직접 진입 때의 소유물도 정리해 재진입 중복 ID를 막는다. 공통 전역 모달은 유지한다.
      document.querySelectorAll('body > [data-soft-modal]').forEach((m) => m.remove());
      Array.from(document.body.children).filter(el => el.matches(pageOwnedSelector)).forEach(el => el.remove());
      document.querySelector('#weekCalOverlay')?.remove();
      Array.from(modalRoot.children).forEach((el) => {
        if (el.matches(pageOwnedSelector)) {
          el.setAttribute('data-soft-modal', '');
          document.body.appendChild(el);
        }
      });
      if (nextWeekOverlay) {
        nextWeekOverlay.setAttribute('data-soft-modal', '');
        document.body.appendChild(nextWeekOverlay);
      }
    }
    if (crossPath) window.scrollTo(0, 0);
    if (!partial) runInline(scripts);
    window.dispatchEvent(new CustomEvent('worksystem:content-updated'));
    if (!crossPath) window.scrollTo(0, oldY);
    if (queryOnly) committedHref = window.location.href;
    return true;
  };

  const softGet = async (url, updateHistory = true, options = {}) => {
    cancelGet();
    window.dispatchEvent(new CustomEvent('worksystem:navigation-start', { detail: { owner: 'soft' } }));
    const current = getSequence;
    const queryFocus = document.activeElement;
    const requestController = new AbortController();
    getController = requestController;
    const content = document.querySelector(contentSelector);
    const explicitOwner = requestOwner(options.source, content);
    url = ownerRequestUrl(url, options.source, explicitOwner, options.formRequest).href;
    const owner = explicitOwner || inferredOwner(content, new URL(url));
    const ownerHint = owner?.getAttribute('data-ws-query-contract') || '';
    const filterSnapshot = captureFilters(owner || content);
    const focusKey = queryFocus ? { id: queryFocus.id, name: queryFocus.name, href: queryFocus.getAttribute?.('href') } : null;
    const loadingSelector = '.table-scroll,.m-emp-list,.vf-report-section [data-section-body]';
    const ownerSurfaces = owner ? [...keyedNodes(owner, 'data-ws-query-region', document)?.values() || []] : [];
    const surfaces = (ownerSurfaces.length ? ownerSurfaces : [...(content?.querySelectorAll?.(loadingSelector) || [])])
      .filter(surface => !surface.closest?.('[hidden],.ws-registration-modal,form[data-edit-form]') && (!surface.getClientRects || surface.getClientRects().length));
    const finishLoading = surfaces.map(surface => window.wsLoading?.start(surface, { variant: 'rows' }) || (() => {}));
    getLoading = finishLoading;
    try {
      const response = await fetch(url, {
        method: 'GET',
        signal: requestController.signal,
        credentials: 'same-origin',
        headers: { 'X-Requested-With': 'fetch' },
      });
      if (current !== getSequence) return;
      await replaceContentFromResponse(response, updateHistory, () => current === getSequence, true, filterSnapshot, ownerHint);
    } catch (error) {
      if (current !== getSequence || error.name === 'AbortError') return;
      // popstate는 응답 전에 주소가 바뀐다. 실패하면 유지한 DOM의 주소로 되돌려
      // 이전 화면과 다른 주소/문맥을 표시하지 않는다. 실패한 대상의 전용 state도 남기지 않는다.
      if (!updateHistory && committedHref && window.location.href !== committedHref) {
        window.history.replaceState(null, '', committedHref);
      }
      prependBanner('banner err', '조회하지 못했습니다. 기존 결과를 유지합니다. 다시 시도해 주세요.');
    } finally {
      finishLoading.forEach(finish => finish());
      if (current === getSequence) {
        getController = null; getLoading = [];
        if (queryFocus && (document.activeElement === document.body || document.activeElement === queryFocus)) {
          const root = document.querySelector(contentSelector);
          const replacement = queryFocus.isConnected ? queryFocus : Array.from(root?.querySelectorAll?.('a[href],input,select,button,textarea') || [])
            .find(node => (focusKey.id && node.id === focusKey.id) || (focusKey.href && node.getAttribute('href') === focusKey.href)
              || (focusKey.name && node.name === focusKey.name));
          replacement?.focus?.({ preventScroll: true });
        }
      }
    }
  };

  const canSoftGetForm = (form) => {
    if (!form || (form.method || '').toLowerCase() !== 'get') return false;
    if (form.target && form.target !== '_self') return false;
    if (form.closest('[data-app-form-body]')) return false;
    const main = document.querySelector(contentSelector), view = form.closest(contentSelector), owner = requestOwner(form, main);
    if (view && view !== main) return false;
    if (!view && !owner?.hasAttribute('data-ws-query-detached-owner')) return false;
    const dialog = form.closest('.schedule-edit-modal,.ws-registration-modal,[role="dialog"]');
    if (dialog && (!owner || !dialog.contains(owner) && !(owner.hasAttribute('data-ws-query-detached-owner') && owner.contains(dialog)))) return false;
    const action = new URL(form.action, window.location.href);
    if (action.origin !== window.location.origin) return false;
    return !blockedGet(action.pathname);
  };

  window.worksystemSoftNavigate = (href, options = {}) => {
    const url = new URL(href, window.location.href);
    if (url.origin !== window.location.origin
        || blockedGet(url.pathname)) {
      window.location.assign(url.href);
      return false;
    }
    softGet(url.href, true, options); // 공통/페이지별 초기화 수명주기를 유지하는 부분 화면 이동
    return true;
  };

  document.addEventListener('submit', async (event) => {
    if (event.defaultPrevented) return;
    const form = event.target.closest('form');
    if (canSoftGetForm(form)) {
      event.preventDefault();
      const url = new URL(form.action, window.location.href);
      url.search = '';
      new FormData(form).forEach((value, key) => {
        if (value !== null && String(value) !== '') url.searchParams.append(key, value);
      });
      await softGet(url.href, true, { source: form, formRequest: true });
      return;
    }
    if (!canSoftSubmit(form)) return;
    event.preventDefault();
    await Promise.resolve();

    const submitter = event.submitter;
    const formData = new FormData(form);
    if (submitter && submitter.name && !formData.has(submitter.name)) {
      formData.append(submitter.name, submitter.value);
    }
    if (submitter) submitter.disabled = true;
    showBusy(form);

    try {
      const response = await fetch(form.action, {
        method: 'POST',
        body: formData,
        credentials: 'same-origin',
        headers: { 'X-Requested-With': 'fetch' },
      });
      // 세션 만료/CSRF 거부(401/403) → 로그아웃 안내. 그 외 비정상(413/400/5xx)은 배너만(로그아웃 X).
      if (response.status === 401 || response.status === 403) { forceLogout(); return; }
      if (!response.ok) { prependBanner('banner err', '요청을 처리하지 못했습니다. 입력 양이 많거나 일시적인 오류일 수 있습니다.'); return; }
      await replaceContentFromResponse(response, false);
      if (response.url) {
        // 서버가 redirect한 화면 문맥(type/year/team 등)을 URL에도 그대로 반영한다.
        const cleanHref = new URL(response.url, window.location.href);
        cleanHref.hash = '';
        if (cleanHref.href !== window.location.href) window.history.replaceState({}, '', cleanHref.href);
      }
    } catch (error) {
      if (error.viewUpdate) prependBanner('banner err', error.message);
      else forceLogout(); // 기존 POST 전송 실패 처리
    } finally {
      hideBusy();
      if (submitter) submitter.disabled = false;
    }
  });

  document.addEventListener('click', async (event) => {
    if (event.defaultPrevented || event.button !== 0 || event.metaKey || event.ctrlKey || event.shiftKey || event.altKey) return;
    const link = event.target.closest('a[href]');
    if (!link || link.closest('[data-app-form-body]')) return;
    const main = document.querySelector(contentSelector), view = link.closest(contentSelector), owner = requestOwner(link, main);
    if (view && view !== main) return;
    if (!view && !link.closest(topbarSelector) && !owner?.hasAttribute('data-ws-query-detached-owner')) return;
    const dialog = link.closest('.schedule-edit-modal,.ws-registration-modal,[role="dialog"]');
    if (dialog && (!owner || !dialog.contains(owner) && !(owner.hasAttribute('data-ws-query-detached-owner') && owner.contains(dialog)))) return;
    if (link.target && link.target !== '_self') return;
    if (link.hasAttribute('download') || link.dataset.fileViewerUrl || link.dataset.receiptUrl) return;
    if (link.hasAttribute('data-app-form')) return; // [UX23] 신청 폼 모달이 처리
    const url = new URL(link.href, window.location.href);
    if (url.origin !== window.location.origin) return;
    if (url.hash && url.pathname === window.location.pathname && url.search === window.location.search) return;
    if (blockedGet(url.pathname)) return;
    event.preventDefault();
    await softGet(url.href, true, { source: link }); // 명시 owner는 해당 조회 키/영역만 소유한다.
  });

  // 사이드바 로고/프로필/서비스 바로가기도 같은 GET 이동을 사용한다. 모바일 서비스 토글은 defaultPrevented로 제외한다.
  document.addEventListener('click', async (event) => {
    if (event.defaultPrevented || event.button !== 0 || event.metaKey || event.ctrlKey || event.shiftKey || event.altKey) return;
    const link = event.target.closest('.sidebar a[href], .nav-flyout a[href], .nav-popout a[href], a.m-tab[data-m-match][href]');
    if (!link || link.hasAttribute('download') || link.dataset.fileViewerUrl || link.dataset.receiptUrl) return;
    if (link.target && link.target !== '_self') return;
    const url = new URL(link.href, window.location.href);
    if (url.origin !== window.location.origin) return;
    if (blockedGet(url.pathname) || url.hash && url.pathname === window.location.pathname && url.search === window.location.search) return;
    event.preventDefault();
    document.body.classList.remove('nav-drawer-open');
    await softGet(url.href);
  });

  window.addEventListener('popstate', () => {
    softGet(window.location.href, false);
  });
  window.addEventListener('worksystem:member-attendance-updated', () => { committedHref = window.location.href; });
})();
