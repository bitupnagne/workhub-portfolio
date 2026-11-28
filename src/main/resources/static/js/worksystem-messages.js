(() => {
  const chatList = document.getElementById('chatList');
  if (!chatList) return;

  const roomId = chatList.dataset.roomId;
  const currentUserId = Number(chatList.dataset.currentUserId);
  let lastSignature = '';

  const escapeHtml = (value) => String(value ?? '')
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#39;');

  const defaultPersonIcon = (className = 'chat-avatar') => `
    <span class="person-icon ${className}" aria-hidden="true">
      <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M20 21a8 8 0 0 0-16 0"></path><circle cx="12" cy="7" r="4"></circle></svg>
    </span>`;

  const avatar = (filename) => filename
    ? `<img class="chat-avatar-img" src="/profile-images/${encodeURIComponent(filename)}" alt="">`
    : defaultPersonIcon();

  const formatDate = (value) => {
    if (!value) return '';
    if (Array.isArray(value)) {
      const [y, m, d, hh = 0, mm = 0] = value;
      return `${y}.${String(m).padStart(2, '0')}.${String(d).padStart(2, '0')} ${String(hh).padStart(2, '0')}:${String(mm).padStart(2, '0')}`;
    }
    return String(value).replace('T', ' ').slice(0, 16);
  };

  const render = (messages) => {
    const signature = JSON.stringify((messages || []).map((msg) => [msg.id, msg.body, msg.senderProfileImageFilename]));
    if (signature === lastSignature) return;
    lastSignature = signature;

    if (!messages || messages.length === 0) {
      chatList.innerHTML = '<div class="empty-search">아직 메시지가 없습니다</div>';
      return;
    }

    chatList.innerHTML = messages.map((msg) => {
      const isSystem = msg.senderUserId == null;
      if (isSystem) {
        return `<div class="chat-msg-system"><span>${escapeHtml(msg.body)}</span></div>`;
      }
      return `<article class="chat-msg ${Number(msg.senderUserId) === currentUserId ? 'mine' : ''}">
        ${avatar(msg.senderProfileImageFilename)}
        <div class="chat-bubble">
          <div class="chat-meta"><b>${escapeHtml(msg.senderName)}</b><span>${formatDate(msg.createdAt)}</span></div>
          <div class="chat-body">${escapeHtml(msg.body)}</div>
        </div>
      </article>`;
    }).join('');
  };

  const load = async () => {
    try {
      const response = await fetch(`/api/messages/${encodeURIComponent(roomId)}`, { headers: { Accept: 'application/json' } });
      if (!response.ok) return;
      const payload = await response.json();
      render(payload.messages || []);
      window.workSystemRightPanelRefresh?.();
    } catch (error) {
      // 메시지 갱신 실패는 다음 실시간 이벤트 또는 폴백에서 복구한다.
    }
  };

  const form = document.querySelector('.chat-form');
  form?.addEventListener('submit', (event) => {
    const textarea = event.currentTarget.querySelector('textarea[name="body"]');
    const body = textarea?.value.trim();
    if (!body) return;
    if (window.workSystemLive?.sendMessage?.(roomId, body)) {
      event.preventDefault();
      textarea.value = '';
    }
  });
  form?.querySelector('textarea[name="body"]')?.addEventListener('keydown', (event) => {
    if (event.key === 'Enter' && !event.shiftKey && !event.isComposing) {
      event.preventDefault();
      event.currentTarget.form?.requestSubmit();
    }
  });

  window.addEventListener('worksystem-live', (event) => {
    const detail = event.detail || {};
    if (detail.type !== 'MESSAGE_CHANGED') return;
    if (Number(detail.payload?.roomId || 0) === Number(roomId)) {
      load();
    }
  });

  window.setInterval(() => {
    if (!window.workSystemLive?.isConnected?.()) {
      load();
    }
  }, 3000);
  load();
})();