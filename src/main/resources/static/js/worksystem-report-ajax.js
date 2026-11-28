(() => {
  const contentSelector = '.content';
  const isReportPost = (form) => {
    if (!form || (form.method || '').toLowerCase() !== 'post') return false;
    const action = new URL(form.action, window.location.href);
    return action.origin === window.location.origin && action.pathname.startsWith('/report/');
  };

  const showError = (message) => {
    const content = document.querySelector(contentSelector);
    if (!content) return;
    const banner = document.createElement('div');
    banner.className = 'banner err';
    banner.textContent = message;
    content.prepend(banner);
  };

  const captureReportRows = (content) => {
    const rows = content ? Array.from(content.querySelectorAll('[data-report-row]')) : [];
    return new Map(rows.map((row) => [row.dataset.reportRow, row.getBoundingClientRect()]));
  };

  const animateReportRows = (before) => {
    if (!before || before.size === 0) return;
    const content = document.querySelector(contentSelector);
    if (!content) return;
    content.querySelectorAll('[data-report-row]').forEach((row) => {
      const prev = before.get(row.dataset.reportRow);
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

  document.addEventListener('submit', async (event) => {
    const form = event.target.closest('form');
    if (!isReportPost(form)) return;
    event.preventDefault();

    const submitter = event.submitter;
    const formData = new FormData(form);
    if (submitter && submitter.name && !formData.has(submitter.name)) {
      formData.append(submitter.name, submitter.value);
    }
    if (submitter) submitter.disabled = true;

    try {
      const response = await fetch(form.action, {
        method: 'POST',
        body: formData,
        credentials: 'same-origin',
        headers: { 'X-Requested-With': 'fetch' },
      });
      const html = await response.text();
      if (!response.ok) throw new Error(`HTTP ${response.status}`);
      const doc = new DOMParser().parseFromString(html, 'text/html');
      const nextContent = doc.querySelector(contentSelector);
      const currentContent = document.querySelector(contentSelector);
      if (!nextContent || !currentContent) {
        window.location.assign(response.url || window.location.href);
        return;
      }
      const beforeRows = captureReportRows(currentContent);
      currentContent.replaceWith(nextContent);
      animateReportRows(beforeRows);
      if (doc.title) document.title = doc.title;
      if (response.url && response.url !== window.location.href) {
        window.history.replaceState({}, '', response.url);
      }
      window.dispatchEvent(new CustomEvent('worksystem:content-updated'));
    } catch (error) {
      showError('요청을 처리하지 못했습니다. 다시 시도해 주세요.');
    } finally {
      if (submitter) submitter.disabled = false;
    }
  });
})();
