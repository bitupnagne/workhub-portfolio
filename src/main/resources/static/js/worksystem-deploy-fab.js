// 시스템 이동 FAB(speed-dial) 제어.
// - 점3개 트리거 클릭 → 연동 시스템 버튼들이 위로 펼쳐짐(.open). 바깥 클릭/Esc 로 닫힘.
// - #vfRightPanel 자식이라 패널이 열리면 함께 좌측으로 밀려난다(CSS transform 상속).
// - 하단 도크(채팅독/자료전송독)가 열리면 그 "측정 높이 + 여백"만큼 위로 올린다.
(() => {
  const fab = document.getElementById('systemFab');
  if (!fab) return;
  const trigger = document.getElementById('systemFabTrigger');

  // ── 펼침/접힘 토글 ─────────────────────────────
  function close() {
    if (!fab.classList.contains('open')) return;
    fab.classList.remove('open');
    trigger?.setAttribute('aria-expanded', 'false');
  }
  if (trigger) {
    trigger.addEventListener('click', (e) => {
      e.stopPropagation();
      const open = fab.classList.toggle('open');
      trigger.setAttribute('aria-expanded', open ? 'true' : 'false');
    });
  }
  document.addEventListener('click', (e) => { if (!fab.contains(e.target)) close(); });
  document.addEventListener('keydown', (e) => { if (e.key === 'Escape') close(); });

  // ── 하단 도크 높이만큼 위로 ────────────────────
  const GAP = 12;
  const docks = ['vfChatDock', 'vfWorkMemoDock']
    .map((id) => document.getElementById(id))
    .filter(Boolean);

  function updateBottom() {
    let lift = 0;
    for (const dock of docks) {
      if (dock.hidden) continue;
      const h = dock.getBoundingClientRect().height;
      if (h > 0) lift = Math.max(lift, h + GAP);
    }
    fab.style.bottom = lift > 0 ? lift + 'px' : '';
  }
  const observer = new MutationObserver(updateBottom);
  docks.forEach((dock) => observer.observe(dock, { attributes: true, attributeFilter: ['hidden'] }));
  window.addEventListener('resize', updateBottom);
  updateBottom();
})();
