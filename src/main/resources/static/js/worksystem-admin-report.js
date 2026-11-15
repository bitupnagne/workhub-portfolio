(() => {
  const init = () => document.querySelectorAll('.team-report-acc').forEach((section) => {
    if (section.dataset.reportAccReady === 'true') return;
    section.dataset.reportAccReady = 'true';
    const teamId = section.dataset.teamId;
    const key = `vf-admin-team-open-${teamId}`;
    const stored = localStorage.getItem(key);
    if (stored !== null) {
      section.open = stored === 'open';
    }
    section.addEventListener('toggle', () => {
      localStorage.setItem(key, section.open ? 'open' : 'closed');
    });
    section.querySelectorAll('.section-tools button').forEach((button) => {
      button.addEventListener('click', (event) => event.stopPropagation());
    });
  });
  init();
  window.addEventListener('worksystem:content-updated', init);
})();
