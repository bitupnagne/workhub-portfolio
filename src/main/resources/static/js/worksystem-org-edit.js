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

  const initOrgEdit = () => {
    const btn = document.getElementById('reorderToggle');
    const list = document.getElementById('memberList');
    const form = document.getElementById('memberOrderForm');
    if (btn && list && form && btn.dataset.orgEditReady !== 'true') {
      btn.dataset.orgEditReady = 'true';
      const cancel = document.getElementById('reorderCancel');
      let originalOrder = [];
      const finishEditing = () => {
        btn.dataset.on = '0';
        btn.textContent = '수정하기';
        btn.classList.remove('primary');
        list.classList.remove('reordering');
        list.querySelectorAll('.mo-up,.mo-down,.mo-remove').forEach((control) => { control.disabled = true; });
        if (cancel) cancel.hidden = true;
      };
      if (cancel) cancel.addEventListener('click', () => {
        originalOrder.forEach((row) => list.appendChild(row));
        finishEditing();
      });
      btn.addEventListener('click', () => {
        if (btn.dataset.on === '1') {
          form.requestSubmit();
          return;
        }
        originalOrder = Array.from(list.children);
        btn.dataset.on = '1';
        btn.textContent = '수정';
        btn.classList.add('primary');
        if (cancel) cancel.hidden = false;
        list.classList.add('reordering');
        list.querySelectorAll('.mo-up,.mo-down,.mo-remove').forEach((control) => { control.disabled = false; });
      });
      list.addEventListener('click', (event) => {
        const remove = event.target.closest('.mo-remove');
        if (remove) {
          if (remove.disabled) return;
          Promise.resolve(window.vfConfirm
            ? window.vfConfirm('이 직원을 현재 조직에서 해제하시겠습니까?', { okText: '해제', danger: true })
            : true)
            .then((ok) => {
              if (ok) submitRemove(form, remove);
            });
          return;
        }
        const control = event.target.closest('.mo-up,.mo-down');
        if (!control || control.disabled) return;
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
