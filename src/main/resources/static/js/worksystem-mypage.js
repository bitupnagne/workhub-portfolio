/* 마이페이지는 저장 후 .content가 교체되므로 새 사진·서명 노드에 다시 연결한다. */
(() => {
  if (window.__wsMypageReady) return;
  window.__wsMypageReady = true;
  let photoBinding = null;
  let signatureBinding = null;

  const bindPhoto = () => {
    const input = document.getElementById('profileImage');
    if (photoBinding?.node === input) return;
    photoBinding?.dispose();
    photoBinding = null;
    const preview = document.querySelector('[data-profile-preview]');
    if (!input || !preview) return;
    const form = input.form;
    const current = document.querySelectorAll('[data-profile-current]');
    const help = document.querySelector('[data-profile-file-name]');
    const controller = new AbortController();
    let objectUrl = null;
    const revokePreview = () => {
      if (objectUrl) URL.revokeObjectURL(objectUrl);
      objectUrl = null;
    };
    const render = () => {
      revokePreview();
      const file = input.files?.[0];
      if (!file) {
        preview.hidden = true;
        preview.removeAttribute('src');
        current.forEach((node) => { node.hidden = false; });
        if (help) help.textContent = 'JPG, PNG, WebP · 2MB 이하';
        return;
      }
      objectUrl = URL.createObjectURL(file);
      preview.src = objectUrl;
      preview.hidden = false;
      current.forEach((node) => { node.hidden = true; });
      if (help) help.textContent = file.name + ' · 저장 전 미리보기';
    };
    input.addEventListener('change', render, { signal: controller.signal });
    form?.addEventListener('worksystem:edit-cancel', () => {
      input.value = '';
      render();
    }, { signal: controller.signal });
    form?.addEventListener('reset', () => queueMicrotask(() => {
      if (input.isConnected) render();
    }), { signal: controller.signal });
    photoBinding = { node: input, dispose: () => { controller.abort(); revokePreview(); } };
    render();
  };

  const bindSignature = () => {
    const canvas = document.getElementById('sigCanvas');
    if (signatureBinding?.node === canvas) return;
    signatureBinding?.dispose();
    signatureBinding = null;
    const data = document.getElementById('sigDataUrl');
    const clear = document.getElementById('sigClear');
    const form = data?.form;
    if (!canvas || !data || !clear || !form) return;
    const context = canvas.getContext('2d');
    if (!context) return;
    context.lineWidth = 2.2;
    context.lineCap = 'round';
    context.strokeStyle = '#111827';
    const controller = new AbortController();
    const options = { signal: controller.signal };
    let pointer = null;
    let dirty = false;
    const position = (event) => {
      const rect = canvas.getBoundingClientRect();
      return { x: (event.clientX - rect.left) * canvas.width / rect.width,
        y: (event.clientY - rect.top) * canvas.height / rect.height };
    };
    const finish = () => {
      const previous = pointer;
      pointer = null;
      if (previous !== null && canvas.hasPointerCapture(previous)) canvas.releasePointerCapture(previous);
    };
    canvas.addEventListener('pointerdown', (event) => {
      if (pointer !== null || (event.pointerType === 'mouse' && event.button !== 0)) return;
      pointer = event.pointerId;
      const point = position(event);
      context.beginPath();
      context.moveTo(point.x, point.y);
      canvas.setPointerCapture(pointer);
      event.preventDefault();
    }, options);
    canvas.addEventListener('pointermove', (event) => {
      if (event.pointerId !== pointer) return;
      const point = position(event);
      context.lineTo(point.x, point.y);
      context.stroke();
      dirty = true;
      data.value = '';
      event.preventDefault();
    }, options);
    canvas.addEventListener('pointerup', finish, options);
    canvas.addEventListener('pointercancel', finish, options);
    canvas.addEventListener('lostpointercapture', () => { pointer = null; }, options);
    const reset = () => {
      finish();
      context.clearRect(0, 0, canvas.width, canvas.height);
      context.beginPath();
      dirty = false;
      data.value = '';
    };
    clear.addEventListener('click', reset, options);
    form.addEventListener('reset', reset, options);
    form.addEventListener('submit', (event) => {
      if (!dirty) {
        event.preventDefault();
        event.stopPropagation();
        if (window.vfAlert) window.vfAlert('먼저 서명을 그려주세요.');
        return;
      }
      data.value = canvas.toDataURL('image/png');
    }, options);
    signatureBinding = { node: canvas, dispose: () => { finish(); controller.abort(); } };
  };

  const init = () => { bindPhoto(); bindSignature(); };
  window.addEventListener('worksystem:content-updated', init);
  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', init, { once: true });
  else init();
})();
