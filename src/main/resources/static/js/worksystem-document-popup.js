(() => {
  const features = 'popup=yes,width=980,height=900,menubar=no,toolbar=no,location=no,status=no,scrollbars=yes,resizable=yes';
  const docPathPattern = /^\/hr\/(?:leave\/\d+\/document|trips\/\d+\/document|flex-time\/\d+\/document|attendance\/correction\/\d+\/document)$/;

  const urlPath = (value) => {
    try {
      return new URL(value, window.location.origin).pathname;
    } catch (error) {
      return '';
    }
  };

  const isDocumentUrl = (value) => docPathPattern.test(urlPath(value));

  const openDocumentPopup = (href) => {
    // 모바일: 팝업 차단/새탭 점프가 어색 → 같은 탭으로 이동(문서 페이지 닫기는 history.back 폴백)
    if (window.matchMedia('(max-width: 980px)').matches) {
      window.location.assign(href);
      return true;
    }
    const popup = window.open(href, `vf-doc-${Date.now()}`, features);
    if (popup) {
      popup.focus();
      return true;
    }
    window.open(href, '_blank', 'noopener');
    return false;
  };

  window.vfIsDocumentPopupUrl = isDocumentUrl;
  window.vfOpenDocumentPopup = openDocumentPopup;

  document.addEventListener('click', (event) => {
    const link = event.target.closest('a');
    if (!link || (!link.hasAttribute('data-doc-popup') && !isDocumentUrl(link.href))) return;
    event.preventDefault();
    openDocumentPopup(link.href);
  });
})();
