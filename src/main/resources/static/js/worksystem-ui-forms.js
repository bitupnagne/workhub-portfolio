/* Shared form interactions. Native controls, names, form ownership and submission remain authoritative. */
(() => {
  const edits = new WeakMap();
  const toolbarOwners = new WeakMap();
  const registrations = new WeakMap();
  const pendingRegistrations = new Map();
  const pendingEdits = new Map();
  const observedModals = new WeakSet();
  let activeRegistration = null;
  let sequence = 0;
  const fields = (form) => [...new Set([...form.elements, ...form.querySelectorAll('[contenteditable]')])];
  const editKey = (form) => {
    // Named controls such as <input name="id"> can shadow form.id with a DOM node.
    // The POST action and entity identifiers survive a server-rendered replacement.
    const action = new URL(form.getAttribute('action') || location.href, location.href).pathname;
    const identifiers = fields(form).filter((node) => node.matches('input[type="hidden"]')
      && /^(?:id|[a-z][\w-]*(?:Id|Code)|key)$/i.test(node.name || '') && !node.name.startsWith('_'))
      .map((node) => [node.name, node.value]).sort((a, b) => a[0].localeCompare(b[0]));
    return JSON.stringify([action, identifiers]);
  };
  const registrationKey = (form) => JSON.stringify([form.getAttribute('id') || '', editKey(form)]);
  const visible = (node) => node.getClientRects().length > 0;
  const submitButtons = (form) => [...form.elements].filter((node) => node.matches('button[type="submit"],input[type="submit"],button:not([type])'));
  const managed = (node) => !node.matches('[data-edit-control],[data-edit-allow-preview],[data-modal-close],[data-modal-open],[type="submit"],button:not([type])')
    && !(node.type === 'hidden' && !node.matches('[data-dp-ready],[data-time-enhanced]'))
    && !node.closest('[data-edit-allow-preview],.vf-search-select')
    && !node.matches('.ws-dp-display');
  const stateOf = (node) => ({ node, disabled: node.disabled, readOnly: node.readOnly,
    value: node.value, checked: node.checked, selected: node.matches('select') ? [...node.options].map((option) => option.selected) : null,
    editable: node.getAttribute('contenteditable'), html: node.hasAttribute('contenteditable') ? node.innerHTML : null });

  const syncCustom = (node, locked) => {
    const date = node.closest('.ws-dp-wrap');
    if (date) {
      date.classList.toggle('ws-control-locked', !!(locked || node.disabled || node.readOnly));
      date.querySelectorAll('.ws-dp-display').forEach((button) => { button.disabled = locked || node.disabled || node.readOnly; });
    }
    if (node.matches('[data-time-enhanced]')) {
      const picker = node.nextElementSibling;
      if (picker?.matches('.vf-time-picker')) {
        picker.classList.toggle('ws-control-locked', !!(locked || node.disabled || node.readOnly));
        picker.querySelectorAll('select,input').forEach((control) => { control.disabled = locked || node.disabled || node.readOnly; });
      }
    }
    if (node.matches('select')) {
      const picker = node.nextElementSibling;
      if (picker?.matches('.vf-search-select')) {
        picker.classList.toggle('ws-control-locked', locked || node.disabled);
        if (locked || node.disabled) picker.classList.remove('open');
        picker.querySelectorAll('button,input').forEach((control) => { control.disabled = locked || node.disabled; });
      }
    }
  };

  const lockField = (snapshot, locked) => {
    const node = snapshot.node;
    if (!managed(node)) return;
    // Form-owned fields may live outside the form (for example grade table rows).
    // Keep visual state on the control without changing its original lock rules.
    node.dataset.editLocked = String(!!(locked || snapshot.disabled || snapshot.readOnly || snapshot.editable === 'false'));
    if (node.hasAttribute('contenteditable')) node.setAttribute('contenteditable', locked ? 'false' : snapshot.editable);
    else if (node.matches('input:not([type="checkbox"]):not([type="radio"]):not([type="file"]):not([type="range"]):not([type="color"]),textarea')) {
      node.readOnly = locked || snapshot.readOnly;
    } else node.disabled = locked || snapshot.disabled;
    syncCustom(node, locked);
  };

  const syncDeleteActions = (form, editing) => {
    const formId = form.getAttribute('id');
    if (!formId) return;
    document.querySelectorAll('[data-edit-delete-owner]').forEach((action) => {
      if (action.getAttribute('data-edit-delete-owner') !== formId) return;
      const waitingInList = action.hasAttribute('data-edit-delete-source') && !action.closest('[data-edit-delete-slot]');
      action.hidden = !editing || waitingInList;
    });
  };
  document.addEventListener('worksystem:edit-state', (event) => {
    if (event.target?.matches?.('form')) syncDeleteActions(event.target, event.detail?.editing === true);
  });
  const setEditing = (form, state, editing) => {
    state.editing = editing;
    form.classList.toggle('ws-form-editing', editing);
    form.classList.toggle('ws-form-view', !editing);
    form.dataset.editState = editing ? 'editing' : 'view';
    state.fields.forEach((snapshot) => lockField(snapshot, !editing));
    state.submitters.forEach((snapshot) => {
      snapshot.node.hidden = !editing || snapshot.hidden;
      snapshot.node.disabled = snapshot.disabled;
      const label = snapshot.node.dataset.editSubmitLabel || form.dataset.editSubmitLabel;
      if (snapshot.node.tagName === 'BUTTON' && label) snapshot.node.textContent = label;
    });
    state.editButton.hidden = editing;
    state.cancelButton.hidden = !editing;
    state.status.textContent = editing ? '수정 중' : '조회 모드';
    syncDeleteActions(form, editing);
    form.dispatchEvent(new CustomEvent('worksystem:edit-state', { bubbles: true, detail: { editing } }));
  };

  const restore = (form, state) => {
    const previousRestoring = form.dataset.editRestoring;
    form.dataset.editRestoring = 'true';
    try {
    if (state.domSnapshot) {
      // Restore the original nodes and order, retaining attached handlers and picker instances.
      state.domSnapshot.forEach(({ node, children }) => node.replaceChildren(...children));
      state.fields = state.originalFields;
    }
    state.fields.forEach((snapshot) => {
      const node = snapshot.node;
      if (!node.isConnected) return;
      if (snapshot.selected) [...node.options].forEach((option, index) => { option.selected = snapshot.selected[index] || false; });
      else if (node.type !== 'file' && snapshot.value !== undefined) node.value = snapshot.value;
      else if (node.type === 'file') node.value = '';
      if (snapshot.checked !== undefined) node.checked = snapshot.checked;
      if (snapshot.html !== null) node.innerHTML = snapshot.html;
      node.disabled = snapshot.disabled;
      if (snapshot.readOnly !== undefined) node.readOnly = snapshot.readOnly;
      node.dispatchEvent(new Event('change', { bubbles: true }));
      // Date picker has its own display and does not listen to programmatic change.
      const button = node.closest('.ws-dp-wrap')?.querySelector('.ws-dp-display');
      if (button) button.textContent = node.value ? node.value.replace('T', '  ') : '날짜 선택';
    });
    } finally {
      if (previousRestoring === undefined) delete form.dataset.editRestoring;
      else form.dataset.editRestoring = previousRestoring;
    }
  };

  const cancelEdit = (form, state) => {
    if (!state?.editing) return;
    restore(form, state);
    form.dispatchEvent(new CustomEvent('worksystem:edit-cancel', { bubbles: true }));
    // [UI] 조회 모드는 없다 — 되돌린 뒤에도 바로 고칠 수 있다.
    setEditing(form, state, true);
    state.syncDirty?.();
  };
  const cancelModalEdits = (modal) => {
    modal.querySelectorAll('form[data-edit-form]').forEach((form) => cancelEdit(form, edits.get(form)));
  };

  const enhanceEdit = (form) => {
    let state = edits.get(form);
    if (state) {
      syncDeleteActions(form, state.editing);
      fields(form).filter(managed).forEach((node) => {
        if (state.fields.some((snapshot) => snapshot.node === node)) return;
        const snapshot = stateOf(node);
        state.fields.push(snapshot);
        lockField(snapshot, !state.editing);
      });
      state.fields.forEach((snapshot) => syncCustom(snapshot.node, !state.editing));
      return;
    }
    const submitters = submitButtons(form);
    if (!submitters.length) return;
    submitters.forEach((node) => { node.dataset.editSubmit = ''; });
    const toolbar = document.createElement('div');
    toolbar.className = 'ws-edit-actions';
    toolbar.innerHTML = '<span class="ws-edit-status" role="status"></span><button type="button" class="btn" data-edit-control data-edit-cancel hidden>되돌리기</button><span class="ws-edit-primary-slot"><button type="button" class="btn" data-edit-control data-edit-start>수정하기</button></span>';
    // Row controls keep their cell ownership; full forms use the card or dialog heading.
    const anchor = submitters.find((button) => !form.contains(button));
    if (anchor) anchor.before(toolbar);
    else if (!form.closest('.schedule-edit-dialog,[data-app-form-body]')) {
      // [UI] 화면에 놓인 설정 폼: 바뀐 게 있을 때만 폼 아래에 '저장 바'가 뜬다.
      toolbar.classList.add('ws-save-bar');
      form.appendChild(toolbar);
    } else {
      const dialog = form.closest('.schedule-edit-dialog');
      const previous = form.previousElementSibling;
      const heading = dialog?.querySelector(':scope > .schedule-edit-head')
        || (previous?.matches('.section-header,.section-head') && !previous.matches('button,summary') ? previous : null)
        || form.querySelector(':scope > .form-section-title');
      if (heading && !heading.querySelector('.ws-edit-actions')) {
        heading.classList.add('ws-edit-heading');
        const close = heading.querySelector(':scope > [data-modal-close],:scope > [data-app-form-close]');
        if (close) close.before(toolbar); else heading.appendChild(toolbar);
      } else {
        toolbar.classList.add('ws-edit-topbar');
        form.prepend(toolbar);
      }
    }
    // Keep the actual submitter: native validation, named values and attached handlers stay intact.
    let ownerId = form.getAttribute('id');
    if (!ownerId) {
      do { ownerId = 'ws-edit-form-' + (++sequence); } while (document.getElementById(ownerId));
      form.setAttribute('id', ownerId);
    }
    toolbar.dataset.editOwner = ownerId;
    toolbarOwners.set(toolbar, form);
    const primarySlot = toolbar.querySelector('.ws-edit-primary-slot');
    const submitterStates = submitters.map((node) => ({ node, hidden: node.hidden, disabled: node.disabled }));
    const actionContainers = new Set();
    const primarySubmitter = submitters.find((node) => !node.hidden);
    submitters.forEach((node) => {
      const footer = node.closest('.form-actions,.schedule-edit-foot');
      if (footer) actionContainers.add(footer);
      node.setAttribute('form', ownerId);
      node.classList.add('ws-edit-save');
      // An input submitter's value is its successful-control value as well as its label.
      if (node.tagName === 'BUTTON' && node === primarySubmitter) {
        node.title = node.title || node.textContent.trim();
        node.textContent = node.dataset.editSubmitLabel || form.dataset.editSubmitLabel || '저장';
      }
      primarySlot.appendChild(node);
    });
    actionContainers.forEach((footer) => {
      if (!footer.textContent.trim() && !footer.querySelector('button,a,input:not([type="hidden"]),select,textarea,[contenteditable]')) {
        footer.dataset.editEmptyFooter = '';
      }
    });
    state = { fields: fields(form).filter(managed).map(stateOf), editing: false, toolbar,
      submitters: submitterStates,
      editButton: toolbar.querySelector('[data-edit-start]'), cancelButton: toolbar.querySelector('[data-edit-cancel]'), status: toolbar.querySelector('[role="status"]') };
    edits.set(form, state);
    state.editButton.addEventListener('click', (event) => {
      event.stopPropagation();
      state.fields.forEach((snapshot) => { if (snapshot.node.isConnected) Object.assign(snapshot, stateOf(snapshot.node), { disabled: snapshot.disabled, readOnly: snapshot.readOnly, editable: snapshot.editable }); });
      if (form.hasAttribute('data-edit-restore-dom')) {
        // Capture unlocked markup; capturing view-mode disabled attributes would permanently disable restored fields.
        setEditing(form, state, true);
        state.originalFields = [...state.fields];
        state.domSnapshot = [form, ...form.querySelectorAll('*')].filter((node) => !node.closest('.ws-edit-actions'))
          .map((node) => ({ node, children: [...node.childNodes] }));
      } else setEditing(form, state, true);
      form.dispatchEvent(new CustomEvent('worksystem:edit-start', { bubbles: true }));
      state.fields.find((snapshot) => managed(snapshot.node) && !snapshot.node.disabled && visible(snapshot.node))?.node.focus({ preventScroll: true });
    });
    state.cancelButton.addEventListener('click', (event) => { event.stopPropagation(); cancelEdit(form, state); state.editButton.focus(); scan(); });
    const error = form.querySelector('.field-error,[aria-invalid="true"]');
    const failed = document.querySelector('.content .banner.err');
    const key = editKey(form);
    const retained = failed && visible(failed) ? pendingEdits.get(key) : null;
    if (retained) { restoreValues(form, retained); pendingEdits.delete(key); }
    // [UI] 조회/수정 모드 없음 — 처음부터 편집 가능, 바뀐 것이 있는지만 본다.
    setEditing(form, state, true);
    state.baseline = JSON.stringify(rememberValues(form));
    state.syncDirty = () => {
      const dirty = !!state.forceDirty || JSON.stringify(rememberValues(form)) !== state.baseline;
      form.classList.toggle('ws-form-dirty', dirty);
      toolbar.classList.toggle('is-dirty', dirty);
      state.cancelButton.hidden = !dirty;
      state.status.textContent = dirty ? '저장하지 않은 변경사항이 있습니다' : '';
    };
    state.forceDirty = !!retained || (!!error && visible(error));
    form.addEventListener('input', () => { state.forceDirty = false; state.syncDirty(); });
    form.addEventListener('change', () => { if (form.dataset.editRestoring === undefined) state.forceDirty = false; state.syncDirty(); });
    state.syncDirty();
  };

  const rememberValues = (form) => fields(form).filter((node) => node.name && !node.matches('[type="password"],[type="file"],button') && !(node.type === 'hidden' && !node.matches('[data-dp-ready],[data-time-enhanced],[data-dtcombo-value]'))).map((node) => ({ name: node.name, value: node.value, checked: node.checked }));
  const restoreValues = (form, values) => {
    const previousRestoring = form.dataset.editRestoring;
    form.dataset.editRestoring = 'true';
    try {
    const remaining = [...values];
    fields(form).forEach((node) => {
      if (!node.name) return;
      const index = remaining.findIndex((value) => value.name === node.name);
      if (index < 0) return;
      const value = remaining.splice(index, 1)[0];
      node.value = value.value;
      if (value.checked !== undefined) node.checked = value.checked;
      node.dispatchEvent(new Event('change', { bubbles: true }));
    });
    } finally {
      if (previousRestoring === undefined) delete form.dataset.editRestoring;
      else form.dataset.editRestoring = previousRestoring;
    }
  };

  const closeRegistration = (state) => {
    state.modal.hidden = true;
    if (activeRegistration === state) activeRegistration = null;
    state.opener.focus({ preventScroll: true });
  };
  const openRegistration = (state) => {
    if (activeRegistration && activeRegistration !== state) closeRegistration(activeRegistration);
    state.modal.hidden = false;
    activeRegistration = state;
    [...state.form.querySelectorAll('input:not([type="hidden"]):not([disabled]),select:not([disabled]),textarea:not([disabled]),button:not([disabled])')].find(visible)?.focus();
  };

  const enhanceRegistration = (form, section) => {
    if (registrations.has(form) || form.closest('.ws-registration-modal') || form.closest('.schedule-edit-modal,[role="dialog"]')) return;
    if ((form.method || '').toLowerCase() !== 'post') return;
    const title = form.dataset.registrationTitle || section?.dataset.registrationTitle || section?.querySelector('h2,h3,.sname,.form-section-title')?.textContent.trim() || '등록';
    const id = 'ws-registration-' + (++sequence);
    const opener = document.createElement('button');
    opener.type = 'button'; opener.className = 'btn primary ws-registration-open';
    opener.textContent = form.dataset.registrationLabel || section?.dataset.registrationLabel || '등록';
    opener.setAttribute('aria-haspopup', 'dialog'); opener.setAttribute('aria-controls', id);
    const heading = section?.querySelector('.section-header,.admin-title-row') || form.parentElement?.querySelector(':scope > .section-header,:scope > .admin-title-row');
    const placeholder = document.createElement('div'); placeholder.className = 'ws-registration-placeholder';
    form.before(placeholder);
    // [UI] 화면 표준: 등록 버튼은 화면 제목 줄 오른쪽(주 버튼). 같은 화면의 두 번째부터는 보조 버튼.
    const titleRow = form.hasAttribute('data-registration-inline') || form.hasAttribute('data-weekly-registration')
      ? null : document.querySelector('.content .admin-title-row');
    if (titleRow) {
      let actions = titleRow.querySelector(':scope > .page-actions');
      if (!actions) { actions = document.createElement('div'); actions.className = 'page-actions'; titleRow.appendChild(actions); }
      if (actions.querySelector('.btn.primary')) opener.className = 'rbtn ws-registration-open';
      opener.insertAdjacentHTML('afterbegin', wsIcon('add') + ' ');
      actions.appendChild(opener);
    } else if (heading && !form.contains(heading)) heading.appendChild(opener);
    else { placeholder.appendChild(opener); }
    const modal = document.createElement('div');
    modal.className = 'schedule-edit-modal ws-registration-modal'; modal.id = id; modal.hidden = true;
    modal.innerHTML = '<div class="schedule-edit-dialog ws-registration-dialog" role="dialog" aria-modal="true"><div class="schedule-edit-head"><strong></strong><button type="button" class="ws-registration-close" aria-label="등록 창 닫기">' + wsIcon('close') + '</button></div><div class="ws-registration-body"></div></div>';
    if (['compact', 'wide'].includes(form.dataset.modalSize || form.dataset.registrationSize)) {
      modal.dataset.modalSize = form.dataset.modalSize || form.dataset.registrationSize;
    }
    modal.querySelector('strong').textContent = title;
    modal.querySelector('strong').id = id + '-title';
    modal.querySelector('[role="dialog"]').setAttribute('aria-labelledby', id + '-title');
    if (titleRow) (titleRow.closest('.content') || document.body).appendChild(modal); else placeholder.after(modal);
    modal.querySelector('.ws-registration-body').appendChild(form);
    // 폼을 모달로 옮기고 나서 빈 껍데기만 남은 구역(머리 줄뿐)은 감춘다 — 한 화면에 목록 하나만 보이게.
    if (titleRow && section && section !== document.querySelector('.content')) {
      const rest = [...section.children].filter((el) => !el.matches('.section-header,.form-section-title,h2,h3,p.muted,.ws-registration-placeholder,.schedule-edit-modal'));
      if (!rest.length) section.hidden = true;
    }
    const state = { form, modal, opener, initial: rememberValues(form) };
    registrations.set(form, state);
    opener.addEventListener('click', () => openRegistration(state));
    modal.querySelector('.ws-registration-close').addEventListener('click', () => closeRegistration(state));
    modal.addEventListener('click', (event) => {
      if (event.target === modal || event.target.closest('[data-registration-close]')) {
        event.preventDefault();
        closeRegistration(state);
      }
    });
    const key = registrationKey(form);
    const errors = document.querySelector('.content .banner.err');
    if (errors && visible(errors) && pendingRegistrations.has(key)) {
      restoreValues(form, pendingRegistrations.get(key)); openRegistration(state); pendingRegistrations.delete(key);
    } else if (errors && form.querySelector('.field-error,[aria-invalid="true"]')) openRegistration(state);
  };

  const toggle = (input) => {
    input.classList.add('ws-native-switch');
    input.setAttribute('role', 'switch');
    if (!input.getAttribute('aria-label') && !input.getAttribute('aria-labelledby') && ![...(input.labels || [])].some((label) => label.textContent.trim())) {
      const cell = input.closest('td,th');
      const heading = cell?.closest('table')?.querySelector('thead tr')?.children[cell.cellIndex]?.textContent.trim();
      input.setAttribute('aria-label', heading || input.title || input.name || '선택');
    }
  };

  function scan(root = document) {
    // Partial replacements can leave a shared heading alive after its form has been replaced.
    document.querySelectorAll('.ws-edit-actions[data-edit-owner]').forEach((toolbar) => {
      if (!toolbarOwners.get(toolbar)?.isConnected) toolbar.remove();
    });
    root.querySelectorAll('input[type="checkbox"]').forEach(toggle);
    root.querySelectorAll('form[data-edit-form]').forEach(enhanceEdit);
    root.querySelectorAll('form[data-registration-form]').forEach((form) => enhanceRegistration(form, form.closest('[data-registration-section],.section-block,.form-panel')));
    root.querySelectorAll('[data-registration-section]').forEach((section) => {
      const form = section.matches('form') ? section : section.querySelector('form[method="post"],form[method="POST"]');
      if (form) enhanceRegistration(form, section);
    });
    root.querySelectorAll('.schedule-edit-modal,[role="dialog"],dialog').forEach((modal) => {
      if (observedModals.has(modal)) return;
      observedModals.add(modal);
      // Observe only the modal's visibility attributes, never field disabled/readOnly changes.
      new MutationObserver(() => {
        if (modal.hidden || (modal.tagName === 'DIALOG' && !modal.open)) cancelModalEdits(modal);
      }).observe(modal, { attributes: true, attributeFilter: ['hidden', 'open'] });
      modal.addEventListener('close', () => cancelModalEdits(modal));
    });
  }

  document.addEventListener('click', (event) => {
    const close = event.target.closest('[data-modal-close]');
    const modal = close?.closest('.schedule-edit-modal,[role="dialog"],dialog');
    if (modal) cancelModalEdits(modal);
  }, true);

  document.addEventListener('submit', (event) => {
    const form = event.target;
    const state = edits.get(form);
    if (state && !state.editing) { event.preventDefault(); event.stopImmediatePropagation(); return; }
  }, true);
  // Form-level editors synchronize their named source fields during the target/bubbling phase.
  // Remember only afterwards, before the later soft-submit document handler replaces the content.
  document.addEventListener('submit', (event) => {
    const form = event.target;
    const state = edits.get(form);
    if (state) pendingEdits.set(editKey(form), rememberValues(form));
    const registration = registrations.get(form);
    if (registration) pendingRegistrations.set(registrationKey(form), rememberValues(form));
  });
  document.addEventListener('keydown', (event) => {
    if (event.key === 'Escape' && activeRegistration && !activeRegistration.modal.hidden) {
      const picker = document.querySelector('.ws-dp-pop.open,.vf-time-picker.open,.vf-select-panel.open');
      if (!picker) { closeRegistration(activeRegistration); event.preventDefault(); }
    }
  });
  document.addEventListener('change', (event) => {
    const node = event.target;
    if (node.matches('[data-dp-ready]')) {
      const button = node.closest('.ws-dp-wrap')?.querySelector('.ws-dp-display');
      if (button) {
        button.disabled = node.disabled || node.readOnly;
        button.textContent = node.value ? node.value.replace('T', '  ') : '날짜 선택';
        button.classList.toggle('empty', !node.value);
      }
    }
    if (node.matches('[data-time-enhanced]') && !event.detail?.worksystemTimePickerSync) {
      const picker = node.nextElementSibling;
      const match = /^(\d{1,2}):(\d{2})/.exec(node.value || '');
      if (picker?.matches('.vf-time-picker')) {
        const period = picker.querySelector('.vf-time-period');
        if (match) {
          const hour = Number(match[1]);
          period.value = hour >= 12 ? 'PM' : 'AM';
          picker.querySelector('.vf-time-h').value = (hour % 12) || 12;
          picker.querySelector('.vf-time-m').value = match[2];
        } else {
          period.value = 'AM';
          picker.querySelector('.vf-time-h').value = '';
          picker.querySelector('.vf-time-m').value = '';
        }
        const selectBox = period.nextElementSibling;
        if (selectBox?.matches('.vf-search-select')) {
          selectBox.querySelector('.vf-select-button span').textContent = period.selectedOptions[0]?.textContent.trim() || '';
          selectBox.querySelectorAll('.vf-select-option').forEach(option => {
            option.setAttribute('aria-selected', String(option.dataset.value === period.value));
          });
        }
      }
    }
  });
  document.addEventListener('invalid', (event) => {
    const state = registrations.get(event.target.form);
    if (state) openRegistration(state);
  }, true);
  let scheduled = false;
  const schedule = () => {
    if (scheduled) return;
    scheduled = true;
    requestAnimationFrame(() => { scheduled = false; scan(); });
  };
  new MutationObserver((records) => { if (records.some((record) => record.addedNodes.length || record.removedNodes.length)) schedule(); })
    .observe(document.documentElement, { subtree: true, childList: true });
  window.addEventListener('worksystem:content-updated', () => {
    if (activeRegistration && !activeRegistration.form.isConnected) activeRegistration = null;
    scan();
    if (!document.querySelector('.content .banner.err')) { pendingEdits.clear(); pendingRegistrations.clear(); }
  });
  document.addEventListener('content-updated', schedule);
  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', () => scan()); else scan();
})();

/* 설명 범례는 body portal로 표시해 카드 경계에 잘리지 않게 한다. */
(() => {
  if (window.__wsLegendHelpReady) return;
  window.__wsLegendHelpReady = true;
  let active = null;
  let positioning = 0;
  const observer = typeof MutationObserver === 'function' ? new MutationObserver(() => {
    if (!active || positioning) return;
    positioning = requestAnimationFrame(() => { positioning = 0; position(); });
  }) : null;
  const close = (restoreFocus = true) => {
    if (!active) return;
    const { trigger, panel, home, next, style } = active;
    active = null;
    observer?.disconnect();
    if (positioning) { cancelAnimationFrame(positioning); positioning = 0; }
    panel.hidden = true;
    delete panel.__wsHelpTrigger;
    trigger.setAttribute('aria-expanded', 'false');
    if (style === null) panel.removeAttribute('style'); else panel.setAttribute('style', style);
    if (home.isConnected) home.insertBefore(panel, next?.parentElement === home ? next : null); else panel.remove();
    if (restoreFocus && trigger.isConnected) trigger.focus({ preventScroll: true });
  };
  const position = () => {
    if (!active) return;
    const { trigger, panel, owner } = active;
    if (!trigger.isConnected || (owner && (!owner.isConnected || owner.hidden || !owner.getClientRects().length))) { close(false); return; }
    const rect = trigger.getBoundingClientRect();
    const viewport = window.visualViewport;
    const bounds = { left: viewport?.offsetLeft || 0, top: viewport?.offsetTop || 0,
      width: viewport?.width || window.innerWidth, height: viewport?.height || window.innerHeight };
    if (rect.bottom < bounds.top || rect.top > bounds.top + bounds.height) { close(false); return; }
    panel.style.maxWidth = `${Math.max(0, bounds.width - 24)}px`;
    panel.style.maxHeight = `${Math.max(0, bounds.height - 24)}px`;
    const size = panel.getBoundingClientRect();
    const left = Math.max(bounds.left + 12, Math.min(rect.right - size.width, bounds.left + bounds.width - size.width - 12));
    const preferredTop = rect.bottom + 8 + size.height <= bounds.top + bounds.height - 12 ? rect.bottom + 8 : rect.top - size.height - 8;
    panel.style.left = `${left}px`;
    panel.style.top = `${Math.max(bounds.top + 12, Math.min(preferredTop, bounds.top + bounds.height - size.height - 12))}px`;
  };
  document.addEventListener('click', (event) => {
    const trigger = event.target.closest('[data-help-toggle]');
    if (trigger) {
      event.preventDefault();
      if (active?.trigger === trigger) { close(); return; }
      close(false);
      const panel = trigger.closest('.ws-help')?.querySelector('[data-help-popover]');
      if (!panel) return;
      const owner = trigger.closest('.schedule-edit-modal,.vf-modal,.vf-common-modal,.ws-registration-modal,[role="dialog"]');
      active = { trigger, panel, owner, home: panel.parentElement, next: panel.nextSibling, style: panel.getAttribute('style') };
      let layer = 1750;
      for (let node = trigger.parentElement; node && node !== document.body; node = node.parentElement) {
        layer = Math.max(layer, (Number(getComputedStyle(node).zIndex) || 0) + 2);
      }
      document.body.appendChild(panel);
      panel.__wsHelpTrigger = trigger;
      panel.style.zIndex = String(layer);
      panel.hidden = false;
      observer?.observe(document.body, { subtree: true, childList: true, attributes: true, attributeFilter: ['hidden', 'aria-hidden', 'class'] });
      trigger.setAttribute('aria-expanded', 'true');
      position();
      panel.querySelector('[data-help-close]')?.focus({ preventScroll: true });
      return;
    }
    if (active?.panel.contains(event.target)) {
      if (event.target.closest('[data-help-close]')) { event.preventDefault(); close(); }
      return;
    }
    close(false);
  });
  window.addEventListener('keydown', (event) => {
    if (event.key === 'Escape' && active) {
      event.preventDefault();
      event.stopImmediatePropagation();
      close();
    }
  }, true);
  document.addEventListener('focusin', (event) => {
    if (active && !active.panel.contains(event.target) && event.target !== active.trigger) close(false);
  });
  window.addEventListener('resize', position);
  window.visualViewport?.addEventListener('resize', position);
  window.visualViewport?.addEventListener('scroll', position);
  window.addEventListener('scroll', position, true);
  window.addEventListener('worksystem:navigation-start', () => close(false));
  window.addEventListener('worksystem:content-updated', position);
})();


/* 기존 삭제 form을 명시된 수정 모달 슬롯으로 옮긴다. POST/확인/권한 노드는 그대로 둔다. */
(() => {
  if (window.__wsEditorDeleteReady) return;
  window.__wsEditorDeleteReady = true;
  const selector = '[data-edit-delete-form][data-edit-delete-slot-target]';
  let active = null;
  const resolve = trigger => {
    let form, slot;
    try {
      form = document.querySelector(trigger.getAttribute('data-edit-delete-form'));
      slot = document.querySelector(trigger.getAttribute('data-edit-delete-slot-target'));
    } catch (_) { return null; }
    if (!form?.matches('form') || !slot?.hasAttribute('data-edit-delete-slot') || slot.closest('form')) return null;
    const modal = slot.closest('.schedule-edit-modal,.vf-modal,[role="dialog"]');
    return modal ? { form, slot, modal } : null;
  };
  const restore = () => {
    if (!active) return;
    const { form, home, next } = active;
    active = null;
    form.hidden = true;
    if (home.isConnected) home.insertBefore(form, next?.parentElement === home ? next : null);
    else form.remove();
  };
  const initialize = () => {
    document.querySelectorAll(selector).forEach(trigger => {
      const entry = resolve(trigger);
      if (entry && entry.form !== active?.form) entry.form.hidden = true;
    });
    if (active && (!active.modal.isConnected || active.modal.hidden || !active.modal.getClientRects().length)) restore();
  };
  document.addEventListener('click', event => {
    const trigger = event.target.closest(selector);
    if (!trigger) return;
    window.setTimeout(() => {
      if (!trigger.isConnected) return;
      const entry = resolve(trigger);
      if (!entry || entry.modal.hidden || !entry.modal.getClientRects().length) return;
      restore();
      active = { ...entry, home: entry.form.parentElement, next: entry.form.nextSibling };
      entry.slot.appendChild(entry.form);
      const ownerId = entry.form.getAttribute('data-edit-delete-owner');
      const owner = ownerId ? document.getElementById(ownerId) : null;
      entry.form.hidden = !!owner && owner.getAttribute('data-edit-state') !== 'editing';
    }, 0);
  }, true);
  if (typeof MutationObserver === 'function') new MutationObserver(() => {
    if (active && (!active.modal.isConnected || active.modal.hidden || !active.modal.getClientRects().length)) restore();
  }).observe(document.body, { subtree: true, attributes: true, childList: true, attributeFilter: ['hidden', 'class'] });
  window.addEventListener('worksystem:navigation-start', restore);
  window.addEventListener('worksystem:content-updated', initialize);
  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', initialize, { once: true });
  else initialize();
})();
