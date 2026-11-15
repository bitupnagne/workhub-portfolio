// 서버가 선택한 하위 메뉴를 유지한다. 같은 path의 box/view 쿼리는 서로 다른 메뉴다.
(() => {
  const selector = '.sidebar .nav-children a[href], .sidebar [data-shell-link], .m-tab[data-m-match]';
  const key = (link) => {
    const url = new URL(link.href, window.location.href);
    url.searchParams.sort();
    return (link.closest('[data-nav-group]')?.getAttribute('data-nav-group') || '') + '|' + url.pathname + url.search;
  };
  window.worksystemSyncMenu = (source = document) => {
    const selected = new Map([...source.querySelectorAll(selector)].map((link) => [key(link), link.classList.contains('active')]));
    document.querySelectorAll(selector).forEach((link) => {
      const active = selected.get(key(link)) === true;
      link.classList.toggle('active', active);
      if (active) link.setAttribute('aria-current', 'page');
      else link.removeAttribute('aria-current');
    });
    document.querySelectorAll('.sidebar .nav-group').forEach((group) => {
      const active = !!group.querySelector('.nav-children a.active');
      group.classList.toggle('active-group', active);
      if (active) group.setAttribute('open', '');
    });
  };
  window.worksystemSyncMenu();
})();

// 선택 항목은 가로 그룹 안에서만 드러내고 문서 스크롤과 키보드 포커스는 유지한다.
(function () {
  if (window.__worksystemSelectionGroups) return;
  window.__worksystemSelectionGroups = true;
  const groupSelector = '.ws-segmented,.ws-section-tabs';
  const activeSelector = '.active,.on,.is-active,.primary,[aria-current="page"],[aria-selected="true"],[aria-pressed="true"]';
  const pending = new Set(), positions = new Map(), timers = new WeakMap(), layouts = new WeakMap();
  let scheduled = false;
  function positionKey(group) {
    const kind = [...group.classList].filter(name => name !== 'ws-choice-moving').sort().join('.');
    const index = [...document.querySelectorAll(groupSelector)].filter(other =>
      [...other.classList].filter(name => name !== 'ws-choice-moving').sort().join('.') === kind).indexOf(group);
    return location.pathname + '|' + kind + '|' + (group.getAttribute('aria-label') || '') + '|' + index;
  }
  function paintIndicator(group, active) {
    const key = positionKey(group), cached = positions.get(key);
    const bounds = group.getBoundingClientRect(), selected = active.getBoundingClientRect();
    const pill = group.getAttribute('data-choice-kind') === 'segment';
    const inset = pill ? 0 : Math.min(12, selected.width / 4);
    const x = selected.left - bounds.left - group.clientLeft + group.scrollLeft + inset;
    const y = selected.top - bounds.top - group.clientTop + group.scrollTop + (pill ? 0 : selected.height - 3);
    const geometry = {transform:`translate(${x}px, ${y}px)`, width:(selected.width - inset * 2) + 'px', height:(pill ? selected.height : 3) + 'px'};
    let indicator = group.querySelector(':scope > .ws-choice-indicator');
    let initial = false;
    if (!indicator) {
      indicator = document.createElement('span');
      indicator.className = 'ws-choice-indicator';
      indicator.setAttribute('aria-hidden', 'true');
      if (cached) { Object.assign(indicator.style, cached.style); layouts.set(indicator,cached.box); indicator.dataset.ready = 'true'; }
      else initial = true;
      group.appendChild(indicator);
    }
    const width = selected.width-inset*2, height = pill ? selected.height : 3, previous = layouts.get(indicator);
    const changed = !previous || Math.abs(previous.x-x)>.25 || Math.abs(previous.y-y)>.25 || Math.abs(previous.width-width)>.25 || Math.abs(previous.height-height)>.25;
    if (!initial && changed) indicator.getBoundingClientRect();
    Object.assign(indicator.style, geometry);
    const box = {x,y,width,height}; layouts.set(indicator,box);
    if (initial) { indicator.getBoundingClientRect(); indicator.dataset.ready = 'true'; }
    if (pill && !initial && changed) {
      clearTimeout(timers.get(group));
      const reduce = window.matchMedia?.('(prefers-reduced-motion: reduce)').matches;
      group.classList.toggle('ws-choice-moving', !reduce);
      if (!reduce) timers.set(group, setTimeout(() => group.classList.remove('ws-choice-moving'), 200));
    }
    positions.set(key, {style:geometry,box});
    if (positions.size > 100) positions.delete(positions.keys().next().value);
  }
  function reveal(root) {
    const groups = [];
    if (root.matches?.(groupSelector)) groups.push(root);
    root.querySelectorAll?.(groupSelector).forEach(group => groups.push(group));
    groups.forEach(group => {
      if (!group.isConnected || !group.clientWidth) return;
      const active = [...group.children].find(child => child.matches(activeSelector));
      if (!active || !active.getClientRects().length) return;
      const bounds = group.getBoundingClientRect(), selected = active.getBoundingClientRect();
      const left = bounds.left + group.clientLeft + 4;
      const right = bounds.left + group.clientLeft + group.clientWidth - 4;
      const delta = selected.left < left ? selected.left - left : selected.right > right ? selected.right - right : 0;
      if (delta) group.scrollTo({ left: group.scrollLeft + delta, top: group.scrollTop, behavior: 'instant' });
      paintIndicator(group, active);
    });
  }
  function schedule(root) {
    pending.add(root || document);
    if (scheduled) return;
    scheduled = true;
    requestAnimationFrame(() => {
      scheduled = false;
      pending.forEach(reveal);
      pending.clear();
    });
  }
  function init() {
    schedule(document);
    if (typeof MutationObserver !== 'function') return;
    new MutationObserver(records => {
      records.forEach(record => {
        if (record.type === 'attributes') {
          const group = record.target.closest?.(groupSelector);
          if (group) schedule(group);
          else if (record.attributeName === 'hidden') schedule(record.target);
        } else {
          record.addedNodes.forEach(node => {
            if (node.nodeType === 1 && (node.matches(groupSelector) || node.querySelector(groupSelector))) schedule(node);
          });
        }
      });
    }).observe(document.body, { childList: true, subtree: true, attributes: true,
      attributeFilter: ['class','aria-current','aria-selected','aria-pressed','hidden'] });
  }
  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', init, { once: true });
  else init();
  window.addEventListener('worksystem:content-updated', event => schedule(event.detail?.root || document));
  window.addEventListener('resize', () => schedule(document));
})();

(() => {
  const groups = document.querySelectorAll('[data-nav-group]');
  groups.forEach((group) => {
    const key = `vf-nav-${group.dataset.navGroup}`;
    const stored = localStorage.getItem(key);
    if (group.classList.contains('active-group')) {
      group.open = true;
    } else if (stored !== null) {
      group.open = stored === 'open';
    }
    group.addEventListener('toggle', () => {
      localStorage.setItem(key, group.open ? 'open' : 'closed');
    });
  });
})();

// 좌측 사이드바 접기/펼치기 (데스크톱). 상태는 localStorage 에 저장한다.
(() => {
  const KEY = 'vf-nav-collapsed';
  const body = document.body;
  if (localStorage.getItem(KEY) === '1') {
    body.classList.add('nav-collapsed');
  }
  // 최초 렌더 후에 트랜지션을 켜서, 새로고침 시 펼침/접힘 애니메이션이 튀지 않게 한다.
  requestAnimationFrame(() => requestAnimationFrame(() => body.classList.add('nav-anim')));
  // 토글 버튼은 topbar fragment 에 있으므로 이벤트 위임으로 처리한다.
  document.addEventListener('click', (e) => {
    if (!e.target.closest('#navCollapseBtn')) return;
    const collapsed = body.classList.toggle('nav-collapsed');
    localStorage.setItem(KEY, collapsed ? '1' : '0');
  });
})();

// 레일(접힘) 모드: 그룹 아이콘 클릭 시 하위메뉴 플라이아웃. 하위가 1개면 즉시 이동.
(() => {
  const body = document.body;
  let flyout = null;
  let activeGroup = null;

  const isRail = () => body.classList.contains('nav-collapsed') && window.innerWidth >= 981;
  function close() {
    if (flyout) { flyout.remove(); flyout = null; }
    activeGroup = null;
  }

  document.addEventListener('click', (e) => {
    const summary = e.target.closest('.nav-group > summary');
    if (summary && isRail()) {
      e.preventDefault();               // details 기본 토글 방지
      const group = summary.parentElement;
      const links = [...group.querySelectorAll('.nav-children a')];
      const reopen = activeGroup === group;
      close();
      if (reopen) return;               // 같은 그룹 재클릭 → 닫기만
      if (links.length === 0) return;
      if (links.length === 1) {
        if (typeof window.worksystemSoftNavigate === 'function') window.worksystemSoftNavigate(links[0].href);
        else window.location.href = links[0].href;
        return;
      }

      flyout = document.createElement('div');
      flyout.className = 'nav-flyout';
      const labelEl = summary.querySelector('span');
      if (labelEl && labelEl.textContent.trim()) {
        const t = document.createElement('div');
        t.className = 'nav-flyout-title';
        t.textContent = labelEl.textContent.trim();
        flyout.appendChild(t);
      }
      links.forEach((a) => {
        const link = document.createElement('a');
        link.href = a.href;
        link.textContent = a.textContent.trim();
        if (a.classList.contains('active')) link.classList.add('active');
        flyout.appendChild(link);
      });
      document.body.appendChild(flyout);

      const r = summary.getBoundingClientRect();
      flyout.style.left = (r.right + 6) + 'px';
      flyout.style.top = r.top + 'px';
      const fr = flyout.getBoundingClientRect();
      if (fr.bottom > window.innerHeight - 8) {
        flyout.style.top = Math.max(8, window.innerHeight - fr.height - 8) + 'px';
      }
      activeGroup = group;
      return;
    }
    // 바깥/토글 버튼 클릭 → 닫기 (플라이아웃 내부 링크는 네비게이션이라 무관)
    if (flyout && !e.target.closest('.nav-flyout')) close();
  });

  document.addEventListener('keydown', (e) => { if (e.key === 'Escape') close(); });
  window.addEventListener('resize', close);
})();

// 모바일 사이드바 드로어(≤980): 햄버거는 인라인 onclick으로 body.nav-drawer-open 토글.
// 여기선 닫기 동작만 보강 — 메뉴 링크 클릭/ESC/데스크톱 복귀 시 닫는다.
(function () {
  const closeDrawer = () => document.body.classList.remove('nav-drawer-open');
  document.addEventListener('click', (e) => {
    // 모바일 서비스 버튼 아코디언 토글(service-nav가 preventDefault)은 이동이 아니므로 닫지 않음
    if (e.defaultPrevented) return;
    if (e.target.closest('.sidebar .nav a')) closeDrawer();      // 메뉴 이동 시 닫기
  });
  document.addEventListener('keydown', (e) => { if (e.key === 'Escape') closeDrawer(); });
  window.addEventListener('resize', () => { if (window.innerWidth > 980) closeDrawer(); });
})();

// 모바일 표 카드화: thead 헤더 텍스트를 각 tbody 셀의 data-label 로 복사한다.
// CSS(@media ≤640)가 이 라벨을 이용해 행을 카드("라벨: 값")로 렌더 → 가로 스크롤/잘림 제거.
// (콘텐츠 <main>은 사이드바 스크립트보다 뒤에 오므로 DOMContentLoaded 후 실행)
(function () {
  function initTableCards() {
    document.querySelectorAll('table.rt, table.doc-table').forEach(function (t) {
      if (t.classList.contains('no-mcard')) return;
      var ths = t.querySelectorAll('thead th');
      if (!ths.length) return;
      var labels = Array.prototype.map.call(ths, function (th) { return th.textContent.trim(); });
      t.querySelectorAll('tbody tr').forEach(function (tr) {
        if (tr.children.length === 1 && tr.children[0].hasAttribute('colspan')) return;
        Array.prototype.forEach.call(tr.children, function (td, i) {
          if (labels[i] && !td.hasAttribute('data-label')) td.setAttribute('data-label', labels[i]);
        });
      });
    });
  }
  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', initTableCards);
  else initTableCards();
})();
