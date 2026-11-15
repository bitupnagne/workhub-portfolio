(() => {
  const sectionStore = window.sessionStorage;
  const editSnapshots = new WeakMap();

  function rememberItem(root, itemId) {
    const row = root.querySelector(`[data-edit-row="${itemId}"]`);
    const form = root.querySelector(`[id="bundle-item-${itemId}"]`);
    if (!row || !form) return;
    const detailAnchor = root.querySelector(`[data-detail-add-row="${itemId}"]`);
    editSnapshots.set(row, {
      fields: Array.from(form.elements).filter((field) => field.matches('input,select,textarea'))
        .map((field) => ({ field, value: field.value, checked: field.checked })),
      detailAnchor,
      nextDetailIndex: detailAnchor?.dataset.nextDetailIndex,
    });
  }

  function restoreItem(root, itemId) {
    const row = root.querySelector(`[data-edit-row="${itemId}"]`);
    if (!row) return;
    const snapshot = editSnapshots.get(row);
    for (const { field, value, checked } of snapshot?.fields || []) {
      field.value = value;
      if (field.type === 'checkbox' || field.type === 'radio') field.checked = checked;
      field.dispatchEvent(new Event('change', { bubbles: true }));
    }
    if (snapshot?.detailAnchor) {
      if (snapshot.nextDetailIndex === undefined) delete snapshot.detailAnchor.dataset.nextDetailIndex;
      else snapshot.detailAnchor.dataset.nextDetailIndex = snapshot.nextDetailIndex;
    }
    editSnapshots.delete(row);
  }

  function setCollapsed(section, collapsed) {
    section.classList.toggle('is-collapsed', collapsed);
    const toggle = section.querySelector('[data-section-toggle]');
    if (toggle) toggle.setAttribute('aria-expanded', String(!collapsed));
  }

  function initSections(root) {
    root.querySelectorAll('[data-vf-section]').forEach((section) => {
      if (section.dataset.vfSectionReady === 'true') return;
      section.dataset.vfSectionReady = 'true';
      const key = section.dataset.sectionKey ? `vf-report-section-${section.dataset.sectionKey}` : '';
      const stored = key ? sectionStore.getItem(key) : null;
      setCollapsed(section, stored === 'closed');

      const toggle = section.querySelector('[data-section-toggle]');
      if (!toggle) return;
      const runToggle = (event) => {
        if (event.target.closest('form,button,select,input,a')) return;
        const collapsed = !section.classList.contains('is-collapsed');
        setCollapsed(section, collapsed);
        if (key) sectionStore.setItem(key, collapsed ? 'closed' : 'open');
      };
      toggle.addEventListener('click', runToggle);
      toggle.addEventListener('keydown', (event) => {
        if (event.key !== 'Enter' && event.key !== ' ') return;
        if (event.target.closest('form,button,select,input,a')) return;
        event.preventDefault();
        runToggle(event);
      });
    });
  }

  function closeAllEditors(root) {
    root.querySelectorAll('[data-edit-row]:not([hidden])').forEach((row) => restoreItem(root, row.dataset.editRow));
    closeStatusMenus(root);
    root.querySelectorAll('[data-edit-row],[data-edit-for],[data-detail-add-row]').forEach((row) => {
      row.hidden = true;
    });
    root.querySelectorAll('[data-display-row],[data-display-for]').forEach((row) => {
      row.hidden = false;
    });
    root.querySelectorAll('[data-new-row]').forEach((row) => {
      row.hidden = true;
      row.querySelectorAll('input:not([type="hidden"]),input[data-dp-ready]').forEach((input) => {
        input.value = '';
        input.dispatchEvent(new Event('change', { bubbles: true }));
      });
    });
    root.querySelectorAll('[data-weekly-registration]').forEach(resetRegistration);
    root.querySelectorAll('[data-dynamic-detail]').forEach((row) => row.remove());
    root.querySelectorAll('[data-status-menu]').forEach(resetStatusMenu);
  }

  function resetRegistration(form) {
    form.querySelectorAll('[name="content"],[name="dueDate"],[name="cycle"]').forEach((field) => {
      field.value = '';
      field.dispatchEvent(new Event('change', { bubbles: true }));
    });
  }

  function initRegistrationModals(root) {
    root.querySelectorAll('[data-weekly-registration]').forEach((form) => {
      const modal = form.closest('.ws-registration-modal');
      if (!modal || form.dataset.weeklyModalReady === 'true') return;
      form.dataset.weeklyModalReady = 'true';
      new MutationObserver(() => {
        if (modal.hidden) resetRegistration(form);
      }).observe(modal, { attributes: true, attributeFilter: ['hidden'] });
    });
  }

  function setItemEditing(root, itemId, editing) {
    root.querySelectorAll(`[data-display-row="${itemId}"],[data-display-for="${itemId}"]`).forEach((row) => {
      row.hidden = editing;
    });
    root.querySelectorAll(`[data-edit-row="${itemId}"],[data-edit-for="${itemId}"],[data-detail-add-row="${itemId}"]`).forEach((row) => {
      row.hidden = !editing;
    });
    if (editing) {
      root.querySelector(`[data-edit-row="${itemId}"] .em-c`)?.focus();
    }
  }

  function initInlineEditors(root) {
    root.querySelectorAll('[data-edit-item]').forEach((button) => {
      if (button.dataset.editorReady === 'true') return;
      button.dataset.editorReady = 'true';
      button.addEventListener('click', () => {
        closeAllEditors(root);
        rememberItem(root, button.dataset.editItem);
        setItemEditing(root, button.dataset.editItem, true);
      });
    });

    root.querySelectorAll('[data-cancel-edit]').forEach((button) => {
      if (button.dataset.cancelReady === 'true') return;
      button.dataset.cancelReady = 'true';
      button.addEventListener('click', () => {
        restoreItem(root, button.dataset.cancelEdit);
        setItemEditing(root, button.dataset.cancelEdit, false);
        root.querySelectorAll(`[data-edit-row="${button.dataset.cancelEdit}"] [data-status-menu]`).forEach(resetStatusMenu);
        root.querySelectorAll('[data-dynamic-detail]').forEach((row) => row.remove());
      });
    });

    root.querySelectorAll('[data-cancel-new]').forEach((button) => {
      if (button.dataset.cancelNewReady === 'true') return;
      button.dataset.cancelNewReady = 'true';
      button.addEventListener('click', () => {
        closeAllEditors(root);
        const modal = button.closest('.ws-registration-modal');
        if (modal) modal.hidden = true;
      });
    });

    root.querySelectorAll('[data-add-detail]').forEach((button) => {
      if (button.dataset.addDetailReady === 'true') return;
      button.dataset.addDetailReady = 'true';
      button.addEventListener('click', () => addDetailRow(button.closest('[data-detail-add-row]')));
    });

    if (root.dataset.dynamicRemoveReady !== 'true') {
      root.dataset.dynamicRemoveReady = 'true';
      root.addEventListener('click', (event) => {
        const removeButton = event.target.closest('[data-remove-new-detail]');
        if (removeButton) removeButton.closest('[data-dynamic-detail]')?.remove();
      });
    }
  }

  function setStatusMenuOpen(menu, open) {
    const toggle = menu.querySelector('[data-status-toggle]');
    const popover = menu.querySelector('.status-popover');
    menu.classList.toggle('is-open', open);
    if (toggle) toggle.setAttribute('aria-expanded', String(open));
    if (popover) popover.hidden = !open;
  }

  function closeStatusMenus(root) {
    root.querySelectorAll('[data-status-menu].is-open').forEach((menu) => setStatusMenuOpen(menu, false));
  }

  function setStatusMenuValue(menu, value, label) {
    const input = menu.querySelector('input[name="status"]');
    const labelTarget = menu.querySelector('[data-status-label]');
    if (input) input.value = value;
    if (labelTarget) labelTarget.textContent = label || value;
    menu.querySelectorAll('.status-option').forEach((option) => {
      const active = option.dataset.statusValue === value;
      option.classList.toggle('is-active', active);
      option.setAttribute('aria-checked', String(active));
    });
    updatePendingStatusNote(menu, value, label || value);
  }

  function updatePendingStatusNote(menu, value, label) {
    const row = menu.closest('[data-edit-row]');
    const note = row?.querySelector('[data-status-pending]');
    if (!note) return;
    const changed = value !== menu.dataset.initialStatus;
    const labelTarget = note.querySelector('[data-status-pending-label]');
    if (labelTarget) labelTarget.textContent = label;
    note.hidden = !changed;
  }

  function resetStatusMenu(menu) {
    const value = menu.dataset.initialStatus;
    if (!value) return;
    setStatusMenuValue(menu, value, menu.dataset.initialLabel || value);
    setStatusMenuOpen(menu, false);
  }

  function initStatusMenus(root) {
    root.querySelectorAll('[data-status-menu]').forEach((menu) => {
      if (menu.dataset.statusMenuReady === 'true') return;
      menu.dataset.statusMenuReady = 'true';

      menu.querySelector('[data-status-toggle]')?.addEventListener('click', (event) => {
        event.stopPropagation();
        const nextOpen = !menu.classList.contains('is-open');
        closeStatusMenus(root);
        setStatusMenuOpen(menu, nextOpen);
        if (nextOpen) menu.querySelector('.status-option.is-active,.status-option')?.focus();
      });

      menu.querySelectorAll('.status-option').forEach((option) => {
        option.addEventListener('click', (event) => {
          event.stopPropagation();
          setStatusMenuValue(menu, option.dataset.statusValue, option.dataset.statusLabel || option.textContent.trim());
          setStatusMenuOpen(menu, false);
          menu.querySelector('[data-status-toggle]')?.focus();
        });
      });
    });

    if (root.dataset.statusMenuRootReady !== 'true') {
      root.dataset.statusMenuRootReady = 'true';
      root.addEventListener('click', (event) => {
        if (!event.target.closest('[data-status-menu]')) closeStatusMenus(root);
      });
      root.addEventListener('keydown', (event) => {
        if (event.key === 'Escape') closeStatusMenus(root);
      });
    }
  }

  function addDetailRow(anchor) {
    if (!anchor) return;
    const formId = anchor.dataset.formId;
    const index = Number(anchor.dataset.nextDetailIndex || '0');
    anchor.dataset.nextDetailIndex = String(index + 1);
    const row = document.createElement('tr');
    row.className = 'det-row detail-edit-row';
    row.dataset.dynamicDetail = 'true';
    row.innerHTML = `
      <td></td>
      <td class="det-content">
        <span class="det-icon">○</span>
        <input class="e-input det-t" name="details[${index}].text" placeholder="세부 내용" required form="${formId}">
      </td>
      <td><input class="e-input det-d" type="date" name="details[${index}].dueDate" form="${formId}"></td>
      <td><input class="e-input det-cy" name="details[${index}].cycle" placeholder="주기" form="${formId}"></td>
      <td><button class="rbtn del" type="button" data-remove-new-detail>삭제</button></td>`;
    anchor.before(row);
    row.querySelector('.det-t')?.focus();
  }

  function setSortMode(root, active) {
    root.classList.toggle('sort-mode', active);
    const key = root.dataset.sortKey ? `vf-report-sort-${root.dataset.sortKey}` : '';
    if (key) sectionStore.setItem(key, active ? 'on' : 'off');
    const button = root.querySelector('[data-toggle-sort]');
    if (button) {
      button.classList.toggle('active', active);
      button.setAttribute('aria-pressed', String(active));
      button.textContent = active ? button.dataset.labelOn : button.dataset.labelOff;
    }
  }

  function initSortMode(root) {
    root.querySelectorAll('[data-sort-root]').forEach((sortRoot) => {
      if (sortRoot.dataset.sortReady === 'true') return;
      sortRoot.dataset.sortReady = 'true';
      const key = sortRoot.dataset.sortKey ? `vf-report-sort-${sortRoot.dataset.sortKey}` : '';
      setSortMode(sortRoot, key ? sectionStore.getItem(key) === 'on' : false);
      sortRoot.querySelector('[data-toggle-sort]')?.addEventListener('click', () => {
        setSortMode(sortRoot, !sortRoot.classList.contains('sort-mode'));
      });
    });
  }

  function initAutoSubmit(root) {
    root.querySelectorAll('[data-auto-submit]').forEach((select) => {
      if (select.dataset.autoSubmitReady === 'true') return;
      select.dataset.autoSubmitReady = 'true';
      select.addEventListener('change', () => select.form?.requestSubmit());
    });
  }

  function init() {
    const root = document.querySelector('.content');
    if (!root) return;
    initSections(root);
    initInlineEditors(root);
    initRegistrationModals(root);
    initStatusMenus(root);
    initSortMode(root);
    initAutoSubmit(root);
  }

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', init);
  } else {
    init();
  }
  window.addEventListener('worksystem:content-updated', init);
})();
