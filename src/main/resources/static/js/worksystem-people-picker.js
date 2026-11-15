/*
 * worksystem-people-picker.js — 복수 사람 선택 공통 모달
 *
 * 화면마다 제각각이던 "여러 명 고르기"(체크박스 그리드, Ctrl+클릭 등)를 하나의 모달로 통일한다.
 * 단일 선택은 검색형 드롭다운(worksystem-search-select.js)이 담당하고, 이 모달은 복수 선택 전용이다.
 *
 * 쓰는 법:
 *   window.PeoplePicker.open({
 *     people:  [{ id, name, teamName, teamId }],   // 후보 (필수)
 *     teams:   [{ id, name, parentId }],            // 조직 계층(평면) — 있으면 '조직' 탭이 생긴다
 *     tree:    [{ teamId, name, children, members:[{id|userId}] }], // 이미 중첩된 트리를 직접 줄 수도 있다
 *                                                   //   (teams 대신. 채팅 초대의 orgTree 처럼.)
 *     selected: [1, 2, 3],                          // 초기 선택 id
 *     multi:   true,                                // 기본 true. false면 단일(참고용)
 *     title:   '받는 사람 선택',
 *     confirmLabel: '선택 완료',
 *     onConfirm: function(ids){ ... }               // 선택 확정 시 id 배열
 *   });
 *
 * 조직 탭은 '직원 탭과 같은 선택 상태(selectedIds)'를 그대로 비춘다 — 한 조직을 누르면 그 조직(하위 포함)의
 * 사람을 모두 선택/해제하고, 일부만 선택된 조직은 – 로 표시한다. 두 탭이 어긋날 여지를 없앤다.
 */
(function () {
  'use strict';

  var CSS = ''
    + '.pp-backdrop{position:fixed;inset:0;z-index:9400;background:rgba(15,23,42,.42);display:flex;align-items:center;justify-content:center;padding:20px}'
    + '.pp-card{width:min(560px,96vw);max-height:88vh;display:flex;flex-direction:column;background:var(--surface,#fff);border:1px solid var(--border,#e5e7eb);border-radius:12px;box-shadow:var(--elev-3,0 24px 60px rgba(0,0,0,.28));overflow:hidden}'
    + '.pp-head{display:flex;align-items:center;justify-content:space-between;gap:12px;padding:14px 16px;border-bottom:1px solid var(--border,#e5e7eb)}'
    + '.pp-head strong{font-size:15px;font-weight:900}'
    + '.pp-x{width:30px;height:30px;border:0;border-radius:8px;background:var(--surface-2,#f1f5f9);color:var(--text-3,#64748b);font-size:20px;line-height:1;cursor:pointer}'
    + '.pp-x:hover{background:#fee2e2;color:#dc2626}'
    + '.pp-tabs{display:flex;gap:6px;padding:10px 16px 0}'
    + '.pp-tab{flex:0 0 auto;padding:7px 14px;border:1px solid var(--border,#e5e7eb);border-radius:8px;background:var(--surface,#fff);color:var(--text-2,#334155);font-size:13px;font-weight:700;cursor:pointer}'
    + '.pp-tab.active{border-color:var(--brand,#4F46E5);color:var(--brand-dark,#4338CA);background:var(--brand-light,#EEF2FF)}'
    + '.pp-chiprow{display:flex;flex-wrap:wrap;gap:6px;min-height:34px;padding:8px 16px;border-bottom:1px solid var(--border,#e5e7eb)}'
    + '.pp-chip{display:inline-flex;align-items:center;gap:5px;padding:3px 6px 3px 10px;border-radius:14px;background:var(--brand-light,#EEF2FF);color:var(--brand-dark,#4338CA);font-size:12px;font-weight:700}'
    + '.pp-chip button{border:0;background:rgba(0,0,0,.12);color:inherit;cursor:pointer;width:15px;height:15px;border-radius:50%;line-height:1;font-size:11px;display:inline-flex;align-items:center;justify-content:center;padding:0}'
    + '.pp-chip button:hover{background:rgba(0,0,0,.28)}'
    + '.pp-chip-empty{align-self:center;color:var(--text-3,#94a3b8);font-size:12px}'
    + '.pp-search{margin:10px 16px 0}'
    + '.pp-search input{width:100%;box-sizing:border-box;min-height:34px;border:1px solid var(--border-2,#cbd5e1);border-radius:8px;padding:7px 10px;font-size:13px}'
    + '.pp-body{flex:1;overflow:auto;padding:8px 12px 12px;min-height:180px}'
    + '.pp-row{display:flex;align-items:center;gap:9px;width:100%;padding:7px 10px;border:0;border-radius:8px;background:transparent;cursor:pointer;text-align:left;font:inherit}'
    + '.pp-row:hover{background:var(--surface-2,#f1f5f9)}'
    + '.pp-row.sel{background:var(--brand-light,#EEF2FF)}'
    + '.pp-row input{margin:0;accent-color:var(--brand,#4F46E5);pointer-events:none}'
    + '.pp-row b{font-weight:700;font-size:13px;color:var(--text,#111827)}'
    + '.pp-row em{font-style:normal;font-size:11px;color:var(--text-3,#6b7280);margin-left:6px}'
    + '.pp-empty{padding:24px;text-align:center;color:var(--text-3,#94a3b8);font-size:13px}'
    + '.pp-org{display:flex;align-items:center;gap:8px;width:100%;padding:7px 10px;border:0;border-radius:8px;background:transparent;cursor:pointer;text-align:left;font:inherit}'
    + '.pp-org:hover{background:var(--surface-2,#f1f5f9)}'
    + '.pp-org-check{flex:0 0 auto;width:18px;height:18px;border:1.5px solid var(--border-2,#cbd5e1);border-radius:5px;display:inline-flex;align-items:center;justify-content:center;font-size:12px;color:#fff;line-height:1}'
    + '.pp-org.on .pp-org-check{background:var(--brand,#4F46E5);border-color:var(--brand,#4F46E5)}'
    + '.pp-org.part .pp-org-check{background:var(--brand-muted,#A5B4FC);border-color:var(--brand-muted,#A5B4FC)}'
    + '.pp-org b{font-weight:700;font-size:13px;color:var(--text,#111827)}'
    + '.pp-org em{font-style:normal;font-size:11px;color:var(--text-3,#6b7280);margin-left:6px}'
    + '.pp-foot{display:flex;align-items:center;justify-content:space-between;gap:8px;padding:12px 16px;border-top:1px solid var(--border,#e5e7eb)}'
    + '.pp-count{display:inline-flex;align-items:center;justify-content:center;border-radius:999px;padding:1px 7px;font-size:12px;line-height:1.6;background:var(--brand,#4F46E5);color:#fff;font-weight:600;white-space:nowrap}'
    + '.pp-foot-btns{display:flex;gap:8px}';

  function injectCss() {
    if (document.getElementById('pp-css')) return;
    var s = document.createElement('style');
    s.id = 'pp-css';
    s.textContent = CSS;
    document.head.appendChild(s);
  }

  function esc(v) {
    return String(v == null ? '' : v).replace(/[&<>"]/g, function (c) {
      return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c];
    });
  }

  function open(opts) {
    injectCss();
    opts = opts || {};
    var multi = opts.multi !== false;
    var people = (opts.people || []).map(function (p) {
      return { id: Number(p.id != null ? p.id : p.userId), name: p.name || '', teamName: p.teamName || '', teamId: Number(p.teamId || 0) };
    }).filter(function (p) { return p.id; });
    var byId = {};
    people.forEach(function (p) { byId[p.id] = p; });

    var selected = new Set((opts.selected || []).map(Number).filter(Boolean));

    // 조직 트리: (a) 이미 중첩된 tree 를 직접 받거나, (b) 평면 teams 를 parentId 로 중첩한다.
    var tree = null;
    var candSet = {};
    people.forEach(function (p) { candSet[p.id] = true; });
    if (opts.tree && opts.tree.length) {
      // (a) 중첩 트리 정규화 — 노드의 members(초대 후보만 남긴다) 를 people 로.
      var norm = function (node) {
        var mine = (node.members || []).map(function (m) { return Number(m.id != null ? m.id : m.userId); })
          .filter(function (id) { return id && candSet[id]; });
        var kids = (node.children || []).map(norm);
        return { teamId: Number(node.teamId || node.id || 0), name: node.name || '', children: kids, people: mine };
      };
      tree = opts.tree.map(norm);
    } else if (opts.teams && opts.teams.length) {
      // (b) 평면 teams → 중첩
      var nodeById = {};
      opts.teams.forEach(function (t) {
        nodeById[Number(t.id)] = { teamId: Number(t.id), name: t.name || '', parentId: t.parentId == null ? null : Number(t.parentId), children: [], people: [] };
      });
      people.forEach(function (p) { if (nodeById[p.teamId]) nodeById[p.teamId].people.push(p.id); });
      var roots = [];
      Object.keys(nodeById).forEach(function (k) {
        var n = nodeById[k];
        if (n.parentId != null && nodeById[n.parentId]) nodeById[n.parentId].children.push(n);
        else roots.push(n);
      });
      tree = roots;
    }
    if (tree) {
      // 하위(자손 포함) 후보 id 집계 — 3상태/캐스케이드 판정용.
      var subtreeIds = function (n) {
        var ids = n.people.slice();
        n.children.forEach(function (c) { ids = ids.concat(subtreeIds(c)); });
        n._all = ids;
        return ids;
      };
      tree.forEach(subtreeIds);
    }

    var backdrop = document.createElement('div');
    backdrop.className = 'pp-backdrop';
    var state = { tab: 'employees' };

    function subtreeSelected(node) {
      var all = node._all || [];
      var on = 0;
      all.forEach(function (id) { if (selected.has(id)) on++; });
      return { total: all.length, on: on };
    }

    function chipsHtml() {
      var ids = [...selected];
      if (!ids.length) return '<span class="pp-chip-empty">아래에서 선택하세요</span>';
      return ids.map(function (id) {
        var p = byId[id];
        if (!p) return '';
        return '<span class="pp-chip"><span>' + esc(p.name) + '</span><button type="button" data-pp-unpick="' + id + '" aria-label="제외">×</button></span>';
      }).join('');
    }

    function employeeListHtml(q) {
      var rows = people.filter(function (p) {
        if (!q) return true;
        return (p.name + ' ' + p.teamName).toLowerCase().indexOf(q) >= 0;
      }).map(function (p) {
        var on = selected.has(p.id);
        return '<button type="button" class="pp-row' + (on ? ' sel' : '') + '" data-pp-pick="' + p.id + '">'
          + '<input type="checkbox" ' + (on ? 'checked' : '') + ' tabindex="-1">'
          + '<span><b>' + esc(p.name) + '</b><em>' + esc(p.teamName || '미지정') + '</em></span></button>';
      }).join('');
      return rows || '<div class="pp-empty">해당하는 직원이 없습니다</div>';
    }

    function orgListHtml() {
      var out = '';
      var walk = function (node, depth) {
        var s = subtreeSelected(node);
        if (s.total === 0) { (node.children || []).forEach(function (c) { walk(c, depth); }); return; }
        var cls = s.on === s.total ? ' on' : (s.on > 0 ? ' part' : '');
        var mark = s.on === s.total ? '✓' : (s.on > 0 ? '–' : '');
        out += '<button type="button" class="pp-org' + cls + '" data-pp-org="' + node.teamId + '" style="padding-left:' + (10 + depth * 16) + 'px">'
          + '<span class="pp-org-check">' + mark + '</span>'
          + '<span><b>' + esc(node.name) + '</b><em>' + s.on + '/' + s.total + '명</em></span></button>';
        (node.children || []).forEach(function (c) { walk(c, depth + 1); });
      };
      (tree || []).forEach(function (n) { walk(n, 0); });
      return out || '<div class="pp-empty">선택할 조직이 없습니다</div>';
    }

    function render(focusTab) {
      var tabs = backdrop.querySelector('.pp-tabs');
      var q = (backdrop.querySelector('[data-pp-search]') || {}).value;
      q = q ? q.trim().toLowerCase() : '';
      backdrop.innerHTML = ''
        + '<div class="pp-card" role="dialog" aria-modal="true">'
        + '  <div class="pp-head"><strong>' + esc(opts.title || '사람 선택') + '</strong><button type="button" class="pp-x" data-pp-close aria-label="닫기">×</button></div>'
        + (tree ? '<div class="pp-tabs ws-segmented" role="group" aria-label="선택 대상 종류"><button type="button" class="pp-tab' + (state.tab === 'employees' ? ' active' : '') + '" data-pp-tab="employees" aria-pressed="' + (state.tab === 'employees') + '">직원</button>'
                + '<button type="button" class="pp-tab' + (state.tab === 'orgs' ? ' active' : '') + '" data-pp-tab="orgs" aria-pressed="' + (state.tab === 'orgs') + '">조직</button></div>' : '')
        + '  <div class="pp-chiprow">' + chipsHtml() + '</div>'
        + (state.tab === 'employees' ? '<div class="pp-search"><input type="search" data-pp-search placeholder="이름/팀 검색" autocomplete="off" value="' + esc(q) + '"></div>' : '')
        + '  <div class="pp-body">' + (state.tab === 'employees' ? employeeListHtml(q) : orgListHtml()) + '</div>'
        + '  <div class="pp-foot"><span class="pp-count">' + selected.size + '명 선택</span>'
        + '    <div class="pp-foot-btns"><button type="button" class="rbtn" data-pp-close>취소</button>'
        + '    <button type="button" class="btn primary" data-pp-confirm>' + esc(opts.confirmLabel || '선택 완료') + '</button></div></div>'
        + '</div>';
      // Keep the navigation and its animated indicator while refreshing the selected list.
      var replacementTabs = backdrop.querySelector('.pp-tabs');
      if (tabs && replacementTabs) {
        tabs.querySelectorAll('[data-pp-tab]').forEach(function (button) {
          var on = button.getAttribute('data-pp-tab') === state.tab;
          button.classList.toggle('active', on);
          button.setAttribute('aria-pressed', String(on));
        });
        replacementTabs.replaceWith(tabs);
      }
      var selectedTab = focusTab && backdrop.querySelector('[data-pp-tab="' + state.tab + '"]');
      var s = backdrop.querySelector('[data-pp-search]');
      if (selectedTab) selectedTab.focus({ preventScroll: true });
      else if (s) { s.focus(); s.setSelectionRange(s.value.length, s.value.length); }
    }

    function pick(id) {
      id = Number(id);
      if (!multi) { selected.clear(); selected.add(id); return; }
      if (selected.has(id)) selected.delete(id); else selected.add(id);
    }

    function toggleOrg(teamId) {
      var find = function (nodes) {
        for (var i = 0; i < nodes.length; i++) {
          if (nodes[i].teamId === teamId) return nodes[i];
          var f = find(nodes[i].children || []); if (f) return f;
        }
        return null;
      };
      var node = find(tree || []);
      if (!node) return;
      var all = node._all || [];
      var s = subtreeSelected(node);
      if (s.on === s.total) all.forEach(function (id) { selected.delete(id); });
      else all.forEach(function (id) { selected.add(id); });
    }

    function close() {
      document.removeEventListener('keydown', onKey);
      backdrop.remove();
    }
    function onKey(e) { if (e.key === 'Escape') close(); }

    backdrop.addEventListener('click', function (e) {
      var t = e.target;
      if (t === backdrop) { close(); return; }
      var el;
      if ((el = t.closest('[data-pp-close]'))) { close(); return; }
      if ((el = t.closest('[data-pp-confirm]'))) { close(); if (opts.onConfirm) opts.onConfirm([...selected]); return; }
      if ((el = t.closest('[data-pp-tab]'))) { state.tab = el.getAttribute('data-pp-tab'); render(true); return; }
      if ((el = t.closest('[data-pp-unpick]'))) { selected.delete(Number(el.getAttribute('data-pp-unpick'))); render(); return; }
      if ((el = t.closest('[data-pp-pick]'))) { pick(el.getAttribute('data-pp-pick')); render(); return; }
      if ((el = t.closest('[data-pp-org]'))) { toggleOrg(Number(el.getAttribute('data-pp-org'))); render(); return; }
    });
    backdrop.addEventListener('input', function (e) {
      if (e.target.matches('[data-pp-search]')) {
        var body = backdrop.querySelector('.pp-body');
        var q = e.target.value.trim().toLowerCase();
        if (body) body.innerHTML = employeeListHtml(q);
      }
    });

    document.addEventListener('keydown', onKey);
    document.body.appendChild(backdrop);
    render();
  }

  window.PeoplePicker = { open: open };
})();
