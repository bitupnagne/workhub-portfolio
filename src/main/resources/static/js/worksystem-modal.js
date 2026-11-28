(() => {
  let activeConfirm = null;

  const ensureModal = () => {
    let modal = document.getElementById('vfCommonModal');
    if (modal) return modal;
    modal = document.createElement('div');
    modal.id = 'vfCommonModal';
    modal.className = 'vf-common-modal';
    modal.hidden = true;
    modal.innerHTML = `
      <div class="vf-common-dialog" role="dialog" aria-modal="true" aria-labelledby="vfCommonTitle">
        <div class="vf-common-head">
          <strong id="vfCommonTitle">확인</strong>
          <button type="button" data-vf-modal-cancel aria-label="닫기">${wsIcon('close')}</button>
        </div>
        <p class="vf-common-message" data-vf-modal-message></p>
        <div class="vf-common-actions">
          <button class="rbtn" type="button" data-vf-modal-cancel data-vf-modal-cancel-action>취소</button>
          <button class="btn primary" type="button" data-vf-modal-ok>확인</button>
        </div>
      </div>`;
    document.body.appendChild(modal);
    return modal;
  };

  const close = (result) => {
    const modal = ensureModal();
    modal.hidden = true;
    const confirm = activeConfirm;
    activeConfirm = null;
    if (confirm?.input && result === false) result = null;
    if (confirm) confirm.resolve(result);
  };

  const open = ({ title = '확인', message = '', confirm = false, okText = '확인', cancelText = '취소', danger = false, input = false, initialValue = '' }) => {
    const modal = ensureModal();
    modal.querySelector('[data-vf-modal-message]').textContent = message;
    modal.querySelector('#vfCommonTitle').textContent = title;
    let inputWrap = modal.querySelector('[data-vf-modal-input-wrap]');
    if (!inputWrap) {
      inputWrap = document.createElement('label');
      inputWrap.className = 'vf-common-input-wrap';
      inputWrap.dataset.vfModalInputWrap = '';
      inputWrap.innerHTML = '<input type="text" data-vf-modal-input autocomplete="off">';
      modal.querySelector('[data-vf-modal-message]').after(inputWrap);
    }
    const inputEl = inputWrap.querySelector('[data-vf-modal-input]');
    const ok = modal.querySelector('[data-vf-modal-ok]');
    const cancel = modal.querySelector('[data-vf-modal-cancel-action]');
    const cancelButtons = modal.querySelectorAll('[data-vf-modal-cancel]');
    ok.textContent = okText;
    ok.classList.toggle('danger', danger);
    cancel.textContent = cancelText;
    cancelButtons.forEach((button) => { button.hidden = !confirm; });
    inputWrap.hidden = !input;
    if (input) inputEl.value = initialValue || '';
    modal.hidden = false;
    if (input) {
      inputEl.focus();
      inputEl.select();
    } else {
      ok.focus();
    }
    return new Promise((resolve) => {
      activeConfirm = { resolve, input };
    });
  };

  window.vfAlert = (message, options = {}) => open({
    title: options.title || '알림',
    message,
    confirm: false,
    okText: options.okText || '확인',
    danger: options.danger === true
  });

  window.vfConfirm = (message, options = {}) => open({
    title: options.title || '확인',
    message,
    confirm: true,
    okText: options.okText || '확인',
    cancelText: options.cancelText || '취소',
    danger: options.danger === true
  });

  window.vfPrompt = (message, options = {}) => open({
    title: options.title || '입력',
    message,
    confirm: true,
    okText: options.okText || '확인',
    cancelText: options.cancelText || '취소',
    input: true,
    initialValue: options.initialValue || ''
  });

  document.addEventListener('click', (event) => {
    const modal = document.getElementById('vfCommonModal');
    if (!modal || modal.hidden) return;
    if (event.target.closest('[data-vf-modal-ok]')) {
      event.preventDefault();
      if (activeConfirm?.input) {
        const input = modal.querySelector('[data-vf-modal-input]');
        close(input ? input.value : '');
      } else {
        close(true);
      }
      return;
    }
    if (event.target.closest('[data-vf-modal-cancel]') || event.target === modal) {
      event.preventDefault();
      close(false);
    }
  });

  document.addEventListener('keydown', (event) => {
    const modal = document.getElementById('vfCommonModal');
    if (event.key === 'Enter' && modal && !modal.hidden && activeConfirm?.input) {
      event.preventDefault();
      const input = modal.querySelector('[data-vf-modal-input]');
      close(input ? input.value : '');
      return;
    }
    if (event.key === 'Escape' && modal && !modal.hidden) close(false);
  });

  // 링크 확인: a[data-confirm-message] 클릭 → 모달 안내 후 이동(화면 전환). target=_blank면 새 창.
  document.addEventListener('click', async (event) => {
    const link = event.target.closest('a[data-confirm-message]');
    if (!link) return;
    if (link.dataset.vfConfirmed === 'true') { delete link.dataset.vfConfirmed; return; }
    event.preventDefault();
    const ok = await window.vfConfirm(link.dataset.confirmMessage, {
      title: link.dataset.confirmTitle || '확인',
      okText: link.dataset.confirmOk || '확인',
      danger: link.dataset.confirmDanger === 'true'
    });
    if (!ok) return;
    if ((link.target || '').toLowerCase() === '_blank') {
      window.open(link.href, '_blank');
    } else if (typeof window.worksystemSoftNavigate === 'function' && !link.hasAttribute('download')) {
      window.worksystemSoftNavigate(link.href); // 확인 뒤 이동도 화면 전체를 다시 그리지 않는다
    } else {
      window.location.href = link.href;
    }
  });

  // 필수 입력 방어: form[data-require-fields="a,b"] — 비어 있으면 모달 안내 후 제출 차단.
  // (confirm 핸들러보다 먼저 등록 → 비면 preventDefault 되어 confirm 단계로 넘어가지 않음)
  document.addEventListener('submit', (event) => {
    if (event.defaultPrevented) return;
    const form = event.target.closest('form[data-require-fields]');
    if (!form) return;
    const names = form.dataset.requireFields.split(',').map((s) => s.trim()).filter(Boolean);
    const missing = names.some((n) => {
      const el = form.querySelector('[name="' + n + '"]');
      return !el || !String(el.value || '').trim();
    });
    if (missing) {
      event.preventDefault();
      window.vfAlert(form.dataset.requireMessage || '필수 항목을 입력하세요.', { title: '입력 필요' });
    }
  });

  document.addEventListener('submit', async (event) => {
    if (event.defaultPrevented) return;
    const form = event.target.closest('form');
    if (!form) return;
    const submitter = event.submitter;
    // 확인 설정은 누른 버튼(submitter) 우선, 없으면 폼에서 가져온다 — 한 폼에 일반 저장 + 확인 필요 버튼 공존 지원.
    const cfg = (submitter && submitter.dataset && submitter.dataset.confirmMessage) ? submitter
      : (form.dataset.confirmMessage ? form : null);
    if (!cfg) return;
    if (form.dataset.vfConfirmed === 'true') {
      delete form.dataset.vfConfirmed;
      return;
    }
    event.preventDefault();
    const ok = await window.vfConfirm(cfg.dataset.confirmMessage, {
      title: cfg.dataset.confirmTitle || '확인',
      okText: cfg.dataset.confirmOk || '확인',
      danger: cfg.dataset.confirmDanger === 'true'
    });
    if (!ok) return;
    form.dataset.vfConfirmed = 'true';
    if (submitter && typeof form.requestSubmit === 'function') {
      form.requestSubmit(submitter);
    } else {
      form.requestSubmit();
    }
  });

})();
