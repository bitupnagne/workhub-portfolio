/**
 * worksystem-pdf-viewer.js
 *  - [data-pdf-view="<inline-url>"] 요소 클릭 시 PDF를 팝업 모달(iframe)로 띄운다.
 *  - data-pdf-title: 제목, data-pdf-download: 다운로드 URL(없으면 inline URL 사용).
 */
(() => {
  const ensure = () => {
    let m = document.getElementById('wsPdfModal');
    if (m) return m;
    m = document.createElement('div');
    m.id = 'wsPdfModal';
    m.className = 'ws-pdf-modal';
    m.hidden = true;
    m.innerHTML =
      '<div class="ws-pdf-dialog">' +
      '  <div class="ws-pdf-head">' +
      '    <strong class="ws-pdf-title">근거자료</strong>' +
      '    <span style="flex:1"></span>' +
      '    <a class="btn ws-pdf-dl" target="_blank" rel="noopener">⬇ 다운로드</a>' +
      '    <button type="button" class="rbtn ws-pdf-close" aria-label="닫기">닫기</button>' +
      '  </div>' +
      '  <iframe class="ws-pdf-frame" title="PDF 뷰어"></iframe>' +
      '</div>';
    document.body.appendChild(m);
    const close = () => { m.hidden = true; m.querySelector('.ws-pdf-frame').src = 'about:blank'; };
    m.addEventListener('click', (e) => {
      if (e.target === m || e.target.closest('.ws-pdf-close')) close();
    });
    return m;
  };

  document.addEventListener('click', (e) => {
    const trigger = e.target.closest('[data-pdf-view]');
    if (!trigger) return;
    e.preventDefault();
    const m = ensure();
    m.querySelector('.ws-pdf-title').textContent = trigger.dataset.pdfTitle || '근거자료';
    m.querySelector('.ws-pdf-frame').src = trigger.dataset.pdfView;
    m.querySelector('.ws-pdf-dl').href = trigger.dataset.pdfDownload || trigger.dataset.pdfView;
    m.hidden = false;
  });

  document.addEventListener('keydown', (e) => {
    if (e.key !== 'Escape') return;
    const m = document.getElementById('wsPdfModal');
    if (m && !m.hidden) { m.hidden = true; m.querySelector('.ws-pdf-frame').src = 'about:blank'; }
  });
})();
