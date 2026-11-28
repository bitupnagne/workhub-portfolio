(() => {
  const animateMove = (list, mutate) => {
    const rows = Array.from(list.querySelectorAll('.member-row'));
    const before = new Map(rows.map((row) => [row, row.getBoundingClientRect()]));
    mutate();
    rows.forEach((row) => {
      const prev = before.get(row);
      if (!prev) return;
      const next = row.getBoundingClientRect();
      const dy = prev.top - next.top;
      if (!dy) return;
      row.style.transform = `translateY(${dy}px)`;
      row.style.transition = 'transform 0s';
      requestAnimationFrame(() => {
        row.style.transition = 'transform .22s ease';
        row.style.transform = '';
      });
    });
  };

  const submitRemove = (form, remove) => {
    const removeForm = document.createElement('form');
    removeForm.method = 'post';
    removeForm.action = remove.formAction || remove.dataset.removeUrl;
    const csrf = form.querySelector('input[type="hidden"][name="_csrf"]');
    if (csrf) {
      const token = document.createElement('input');
      token.type = 'hidden';
      token.name = csrf.name;
      token.value = csrf.value;
      removeForm.appendChild(token);
    }
    document.body.appendChild(removeForm);
    removeForm.requestSubmit();
  };

  // 인원 순서 — 보기/수정 모드 없이 바로 옮기고, 순서가 바뀌었을 때만 '되돌리기·순서 저장'이 보인다.
  const initOrgEdit = () => {
    const save = document.getElementById('reorderSave');
    const list = document.getElementById('memberList');
    const form = document.getElementById('memberOrderForm');
    if (save && list && form && save.dataset.orgEditReady !== 'true') {
      save.dataset.orgEditReady = 'true';
      const cancel = document.getElementById('reorderCancel');
      const originalOrder = Array.from(list.children);
      const sync = () => {
        const now = Array.from(list.children);
        const dirty = now.some((row, i) => row !== originalOrder[i]);
        save.hidden = !dirty;
        if (cancel) cancel.hidden = !dirty;
        list.classList.toggle('reordering', dirty);
      };
      if (cancel) cancel.addEventListener('click', () => {
        originalOrder.forEach((row) => list.appendChild(row));
        sync();
      });
      save.addEventListener('click', () => form.requestSubmit());
      list.addEventListener('click', (event) => {
        const remove = event.target.closest('.mo-remove');
        if (remove) {
          Promise.resolve(window.vfConfirm
            ? window.vfConfirm('이 직원을 현재 조직에서 해제하시겠습니까?', { okText: '해제', danger: true })
            : true)
            .then((ok) => {
              if (ok) submitRemove(form, remove);
            });
          return;
        }
        const control = event.target.closest('.mo-up,.mo-down');
        if (!control) return;
        const row = control.closest('.member-row');
        if (!row) return;
        animateMove(list, () => {
          if (control.classList.contains('mo-up')) {
            const prev = row.previousElementSibling;
            if (prev && prev.classList.contains('member-row')) list.insertBefore(row, prev);
          } else {
            const next = row.nextElementSibling;
            if (next && next.classList.contains('member-row')) list.insertBefore(next, row);
          }
        });
        sync();
      });
    }

    const toggle = document.getElementById('wtToggle');
    const target = document.getElementById('wtTarget');
    const wtForm = document.getElementById('wtForm');
    if (toggle && target && wtForm && toggle.dataset.weekTargetReady !== 'true') {
      toggle.dataset.weekTargetReady = 'true';
      toggle.addEventListener('change', () => {
        target.value = toggle.checked ? 'true' : 'false';
      });
      wtForm.addEventListener('submit', () => { target.value = toggle.checked ? 'true' : 'false'; });
    }

  };

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', initOrgEdit);
  } else {
    initOrgEdit();
  }
  window.addEventListener('worksystem:content-updated', initOrgEdit);
})();
