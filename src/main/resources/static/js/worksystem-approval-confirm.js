(() => {
  let activeForm = null;

  const ensureModal = () => {
    let modal = document.getElementById('vfApprovalConfirm');
    if (modal) return modal;
    modal = document.createElement('div');
    modal.id = 'vfApprovalConfirm';
    modal.className = 'approval-confirm-modal';
    modal.hidden = true;
    modal.innerHTML = `
      <div class="approval-confirm-dialog" role="dialog" aria-modal="true" aria-labelledby="vfApprovalConfirmTitle">
        <div class="approval-confirm-head">
          <strong id="vfApprovalConfirmTitle">결재 처리</strong>
          <button type="button" data-approval-close aria-label="닫기">×</button>
        </div>
        <p class="approval-confirm-text" data-approval-message></p>
        <form data-approval-modal-form>
          <label class="approval-confirm-field" data-approval-reason-wrap hidden>
            <span>반려 사유</span>
            <textarea data-approval-reason rows="3" placeholder="반려 사유를 입력하세요"></textarea>
          </label>
          <label class="approval-confirm-field">
            <span>로그인 비밀번호</span>
            <input data-approval-password type="password" autocomplete="current-password" placeholder="로그인 비밀번호를 입력하세요">
          </label>
          <div class="approval-confirm-error" data-approval-error hidden></div>
          <div class="approval-confirm-actions">
            <button class="rbtn" type="button" data-approval-close>취소</button>
            <button class="btn primary" type="button" data-approval-submit>확인</button>
          </div>
        </form>
      </div>`;
    document.body.appendChild(modal);
    return modal;
  };

  const field = (modal, selector) => modal.querySelector(selector);

  const closeModal = () => {
    const modal = ensureModal();
    modal.hidden = true;
    activeForm = null;
  };

  const openModal = (form) => {
    activeForm = form;
    const action = form.dataset.approvalAction || '처리';
    const needsReason = form.dataset.approvalNeedsReason === 'true';
    const modal = ensureModal();
    field(modal, '[data-approval-message]').textContent =
      `${action}하시겠습니까? (로그인 비밀번호를 입력해 주세요)`;
    const reasonWrap = field(modal, '[data-approval-reason-wrap]');
    const reason = field(modal, '[data-approval-reason]');
    reasonWrap.hidden = !needsReason;
    reason.value = '';
    const password = field(modal, '[data-approval-password]');
    password.value = '';
    const error = field(modal, '[data-approval-error]');
    error.hidden = true;
    error.textContent = '';
    field(modal, '[data-approval-submit]').textContent = action;
    modal.hidden = false;
    (needsReason ? reason : password).focus();
  };

  const setHidden = (form, name, value) => {
    let input = form.querySelector(`input[type="hidden"][name="${name}"]`);
    if (!input) {
      input = document.createElement('input');
      input.type = 'hidden';
      input.name = name;
      form.appendChild(input);
    }
    input.value = value;
  };

  document.addEventListener('submit', (event) => {
    if (event.target.closest('[data-approval-modal-form]')) {
      event.preventDefault();
      document.querySelector('#vfApprovalConfirm [data-approval-submit]')?.click();
      return;
    }
    const form = event.target.closest('[data-approval-confirm-form]');
    if (!form || form.dataset.approvalConfirmed === 'true') return;
    event.preventDefault();
    openModal(form);
  });

  document.addEventListener('click', (event) => {
    if (event.target.closest('[data-approval-close]')) {
      event.preventDefault();
      closeModal();
      return;
    }
    const modal = document.getElementById('vfApprovalConfirm');
    // [UX24] 비밀번호 입력형 - backdrop 클릭으로 닫지 않음
    if (!event.target.closest('[data-approval-submit]')) return;
    event.preventDefault();
    if (!activeForm) return;
    const password = field(modal, '[data-approval-password]').value.trim();
    const error = field(modal, '[data-approval-error]');
    if (!password) {
      error.textContent = '로그인 비밀번호를 입력하세요.';
      error.hidden = false;
      field(modal, '[data-approval-password]').focus();
      return;
    }
    setHidden(activeForm, 'password', password);
    if (activeForm.dataset.approvalNeedsReason === 'true') {
      setHidden(activeForm, 'reason', field(modal, '[data-approval-reason]').value.trim());
    }
    const submitForm = activeForm;
    submitForm.dataset.approvalConfirmed = 'true';
    closeModal();
    submitForm.requestSubmit();
  });

  document.addEventListener('keydown', (event) => {
    if (event.key === 'Escape') closeModal();
  });
})();
