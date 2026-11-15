(function () {
  function docDates(form) {
    var workDate = form.querySelector('[name="workDate"]');
    var start = form.querySelector('[name="startDate"]');
    var end = form.querySelector('[name="endDate"]');
    var startDate = workDate && workDate.value ? workDate.value : (start && start.value ? start.value : '');
    var endDate = workDate && workDate.value ? workDate.value : (end && end.value ? end.value : startDate);
    return { startDate: startDate, endDate: endDate };
  }

  function markConfirmed(form) { form.dataset.routeWarningConfirmed = 'true'; return true; }

  async function confirmWarning(form) {
    if (form.dataset.routeWarningConfirmed === 'true') return true;
    var type = form.dataset.approvalDocType;
    if (!type) return markConfirmed(form);
    var dates = docDates(form);
    if (!dates.startDate) return markConfirmed(form);
    var params = new URLSearchParams({ type: type, startDate: dates.startDate, endDate: dates.endDate || dates.startDate });
    var response;
    try {
      response = await fetch('/api/hr/approval-route/warning?' + params.toString(), {
        headers: { Accept: 'application/json' },
        credentials: 'same-origin'
      });
    } catch (error) {
      return markConfirmed(form);
    }
    if (!response.ok) return markConfirmed(form);
    var payload;
    try { payload = await response.json(); } catch (error) { return markConfirmed(form); }
    if (!payload || !payload.warning) return markConfirmed(form);
    var message = payload.warning + '\n계속 상신하시겠습니까?';
    var ok = window.vfConfirm
      ? await window.vfConfirm(message, { okText: '상신', danger: false })
      : window.confirm(message);
    if (ok) form.dataset.routeWarningConfirmed = 'true';
    return ok;
  }

  document.addEventListener('submit', function (event) {
    var form = event.target.closest('[data-approval-route-warning]');
    if (!form) return;
    if (form.dataset.routeWarningConfirmed === 'true') return;
    event.preventDefault();
    confirmWarning(form).then(function (ok) {
      if (ok) form.requestSubmit();
    });
  });
})();
