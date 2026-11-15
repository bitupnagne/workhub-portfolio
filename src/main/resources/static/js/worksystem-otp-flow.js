(() => {
  'use strict';
  const root = document.getElementById('wsOtpFlow');
  if (!root) return;
  const {entry, base, csrfHeader, csrfToken} = root.dataset;
  const login = entry === 'login';
  const management = root.querySelector('[data-otp-management]');
  const recovery = root.querySelector('[data-otp-recovery]');
  let loading = false;
  let noticeTimer;

  const url = path => base + path.replace(/^\//, '');
  function cancel() {
    if (entry !== 'manage') root.querySelector('[data-otp-logout]').requestSubmit();
  }
  function notice(message) {
    const element = root.querySelector('[data-otp-notice]');
    element.textContent = message;
    element.hidden = false;
    clearTimeout(noticeTimer);
    noticeTimer = setTimeout(() => { element.hidden = true; }, 3500);
  }
  function sessionExpired() {
    location.replace(url('login?expired=true'));
    throw new Error('세션이 만료되었습니다. 다시 로그인해 주세요.');
  }
  async function request(path, body) {
    const headers = {Accept: 'application/json', 'X-Requested-With': 'fetch'};
    if (body) headers[csrfHeader] = csrfToken;
    const controller = new AbortController();
    const timeout = setTimeout(() => controller.abort(), 12000);
    try {
      const response = await fetch(url(path), {
        method: body ? 'POST' : 'GET', headers, body, cache: 'no-store',
        credentials: 'same-origin', signal: controller.signal
      });
      const redirectedToLogin = response.redirected
        && new URL(response.url).pathname === new URL(url('login'), location.href).pathname;
      if (response.status === 401 || redirectedToLogin) sessionExpired();
      if (body && response.status === 403) {
        // A stale CSRF token can be rejected before the OTP controller runs.
        if (login && !(await request('login/otp/state')).pending) sessionExpired();
        if (!login) await request('api/session/ping');
      }
      if (!response.ok || !(response.headers.get('Content-Type') || '').includes('application/json')) {
        throw new Error('인증 정보를 불러오지 못했습니다. 화면을 새로고침하고 다시 시도해 주세요.');
      }
      const result = await response.json();
      if (result.expired || result.error === 'unauthorized') sessionExpired();
      return result;
    } catch (error) {
      if (controller.signal.aborted) throw new Error('서버 응답이 지연되고 있습니다. 다시 시도해 주세요.');
      if (error instanceof TypeError) throw new Error('서버에 연결할 수 없습니다. 연결 상태를 확인하고 다시 시도해 주세요.');
      throw error;
    } finally {
      clearTimeout(timeout);
    }
  }
  async function clock() {
    const start = Date.now();
    const result = await request((login ? 'login/otp' : 'otp') + '/server-time');
    return result.epochMs + (Date.now() - start) / 2 - Date.now();
  }
  function showRecovery(message) {
    root.querySelector('[data-otp-error]').textContent = message;
    recovery.hidden = false;
    recovery.style.display = 'flex';
    root.querySelector('[data-otp-retry]').focus();
  }
  function hideRecovery() {
    recovery.hidden = true;
    recovery.style.display = 'none';
  }
  async function state() {
    if (login) return request('login/otp/state');
    if (entry === 'manage') return request('otp/setup/data');
    if (entry === 'setup') {
      const data = root.querySelector('[data-otp-enrollment]').dataset;
      return {secret: data.secret, qrDataUri: data.qr, account: data.account};
    }
    return {};
  }
  async function open() {
    if (loading) return;
    loading = true;
    hideRecovery();
    try {
      const [data, offset] = await Promise.all([state(), clock().catch(() => undefined)]);
      if (login && !data.pending) {
        location.replace(url('login?expired=true'));
        return;
      }
      if (entry === 'manage' && data.already) {
        if (!management.open) management.showModal();
        return;
      }
      const auth = entry === 'auth' || (login && data.enrolled);
      if (!auth && !data.secret) throw new Error('등록 정보를 불러오지 못했습니다. 다시 시도해 주세요.');
      let result;
      await window.SystemOtp.open({
        mode: auth ? 'auth' : 'setup',
        data: {qr: data.qrDataUri, secret: data.secret, account: data.account},
        cancelLabel: entry === 'manage' ? '취소' : '로그아웃',
        serverTime: Number.isFinite(offset) ? Date.now() + offset : undefined,
        async verify(code) {
          const endpoint = login ? 'login/otp/verify' : auth ? 'otp/verify-ajax' : 'otp/confirm';
          result = await request(endpoint, new URLSearchParams({code}));
          if (!result.ok) throw new Error(result.message || '인증 코드를 다시 확인해 주세요.');
        },
        onCancel: cancel,
        onSuccess() {
          if (entry === 'manage') notice(result.message || '2차 인증이 등록되었습니다.');
          else location.assign(url(result.redirect || '/'));
        }
      });
    } catch (error) {
      showRecovery(error.message || '2차 인증을 열 수 없습니다. 다시 시도해 주세요.');
    } finally {
      loading = false;
    }
  }
  root.querySelector('[data-otp-retry]').addEventListener('click', open);
  root.querySelector('[data-otp-cancel]').addEventListener('click', () => {
    hideRecovery();
    cancel();
  });
  if (entry === 'manage') {
    document.querySelector('[data-otp-manage]')?.addEventListener('click', open);
    management.querySelector('[data-otp-close]').addEventListener('click', () => management.close());
    const disable = management.querySelector('[data-otp-disable]');
    disable.addEventListener('click', async () => {
      disable.disabled = true;
      try {
        const result = await request('otp/off', new URLSearchParams());
        if (!result.ok) throw new Error(result.message || '2차 인증을 해제하지 못했습니다.');
        management.close();
        notice(result.message || '2차 인증이 해제되었습니다.');
      } catch (error) {
        management.close();
        notice(error.message);
      } finally {
        disable.disabled = false;
      }
    });
  } else {
    open();
  }
})();
