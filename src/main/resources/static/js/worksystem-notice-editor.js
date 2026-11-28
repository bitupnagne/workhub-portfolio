(() => {
  const initNoticeEditors = () => {
  const wrappers = document.querySelectorAll('[data-notice-editor]');
  if (!wrappers.length) return;

  wrappers.forEach((wrapper) => {
    if (wrapper.dataset.noticeEditorReady === 'true') return;
    const editorEl = wrapper.querySelector('[data-editor-surface]');
    const source = wrapper.querySelector('.notice-editor-source');
    const status = wrapper.querySelector('[data-editor-status]');
    const form = wrapper.closest('form');
    if (!editorEl || !source || !form) return;
    wrapper.dataset.noticeEditorReady = 'true';

    const uploadUrl = wrapper.dataset.imageUpload;
    const csrfHeader = wrapper.dataset.csrfHeader;
    const csrfToken = wrapper.dataset.csrfToken;

    const Editor = window.toastui && window.toastui.Editor;
    let editor = null;

    if (Editor) {
      editor = new Editor({
        el: editorEl,
        height: '460px',
        initialEditType: 'wysiwyg',
        previewStyle: 'vertical',
        hideModeSwitch: true,
        usageStatistics: false,
        placeholder: '공지 내용을 입력하세요.',
        toolbarItems: [
          ['heading', 'bold', 'italic', 'strike'],
          ['hr', 'quote'],
          ['ul', 'ol'],
          ['image', 'link'],
        ],
        hooks: {
          addImageBlobHook(blob, callback) {
            if (!uploadUrl) {
              callback('', '');
              return;
            }
            const data = new FormData();
            data.append('image', blob, blob.name || 'image.png');
            const headers = {};
            if (csrfHeader && csrfToken) headers[csrfHeader] = csrfToken;
            fetch(uploadUrl, { method: 'POST', headers, body: data })
              .then((res) => (res.ok ? res.json() : Promise.reject(new Error('upload failed'))))
              .then((json) => callback(json.url, blob.name || ''))
              .catch(() => {
                if (status) {
                  status.textContent = '이미지 업로드에 실패했습니다.';
                  status.hidden = false;
                }
              });
          },
        },
      });
      if (source.value) editor.setHTML(source.value);
      editor.on('change', () => { source.value = editor.getHTML(); if (status) status.hidden = true; });
    } else {
      editorEl.contentEditable = 'true';
      editorEl.innerHTML = source.value || '';
      editorEl.addEventListener('input', () => { source.value = editorEl.innerHTML; if (status) status.hidden = true; });
    }

    if (form.hasAttribute('data-edit-form')) {
      let savedBody = source.value;
      const setLocked = (locked) => {
        wrapper.inert = locked;
        wrapper.setAttribute('aria-readonly', String(locked));
      };
      form.addEventListener('worksystem:edit-start', () => {
        savedBody = editor ? editor.getHTML() : editorEl.innerHTML;
      });
      form.addEventListener('worksystem:edit-cancel', () => {
        if (editor) editor.setHTML(savedBody);
        else editorEl.innerHTML = savedBody;
        source.value = savedBody;
        if (status) status.hidden = true;
      });
      form.addEventListener('worksystem:edit-state', (event) => setLocked(!event.detail.editing));
      setLocked(form.dataset.editState !== 'editing');
    }

    form.addEventListener('submit', (event) => {
      source.value = editor ? editor.getHTML() : editorEl.innerHTML;
      const plain = editor
        ? editor.getMarkdown().replace(/[#>*_\-`!\[\]()]/g, '').trim()
        : editorEl.innerText.replace(/ /g, ' ').trim();
      if (/<img[\s>]/i.test(source.value || '') || plain) return;
      event.preventDefault();
      if (status) {
        status.textContent = '공지 내용을 입력하세요.';
        status.hidden = false;
      }
      editorEl.scrollIntoView({ block: 'center' });
    });
  });
  };

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', initNoticeEditors);
  } else {
    initNoticeEditors();
  }
  window.addEventListener('worksystem:content-updated', initNoticeEditors);
})();
