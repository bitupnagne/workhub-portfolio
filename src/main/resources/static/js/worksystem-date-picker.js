/**
 * worksystem-date-picker.js
 *  - 모든 <input type="date"> / <input type="datetime-local">를 커스텀 달력 팝업으로 대체.
 *  - 팝업 안에 '확인' 버튼이 있어, 날짜(및 시간)를 고르고 '확인'을 눌러야 적용된다.
 *  - 원본 input은 hidden으로 유지(name/value/required 그대로 form 제출에 사용).
 *  - data-dp-skip 속성이 있으면 제외.
 */
(() => {
  const pad = (n) => String(n).padStart(2, '0');
  const DOW = ['일', '월', '화', '수', '목', '금', '토'];
  let repositionOpen = () => {};

  const parseVal = (input, hasTime) => {
    const v = (input.value || '').trim();
    const re = hasTime ? /^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2})/ : /^(\d{4})-(\d{2})-(\d{2})/;
    const m = re.exec(v);
    if (!m) return null;
    return { y: +m[1], mo: +m[2] - 1, d: +m[3], h: hasTime ? +m[4] : 9, mi: hasTime ? +m[5] : 0 };
  };

  const enhance = (input) => {
    if (input.dataset.dpReady === '1' || input.hasAttribute('data-dp-skip')) return;
    const hasTime = input.type === 'datetime-local';
    if (input.type !== 'date' && !hasTime) return;
    input.dataset.dpReady = '1';

    const wrap = document.createElement('span');
    wrap.className = 'ws-dp-wrap';
    const display = document.createElement('button');
    display.type = 'button';
    display.className = 'ws-dp-display';
    input.parentNode.insertBefore(wrap, input);
    wrap.appendChild(input);
    wrap.appendChild(display);
    input.type = 'hidden';

    const fmt = () => {
      const p = parseVal(input, hasTime);
      if (!p) { display.textContent = hasTime ? '날짜·시간 선택' : '날짜 선택'; display.classList.add('empty'); return; }
      display.classList.remove('empty');
      const base = `${p.y}-${pad(p.mo + 1)}-${pad(p.d)}`;
      display.textContent = hasTime
        ? `${base}  ${p.h < 12 ? '오전' : '오후'} ${pad(((p.h + 11) % 12) + 1)}:${pad(p.mi)}`
        : base;
    };
    fmt();
    input.addEventListener('worksystem:query-filter-sync', fmt);

    let pop = null, view = null, sel = null;

    const close = () => {
      if (pop) pop.classList.remove('open');
      if (display.isConnected && !display.disabled) display.focus({ preventScroll: true });
    };

    const apply = () => {
      const base = `${sel.y}-${pad(sel.mo + 1)}-${pad(sel.d)}`;
      input.value = hasTime ? `${base}T${pad(sel.h)}:${pad(sel.mi)}` : base;
      input.dispatchEvent(new Event('change', { bubbles: true }));
      fmt();
      close();
    };

    const renderGrid = () => {
      const title = pop.querySelector('.ws-dp-title');
      title.textContent = `${view.y}년 ${view.mo + 1}월`;
      const grid = pop.querySelector('.ws-dp-grid');
      grid.innerHTML = '';
      DOW.forEach((d, i) => {
        const h = document.createElement('span');
        h.className = 'ws-dp-dow' + (i === 0 ? ' sun' : (i === 6 ? ' sat' : ''));
        h.textContent = d;
        grid.appendChild(h);
      });
      const first = new Date(view.y, view.mo, 1).getDay();
      const days = new Date(view.y, view.mo + 1, 0).getDate();
      for (let i = 0; i < first; i++) grid.appendChild(document.createElement('span'));
      const today = new Date();
      for (let d = 1; d <= days; d++) {
        const cell = document.createElement('button');
        cell.type = 'button';
        cell.className = 'ws-dp-day';
        cell.textContent = d;
        if (sel && sel.y === view.y && sel.mo === view.mo && sel.d === d) cell.classList.add('sel');
        if (today.getFullYear() === view.y && today.getMonth() === view.mo && today.getDate() === d) cell.classList.add('today');
        cell.addEventListener('click', () => {
          sel.y = view.y; sel.mo = view.mo; sel.d = d;
          grid.querySelectorAll('.ws-dp-day.sel').forEach((c) => c.classList.remove('sel'));
          cell.classList.add('sel');
        });
        grid.appendChild(cell);
      }
    };

    const syncTimeInputs = () => {
      if (!hasTime) return;
      pop.querySelector('.ws-dp-period').value = sel.h < 12 ? 'AM' : 'PM';
      pop.querySelector('.ws-dp-h').value = ((sel.h + 11) % 12) + 1;
      pop.querySelector('.ws-dp-m').value = pad(sel.mi);
    };

    const readTime = () => {
      if (!hasTime) return;
      const hourInput = pop.querySelector('.ws-dp-h');
      const minuteInput = pop.querySelector('.ws-dp-m');
      let h = hourInput.value.trim() === '' ? ((sel.h + 11) % 12) + 1 : Number(hourInput.value);
      let mi = minuteInput.value.trim() === '' ? sel.mi : Number(minuteInput.value);
      const period = pop.querySelector('.ws-dp-period').value;
      h = Number.isFinite(h) ? Math.max(1, Math.min(12, Math.trunc(h))) : ((sel.h + 11) % 12) + 1;
      mi = Number.isFinite(mi) ? Math.max(0, Math.min(59, Math.trunc(mi))) : sel.mi;
      hourInput.value = h;
      minuteInput.value = pad(mi);
      if (period === 'PM' && h !== 12) h += 12;
      if (period === 'AM' && h === 12) h = 0;
      sel.h = h; sel.mi = mi;
    };

    const build = () => {
      pop = document.createElement('div');
      pop.className = 'ws-dp-pop';
      pop.setAttribute('role', 'dialog');
      pop.setAttribute('aria-label', hasTime ? '날짜와 시간 선택' : '날짜 선택');
      const timeRow = hasTime
        ? `<div class="ws-dp-time">
             <select class="ws-dp-period"><option value="AM">오전</option><option value="PM">오후</option></select>
             <input type="number" class="ws-dp-h" min="1" max="12" inputmode="numeric"> <span>:</span>
             <input type="number" class="ws-dp-m" min="0" max="59" inputmode="numeric">
           </div>` : '';
      pop.innerHTML = `
        <div class="ws-dp-head">
          <button type="button" class="ws-dp-nav" data-dp-prev aria-label="이전 달">‹</button>
          <strong class="ws-dp-title"></strong>
          <button type="button" class="ws-dp-nav" data-dp-next aria-label="다음 달">›</button>
        </div>
        <div class="ws-dp-grid"></div>
        ${timeRow}
        <div class="ws-dp-actions">
          <button type="button" class="rbtn" data-dp-today>오늘</button>
          <span style="flex:1"></span>
          <button type="button" class="rbtn" data-dp-cancel>취소</button>
          <button type="button" class="btn primary" data-dp-ok>확인</button>
        </div>`;
      document.body.appendChild(pop);
      pop.addEventListener('mousedown', (e) => e.stopPropagation());
      pop.querySelector('[data-dp-prev]').addEventListener('click', () => { view.mo--; if (view.mo < 0) { view.mo = 11; view.y--; } renderGrid(); position(); });
      pop.querySelector('[data-dp-next]').addEventListener('click', () => { view.mo++; if (view.mo > 11) { view.mo = 0; view.y++; } renderGrid(); position(); });
      pop.querySelector('[data-dp-today]').addEventListener('click', () => { const n = new Date(); view = { y: n.getFullYear(), mo: n.getMonth() }; sel.y = n.getFullYear(); sel.mo = n.getMonth(); sel.d = n.getDate(); renderGrid(); position(); });
      pop.querySelector('[data-dp-cancel]').addEventListener('click', close);
      pop.querySelector('[data-dp-ok]').addEventListener('click', () => { readTime(); apply(); });
      if (hasTime) {
        pop.querySelector('.ws-dp-h').addEventListener('blur', readTime);
        pop.querySelector('.ws-dp-m').addEventListener('blur', readTime);
        pop.querySelector('.ws-dp-period').addEventListener('change', readTime);
        pop.querySelector('.ws-dp-time').addEventListener('keydown', (event) => {
          if (event.key !== 'Enter') return;
          event.preventDefault();
          readTime();
        });
      }
    };

    const position = () => {
      const r = display.getBoundingClientRect();
      const viewport = window.visualViewport;
      const topEdge = (viewport?.offsetTop || 0) + 8;
      const leftEdge = (viewport?.offsetLeft || 0) + 8;
      const bottomEdge = topEdge + (viewport?.height || window.innerHeight) - 16;
      const rightEdge = leftEdge + (viewport?.width || window.innerWidth) - 16;
      pop.style.maxWidth = Math.max(0, rightEdge - leftEdge) + 'px';
      pop.style.maxHeight = Math.max(0, bottomEdge - topEdge) + 'px';
      const height = pop.offsetHeight;
      const below = r.bottom + 4;
      const preferred = below + height <= bottomEdge ? below : r.top - height - 4;
      pop.style.top = Math.max(topEdge, Math.min(preferred, bottomEdge - height)) + 'px';
      pop.style.left = Math.max(leftEdge, Math.min(r.left, rightEdge - pop.offsetWidth)) + 'px';
    };

    const open = () => {
      const p = parseVal(input, hasTime) || (() => { const n = new Date(); return { y: n.getFullYear(), mo: n.getMonth(), d: n.getDate(), h: 9, mi: 0 }; })();
      sel = { ...p };
      view = { y: p.y, mo: p.mo };
      if (!pop) build();
      renderGrid();
      syncTimeInputs();
      pop.classList.add('open');
      position();
      (pop.querySelector('.ws-dp-day.sel') || pop.querySelector('[data-dp-prev]'))?.focus({ preventScroll: true });
      repositionOpen = () => { if (pop.classList.contains('open') && display.isConnected) position(); };
    };

    display.addEventListener('click', (e) => {
      e.stopPropagation();
      if (input.disabled || input.readOnly) return;
      if (pop && pop.classList.contains('open')) { close(); return; }
      // 다른 열린 팝업 닫기
      document.querySelectorAll('.ws-dp-pop.open').forEach((p) => p.classList.remove('open'));
      open();
    });
  };

  const scan = (root) => (root || document).querySelectorAll('input[type="date"], input[type="datetime-local"]').forEach(enhance);

  document.addEventListener('mousedown', () => document.querySelectorAll('.ws-dp-pop.open').forEach((p) => p.classList.remove('open')));
  document.addEventListener('keydown', (event) => {
    if (event.key !== 'Escape') return;
    const popup = document.activeElement?.closest('.ws-dp-pop.open');
    if (!popup) return;
    // Consume only the focused picker layer; parent modal Escape handlers must wait.
    event.preventDefault();
    event.stopImmediatePropagation();
    popup.querySelector('[data-dp-cancel]')?.click();
  }, true);
  window.addEventListener('resize', () => repositionOpen());
  window.addEventListener('scroll', () => repositionOpen(), true);
  window.visualViewport?.addEventListener('resize', () => repositionOpen());
  window.visualViewport?.addEventListener('scroll', () => repositionOpen());

  // required 날짜(이제 hidden이라 브라우저 검증이 안 됨) 미선택 시 제출 차단
  document.addEventListener('submit', (e) => {
    if (e.defaultPrevented) return;
    const form = e.target.closest('form');
    if (!form) return;
    const missing = Array.prototype.find.call(
      form.querySelectorAll('input[data-dp-ready="1"][required]'),
      (i) => !(i.value || '').trim());
    if (missing) {
      e.preventDefault();
      if (window.vfAlert) window.vfAlert('날짜를 선택해 주세요.', { title: '입력 필요' });
      else alert('날짜를 선택해 주세요.');
    }
  });

  const run = () => scan(document);
  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', run); else run();
  document.addEventListener('content-updated', run);
  if (document.body) {
    new MutationObserver((muts) => { for (const m of muts) for (const n of m.addedNodes) if (n.nodeType === 1) scan(n); }).observe(document.body, { childList: true, subtree: true });
  }
})();
