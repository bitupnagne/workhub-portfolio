/* [OT] 시간외 근무 — 미신청 안내(접속 시 1회)와 주간 한도 확인. */
(() => {
  if (window.__worksystemOvertime) return;
  window.__worksystemOvertime = true;

  /* ── 신청하지 않은 시간외 근무 안내 ──
     접속했을 때 한 번만 묻는다. 같은 건수로는 그 브라우저 탭에서 다시 묻지 않고,
     건수가 늘면(새로 생긴 근무) 다시 알린다. */
  const noticeKey = 'ws.overtimeNotice';
  const announce = async () => {
    const marker = document.getElementById('overtimeNotice');
    const count = Number(marker?.dataset.count || 0);
    if (!count || typeof window.vfConfirm !== 'function') return;
    if (window.location.pathname.startsWith('/hr/overtime')) return; // 이미 그 화면에 있다
    let seen = 0;
    try { seen = Number(window.sessionStorage.getItem(noticeKey) || 0); } catch { /* 저장소를 못 쓰면 매번 안내 */ }
    if (seen >= count) return;
    try { window.sessionStorage.setItem(noticeKey, String(count)); } catch { /* ignore */ }
    const ok = await window.vfConfirm(
      '신청하지 않은 시간외 근무가 ' + count + '건 있어요.\n이번 달이 끝나기 전에 신청해야 인정됩니다. 지금 확인할까요?',
      { title: '시간외 근무', okText: '확인하러 가기', cancelText: '나중에' });
    if (!ok) return;
    if (typeof window.worksystemSoftNavigate === 'function') window.worksystemSoftNavigate('/hr/overtime');
    else window.location.href = '/hr/overtime';
  };
  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', announce, { once: true });
  else announce();

  /* ── 주간 한도(기본 52시간) 확인 ──
     막지 않는다. 넘는다는 것을 알리고 그래도 올릴지 한 번 묻는다. */
  document.addEventListener('submit', async (event) => {
    const form = event.target.closest?.('form[data-overtime-week-check]');
    if (!form || form.dataset.weekConfirmed === 'true') return;
    event.preventDefault();
    event.stopImmediatePropagation();
    const value = (name) => form.querySelector('[name="' + name + '"]')?.value || '';
    let warning = null;
    try {
      const params = new URLSearchParams({
        workDate: value('workDate'), startTime: value('startTime'),
        endTime: value('endTime')
      });
      const response = await fetch('/hr/overtime/week-check?' + params.toString(), {
        headers: { Accept: 'application/json' }, credentials: 'same-origin'
      });
      if (response.ok) warning = (await response.json()).warning || null;
    } catch { /* 확인에 실패하면 신청은 그대로 진행한다 — 서버 검증은 따로 있다 */ }
    if (warning) {
      const ok = window.vfConfirm
        ? await window.vfConfirm(warning + '\n그래도 신청하시겠습니까?', { title: '주간 근로시간 초과', okText: '신청' })
        : window.confirm(warning + '\n그래도 신청하시겠습니까?');
      if (!ok) return;
    }
    form.dataset.weekConfirmed = 'true';
    if (form.requestSubmit) form.requestSubmit(event.submitter || undefined);
    else form.submit();
  }, true);

  /* ── 자동 휴게 미리보기 ── 서버(OvertimeCalculator.autoBreakMinutes)와 같은 규칙.
     기준 근로(4시간·8시간)를 채우고 바로 퇴근하면 빼지 않고, 그 뒤 더 머문 시간부터 휴게로 본다. */
  const autoBreak = (span) => {
    if (span <= 240) return 0;
    if (span < 270) return span - 240;
    if (span < 510) return 30;
    if (span < 540) return span - 480;
    return 60;
  };
  const minutesText = (m) => {
    const h = Math.floor(m / 60), r = m % 60;
    return h === 0 ? r + '분' : (r === 0 ? h + '시간' : h + '시간 ' + r + '분');
  };
  const showBreak = (form) => {
    const out = form?.querySelector('[data-overtime-break-result]');
    if (!out) return;
    const parse = (name) => {
      const m = /^([0-9]{2}):([0-9]{2})/.exec(form.querySelector('[name="' + name + '"]')?.value || '');
      return m ? Number(m[1]) * 60 + Number(m[2]) : null;
    };
    const start = parse('startTime'), end = parse('endTime');
    if (start == null || end == null || start === end) { out.textContent = ''; return; }
    const span = end > start ? end - start : end + 1440 - start;
    const rest = autoBreak(span);
    out.textContent = rest === 0
      ? '이 신청은 ' + minutesText(span) + ' 전부 인정 대상입니다.'
      : '이 신청은 ' + minutesText(span) + ' 중 휴게 ' + minutesText(rest) + '을 빼고 ' + minutesText(span - rest) + '입니다.';
  };
  const showAllBreaks = () => document.querySelectorAll('form[data-overtime-week-check]').forEach(showBreak);
  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', showAllBreaks, { once: true });
  else showAllBreaks();
  document.addEventListener('worksystem:content-updated', showAllBreaks);
  document.addEventListener('input', (event) => showBreak(event.target.closest?.('form[data-overtime-week-check]')));
  document.addEventListener('change', (event) => showBreak(event.target.closest?.('form[data-overtime-week-check]')));

  // 값을 바꾸면 다시 확인받는다.
  document.addEventListener('change', (event) => {
    const form = event.target.closest?.('form[data-overtime-week-check]');
    if (form) delete form.dataset.weekConfirmed;
  });
})();
