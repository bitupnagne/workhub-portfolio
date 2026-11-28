/* 서비스 허브: 사이드바 서비스 바로가기 + 컨텍스트 메뉴.
 * 선택된 서비스의 하위 그룹은 해당 서비스 버튼 "바로 아래"에 펼쳐 붙는다(헤더 중복 제거 — 버튼이 그룹 헤더를 겸함).
 * 현재 페이지가 속한 서비스를 자동 감지해 항상 화면=서비스 일치. 선택은 localStorage(ws_service). */
(function () {
  'use strict';
  var KEY = 'ws_service';
  /* nav-group → 서비스. null = 공통(항상 표시, 이동 안 함) */
  var GROUP_SERVICE = {
    projects: 'projects',
    reports: 'reports',
    hr: 'hrself',
    documents: 'approval',
    organization: 'admin',
    communication: 'admin',
    'super-admin': 'admin',
    members: 'admin',
    account: null
  };
  var SERVICES = { all: 1, projects: 1, reports: 1, hrself: 1, approval: 1, admin: 1 };

  function current() {
    var v = null;
    try { v = localStorage.getItem(KEY); } catch (e) {}
    return SERVICES[v] ? v : 'all';
  }
  function save(v) {
    try { localStorage.setItem(KEY, v); } catch (e) {}
  }

  /* 현재 페이지가 속한 서비스 — active 그룹 기반(어떤 경로로 진입해도 메뉴가 화면을 따라감) */
  function detectPageService() {
    var found = null;
    document.querySelectorAll('.sidebar .nav-group.active-group[data-nav-group]').forEach(function (g) {
      var gs = GROUP_SERVICE[g.getAttribute('data-nav-group')];
      if (gs) found = gs;
    });
    return found;
  }

  /* 대시보드(허브)는 어떤 서비스에도 속하지 않음 — 마지막 컨텍스트를 하이라이트하지 않는다 */
  function onDashboard() {
    return location.pathname === '/org' || location.pathname === '/dashboard' || location.pathname === '/';
  }

  function apply() {
    hidePopout(); /* 이전 페이지의 플로팅 메뉴를 남기지 않는다. */
    var home = location.pathname === '/org';
    var pageService = home ? null : detectPageService();
    if (pageService) save(pageService); /* 화면 = 서비스 항상 일치 */
    var service = home ? 'all' : (pageService || current());
    var nav = document.querySelector('.sidebar .nav');
    if (!nav) return;
    var label = nav.querySelector('.nav-label');

    document.querySelectorAll('.sidebar .nav-group[data-nav-group]').forEach(function (g) {
      var gs = GROUP_SERVICE[g.getAttribute('data-nav-group')];
      var common = gs === null || gs === undefined;
      var inService = service !== 'all' && gs === service;
      g.style.display = ((!home && service === 'all') || common || inService) ? '' : 'none';
      g.classList.toggle('in-svc', inService);
      if (home) g.classList.remove('m-open');
      if (inService) g.setAttribute('open', ''); /* 헤더 없이 항상 펼침 */
    });

    document.querySelectorAll('.sidebar .nav-services .nav-svc[data-set-service]').forEach(function (a) {
      a.classList.toggle('svc-open', !home && isMobileNav() && service !== 'all'
        && a.getAttribute('data-set-service') === service);
    });

    /* 선택 서비스의 그룹들을 해당 서비스 버튼 바로 아래로 이동(버튼 = 그룹 헤더) */
    if (service !== 'all') {
      var anchor = document.querySelector('.sidebar .nav-services .nav-svc[data-set-service="' + service + '"]');
      if (anchor) {
        var insertAfter = anchor;
        document.querySelectorAll('.sidebar .nav-group[data-nav-group]').forEach(function (g) {
          if (GROUP_SERVICE[g.getAttribute('data-nav-group')] === service) {
            insertAfter.parentNode.insertBefore(g, insertAfter.nextSibling);
            insertAfter = g;
          }
        });
        /* 모바일 아코디언: 현재 서비스는 펼침 상태로 표기(탭하면 접힘) */
        if (isMobileNav() && !onDashboard()) anchor.classList.add('svc-open');
      }
    }
    if (label) label.style.display = !home && service === 'all' ? '' : 'none';
    highlightCurrent(service);
  }

  /* 현재 컨텍스트 서비스의 바로가기를 선택 상태로 표시(대시보드에서는 표시하지 않음) */
  function highlightCurrent(service) {
    var dash = onDashboard();
    document.querySelectorAll('.sidebar .nav-services .nav-svc[data-set-service]').forEach(function (a) {
      a.classList.toggle('active', !dash && service !== 'all' && a.getAttribute('data-set-service') === service);
    });
  }

  /* ───── 모바일(호버 불가) 아코디언: 서비스 버튼 탭 = 이동 대신 하위메뉴 접기/펼치기 ───── */
  function isMobileNav() {
    return window.matchMedia('(max-width: 980px), (hover: none)').matches;
  }
  function mCollapse(btn) {
    var svc = btn.getAttribute('data-set-service');
    btn.classList.remove('svc-open');
    document.querySelectorAll('.sidebar .nav-group[data-nav-group]').forEach(function (g) {
      if (GROUP_SERVICE[g.getAttribute('data-nav-group')] !== svc) return;
      g.classList.remove('m-open', 'in-svc');
      g.style.display = 'none';
    });
  }
  function mExpand(btn) {
    var svc = btn.getAttribute('data-set-service');
    var after = btn;
    document.querySelectorAll('.sidebar .nav-group[data-nav-group]').forEach(function (g) {
      if (GROUP_SERVICE[g.getAttribute('data-nav-group')] !== svc) return;
      btn.parentNode.insertBefore(g, after.nextSibling);
      after = g;
      g.style.display = '';
      g.classList.add('in-svc', 'm-open');
      g.setAttribute('open', '');
    });
    btn.classList.add('svc-open');
  }
  function mToggle(btn) {
    var wasOpen = btn.classList.contains('svc-open');
    document.querySelectorAll('.sidebar .nav-svc.svc-open').forEach(mCollapse);
    if (!wasOpen) mExpand(btn);
  }

  /* 대시보드 서비스 카드/사이드바 버튼: 클릭 시 서비스 설정 후 이동
     (모바일 사이드바 버튼은 이동 대신 아코디언 토글) */
  document.addEventListener('click', function (e) {
    var card = e.target.closest('[data-set-service]');
    if (!card) return;
    if (card.classList.contains('nav-svc') && isMobileNav()) {
      e.preventDefault();
      mToggle(card);
      return;
    }
    save(card.getAttribute('data-set-service'));
  }, true);

  /* ───── 호버 팝아웃: 비활성 서비스 버튼에 마우스를 올리면 하위메뉴가
     우측 플로팅 패널로 펼쳐진다(현재 화면의 서비스는 인라인 펼침 그대로).
     접힌 사이드바(아이콘만)에서는 현재 서비스 버튼도 팝아웃을 보여준다. ───── */
  var popout = null, popoutSvc = null, hideTimer = null;

  function canPopout() {
    return window.matchMedia('(min-width: 981px) and (hover: hover)').matches;
  }
  function groupsOf(service) {
    var out = [];
    document.querySelectorAll('.sidebar .nav-group[data-nav-group]').forEach(function (g) {
      if (GROUP_SERVICE[g.getAttribute('data-nav-group')] === service) out.push(g);
    });
    return out;
  }
  function hidePopout() {
    clearTimeout(hideTimer);
    if (popout) { popout.remove(); popout = null; popoutSvc = null; }
  }
  function scheduleHide() {
    clearTimeout(hideTimer);
    hideTimer = setTimeout(hidePopout, 180);
  }
  function showPopout(btn, service) {
    if (popoutSvc === service) { clearTimeout(hideTimer); return; }
    hidePopout();
    var groups = groupsOf(service);
    if (!groups.length) return;
    var panel = document.createElement('div');
    panel.className = 'nav-popout';
    var hasLink = false;
    groups.forEach(function (g) {
      if (!g.querySelector('.nav-children a')) return;
      var children = g.querySelectorAll('.nav-children > a, .nav-children > .nav-subhead');
      var sec = document.createElement('div');
      sec.className = 'nav-popout-group';
      var sum = g.querySelector('summary > span');
      if (sum) {
        var t = document.createElement('div');
        t.className = 'nav-popout-title';
        t.innerHTML = sum.innerHTML;
        sec.appendChild(t);
      }
      children.forEach(function (el) { sec.appendChild(el.cloneNode(true)); if (el.tagName === 'A') hasLink = true; });
      panel.appendChild(sec);
    });
    if (!hasLink) return;
    panel.addEventListener('mouseenter', function () { clearTimeout(hideTimer); });
    panel.addEventListener('mouseleave', scheduleHide);
    document.body.appendChild(panel);
    var r = btn.getBoundingClientRect();
    panel.style.left = (r.right + 10) + 'px';
    var top = r.top - 4;
    var maxTop = window.innerHeight - panel.offsetHeight - 10;
    if (top > maxTop) top = Math.max(8, maxTop);
    panel.style.top = top + 'px';
    popout = panel;
    popoutSvc = service;
  }

  document.addEventListener('mouseover', function (e) {
    if (!canPopout()) return;
    var btn = e.target.closest('.sidebar .nav-svc[data-set-service]');
    if (btn) {
      clearTimeout(hideTimer);
      var svc = btn.getAttribute('data-set-service');
      var collapsed = document.body.classList.contains('nav-collapsed');
      // 현재 화면의 서비스(인라인 펼침 중)는 팝아웃 생략 — 접힘/대시보드에선 항상 팝아웃
      if (!collapsed && !onDashboard() && svc === current()) { hidePopout(); return; }
      showPopout(btn, svc);
      return;
    }
    if (popout) {
      if (popout.contains(e.target)) { clearTimeout(hideTimer); return; }
      scheduleHide();
    }
  });
  document.addEventListener('click', function (e) {
    if (popout && !popout.contains(e.target)) hidePopout();
  });
  window.addEventListener('scroll', function (e) {
    if (popout && !(e.target instanceof Node && popout.contains(e.target))) hidePopout();
  }, true);

  window.addEventListener('worksystem:content-updated', apply);
  window.matchMedia('(max-width: 980px), (hover: none)').addEventListener?.('change', apply);

  /* [무점프] 이 스크립트는 사이드바 마크업 뒤에서 '동기'로 로드된다(defer 아님) —
   * nav 파싱 직후·첫 페인트 전에 재배치가 끝나 메뉴가 튀어 보이지 않는다.
   * 외부 스크립트 fetch 동안 첫 페인트가 일어나도 .nav-init CSS 클로크가 그룹을 가려두므로
   * '구UI 좌르륵 펼침'이 보이지 않는다. 재배치가 끝나면(또는 예외 시에도) 클로크를 해제한다. */
  function releaseCloak() {
    document.querySelectorAll('.sidebar .nav.nav-init').forEach(function (n) { n.classList.remove('nav-init'); });
  }
  try {
    apply();
  } finally {
    releaseCloak();
  }
})();
