(() => {
  'use strict';
  if (window.__worksystemProjectsInstalled) return;
  window.__worksystemProjectsInstalled = true;
  const viewKey = 'workhub.project.task-view';
  const normalize = value => String(value || '').trim().toLocaleLowerCase('ko-KR');

  function setView(root, view, persist = false) {
    const safeView = view === 'list' ? 'list' : 'board';
    root.querySelectorAll('[data-task-view-panel]').forEach(panel => {
      panel.hidden = panel.dataset.taskViewPanel !== safeView;
    });
    root.querySelectorAll('[data-task-view]').forEach(button => {
      const selected = button.dataset.taskView === safeView;
      button.classList.toggle('active', selected);
      button.setAttribute('aria-pressed', String(selected));
    });
    if (persist) {
      try { localStorage.setItem(viewKey, safeView); } catch (_) { /* 저장 제한 시 현재 화면만 전환한다. */ }
    }
  }

  function syncProgress(form, changed = false) {
    const status = form.querySelector('[data-task-status]');
    const progress = form.querySelector('[data-task-progress]');
    if (!status || !progress) return;
    const fixed = status.value === 'TODO' || status.value === 'DONE';
    if (fixed) progress.value = status.value === 'DONE' ? '100' : '0';
    else if (changed && Number(progress.value) > 99) progress.value = '99';
    progress.max = fixed ? '100' : '99';
    progress.readOnly = fixed;
    progress.setAttribute('aria-readonly', String(fixed));
  }

  function filterProjects(root, selected) {
    const cards = [...root.querySelectorAll('[data-project-status]')];
    let count = 0;
    cards.forEach(card => {
      card.hidden = selected !== 'ALL' && card.dataset.projectStatus !== selected;
      if (!card.hidden) count++;
    });
    root.querySelectorAll('[data-project-filter]').forEach(button => {
      const active = button.dataset.projectFilter === selected;
      button.classList.toggle('active', active);
      button.setAttribute('aria-pressed', String(active));
    });
    const empty = root.querySelector('[data-project-filter-empty]');
    if (empty) empty.hidden = !cards.length || count > 0;
  }

  function filterTasks(root, value) {
    const query = normalize(value);
    const rows = [...root.querySelectorAll('[data-task-search-text]')];
    let count = 0;
    rows.forEach(row => {
      row.hidden = !normalize(row.dataset.taskSearchText).includes(query);
      if (!row.hidden) count++;
    });
    const empty = root.querySelector('[data-task-search-empty]');
    if (empty) empty.hidden = !query || !rows.length || count > 0;
  }

  function filterPeople(root, value) {
    const query = normalize(value);
    let count = 0;
    root.querySelectorAll('[data-person-text]').forEach(row => {
      row.hidden = !normalize(row.dataset.personText).includes(query);
      if (!row.hidden) count++;
    });
    const empty = root.querySelector('[data-person-empty]');
    if (empty) empty.hidden = count > 0;
  }

  function mount() {
    document.querySelectorAll('[data-task-views]').forEach(root => {
      let view;
      try { view = localStorage.getItem(viewKey); } catch (_) { /* 기본 보기를 사용한다. */ }
      if (view !== 'list' && view !== 'board') view = window.matchMedia('(max-width: 850px)').matches ? 'list' : 'board';
      setView(root, view);
    });
    document.querySelectorAll('[data-task-form]').forEach(form => syncProgress(form));
    document.querySelectorAll('[data-project-form]').forEach(form => {
      delete form.dataset.projectSubmitting;
      form.removeAttribute('aria-busy');
    });
    const error = document.querySelector('[data-project-error]');
    if (error) error.focus({ preventScroll: true });
  }

  document.addEventListener('click', event => {
    const view = event.target.closest('[data-task-view]');
    if (view) {
      const root = view.closest('[data-task-views]');
      if (root) setView(root, view.dataset.taskView, true);
    }
    const filter = event.target.closest('[data-project-filter]');
    if (filter) {
      const root = filter.closest('[data-project-filter-root]');
      if (root) filterProjects(root, filter.dataset.projectFilter);
    }
  });
  document.addEventListener('input', event => {
    if (event.target.matches('[data-task-search]')) filterTasks(event.target.closest('[data-task-views]'), event.target.value);
    if (event.target.matches('[data-person-search]')) filterPeople(event.target.closest('[data-person-picker]'), event.target.value);
  });
  document.addEventListener('change', event => {
    if (event.target.matches('[data-task-status]')) syncProgress(event.target.closest('[data-task-form]'), true);
  });
  document.addEventListener('submit', event => {
    const form = event.target;
    if (!form.matches('[data-project-form]') || event.defaultPrevented) return;
    // 일반 폼은 공통 soft-submit이 처리한다. 파일 폼의 네이티브 제출도 중복 요청을 막는다.
    if (form.dataset.projectSubmitting === 'true') { event.preventDefault(); return; }
    form.dataset.projectSubmitting = 'true';
    form.setAttribute('aria-busy', 'true');
  });
  window.addEventListener('worksystem:content-updated', mount);
  window.addEventListener('pageshow', mount);
  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', mount, { once: true });
  else mount();
})();
