// 승인된 본인 휴가의 취소 결재. 보관 문서는 그대로 두고 기존 취소 결재 API를 사용한다.
(() => {
  if (window.worksystemLeaveCancelInitialized) return;
  window.worksystemLeaveCancelInitialized = true;
  let modal, opener, loadController, submitting = false;
  let stopLoading = () => {};
  const close = () => {
    if (submitting) return;
    loadController?.abort();
    loadController = null;
    stopLoading();
    stopLoading = () => {};
    if (modal) modal.hidden = true;
    if (opener?.isConnected) opener.focus({preventScroll:true});
  };
  const ensureModal = () => {
    if (modal?.isConnected) return modal;
    modal = document.createElement('div');
    modal.className = 'leave-cancel-modal';
    modal.hidden = true;
    modal.innerHTML = '<div class="leave-cancel-dialog" role="dialog" aria-modal="true" aria-labelledby="leaveCancelTitle">'
      + '<div class="approval-confirm-head"><strong id="leaveCancelTitle">휴가 취소 신청</strong><button type="button" data-leave-cancel-close aria-label="닫기">×</button></div>'
      + '<div class="leave-cancel-stamp" data-leave-cancel-preview aria-live="polite"></div>'
      + '<form method="post" data-leave-cancel-form><div class="leave-cancel-field"><label class="field"><span>취소 사유</span>'
      + '<textarea name="reason" rows="3" maxlength="500" required placeholder="휴가를 취소하는 사유를 입력하세요."></textarea></label>'
      + '<p class="muted">취소 결재가 승인될 때까지 기존 휴가는 유지됩니다.</p></div>'
      + '<div class="approval-confirm-actions"><button class="rbtn" type="button" data-leave-cancel-close>닫기</button><button class="btn primary" type="submit" disabled>취소 결재 신청</button></div></form></div>';
    document.body.appendChild(modal);
    modal.addEventListener('click', e => { if (e.target === modal || e.target.closest('[data-leave-cancel-close]')) close(); });
    modal.querySelector('form').addEventListener('submit', e => {
      if (submitting) { e.preventDefault(); return; }
      submitting = true;
      modal.querySelectorAll('button').forEach(b => { b.disabled = true; });
      // 이 body 직속 폼은 일반 POST로 이동한다. 서버의 성공/오류 flash와 CSRF 처리를 보존한다.
    });
    return modal;
  };
  document.addEventListener('click', async e => {
    const button = e.target.closest('[data-leave-cancel-open]');
    if (!button) return;
    e.preventDefault();
    opener = button;
    const root = ensureModal(), form = root.querySelector('form'), preview = root.querySelector('[data-leave-cancel-preview]');
    loadController?.abort(); stopLoading(); loadController = new AbortController();
    const controller = loadController;
    submitting = false;
    form.reset(); form.action = button.dataset.submitUrl;
    form.querySelectorAll('input[type="hidden"]').forEach(n => n.remove());
    const csrf = document.querySelector('input[type="hidden"][name="_csrf"]');
    if (!csrf) { await window.vfAlert?.('보안 정보를 확인할 수 없습니다. 화면을 새로고침한 뒤 다시 시도하세요.'); return; }
    form.appendChild(csrf.cloneNode(true));
    const back = document.createElement('input'); back.type = 'hidden'; back.name = 'back'; back.value = '/hr/attendance?tab=leave'; form.appendChild(back);
    root.querySelectorAll('button').forEach(b => { b.disabled = false; });
    const submit = form.querySelector('[type="submit"]'); submit.disabled = true; submit.textContent = '취소 결재 신청';
    preview.removeAttribute('role');
    preview.replaceChildren();
    const finishLoading = window.wsLoading?.start(preview, { variant: 'card', replace: true }) || (() => {});
    stopLoading = finishLoading;
    root.hidden = false;
    root.querySelector('[data-leave-cancel-close]').focus();
    try {
      const response = await fetch(button.dataset.previewUrl, {credentials:'same-origin',signal:controller.signal,headers:{'X-Requested-With':'fetch'}});
      const html = await response.text(), doc = new DOMParser().parseFromString(html,'text/html'), content = doc.querySelector('.leave-cancel-preview-body');
      if (!response.ok || !content || response.redirected) {
        const message = doc.querySelector('.banner.err')?.textContent?.trim();
        throw new Error(message || '휴가 내용을 불러올 수 없습니다. 승인 상태와 로그인 상태를 확인한 뒤 다시 시도하세요.');
      }
      if (controller.signal.aborted || controller !== loadController || root.hidden) return;
      preview.replaceChildren(content); submit.disabled = false;
    } catch (error) {
      if (error.name === 'AbortError' || controller !== loadController || root.hidden) return;
      preview.textContent = error.message || '휴가 내용을 불러오지 못했습니다. 잠시 후 다시 시도하세요.';
      preview.setAttribute('role','alert');
    } finally {
      finishLoading();
      if (controller === loadController) { loadController = null; stopLoading = () => {}; }
    }
  });
  document.addEventListener('keydown', e => {
    if (e.key === 'Escape' && modal && !modal.hidden) { e.preventDefault(); close(); }
  });
  window.addEventListener('worksystem:content-updated', close);
})();
