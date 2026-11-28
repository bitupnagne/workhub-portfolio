/**
 * worksystem-personnel.js — 인사이동 화면(전역 위임, soft-nav 안전).
 *  - 조직원 선택 시 현재부서 표시
 *  - 인사이동 확정 시 재확인 모달("OOO을 [현재팀]→[대상팀]으로 yyyy년 mm월 dd일에 인사발령 시키겠습니까?")
 *  - 발령일 수정 / 이력 삭제('삭제합니다' 타이핑) 모달
 */
(() => {
  const fmtKDate = (iso) => {
    const m = /^(\d{4})-(\d{2})-(\d{2})/.exec(iso || '');
    return m ? (m[1] + '년 ' + Number(m[2]) + '월 ' + Number(m[3]) + '일') : iso;
  };

  // 인사이동: 발령/날짜수정/삭제 시 전체 새로고침 대신 '이력(#ptHistory)' 영역만 AJAX 교체.
  const ptToast = (msg, isErr) => {
    if (!msg) return;
    const content = document.querySelector('.content');
    if (!content) return;
    const b = document.createElement('div');
    b.className = 'banner ' + (isErr ? 'err' : 'ok');
    b.textContent = msg;
    content.prepend(b);
    setTimeout(() => { b.remove(); }, 4000);
  };
  const closeRegister = () => {
    const modal = document.getElementById('ptRegisterBg');
    if (modal) modal.hidden = true;
    document.querySelector('[data-pt-register-open]')?.focus({ preventScroll: true });
  };
  const registrationError = (message) => {
    const banner = document.getElementById('ptRegisterError');
    if (banner) { banner.textContent = message || ''; banner.hidden = !message; }
  };
  const closePtModals = () => {
    ['ptDateBg', 'ptDeleteBg'].forEach((id) => { const el = document.getElementById(id); if (el) el.style.display = 'none'; });
  };
  const ptAjaxSubmit = async (form) => {
    if (form.dataset.ptSubmitting === 'true') return;
    const board = document.getElementById('ptHistory');
    const registration = form.id === 'transferForm';
    const fail = (message) => registration ? registrationError(message) : ptToast(message, true);
    const submitters = [...form.querySelectorAll('[type="submit"]')];
    form.dataset.ptSubmitting = 'true';
    submitters.forEach(button => { button.disabled = true; });
    if (registration) registrationError('');
    try {
      const resp = await fetch(form.action, {
        method: 'POST', body: new FormData(form), credentials: 'same-origin',
        headers: { 'X-Requested-With': 'fetch' },
      });
      const html = await resp.text();
      if (!resp.ok) { fail('처리하지 못했습니다. 잠시 후 다시 시도하세요.'); return; }
      const doc = new DOMParser().parseFromString(html, 'text/html');
      const fresh = doc.getElementById('ptHistory');
      const okB = doc.querySelector('.content > .banner.ok');
      const errB = doc.querySelector('.content > .banner.err');
      if (errB) { fail(errB.textContent.trim()); return; }
      if (!fresh || !okB) { fail('처리 결과를 확인하지 못했습니다. 새로고침 후 이력을 확인해 주세요.'); return; }
      if (board) board.replaceWith(fresh);
      ptToast(okB.textContent.trim(), false);
      closePtModals();
      if (registration) {
        form.reset();
        // 날짜 피커는 원본을 hidden으로 바꾸므로 reset만으로 선택값이 지워지지 않는다.
        form.querySelector('#ptDate').value = '';
        form.querySelectorAll('select,input[type="date"],[data-dp-ready]').forEach(control => control.dispatchEvent(new Event('change', { bubbles: true })));
        const ct = document.getElementById('ptCurrentTeam');
        if (ct) ct.textContent = '직원을 선택하세요.';
        closeRegister();
      }
      document.dispatchEvent(new Event('content-updated'));
    } catch (err) {
      fail('처리 중 오류가 발생했습니다.');
    } finally {
      delete form.dataset.ptSubmitting;
      submitters.forEach(button => { button.disabled = false; });
    }
  };
  document.addEventListener('click', event => {
    if (event.target.closest('[data-pt-register-open]')) {
      const modal = document.getElementById('ptRegisterBg');
      if (modal) {
        modal.hidden = false;
        modal.querySelector('button,input:not([type="hidden"]),select')?.focus();
      }
    }
    if (event.target.closest('[data-pt-register-close]')) closeRegister();
  });
  document.addEventListener('keydown', event => {
    const modal = document.getElementById('ptRegisterBg');
    if (event.key !== 'Escape' || !modal || modal.hidden) return;
    const child = document.querySelector('.ws-dp-pop.open,.vf-search-select.open,.vf-common-modal:not([hidden])');
    if (child && child.getClientRects().length) return;
    closeRegister();
  }, true);
  // 날짜수정·삭제 폼: 캡처 단계에서 가로채 soft-submit보다 먼저 처리(이력만 교체).
  document.addEventListener('submit', (e) => {
    const form = e.target.closest('form[data-pt-ajax]');
    if (!form || form.id === 'transferForm') return;
    e.preventDefault();
    ptAjaxSubmit(form);
  }, true);

  // 조직원 선택 → 현재부서
  document.addEventListener('change', (e) => {
    if (!e.target.closest('#ptUser')) return;
    const sel = e.target.closest('#ptUser');
    const opt = sel.options[sel.selectedIndex];
    const cur = document.getElementById('ptCurrentTeam');
    if (cur) cur.textContent = (opt && opt.getAttribute('data-team')) || '직원을 선택하세요.';
  });

  // 인사이동 확정 → 재확인
  document.addEventListener('submit', async (e) => {
    const form = e.target.closest('#transferForm');
    if (!form) return;
    if (e.defaultPrevented) return;            // require-fields 가 막았으면 패스
    if (form.dataset.vfConfirmed === 'true') { delete form.dataset.vfConfirmed; return; }
    e.preventDefault();
    const userSel = form.querySelector('#ptUser');
    const opt = userSel.options[userSel.selectedIndex];
    const name = (opt && opt.getAttribute('data-name')) || '해당 조직원';
    const fromTeam = (opt && opt.getAttribute('data-team')) || '현재 팀';
    const toSel = form.querySelector('#ptToTeam');
    const toTeam = toSel.options[toSel.selectedIndex] ? toSel.options[toSel.selectedIndex].textContent : '대상 팀';
    const date = form.querySelector('#ptDate').value;
    const msg = '‘' + name + '’ 을(를) [' + fromTeam + '] 에서 → [' + toTeam + '] 으로 '
      + fmtKDate(date) + '에 인사발령 시키겠습니까?';
    const ok = window.vfConfirm ? await window.vfConfirm(msg, { title: '인사발령 확인', okText: '발령' }) : confirm(msg);
    if (!ok) return;
    ptAjaxSubmit(form);   // 전체 새로고침 대신 이력만 갱신
  });

  // 모달 열기/닫기
  document.addEventListener('click', (e) => {
    const ed = e.target.closest('[data-pt-editdate]');
    if (ed) {
      const bg = document.getElementById('ptDateBg');
      const f = document.getElementById('ptDateForm');
      if (bg && f) {
        f.setAttribute('action', '/admin/personnel/transfer/' + ed.getAttribute('data-pt-editdate') + '/date');
        const deleteButton = document.getElementById('ptDateDelete');
        if (deleteButton) {
          deleteButton.dataset.ptDelete = ed.dataset.ptEditdate;
          deleteButton.dataset.ptName = ed.dataset.ptName || '';
          deleteButton.dataset.ptYear = ed.dataset.ptYear || '';
        }
        const di = f.querySelector('input[name="effectiveDate"]');
        if (di) {
          di.value = ed.getAttribute('data-pt-date') || '';
          di.dispatchEvent(new Event('change', { bubbles: true }));
        }
        bg.style.display = 'flex';
      }
      return;
    }
    const del = e.target.closest('[data-pt-delete]');
    if (del) {
      const bg = document.getElementById('ptDeleteBg');
      const f = document.getElementById('ptDeleteForm');
      if (bg && f) {
        const editor = document.getElementById('ptDateBg');
        if (editor) editor.style.display = 'none';
        f.setAttribute('action', '/admin/personnel/transfer/' + del.getAttribute('data-pt-delete') + '/delete');
        const nm = document.getElementById('ptDelName');
        if (nm) nm.textContent = del.getAttribute('data-pt-name') || '';
        const yr = document.getElementById('ptDelYear');
        if (yr && del.getAttribute('data-pt-year')) yr.value = del.getAttribute('data-pt-year');
        const ci = f.querySelector('input[name="confirmText"]'); if (ci) ci.value = '';
        bg.style.display = 'flex';
      }
      return;
    }
    if (e.target.closest('#ptDateCancel')) { const b = document.getElementById('ptDateBg'); if (b) b.style.display = 'none'; }
    if (e.target.closest('#ptDeleteCancel')) {
      const b = document.getElementById('ptDeleteBg'); if (b) b.style.display = 'none';
      const editor = document.getElementById('ptDateBg'); if (editor) editor.style.display = 'flex';
      document.getElementById('ptDateDelete')?.focus({ preventScroll: true });
    }
  });
})();
