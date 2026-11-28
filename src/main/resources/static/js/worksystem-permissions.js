/*
 * worksystem-permissions.js — 관리자 권한 관리(/admin/permissions)
 *
 * 메뉴 한 줄 = 아코디언. 펼치면 지금 권한을 가진 사람·조직장 자리가 칩으로 보이고, '권한 주기'로 모달을 연다.
 * 모달 위쪽은 이미 받은 칩(× 회수), 아래는 조직장 자리 | 직원 두 칸 검색 목록. 항목을 누르면 확인 후 부여한다.
 *
 * 화면 전환(soft navigation) 뒤에도 동작하도록 요소는 전부 '누른 시점'에 찾는다(로드 시 캐싱 금지).
 */
(function () {
  'use strict';
  if (window.__wsPermissionsBound) return;
  window.__wsPermissionsBound = true;

  var openMenuKey = null;

  function page() { return document.querySelector('[data-perm-page]'); }
  function modal() { return document.querySelector('[data-perm-modal]'); }
  function menuEl(key) {
    return document.querySelector('.perm-menu[data-perm-menu="' + (window.CSS && CSS.escape ? CSS.escape(key) : key) + '"]');
  }

  function csrf() {
    var input = document.querySelector('[data-perm-csrf]');
    return input ? { name: input.name, value: input.value } : null;
  }

  function confirmBox(message, okText) {
    if (window.vfConfirm) return window.vfConfirm(message, { title: '관리자 권한', okText: okText || '확인' });
    return Promise.resolve(window.confirm(message));
  }

  function alertBox(message) {
    if (window.vfAlert) return window.vfAlert(message, { title: '관리자 권한' });
    window.alert(message);
    return Promise.resolve();
  }

  function post(url, params) {
    var body = new URLSearchParams(params);
    var token = csrf();
    if (token) body.append(token.name, token.value);
    return fetch(url, {
      method: 'POST',
      credentials: 'same-origin',
      headers: { 'Content-Type': 'application/x-www-form-urlencoded', 'X-Requested-With': 'fetch', Accept: 'application/json' },
      body: body.toString()
    }).then(function (response) {
      return response.json().catch(function () { return { ok: false, message: '요청을 처리하지 못했습니다.' }; });
    });
  }

  /* ── 칩 ── */
  function chip(grant, removable) {
    var el = document.createElement('span');
    el.className = 'perm-chip' + (grant.type === 'TEAM_LEADER' ? ' is-seat' : '');
    el.setAttribute('data-grant-id', grant.id);
    el.setAttribute('data-type', grant.type);
    el.setAttribute('data-target-id', grant.targetId);
    var b = document.createElement('b');
    b.textContent = grant.label;
    var small = document.createElement('small');
    small.textContent = grant.detail || '';
    el.appendChild(b);
    el.appendChild(small);
    if (removable) {
      var x = document.createElement('button');
      x.type = 'button';
      x.className = 'perm-chip-x';
      x.setAttribute('data-perm-revoke', grant.id);
      x.setAttribute('aria-label', grant.label + ' 권한 회수');
      x.innerHTML = wsIcon('close');
      el.appendChild(x);
    }
    return el;
  }

  function grantsOf(menu) {
    return Array.prototype.map.call(menu.querySelectorAll('[data-perm-chips] .perm-chip'), function (el) {
      return {
        id: el.getAttribute('data-grant-id'),
        type: el.getAttribute('data-type'),
        targetId: el.getAttribute('data-target-id'),
        label: el.querySelector('b') ? el.querySelector('b').textContent : '',
        detail: el.querySelector('small') ? el.querySelector('small').textContent : ''
      };
    });
  }

  function renderMenuChips(menu, grants) {
    var box = menu.querySelector('[data-perm-chips]');
    box.innerHTML = '';
    if (!grants.length) {
      var empty = document.createElement('span');
      empty.className = 'perm-empty';
      empty.textContent = '아직 권한을 받은 사람이 없습니다.';
      box.appendChild(empty);
    }
    grants.forEach(function (g) { box.appendChild(chip(g, false)); });
    var count = menu.querySelector('[data-perm-count]');
    if (count) count.textContent = grants.length + '건';
  }

  function renderModal() {
    var m = modal();
    var menu = openMenuKey && menuEl(openMenuKey);
    if (!m || !menu) return;
    var grants = grantsOf(menu);
    var box = m.querySelector('[data-perm-modal-chips]');
    box.innerHTML = '';
    if (!grants.length) {
      var empty = document.createElement('span');
      empty.className = 'perm-empty';
      empty.textContent = '아직 없습니다. 아래에서 조직장 자리나 직원을 고르세요.';
      box.appendChild(empty);
    }
    grants.forEach(function (g) { box.appendChild(chip(g, true)); });
    // 이미 받은 대상은 목록에서 '부여됨'으로 잠근다.
    var taken = {};
    grants.forEach(function (g) { taken[g.type + ':' + g.targetId] = true; });
    m.querySelectorAll('[data-perm-pick]').forEach(function (btn) {
      var key = btn.getAttribute('data-perm-pick') + ':' + btn.getAttribute('data-target-id');
      var done = !!taken[key];
      btn.disabled = done;
      btn.classList.toggle('is-granted', done);
    });
  }

  function openModal(key) {
    var m = modal();
    var menu = menuEl(key);
    if (!m || !menu) return;
    openMenuKey = key;
    m.querySelector('[data-perm-modal-title]').textContent = '\'' + menu.getAttribute('data-perm-label') + '\' 권한 주기';
    m.querySelectorAll('[data-perm-search]').forEach(function (input) { input.value = ''; filter(input); });
    renderModal();
    m.hidden = false;
    document.body.classList.add('perm-modal-open');
    var first = m.querySelector('[data-perm-search="seat"]');
    if (first) first.focus();
  }

  function closeModal() {
    var m = modal();
    if (m) m.hidden = true;
    document.body.classList.remove('perm-modal-open');
    var menu = openMenuKey && menuEl(openMenuKey);
    openMenuKey = null;
    var opener = menu && menu.querySelector('[data-perm-open]');
    if (opener) opener.focus();
  }

  function filter(input) {
    var kind = input.getAttribute('data-perm-search');
    var list = modal() && modal().querySelector('[data-perm-options="' + kind + '"]');
    if (!list) return;
    var q = input.value.trim().toLowerCase();
    list.querySelectorAll('li').forEach(function (li) {
      var btn = li.querySelector('[data-search]');
      var text = (btn ? btn.getAttribute('data-search') : li.textContent).toLowerCase();
      li.hidden = q !== '' && text.indexOf(q) < 0;
    });
  }

  document.addEventListener('click', function (event) {
    if (!page()) return;
    var opener = event.target.closest('[data-perm-open]');
    if (opener) { openModal(opener.closest('.perm-menu').getAttribute('data-perm-menu')); return; }

    if (event.target.closest('[data-perm-close]') || event.target.matches('[data-perm-modal]')) { closeModal(); return; }

    var pick = event.target.closest('[data-perm-pick]');
    if (pick && !pick.disabled && openMenuKey) {
      var menu = menuEl(openMenuKey);
      var label = pick.getAttribute('data-label');
      var menuLabel = menu.getAttribute('data-perm-label');
      confirmBox('\'' + label + '\'에게 \'' + menuLabel + '\' 권한을 부여하시겠습니까?', '부여').then(function (ok) {
        if (!ok) return;
        post('/admin/permissions/grant', {
          menuKey: openMenuKey, granteeType: pick.getAttribute('data-perm-pick'), targetId: pick.getAttribute('data-target-id')
        }).then(function (result) {
          if (!result.ok) { alertBox(result.message || '권한을 주지 못했습니다.'); return; }
          var grants = grantsOf(menu);
          grants.push(result.grant);
          renderMenuChips(menu, grants);
          renderModal();
          if (window.wsToast) window.wsToast(label + '에게 권한을 부여했습니다.');
        });
      });
      return;
    }

    var revoke = event.target.closest('[data-perm-revoke]');
    if (revoke && openMenuKey) {
      var owner = menuEl(openMenuKey);
      var grantId = revoke.getAttribute('data-perm-revoke');
      var name = revoke.parentElement.querySelector('b').textContent;
      confirmBox('\'' + name + '\'의 \'' + owner.getAttribute('data-perm-label') + '\' 권한을 회수하시겠습니까?', '회수').then(function (ok) {
        if (!ok) return;
        post('/admin/permissions/revoke', { grantId: grantId }).then(function (result) {
          if (!result.ok) { alertBox(result.message || '권한을 회수하지 못했습니다.'); return; }
          renderMenuChips(owner, grantsOf(owner).filter(function (g) { return String(g.id) !== String(grantId); }));
          renderModal();
        });
      });
    }
  });

  document.addEventListener('keydown', function (event) {
    if (!page()) return;
    if (event.key === 'Escape' && modal() && !modal().hidden) closeModal();
  });

  document.addEventListener('input', function (event) {
    if (event.target.matches && event.target.matches('[data-perm-search]')) filter(event.target);
  });
})();
