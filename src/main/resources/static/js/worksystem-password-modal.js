/* [UX34/35] 비밀번호 변경 모달 — 현재 비밀번호 [검증](서버 확인) 후 새 비밀번호 입력 활성화,
   입력할 때마다 일치/불일치 실시간 표시. data-pw-force(초기 비밀번호 사용 중)면
   닫을 수 없는 강제 모달로 자동 표시된다. 제출은 일반 POST(/mypage/password). */
(() => {
  const modal = document.querySelector('[data-pw-modal]');
  if (!modal) return;
  const form = modal.querySelector('form');
  const cur = modal.querySelector('[data-pw-current]');
  const nw = modal.querySelector('[data-pw-new]');
  const cf = modal.querySelector('[data-pw-confirm]');
  const submit = modal.querySelector('[data-pw-submit]');
  const verifyBtn = modal.querySelector('[data-pw-verify]');
  const verifyMsg = modal.querySelector('[data-pw-verify-msg]');
  const matchMsg = modal.querySelector('[data-pw-match-msg]');
  const forced = modal.hasAttribute('data-pw-force');
  let verified = false;

  const setMsg = (el, text, ok) => {
    if (!el) return;
    el.textContent = text;
    el.classList.toggle('ok', !!ok && text !== '');
    el.classList.toggle('err', !ok && text !== '');
  };

  const refresh = () => {
    nw.disabled = !verified;
    cf.disabled = !verified;
    const match = nw.value !== '' && nw.value === cf.value;
    if (nw.value === '' && cf.value === '') setMsg(matchMsg, '', true);
    else if (match) setMsg(matchMsg, '✓ 새 비밀번호가 일치합니다.', true);
    else setMsg(matchMsg, '✗ 새 비밀번호가 일치하지 않습니다.', false);
    submit.disabled = !(verified && nw.value.length >= 9 && match);
  };

  const reset = () => {
    verified = false;
    cur.readOnly = false;
    cur.value = ''; nw.value = ''; cf.value = '';
    setMsg(verifyMsg, '', true);
    setMsg(matchMsg, '', true);
    refresh();
  };

  const close = () => { if (!forced) modal.hidden = true; };

  document.addEventListener('click', (event) => {
    if (event.target.closest('[data-pw-open]')) {
      event.preventDefault();
      reset();
      modal.hidden = false;
      cur.focus();
      return;
    }
    if (event.target.closest('[data-pw-close]')) {
      event.preventDefault();
      close();
    }
  });
  document.addEventListener('keydown', (event) => {
    if (event.key === 'Escape') close();
  });

  verifyBtn.addEventListener('click', async () => {
    if (!cur.value) {
      setMsg(verifyMsg, '현재 비밀번호를 입력하세요.', false);
      cur.focus();
      return;
    }
    verifyBtn.disabled = true;
    try {
      const fd = new FormData();
      const csrf = form.querySelector('input[type="hidden"]');
      if (csrf && csrf.name) fd.append(csrf.name, csrf.value);
      fd.append('currentPassword', cur.value);
      const res = await fetch(form.getAttribute('data-pw-verify-url'), {
        method: 'POST', body: fd, credentials: 'same-origin',
      });
      const data = res.ok ? await res.json() : { ok: false };
      verified = !!data.ok;
      setMsg(verifyMsg,
        verified ? '✓ 확인되었습니다. 새 비밀번호를 입력하세요.' : '✗ 현재 비밀번호가 올바르지 않습니다.',
        verified);
      if (verified) { cur.readOnly = true; refresh(); nw.focus(); }
    } catch (e) {
      setMsg(verifyMsg, '검증 요청에 실패했습니다. 잠시 후 다시 시도하세요.', false);
    } finally {
      verifyBtn.disabled = false;
      refresh();
    }
  });

  // 현재 비밀번호를 다시 고치면 검증 무효화
  cur.addEventListener('input', () => {
    if (verified) { verified = false; setMsg(verifyMsg, '', true); }
    refresh();
  });
  nw.addEventListener('input', refresh);
  cf.addEventListener('input', refresh);

  if (forced) {
    modal.querySelectorAll('[data-pw-close]').forEach((b) => { b.hidden = true; });
    const note = modal.querySelector('[data-pw-force-note]');
    if (note) note.hidden = false;
    modal.hidden = false;
    setTimeout(() => cur.focus(), 0);
  }
  refresh();
})();
