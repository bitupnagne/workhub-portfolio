/* The list's explicit edit action opens the existing form editor in its modal. */
(() => {
  document.addEventListener('click', (event) => {
    const trigger = event.target.closest('[data-grade-edit][data-modal-open]');
    if (!trigger) return;
    requestAnimationFrame(() => {
      const modal = document.querySelector(trigger.getAttribute('data-modal-open'));
      const form = modal?.querySelector('form[data-edit-form]');
      if (!modal || modal.hidden || !form || form.dataset.editState === 'editing') return;
      modal.querySelector('[data-edit-start]')?.click();
    });
  });
})();
