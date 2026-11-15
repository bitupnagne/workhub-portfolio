(() => {
  // Google Material Icons Rounded expand_more, Apache-2.0 (fonts.google.com/icons).
  if (window.worksystemSearchSelectInit) { window.worksystemSearchSelectInit(); return; }
  const states = new WeakMap();
  const boxes = new WeakMap();
  let activeBox = null;
  let nextId = 0;
  const availableOptions = (select) => Array.from(select.options).filter((option) =>
    !option.disabled && !option.hidden && !option.closest('optgroup[disabled],optgroup[hidden]'));
  const selectedLabel = (select) => select.selectedOptions[0]?.textContent.trim() || '선택';
  const close = (box, restoreFocus = false) => {
    const state = boxes.get(box);
    if (state) {
      state.panel.classList.remove('open');
      state.panel.removeAttribute('style');
      box.appendChild(state.panel);
    }
    box.classList.remove('open', 'opens-up');
    box.querySelector('.vf-select-button').setAttribute('aria-expanded', 'false');
    if (activeBox === box) activeBox = null;
    if (restoreFocus && box.isConnected) box.querySelector('.vf-select-button').focus({ preventScroll: true });
  };
  const closeAll = (except) => document.querySelectorAll('.vf-search-select.open').forEach((box) => {
    if (box !== except) close(box);
  });

  const enhanceSelect = (select) => {
    if (states.has(select) || select.multiple || select.closest('.dtp-pop,.ws-dp-pop,template')
        || select.dataset.noSearchSelect === 'true') return;
    // A cloned form may contain enhancement markup without its original event handlers.
    if (select.dataset.searchSelectReady === 'true' && select.nextElementSibling?.matches('.vf-search-select')) {
      select.nextElementSibling.remove();
    }
    select.dataset.searchSelectReady = 'true';
    select.classList.add('vf-native-select-hidden');
    select.tabIndex = -1;
    select.setAttribute('aria-hidden', 'true');
    const box = document.createElement('div');
    box.className = 'vf-search-select';
    box.innerHTML = `
      <button type="button" class="vf-select-button" role="combobox" aria-haspopup="listbox" aria-expanded="false">
        <span></span><svg class="vf-select-chevron" xmlns="http://www.w3.org/2000/svg" width="24" height="24" viewBox="0 0 24 24" aria-hidden="true" focusable="false"><path d="M15.88 9.29L12 13.17 8.12 9.29c-.39-.39-1.02-.39-1.41 0-.39.39-.39 1.02 0 1.41l4.59 4.59c.39.39 1.02.39 1.41 0l4.59-4.59c.39-.39.39-1.02 0-1.41-.39-.38-1.03-.39-1.42 0z"/></svg>
      </button>
      <div class="vf-select-panel">
        <input type="search" class="vf-select-search" placeholder="검색어 입력" autocomplete="off" aria-label="선택 항목 검색">
        <div class="vf-select-list" role="listbox" data-vf-select-list></div>
      </div>`;
    select.after(box);
    const button = box.querySelector('.vf-select-button');
    const label = button.querySelector('span');
    const search = box.querySelector('.vf-select-search');
    const panel = box.querySelector('.vf-select-panel');
    const list = box.querySelector('[data-vf-select-list]');
    list.id = `vf-select-list-${++nextId}`;
    button.setAttribute('aria-controls', list.id);
    search.setAttribute('aria-controls', list.id);
    const ariaLabelledBy = select.getAttribute('aria-labelledby');
    const labelText = (item) => {
      const copy = item.cloneNode(true);
      copy.querySelectorAll('select,.vf-search-select').forEach((node) => node.remove());
      return copy.textContent.trim();
    };
    if (ariaLabelledBy) button.setAttribute('aria-labelledby', ariaLabelledBy);
    else button.setAttribute('aria-label', select.getAttribute('aria-label')
      || Array.from(select.labels || []).map(labelText).filter(Boolean).join(' ') || select.name || '선택');

    const items = () => Array.from(list.querySelectorAll('.vf-select-option'));
    const positionPanel = () => {
      if (!box.classList.contains('open')) return;
      const rect = button.getBoundingClientRect();
      if (rect.bottom < 0 || rect.top > window.innerHeight) { close(box); return; }
      const below = window.innerHeight - rect.bottom - 13;
      const above = rect.top - 13;
      const up = panel.offsetHeight > below && above > below;
      const available = Math.max(60, up ? above : below);
      list.style.maxHeight = `${Math.max(40, Math.min(220, available - (search.hidden ? 14 : search.offsetHeight + 21)))}px`;
      panel.style.left = `${rect.left}px`;
      panel.style.width = `${rect.width}px`;
      panel.style.top = `${up ? Math.max(8, rect.top - panel.offsetHeight - 5) : rect.bottom + 5}px`;
      box.classList.toggle('opens-up', up);
    };
    const focusItem = (index) => {
      const options = items();
      if (!options.length) return;
      const item = options[Math.max(0, Math.min(options.length - 1, index))];
      item.focus({ preventScroll: true });
      item.scrollIntoView({ block: 'nearest' });
    };
    const render = () => {
      const focusedOption = panel.contains(document.activeElement)
        ? document.activeElement.closest('.vf-select-option')?.dataset.index : null;
      const options = availableOptions(select);
      const searchable = options.length >= 10;
      search.hidden = !searchable;
      if (!searchable) search.value = '';
      box.classList.toggle('is-searchable', searchable);
      label.textContent = selectedLabel(select);
      button.title = label.textContent;
      button.disabled = select.matches(':disabled');
      button.setAttribute('aria-required', String(select.required));
      box.hidden = select.hidden;
      if (button.disabled || box.hidden) close(box);
      const keyword = search.value.trim().toLocaleLowerCase();
      const content = document.createDocumentFragment();
      options.forEach((option) => {
        const text = option.textContent.trim();
        if (keyword && !text.toLocaleLowerCase().includes(keyword)) return;
        const item = document.createElement('button');
        item.type = 'button';
        item.tabIndex = -1;
        item.className = 'vf-select-option';
        item.textContent = text;
        item.title = text;
        item.dataset.value = option.value;
        item.dataset.index = String(option.index);
        item.setAttribute('role', 'option');
        item.setAttribute('aria-selected', String(option.selected));
        content.appendChild(item);
      });
      if (!content.childNodes.length) {
        const empty = document.createElement('div');
        empty.className = 'vf-select-empty';
        empty.setAttribute('role', 'status');
        empty.textContent = '검색 결과가 없습니다.';
        content.appendChild(empty);
      }
      list.replaceChildren(content);
      if (focusedOption != null && box.classList.contains('open')) {
        const nextFocus = items().find((item) => item.dataset.index === focusedOption)
          || (!search.hidden ? search : items()[0]) || button;
        nextFocus.focus({ preventScroll: true });
      }
      positionPanel();
    };
    const open = (focusList = false, last = false) => {
      if (button.disabled) return;
      closeAll(box);
      search.value = '';
      render();
      box.classList.add('open');
      activeBox = box;
      button.setAttribute('aria-expanded', 'true');
      // A body portal avoids clipping by scrollable modal bodies and table cells.
      let zIndex = 110;
      for (let parent = box.parentElement; parent; parent = parent.parentElement) {
        zIndex = Math.max(zIndex, (Number(getComputedStyle(parent).zIndex) || 0) + 20);
      }
      document.body.appendChild(panel);
      panel.classList.add('open');
      panel.style.position = 'fixed';
      panel.style.bottom = 'auto';
      panel.style.zIndex = String(zIndex);
      positionPanel();
      if (!focusList && !search.hidden) search.focus({ preventScroll: true });
      else {
        const selected = items().findIndex((item) => item.getAttribute('aria-selected') === 'true');
        focusItem(last ? items().length - 1 : Math.max(0, selected));
      }
    };
    const choose = (item) => {
      if (!item || button.disabled) return;
      select.selectedIndex = Number(item.dataset.index);
      select.dispatchEvent(new Event('change', { bubbles: true }));
      render();
      close(box, true);
    };
    button.addEventListener('click', () => box.classList.contains('open') ? close(box) : open());
    button.addEventListener('keydown', (event) => {
      if (!['ArrowDown', 'ArrowUp', 'Home', 'End'].includes(event.key)) return;
      event.preventDefault();
      open(true, event.key === 'ArrowUp' || event.key === 'End');
    });
    search.addEventListener('input', render);
    list.addEventListener('click', (event) => choose(event.target.closest('.vf-select-option')));
    const onKeydown = (event) => {
      if (!box.classList.contains('open') || event.target === button) return;
      const options = items();
      const index = options.indexOf(document.activeElement);
      if (event.key === 'ArrowDown' || event.key === 'ArrowUp') {
        event.preventDefault();
        focusItem(index < 0 ? (event.key === 'ArrowDown' ? 0 : options.length - 1)
          : index + (event.key === 'ArrowDown' ? 1 : -1));
      } else if (event.target !== search && (event.key === 'Home' || event.key === 'End')) {
        event.preventDefault();
        focusItem(event.key === 'Home' ? 0 : options.length - 1);
      } else if (event.key === 'Enter' || (event.key === ' ' && event.target !== search)) {
        event.preventDefault();
        choose(options[index < 0 ? 0 : index]);
      } else if (event.key === 'Tab') close(box);
    };
    box.addEventListener('keydown', onKeydown);
    panel.addEventListener('keydown', onKeydown);
    const onFocusout = (event) => {
      // During an internal focus transfer activeElement can briefly be body.
      const next = event.relatedTarget;
      if (next && (box.contains(next) || panel.contains(next))) return;
      queueMicrotask(() => {
        if (!box.contains(document.activeElement) && !panel.contains(document.activeElement)) close(box);
      });
    };
    box.addEventListener('focusout', onFocusout);
    panel.addEventListener('focusout', onFocusout);
    select.addEventListener('change', render);
    select.addEventListener('worksystem:query-filter-sync', render);
    select.addEventListener('focus', () => button.focus({ preventScroll: true }));
    select.addEventListener('invalid', () => button.focus({ preventScroll: true }));
    select.form?.addEventListener('reset', () => queueMicrotask(render));
    new MutationObserver(render).observe(select, { childList: true, subtree: true, attributes: true,
      attributeFilter: ['disabled', 'hidden', 'selected', 'label', 'value', 'required'], characterData: true });
    states.set(select, { box, render });
    boxes.set(box, { panel, positionPanel });
    render();
  };
  const init = () => document.querySelectorAll('select').forEach(enhanceSelect);
  window.worksystemSearchSelectInit = init;
  document.addEventListener('click', (event) => {
    if (!event.target.closest('.vf-search-select,.vf-select-panel')) closeAll();
  });
  // Handle the child picker before document-level modal Escape handlers run.
  window.addEventListener('keydown', (event) => {
    if (event.key !== 'Escape') return;
    const box = activeBox;
    if (!box) return;
    event.preventDefault();
    event.stopImmediatePropagation();
    close(box, true);
  }, true);
  const reposition = () => { if (activeBox) boxes.get(activeBox)?.positionPanel(); };
  window.addEventListener('resize', reposition);
  window.addEventListener('scroll', reposition, true);
  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', init); else init();
  window.addEventListener('worksystem:content-updated', init);
  new MutationObserver((records) => {
    if (activeBox && !activeBox.isConnected) close(activeBox);
    if (records.some((record) => Array.from(record.addedNodes).some((node) => node.nodeType === 1
      && (node.matches('select') || node.querySelector('select'))))) init();
  }).observe(document.documentElement, { childList: true, subtree: true });
})();
