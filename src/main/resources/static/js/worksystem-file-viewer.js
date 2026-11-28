(() => {
  const ensureViewer = () => {
    let modal = document.getElementById('vfFileViewer');
    if (modal) return modal;
    modal = document.createElement('div');
    modal.id = 'vfFileViewer';
    modal.className = 'file-viewer-modal';
    modal.hidden = true;
    modal.innerHTML = `
      <div class="file-viewer-dialog">
        <div class="file-viewer-head">
          <strong data-file-viewer-title>파일 보기</strong>
          <button type="button" data-file-viewer-close aria-label="닫기">${wsIcon('close')}</button>
        </div>
        <div class="file-viewer-image-wrap" data-file-viewer-image-wrap hidden>
          <button class="file-viewer-nav prev" type="button" data-file-viewer-prev aria-label="이전 이미지">${wsIcon('chevron_left')}</button>
          <img data-file-viewer-image alt="">
          <button class="file-viewer-nav next" type="button" data-file-viewer-next aria-label="다음 이미지">${wsIcon('chevron_right')}</button>
        </div>
        <iframe data-file-viewer-frame title="파일 보기"></iframe>
        <div class="file-viewer-thumbs" data-file-viewer-thumbs hidden></div>
      </div>`;
    document.body.appendChild(modal);

    const wrap = modal.querySelector('[data-file-viewer-image-wrap]');
    if (wrap) {
      // 휠로 확대/축소 (스크롤은 생기지 않음 — overflow:hidden + transform)
      wrap.addEventListener('wheel', (e) => {
        if (wrap.hidden) return;
        e.preventDefault();
        const factor = e.deltaY < 0 ? 1.15 : 1 / 1.15;
        zoom.scale = Math.min(6, Math.max(1, zoom.scale * factor));
        if (zoom.scale <= 1.001) { zoom.x = 0; zoom.y = 0; }
        applyZoom(modal);
      }, { passive: false });
      // 더블클릭: 1x ↔ 2x
      wrap.addEventListener('dblclick', (e) => {
        if (e.target.closest('[data-file-viewer-prev],[data-file-viewer-next]')) return;
        zoom.scale = zoom.scale > 1.001 ? 1 : 2;
        zoom.x = 0; zoom.y = 0;
        applyZoom(modal);
      });
      // 확대 상태에서 드래그로 이동(팬)
      const image = modal.querySelector('[data-file-viewer-image]');
      image.addEventListener('mousedown', (e) => {
        if (zoom.scale <= 1.001) return;
        e.preventDefault();
        zoom.panning = true;
        zoom.sx = e.clientX; zoom.sy = e.clientY; zoom.ox = zoom.x; zoom.oy = zoom.y;
        wrap.classList.add('is-panning');
      });
      window.addEventListener('mousemove', (e) => {
        if (!zoom.panning) return;
        zoom.x = zoom.ox + (e.clientX - zoom.sx);
        zoom.y = zoom.oy + (e.clientY - zoom.sy);
        applyZoom(modal);
      });
      window.addEventListener('mouseup', () => {
        if (!zoom.panning) return;
        zoom.panning = false;
        wrap.classList.remove('is-panning');
      });
    }
    return modal;
  };

  const isImageUrl = (url) => /\.(png|jpe?g|webp|gif|bmp|svg)(?:[?#].*)?$/i.test(String(url || ''));

  const state = { items: [], index: 0 };
  const zoom = { scale: 1, x: 0, y: 0, panning: false, sx: 0, sy: 0, ox: 0, oy: 0 };

  const applyZoom = (modal) => {
    const wrap = modal.querySelector('[data-file-viewer-image-wrap]');
    const image = modal.querySelector('[data-file-viewer-image]');
    if (!image) return;
    image.style.transform = `translate(${zoom.x}px, ${zoom.y}px) scale(${zoom.scale})`;
    if (wrap) wrap.classList.toggle('is-zoomed', zoom.scale > 1.001);
  };

  const resetZoom = (modal) => {
    zoom.scale = 1; zoom.x = 0; zoom.y = 0; zoom.panning = false;
    applyZoom(modal);
    const wrap = modal.querySelector('[data-file-viewer-image-wrap]');
    if (wrap) wrap.classList.remove('is-panning');
  };

  const closeViewer = (modal) => {
    modal.hidden = true;
    resetZoom(modal);
    const frame = modal.querySelector('[data-file-viewer-frame]');
    if (frame) frame.src = 'about:blank';
    const image = modal.querySelector('[data-file-viewer-image]');
    if (image) {
      image.removeAttribute('src');
      image.alt = '';
    }
    const thumbs = modal.querySelector('[data-file-viewer-thumbs]');
    if (thumbs) { thumbs.hidden = true; thumbs.innerHTML = ''; }
  };

  const readGallery = (trigger, url, name) => {
    const raw = trigger.dataset.fileViewerGallery;
    if (!raw) return [{ url, name }];
    try {
      const parsed = JSON.parse(raw);
      if (Array.isArray(parsed) && parsed.length) {
        return parsed
          .filter((item) => item && item.url)
          .map((item) => ({ url: String(item.url), name: String(item.name || '이미지') }));
      }
    } catch (error) {
      // 잘못된 갤러리 데이터는 단일 이미지 보기로 처리한다.
    }
    return [{ url, name }];
  };

  const showImageAt = (modal, index) => {
    if (!state.items.length) return;
    state.index = (index + state.items.length) % state.items.length;
    resetZoom(modal);
    const item = state.items[state.index];
    const image = modal.querySelector('[data-file-viewer-image]');
    const title = modal.querySelector('[data-file-viewer-title]');
    const prev = modal.querySelector('[data-file-viewer-prev]');
    const next = modal.querySelector('[data-file-viewer-next]');
    if (image) {
      image.src = item.url;
      image.alt = item.name;
    }
    if (title) title.textContent = state.items.length > 1 ? `${item.name} (${state.index + 1}/${state.items.length})` : item.name;
    if (prev) prev.hidden = state.items.length < 2;
    if (next) next.hidden = state.items.length < 2;
    const thumbs = modal.querySelector('[data-file-viewer-thumbs]');
    if (thumbs) {
      if (state.items.length > 1) {
        thumbs.hidden = false;
        if (thumbs.children.length !== state.items.length) {
          thumbs.innerHTML = state.items
            .map((it, i) => `<button type="button" class="file-viewer-thumb" data-file-viewer-thumb="${i}"><img src="${it.url}" alt=""></button>`)
            .join('');
        }
        thumbs.querySelectorAll('.file-viewer-thumb').forEach((b, i) => b.classList.toggle('active', i === state.index));
        const active = thumbs.querySelector('.file-viewer-thumb.active');
        if (active) active.scrollIntoView({ inline: 'center', block: 'nearest' });
      } else {
        thumbs.hidden = true;
        thumbs.innerHTML = '';
      }
    }
  };

  document.addEventListener('click', (event) => {
    const trigger = event.target.closest('[data-file-viewer-url],[data-receipt-url]');
    if (trigger) {
      event.preventDefault();
      event.stopImmediatePropagation();
      const modal = ensureViewer();
      const url = trigger.dataset.fileViewerUrl || trigger.dataset.receiptUrl;
      const name = trigger.dataset.fileViewerName || trigger.dataset.receiptName || '파일 보기';
      const type = trigger.dataset.fileViewerType || '';
      const imageWrap = modal.querySelector('[data-file-viewer-image-wrap]');
      const image = modal.querySelector('[data-file-viewer-image]');
      const frame = modal.querySelector('[data-file-viewer-frame]');
      modal.querySelector('[data-file-viewer-title]').textContent = name;
      if (type === 'image' || isImageUrl(url)) {
        state.items = readGallery(trigger, url, name);
        const startIndex = Number(trigger.dataset.fileViewerIndex || 0);
        if (frame) frame.src = 'about:blank';
        if (imageWrap) imageWrap.hidden = false;
        if (frame) frame.hidden = true;
        showImageAt(modal, Number.isFinite(startIndex) ? startIndex : 0);
      } else {
        state.items = [];
        if (imageWrap) imageWrap.hidden = true;
        if (image) image.removeAttribute('src');
        const thumbs = modal.querySelector('[data-file-viewer-thumbs]');
        if (thumbs) { thumbs.hidden = true; thumbs.innerHTML = ''; }
        if (frame) {
          frame.hidden = false;
          frame.src = url;
        }
      }
      modal.hidden = false;
      return;
    }
    const thumb = event.target.closest('[data-file-viewer-thumb]');
    if (thumb) {
      event.preventDefault();
      showImageAt(thumb.closest('.file-viewer-modal'), Number(thumb.dataset.fileViewerThumb));
      return;
    }
    const prev = event.target.closest('[data-file-viewer-prev]');
    if (prev) {
      event.preventDefault();
      showImageAt(prev.closest('.file-viewer-modal'), state.index - 1);
      return;
    }
    const next = event.target.closest('[data-file-viewer-next]');
    if (next) {
      event.preventDefault();
      showImageAt(next.closest('.file-viewer-modal'), state.index + 1);
      return;
    }
    const close = event.target.closest('[data-file-viewer-close],[data-receipt-viewer-close]');
    if (close) {
      closeViewer(close.closest('.file-viewer-modal'));
      return;
    }
    if (event.target.classList.contains('file-viewer-modal')) {
      closeViewer(event.target);
    }
  });

  document.addEventListener('keydown', (event) => {
    const modal = document.querySelector('.file-viewer-modal:not([hidden])');
    if (!modal) return;
    if (event.key === 'Escape') {
      closeViewer(modal);
    } else if (event.key === 'ArrowLeft' && state.items.length > 1) {
      showImageAt(modal, state.index - 1);
    } else if (event.key === 'ArrowRight' && state.items.length > 1) {
      showImageAt(modal, state.index + 1);
    }
  });
})();
