/* 요청별 소유권으로 스켈레톤과 기존 aria-busy/배치 상태를 함께 정리한다. */
(() => {
  if (window.wsLoading) return;
  const active = new WeakMap();
  const pending = new Set();
  const variantOf = value => ['rows', 'form', 'text', 'card'].includes(value) ? value : 'form';
  const bars = '<span></span><span></span><span></span><span></span><span></span><span></span>';
  const markup = variant => '<div class="ws-loading-skeleton ws-loading-skeleton--' + variantOf(variant)
    + '" aria-hidden="true">' + bars + '</div>';
  const start = (target, options = {}) => {
    if (!target) return () => {};
    active.get(target)?.stop();
    const surface = options.surface || target;
    const previousBusy = target.getAttribute('aria-busy');
    const blocked = options.replace || options.block === false ? [] : [...surface.children].map(child => [child, child.getAttribute('inert')]);
    blocked.forEach(([child]) => child.setAttribute('inert', ''));
    const previousPosition = surface.style.position;
    const positioned = window.getComputedStyle(surface).position === 'static';
    if (positioned) surface.style.position = 'relative';
    const node = document.createElement('div');
    node.className = 'ws-loading-skeleton ws-loading-skeleton--' + variantOf(options.variant)
      + (options.replace ? '' : ' ws-loading-skeleton--overlay');
    node.setAttribute('aria-hidden', 'true');
    node.style.opacity = '0';
    node.style.transition = window.matchMedia?.('(prefers-reduced-motion: reduce)')?.matches ? 'none' : 'opacity .12s ease';
    const rows = options.variant === 'rows' ? Math.max(6, Math.min(60, Math.ceil((surface.clientHeight || 240) / 40))) : 6;
    node.innerHTML = '<span></span>'.repeat(rows);
    if (options.replace) surface.replaceChildren(node);
    else surface.appendChild(node);
    target.setAttribute('aria-busy', 'true');
    const reveal = window.setTimeout(() => { if (active.get(target) === record) node.style.opacity = '1'; }, 120);
    const record = { target, stop() {
      if (active.get(target) !== record) return;
      active.delete(target);
      pending.delete(record);
      window.clearTimeout(reveal);
      node.remove();
      blocked.forEach(([child, value]) => { if (value === null) child.removeAttribute('inert'); else child.setAttribute('inert', value); });
      if (previousBusy === null) target.removeAttribute('aria-busy');
      else target.setAttribute('aria-busy', previousBusy);
      if (positioned && surface.style.position === 'relative') surface.style.position = previousPosition;
    } };
    active.set(target, record);
    pending.add(record);
    return record.stop;
  };
  const clear = () => [...pending].forEach(record => record.stop());
  window.wsLoading = { start, markup, clear };
  window.addEventListener('pagehide', clear);
  window.addEventListener('pageshow', (event) => {
    if (!event.persisted) return;
    clear();
    document.getElementById('wsBusyOverlay')?.remove();
  });
  window.addEventListener('worksystem:content-updated', () => {
    [...pending].forEach(record => { if (!record.target.isConnected) record.stop(); });
  });
})();
