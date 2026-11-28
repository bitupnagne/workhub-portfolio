(() => {
  const initNoticeForm = () => {
    const box = document.querySelector('[data-poll-box]');
    const toggle = document.querySelector('[data-poll-toggle]');
    const list = document.querySelector('[data-poll-opts]');
    if (toggle && box && toggle.dataset.pollToggleReady !== 'true') {
      toggle.dataset.pollToggleReady = 'true';
      const sync = () => { box.classList.toggle('is-hidden', !toggle.checked); };
      toggle.addEventListener('change', sync);
      sync();
    }
    if (list && list.dataset.pollOptsReady !== 'true') {
      list.dataset.pollOptsReady = 'true';
      const form = list.closest('form');
      let savedOptions = null;
      if (form?.hasAttribute('data-edit-form')) {
        form.addEventListener('worksystem:edit-start', () => {
          savedOptions = [...list.childNodes].map((node) => ({
            node,
            values: node.querySelectorAll ? [...node.querySelectorAll('input')].map((input) => ({ input, value: input.value })) : [],
          }));
        });
        form.addEventListener('worksystem:edit-cancel', () => {
          if (!savedOptions) return;
          list.replaceChildren(...savedOptions.map((option) => option.node));
          savedOptions.forEach((option) => option.values.forEach(({ input, value }) => { input.value = value; }));
        });
      }
      const add = document.querySelector('[data-poll-add]');
      if (add && add.dataset.pollAddReady !== 'true') {
        add.dataset.pollAddReady = 'true';
        add.addEventListener('click', () => {
          const row = document.createElement('div');
          row.className = 'poll-opt is-new';
          row.innerHTML = '<input name="pollOptions" maxlength="200" placeholder="항목 '
            + (list.querySelectorAll('.poll-opt').length + 1)
            + '"><button type="button" class="rbtn del" data-poll-del>-</button>';
          list.appendChild(row);
          row.addEventListener('animationend', () => row.classList.remove('is-new'), { once: true });
          row.querySelector('input')?.focus();
        });
      }
      list.addEventListener('click', (event) => {
        const del = event.target.closest('[data-poll-del]');
        if (del && list.querySelectorAll('.poll-opt').length > 1) {
          del.closest('.poll-opt')?.remove();
        }
      });
    }
  };

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', initNoticeForm);
  } else {
    initNoticeForm();
  }
  window.addEventListener('worksystem:content-updated', initNoticeForm);
})();
