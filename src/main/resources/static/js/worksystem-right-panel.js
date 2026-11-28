(() => {
  const panel = document.getElementById('vfRightPanel');
  if (!panel) return;
  const messagingEnabled = panel.dataset.messagingEnabled !== 'false';
  const attendanceEnabled = panel.dataset.attendanceEnabled !== 'false';
  const presenceEnabled = panel.dataset.presenceEnabled === 'true';

  const activeUploads = new Map();
  const chatDock = document.getElementById('vfChatDock');
  const employeeModal = document.createElement('div');
  employeeModal.className = 'vf-employee-modal';
  employeeModal.hidden = true;
  document.body.appendChild(employeeModal);
  const tabs = [...panel.querySelectorAll('[data-panel-tab]')];
  const views = [...panel.querySelectorAll('[data-panel-view]')];
  const title = panel.querySelector('[data-panel-title]');
  const contactsDownload = panel.querySelector('[data-contacts-download]');
  const closeBtn = panel.querySelector('[data-panel-close]');
  const titles = { contacts: '직원 현황', notifications: '알림', messages: '메시지', workMemos: '자료전송' };
  const csrfHeader = panel.dataset.csrfHeader;
  const csrfToken = panel.dataset.csrfToken;
  let activeTab = 'contacts';
  let latestPayload = null;
  let currentChatRoomId = null;
  let currentChatSignature = '';
  let currentChatMembers = [];
  let liveSocket = null;
  let liveConnected = false;
  let liveRetry = 0;
  let reconnectTimer = null;
  let loadTimer = null;
  let initialMessageRoomOpened = false;
  let pendingMessages = []; // [{ tempId, body, sentAt, failed }]
  let lastCreatedMessageRoomId = null;
  let lastCreatedMessageAt = 0;
  let messageCreateState = null;
  let selectedChatFiles = [];
  let chatSearchState = { matches: [], index: 0 };
  // 페이지네이션(위로 스크롤 시 과거 로드)
  const CHAT_PAGE_SIZE = 80;
  let chatOlderMessages = [];   // /before 로 누적한 과거 메시지(현재 방)
  let chatRecentMessages = [];  // 최근 스트림(폴링/웹소켓이 갱신)
  let chatHasMore = false;
  let chatLoadingOlder = false;
  let chatAnchorTop = false;    // 과거 로드 직후 스크롤 위치 보존 플래그
  let authHeartbeatBusy = false;
  let authHeartbeatFailed = false;
  const formatTime = (value) => {
    if (!value) return '';
    if (Array.isArray(value)) {
      const [, , , hh = 0, mm = 0] = value;
      return `${String(hh).padStart(2, '0')}:${String(mm).padStart(2, '0')}`;
    }
    const s = String(value);
    const t = s.includes('T') ? s.split('T')[1] : s;
    return t.slice(0, 5);
  };
  const markRoomNotificationsReadOnce = (() => {
    const seen = new Set();
    return (roomId) => {
      if (!roomId) return;
      const key = String(roomId);
      if (seen.has(key)) return;
      seen.add(key);
      fetch(`/api/messages/${encodeURIComponent(roomId)}/read-notifications`, {
        method: 'POST', headers: csrfHeaders(), credentials: 'same-origin',
      }).catch(() => null).finally(() => scheduleLoad());
      setTimeout(() => seen.delete(key), 1500); // 같은 방 재진입 시 다시 호출 허용
    };
  })();

  const isMessageNotification = (notification) => String(notification?.type || '').toUpperCase() === 'MESSAGE';

  const shouldHideNotification = (notification) => {
    if (!isMessageNotification(notification)) return false;
    if (notification.readAt) return true;
    const link = String(notification.linkUrl || '');
    const match = link.match(/messageRoomId=(\d+)/);
    const roomId = match ? Number(match[1]) : 0;
    if (!roomId || !currentChatRoomId || Number(currentChatRoomId) !== roomId || !chatDock || chatDock.hidden) {
      return false;
    }
    markRoomNotificationsReadOnce(roomId);
    return true;
  };

  const visibleNotifications = (notifications = []) => notifications.filter((notification) => !shouldHideNotification(notification));

  const currentRoom = () => (window.__lastChatPayload?.room || (latestPayload?.rooms || []).find((room) => Number(room.id) === Number(currentChatRoomId)) || null);

  const escapeHtml = (value) => String(value ?? '')
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#39;');

  const escapeRegExp = (value) => String(value ?? '').replace(/[.*+?^${}()|[\]\\]/g, '\\$&');

  const defaultPersonIcon = (className = 'vf-person') => `
    <span class="person-icon ${className}" aria-hidden="true">
      <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M20 21a8 8 0 0 0-16 0"></path><circle cx="12" cy="7" r="4"></circle></svg>
    </span>`;

  const avatar = (filename, className = 'vf-person') => filename
    ? `<img class="${className}" src="/profile-images/${encodeURIComponent(filename)}" alt="" data-file-viewer-url="/profile-images/${encodeURIComponent(filename)}" data-file-viewer-name="프로필 사진">`
    : defaultPersonIcon(className);

  const roomAvatarHtml = (members = []) => {
    const list = (members || []).slice(0, 4);
    const safe = list.length ? list : [{ userName: '?' }];
    return `<span class="vf-room-avatar count-${Math.min(safe.length, 4)}" aria-hidden="true">${safe.map((member, index) => {
      const name = member.userName || member.name || '?';
      const image = member.profileImageFilename;
      const initial = String(name).trim().slice(0, 1) || '?';
      return `<span class="vf-room-avatar-cell cell-${index}">
        ${image ? `<img src="/profile-images/${encodeURIComponent(image)}" alt="">` : `<b>${escapeHtml(initial)}</b>`}
      </span>`;
    }).join('')}</span>`;
  };

  const roomFlagIcon = (type) => {
    if (type === 'pin') {
      return `<span class="vf-room-flag vf-room-flag-pin" aria-label="상단 고정">
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M12 17v5"/><path d="M5 17h14"/><path d="m7 11 5-5 5 5"/><path d="M9 3h6l-1 8H10L9 3Z"/></svg>
      </span>`;
    }
    return `<span class="vf-room-flag vf-room-flag-muted" aria-label="알림 꺼짐">
      <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M10.268 21a2 2 0 0 0 3.464 0"/><path d="M17 17H4a1 1 0 0 1-.74-1.673C4.59 13.956 6 12.499 6 8a6 6 0 0 1 .258-1.742"/><path d="m2 2 20 20"/><path d="M8.668 3.01A6 6 0 0 1 18 8c0 2.687.77 4.653 1.707 6.05"/></svg>
    </span>`;
  };

  const formatDate = (value) => {
    if (!value) return '';
    if (Array.isArray(value)) {
      const [y, m, d, hh = 0, mm = 0] = value;
      return `${y}.${String(m).padStart(2, '0')}.${String(d).padStart(2, '0')} ${String(hh).padStart(2, '0')}:${String(mm).padStart(2, '0')}`;
    }
    return String(value).replace('T', ' ').slice(0, 16);
  };

  const dateParts = (value) => {
    if (!value) return null;
    if (Array.isArray(value)) return { y: value[0], m: value[1], d: value[2] };
    const s = String(value);
    const m = s.match(/^(\d{4})-(\d{2})-(\d{2})/);
    return m ? { y: Number(m[1]), m: Number(m[2]), d: Number(m[3]) } : null;
  };

  const chatDateKey = (value) => {
    const p = dateParts(value);
    return p ? `${p.y}-${String(p.m).padStart(2, '0')}-${String(p.d).padStart(2, '0')}` : '';
  };

  const chatDateLabel = (value) => {
    const p = dateParts(value);
    if (!p) return '';
    const date = new Date(p.y, p.m - 1, p.d);
    const days = ['일요일', '월요일', '화요일', '수요일', '목요일', '금요일', '토요일'];
    return `${p.y}년 ${p.m}월 ${p.d}일 ${days[date.getDay()]}`;
  };

  const attachmentUrl = (attachment) => `/api/messages/attachments/${encodeURIComponent(attachment.id)}`;

  const formatBytes = (bytes) => {
    const n = Number(bytes || 0);
    if (n >= 1024 * 1024) return `${(n / 1024 / 1024).toFixed(n >= 10 * 1024 * 1024 ? 0 : 1)}MB`;
    if (n >= 1024) return `${Math.ceil(n / 1024)}KB`;
    return `${n}B`;
  };

  const renderAttachments = (attachments = []) => {
    if (!attachments.length) return '';
    const images = attachments.filter((a) => String(a.attachmentType || '').toUpperCase() === 'IMAGE');
    const files = attachments.filter((a) => String(a.attachmentType || '').toUpperCase() !== 'IMAGE');
    const gallery = images.map((image) => ({ url: attachmentUrl(image), name: image.originalFilename || '이미지' }));
    const galleryAttr = escapeHtml(JSON.stringify(gallery));
    const imageHtml = images.length ? (() => {
      const visible = images.slice(0, 4);
      const more = Math.max(0, images.length - visible.length);
      const n = visible.length;
      return `<div class="vf-chat-images count-${n}">${visible.map((image, index) => {
        const full = attachmentUrl(image);
        const thumb = `${full}?w=240&v=2`; /* v: SVG 썸네일 Content-Type 수정 캐시버스터 */
        return `
        <button class="vf-chat-image" type="button"
                data-file-viewer-url="${escapeHtml(full)}"
                data-file-viewer-name="${escapeHtml(image.originalFilename || '이미지')}"
                data-file-viewer-type="image"
                data-file-viewer-gallery="${galleryAttr}"
                data-file-viewer-index="${index}"
                title="${escapeHtml(image.originalFilename || '이미지')}">
          <img src="${escapeHtml(thumb)}" alt="${escapeHtml(image.originalFilename || '이미지')}" loading="lazy" decoding="async">
          ${more > 0 && index === n - 1 ? `<span class="vf-chat-image-more">+${more}</span>` : ''}
        </button>`;
      }).join('')}</div>`;
    })() : '';
    const fileHtml = files.map((file) => `
      <a class="vf-chat-file" href="${attachmentUrl(file)}" target="_blank" rel="noopener">
        <span class="vf-chat-file-ic">↓</span>
        <span><b>${escapeHtml(file.originalFilename || '첨부파일')}</b><em>유효기간: ${escapeHtml((file.expiresAt || '').toString().slice(0, 10).replaceAll('-', '.'))} · ${formatBytes(file.fileSize)}</em></span>
      </a>`).join('');
    return `<div class="vf-chat-attachments">${imageHtml}${fileHtml}</div>`;
  };

  const renderSelectedChatFiles = () => {
    const wrap = chatDock?.querySelector('[data-chat-pending-files]');
    if (!wrap) return;
    if (!selectedChatFiles.length) {
      wrap.hidden = true;
      wrap.innerHTML = '';
      return;
    }
    wrap.hidden = false;
    wrap.innerHTML = selectedChatFiles.map((file, index) => {
      const isImage = file.type?.startsWith('image/');
      const preview = isImage ? URL.createObjectURL(file) : '';
      return `<div class="vf-chat-pending-file">
        ${isImage ? `<img src="${preview}" alt="">` : `<span class="vf-chat-pending-file-icon">파일</span>`}
        <button type="button" data-chat-pending-remove="${index}" aria-label="첨부 제거">${wsIcon('close')}</button>
        <em>${escapeHtml(file.name || '첨부파일')}</em>
      </div>`;
    }).join('');
  };

  const csrfHeaders = (extra = {}) => {
    const headers = { Accept: 'application/json', ...extra };
    if (csrfHeader && csrfToken) headers[csrfHeader] = csrfToken;
    return headers;
  };

  const openPanel = (tab) => {
    activeTab = tab;
    panel.classList.add('open');
    tabs.forEach((button) => button.classList.toggle('active', button.dataset.panelTab === tab));
    views.forEach((view) => { view.hidden = view.dataset.panelView !== tab; });
    if (title) title.textContent = titles[tab] || '';
    if (contactsDownload) contactsDownload.hidden = tab !== 'contacts';
    render();
    if (tab === 'workMemos' && !latestPayload) loadWorkMemos();
  };

  // [1.0.90] 자료전송 — 목록은 우측 패널 / 상세·작성은 별도 vfWorkMemoDock(좌측 옆).
  const wmDock = document.getElementById('vfWorkMemoDock');
  const wmState = {
    box: 'received',       // 'received' | 'sent' | 'starred'
    list: null,            // 마지막 목록 응답
    listError: '',
    dockMode: null,        // null | 'detail' | 'compose'
    memo: null,            // 상세 메모
    recipients: [],        // 상세 수신자
    compose: { recipientIds: new Set(), title: '', body: '', files: [], uploading: false, progress: 0, error: null, candFilter: '' },
  };
  const wmEsc = (s) => (s||'').replace(/[&<>"']/g, (c)=>({ '&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;' }[c]));
  const wmFmtDt = (s) => {
    if (!s) return '';
    const d = String(s);
    return d.length >= 16 ? d.substring(0,10).replace(/-/g,'.') + ' ' + d.substring(11,16) : d;
  };
  const wmFmtSize = (b) => {
    const n = Number(b||0);
    if (n < 1024) return n + ' B';
    if (n < 1024*1024) return (n/1024).toFixed(1) + ' KB';
    if (n < 1024*1024*1024) return (n/1024/1024).toFixed(1) + ' MB';
    return (n/1024/1024/1024).toFixed(2) + ' GB';
  };

  let wmListRequest = null;
  let wmListSequence = 0;
  let wmDetailRequest = null;
  let wmDetailSequence = 0;
  let wmDetailError = '';
  const loadWorkMemos = () => {
    if (!messagingEnabled) return;
    wmListRequest?.abort();
    const controller = new AbortController();
    wmListRequest = controller;
    const current = ++wmListSequence, box = wmState.box;
    const view = panel.querySelector('[data-panel-view="workMemos"]');
    const position = view?.scrollTop || 0;
    if (view && !view.querySelector('[data-wm-list-body]')) renderWmList(view);
    const finish = window.wsLoading?.start(view, { variant: 'rows', surface: view?.querySelector('[data-wm-list-body]') || view }) || (() => {});
    fetch(`/api/work-memos?box=${encodeURIComponent(box)}&page=0`, {
      credentials: 'same-origin', signal: controller.signal, headers: { Accept: 'application/json' },
    }).then(r => { if (!r.ok) throw new Error('response'); return r.json(); })
      .then(data => {
        if (current !== wmListSequence || controller.signal.aborted) return;
        finish();
        wmState.list = data;
        wmState.listError = '';
        renderWorkMemos();
        if (view) view.scrollTop = position;
      }).catch(error => {
        if (current !== wmListSequence || controller.signal.aborted || error.name === 'AbortError') return;
        finish();
        wmState.listError = '자료전송 목록을 불러오지 못했습니다. 다시 시도해 주세요.';
        renderWorkMemos();
        if (view) view.scrollTop = position;
      }).finally(() => { finish(); if (current === wmListSequence) wmListRequest = null; });
    if (messagingEnabled) fetch('/api/work-memos/unread-count', { credentials: 'same-origin', headers: { Accept: 'application/json' } })
      .then(r => r.ok ? r.json() : null)
      .then(data => { if (data && typeof data.count === 'number') setCount('workMemos', data.count); })
      .catch(() => {});
  };

  const fetchWmDetail = (memoId) => {
    wmDetailRequest?.abort();
    const controller = new AbortController();
    wmDetailRequest = controller;
    const current = ++wmDetailSequence;
    wmDetailError = '';
    fetch(`/api/work-memos/${memoId}?markRead=true`, { credentials: 'same-origin', signal: controller.signal, headers: { Accept: 'application/json' } })
      .then(r => { if (!r.ok) throw new Error('response'); return r.json(); })
      .then(data => {
        if (current !== wmDetailSequence || controller.signal.aborted || wmState.dockMode !== 'detail' || Number(wmState.memo?.id) !== Number(memoId)) return;
        if (!data?.memo) throw new Error('response');
        wmState.memo = data.memo;
        wmState.recipients = data.recipients || [];
        renderWmDock();
        loadWorkMemos();
      }).catch(error => {
        if (current !== wmDetailSequence || controller.signal.aborted || error.name === 'AbortError' || wmState.dockMode !== 'detail') return;
        wmDetailError = '자료전송을 불러오지 못했습니다. 다시 열어 주세요.';
        renderWmDock();
      }).finally(() => { if (current === wmDetailSequence) wmDetailRequest = null; });
  };

  const renderWorkMemos = () => {
    const view = panel.querySelector('[data-panel-view="workMemos"]');
    if (view) renderWmList(view);
    renderWmDock();
  };

  const closeWmDock = () => {
    wmDetailSequence++;
    wmDetailRequest?.abort();
    wmDetailRequest = null;
    wmDetailError = '';
    wmState.dockMode = null;
    wmState.memo = null;
    wmState.recipients = [];
    if (wmDock) { wmDock.hidden = true; wmDock.innerHTML = ''; }
  };

  const renderWmDock = () => {
    if (!wmDock) return;
    if (!wmState.dockMode) { wmDock.hidden = true; wmDock.innerHTML = ''; return; }
    wmDock.hidden = false;
    if (wmState.dockMode === 'detail') return renderWmDetail();
    if (wmState.dockMode === 'compose') return renderWmCompose();
  };

  // 이 컴포넌트가 소유한 동일 선택 그룹만 재사용한다. 결과 응답이 버튼의
  // 포커스/indicator를 지우지 않으며, 보존한 버튼에 listener를 다시 등록하지 않는다.
  const retainPanelChoices = (view, selector, attribute) => {
    const previous = view.querySelector(selector);
    const focused = previous?.contains(document.activeElement) ? document.activeElement : null;
    return () => {
      const incoming = view.querySelector(selector);
      if (!previous || !incoming) return false;
      const before = Array.from(previous.querySelectorAll('[' + attribute + ']'));
      const after = Array.from(incoming.querySelectorAll('[' + attribute + ']'));
      if (!before.length || before.length !== after.length
          || before.some((button, i) => button.getAttribute(attribute) !== after[i].getAttribute(attribute))) return false;
      before.forEach((button, i) => {
        button.className = after[i].className;
        button.setAttribute('aria-pressed', after[i].getAttribute('aria-pressed'));
      });
      incoming.replaceWith(previous);
      if (focused?.isConnected && document.activeElement === document.body) focused.focus({ preventScroll: true });
      return true;
    };
  };

  const renderWmList = (view) => {
    const data = wmState.list;
    const items = (data && Array.isArray(data.items)) ? data.items : [];
    const boxTabs = `
      <div class="vf-wm-list-tools">
        <div class="ws-segmented" role="group" aria-label="자료전송함 필터">
        ${['received','sent','starred'].map(b => {
          const labels = { received: '수신함', sent: '발신함', starred: '즐겨찾기' };
          const active = wmState.box === b;
          return `<button type="button" class="btn ${active?'primary':''}" data-wm-box="${b}" aria-pressed="${active}">${labels[b]}</button>`;
        }).join('')}
        </div>
        <button class="btn primary" type="button" data-wm-compose>+ 새 자료전송</button>
      </div>`;
    let body;
    if (!data) {
      body = wmState.listError ? '' : (window.wsLoading?.markup('rows') || '');
    } else if (items.length === 0) {
      body = wmState.listError ? '' : `<div style="padding:30px 14px;text-align:center;color:var(--text-3);font-size:12.5px;line-height:1.6">표시할 자료전송이 없습니다.</div>`;
    } else {
      const parts = items.slice(0, 50).map(m => {
        const unread = wmState.box === 'received' && !m.readAt;
        const starred = !!m.starred;
        const att = (m.attachments && m.attachments.length) ? `📎 ${m.attachments.length}` : '';
        const title = wmEsc(m.title || '(제목 없음)');
        const sender = wmEsc(m.senderName || '');
        const body = wmEsc(m.body || '').slice(0, 80);
        return `
          <div class="notice-item${unread ? ' unread' : ''}" data-wm-open="${m.id}" style="cursor:pointer;display:flex;justify-content:space-between;gap:8px;padding:10px 12px;border-bottom:.5px solid var(--border)">
            <div style="min-width:0;flex:1">
              <div style="display:flex;align-items:center;gap:5px;margin-bottom:3px;font-weight:800;font-size:12.5px;color:var(--text)">
                ${unread ? '<span style="background:#ef4444;color:#fff;font-size:9px;font-weight:900;padding:1px 5px;border-radius:3px">NEW</span>' : ''}
                ${starred ? '<span class="star-on">' + wsIcon('star') + '</span>' : ''}
                <span style="overflow:hidden;text-overflow:ellipsis;white-space:nowrap">${title}</span>
              </div>
              <div style="font-size:11.5px;color:var(--text-2);margin-bottom:3px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap">${body}</div>
              <div style="display:flex;gap:8px;font-size:10.5px;color:var(--text-3)">
                <span>${sender}</span><span>${wmFmtDt(m.sentAt)}</span>${att ? `<span>${att}</span>` : ''}
              </div>
            </div>
          </div>`;
      }).join('');
      body = `<div style="overflow:auto">${parts}</div>`;
    }
    const error = wmState.listError ? `<p role="alert" class="muted">${wmEsc(wmState.listError)}</p>` : '';
    const resultBody = view.querySelector('[data-wm-list-body]');
    const existingTools = view.querySelector('.vf-wm-list-tools');
    if (existingTools && resultBody) {
      // 진행 중 indicator transition을 끊지 않도록 tools는 잠깐도 detach하지 않는다.
      resultBody.innerHTML = error + body;
      if (!data && !wmState.listError) resultBody.setAttribute('aria-busy', 'true');
      else resultBody.removeAttribute('aria-busy');
      view.querySelectorAll('[data-wm-box]').forEach(button => {
        const active = button.dataset.wmBox === wmState.box;
        button.classList.toggle('primary', active);
        button.setAttribute('aria-pressed', String(active));
      });
    } else {
      view.innerHTML = boxTabs + `<div data-wm-list-body ${!data && !wmState.listError ? 'aria-busy="true"' : ''}>${error}${body}</div>`;
      view.querySelectorAll('[data-wm-box]').forEach(b => b.addEventListener('click', () => {
      wmState.box = b.dataset.wmBox;
      view.querySelectorAll('[data-wm-box]').forEach(item => {
        const selected = item === b;
        item.classList.toggle('primary', selected);
        item.setAttribute('aria-pressed', String(selected));
      });
      loadWorkMemos();
    }));
    view.querySelector('[data-wm-compose]')?.addEventListener('click', () => {
      wmState.compose = { recipientIds: new Set(), title: '', body: '', files: [], uploading: false, progress: 0, error: null, candFilter: '' };
      wmState.dockMode = 'compose';
      renderWmDock();
    });
    }
    view.querySelectorAll('[data-wm-open]').forEach(c => c.addEventListener('click', () => {
      const memoId = Number(c.dataset.wmOpen);
      wmState.dockMode = 'detail';
      wmDetailError = '';
      wmState.memo = { id: memoId };
      renderWmDock();
      fetchWmDetail(memoId);
    }));
  };

  const renderWmDetail = () => {
    const m = wmState.memo;
    if (!m || !m.title) {
      wmDock.innerHTML = `
        <div class="vf-wmdock-head"><span class="vf-wmdock-title">자료전송 상세</span><button class="rbtn" data-wm-close>${wsIcon('close')}</button></div>
        <div class="vf-wmdock-body" ${wmDetailError ? 'role="alert"' : 'aria-busy="true"'}>${wmDetailError ? wmEsc(wmDetailError) : (window.wsLoading?.markup('form') || '')}</div>`;
      wmDock.querySelector('[data-wm-close]')?.addEventListener('click', closeWmDock);
      return;
    }
    const starred = !!m.starred;
    const recipChips = (wmState.recipients || []).map(r =>
      `<span style="display:inline-block;padding:2px 9px;border:1px solid #e5e7eb;border-radius:12px;font-size:11px;background:#f8fafc;margin-right:4px;margin-bottom:4px">${wmEsc(r.recipientName||'')}</span>`
    ).join('');
    const attachments = (m.attachments || []).map(a => `
      <div style="display:flex;align-items:center;gap:8px;padding:6px 10px;border:1px solid #e5e7eb;border-radius:7px;background:#f9fafb;margin-bottom:4px">
        <span>📎</span>
        <a href="/api/work-memos/attachments/${a.id}" target="_blank" rel="noopener" style="flex:1;color:var(--brand-dark,#4338CA);font-weight:600;text-decoration:none;font-size:12px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap">${wmEsc(a.originalFilename||'')}</a>
        <span style="font-size:10.5px;color:#6b7280">${wmFmtSize(a.fileSize)}</span>
      </div>`).join('');
    wmDock.innerHTML = `
      <div class="vf-wmdock-head">
        <span class="vf-wmdock-title">${wmEsc(m.title||'')}</span>
        <button class="rbtn" data-wm-star title="즐겨찾기">${starred ? '<span class="star-on">' + wsIcon('star') + '</span>' : wsIcon('star', 'star-off')}</button>
        <button class="rbtn" data-wm-close title="닫기">${wsIcon('close')}</button>
      </div>
      <div class="vf-wmdock-body">
        <div style="font-size:11px;color:var(--text-3);margin-bottom:10px">
          <b style="color:var(--text)">${wmEsc(m.senderName||'')}</b> · ${wmFmtDt(m.sentAt)}
        </div>
        <div style="background:#f8fafc;border:1px solid #e5e7eb;border-radius:8px;padding:8px 10px;margin-bottom:10px">
          <div style="font-size:11px;color:#6b7280;margin-bottom:4px;font-weight:700">받는 사람</div>
          <div>${recipChips || '<span style="color:#6b7280;font-size:11px">없음</span>'}</div>
        </div>
        <div style="white-space:pre-wrap;font-size:13px;color:var(--text);line-height:1.7;margin-bottom:12px;min-height:60px">${wmEsc(m.body||'')}</div>
        ${attachments ? `<div><div style="font-size:11px;font-weight:700;color:#6b7280;margin:8px 0 6px">첨부파일 ${(m.attachments||[]).length}건</div>${attachments}</div>` : ''}
      </div>`;
    wmDock.querySelector('[data-wm-close]')?.addEventListener('click', closeWmDock);
    wmDock.querySelector('[data-wm-star]')?.addEventListener('click', () => toggleWmStar(m.id));
  };

  const toggleWmStar = (memoId) => {
    const m = wmState.memo;
    if (m && m.starred && !confirm('이 자료전송을 즐겨찾기에서 해제할까요?')) return;
    fetch(`/api/work-memos/${memoId}/star`, { method: 'POST', credentials: 'same-origin', headers: csrfHeaders() })
      .then(r => r.ok ? r.json() : null)
      .then(data => {
        if (!data) return;
        if (wmState.memo && wmState.memo.id === memoId) wmState.memo.starred = !!data.starred;
        renderWmDock();
        loadWorkMemos();
      })
      .catch(() => {});
  };

  // [1.0.91] 칩 영역만 부분 갱신 (한글 IME 조합 유지를 위해 dock 재렌더 회피).
  const updateWmChips = (s, people) => {
    const chipsEl = wmDock?.querySelector('[data-wm-chips]');
    const countEl = wmDock?.querySelector('[data-wm-cnt]');
    if (!chipsEl) return;
    const arr = [...s.recipientIds];
    if (countEl) countEl.textContent = `${arr.length}명 선택`;
    if (arr.length === 0) {
      chipsEl.innerHTML = '<span style="color:#6b7280;font-size:11px;padding:3px 6px">아래에서 직원을 선택하세요</span>';
    } else {
      chipsEl.innerHTML = arr.map(id => {
        const u = people.find(c => Number(c.id) === Number(id));
        return u
          ? `<span class="vf-msg-chip" style="display:inline-flex;align-items:center;gap:4px;background:#EEF2FF;color:#4338CA;padding:3px 9px;border-radius:12px;font-size:11.5px;font-weight:700;margin:0 4px 4px 0">${escapeHtml(u.name)}<span data-wm-chip-x="${u.id}" style="cursor:pointer;opacity:0.7">${wsIcon('close')}</span></span>`
          : '';
      }).join('');
      chipsEl.querySelectorAll('[data-wm-chip-x]').forEach(x => x.addEventListener('click', () => {
        const uid = Number(x.dataset.wmChipX);
        s.recipientIds.delete(uid);
        // 후보 리스트의 해당 버튼도 동기화
        const btn = wmDock.querySelector(`.vf-msg-pick[data-wm-uid="${uid}"]`);
        if (btn) { btn.classList.remove('is-selected'); const cb = btn.querySelector('input[type=checkbox]'); if (cb) cb.checked = false; }
        updateWmChips(s, people);
      }));
    }
  };

  const renderWmCompose = () => {
    const s = wmState.compose;
    // 메시지방 만들기와 동일한 직원 평탄화
    const people = flattenEmployees();
    const filter = (s.candFilter || '').toLowerCase();
    const filtered = filter
      ? people.filter(u => (u.key||'').includes(filter))
      : people;
    // chips/count 는 updateWmChips 에서 부분 갱신 — 초기 placeholder.
    const chipsInit = '';
    // 메시지방 picker 동일 패턴(vf-msg-pick + data-key) — display toggle 로 필터해서 IME 조합 안 깨짐.
    const candHtml = people.slice(0, 200).map(u => {
      const checked = s.recipientIds.has(Number(u.id));
      return `
        <button type="button" class="vf-msg-pick${checked ? ' is-selected' : ''}" data-wm-uid="${escapeHtml(u.id)}" data-wm-key="${escapeHtml(u.key)}">
          <input type="checkbox" ${checked ? 'checked' : ''} tabindex="-1">
          <span><b>${escapeHtml(u.name)}</b><em>${escapeHtml(u.team)}</em></span>
        </button>`;
    }).join('') || '<div class="vf-panel-empty">검색 결과가 없습니다</div>';
    const filesHtml = s.files.map((f, i) => `
      <div style="display:flex;align-items:center;gap:6px;padding:5px 9px;border:1px solid #e5e7eb;border-radius:6px;background:#f9fafb;margin-bottom:4px">
        <span style="flex:1;font-size:11.5px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap">${wmEsc(f.name)}</span>
        <span style="font-size:10.5px;color:#6b7280">${wmFmtSize(f.size)}</span>
        <span data-wm-file-x="${i}" style="cursor:pointer;color:#6b7280;font-size:13px">${wsIcon('close')}</span>
      </div>`).join('');
    const totalSize = s.files.reduce((a,f)=>a+f.size,0);
    const summary = s.files.length ? `${s.files.length}개 / ${wmFmtSize(totalSize)}` : '';
    const errorBanner = s.error ? `<div style="margin:6px 0;padding:6px 10px;background:#fee2e2;color:#991b1b;border-radius:6px;font-size:11.5px">${wmEsc(s.error)}</div>` : '';
    const progressBar = s.uploading ? `<div style="margin:6px 0"><div style="height:6px;background:#f1f5f9;border-radius:3px;overflow:hidden"><div style="width:${s.progress}%;height:100%;background:#4F46E5;transition:width 120ms"></div></div><div style="font-size:11px;color:#6b7280;text-align:right;margin-top:2px">${s.progress}%</div></div>` : '';
    wmDock.innerHTML = `
      <div class="vf-wmdock-head">
        <span class="vf-wmdock-title">새 자료전송 작성</span>
        <button class="rbtn" data-wm-close title="닫기">${wsIcon('close')}</button>
      </div>
      <div class="vf-wmdock-body">
        ${errorBanner}${progressBar}
        <div style="font-size:11px;font-weight:700;color:#374151;margin-bottom:4px">받는 사람 <small data-wm-cnt style="font-weight:500;color:#6b7280">${s.recipientIds.size}명 선택</small></div>
        <div data-wm-chips style="border:1px dashed #e5e7eb;border-radius:8px;padding:6px;background:#fff;min-height:30px;margin-bottom:6px">
          ${chipsInit}
        </div>
        <input type="search" class="vf-panel-search-input" data-wm-cand-filter value="${wmEsc(s.candFilter||'')}" placeholder="이름/팀/전화 검색" autocomplete="off">
        <div class="vf-msg-create-list" style="max-height:200px;overflow:auto;margin-bottom:12px">${candHtml}</div>

        <div style="font-size:11px;font-weight:700;color:#374151;margin-bottom:4px">제목</div>
        <input type="text" data-wm-title value="${wmEsc(s.title||'')}" placeholder="제목을 입력하세요" maxlength="255" style="width:100%;padding:7px 10px;border:1px solid #e5e7eb;border-radius:7px;font-size:13px;margin-bottom:10px;box-sizing:border-box">

        <div style="font-size:11px;font-weight:700;color:#374151;margin-bottom:4px">본문</div>
        <textarea data-wm-body placeholder="내용을 입력하세요" style="width:100%;min-height:90px;padding:8px 10px;border:1px solid #e5e7eb;border-radius:7px;font-size:13px;font-family:inherit;resize:vertical;box-sizing:border-box;margin-bottom:10px">${wmEsc(s.body||'')}</textarea>

        <div style="font-size:11px;font-weight:700;color:#374151;margin-bottom:4px;display:flex;align-items:center;gap:6px">
          첨부파일 <span style="font-weight:500;color:#4338CA;font-size:10.5px">${summary}</span>
          <span style="flex:1"></span>
          <button class="btn" type="button" data-wm-pick-file style="font-size:10.5px;padding:3px 8px">+ 파일 추가</button>
          <input type="file" data-wm-files multiple hidden>
        </div>
        <div>${filesHtml}</div>
      </div>
      <div class="vf-wmdock-foot">
        <button class="btn primary vf-wmdock-send" type="button" data-wm-send ${s.uploading?'disabled':''}>보내기</button>
      </div>`;
    wmDock.querySelector('[data-wm-close]')?.addEventListener('click', () => { if (!s.uploading || confirm('업로드 중입니다. 닫을까요?')) closeWmDock(); });
    wmDock.querySelector('[data-wm-cancel]')?.addEventListener('click', () => { if (!s.uploading || confirm('업로드 중입니다. 취소할까요?')) closeWmDock(); });
    const filterInput = wmDock.querySelector('[data-wm-cand-filter]');
    const candListEl = wmDock.querySelector('.vf-msg-create-list');
    // [1.0.91] re-render 금지 — 한글 IME 조합 유지를 위해 hide/show 만.
    filterInput?.addEventListener('input', () => {
      const q = (filterInput.value || '').trim().toLowerCase();
      s.candFilter = q;
      candListEl?.querySelectorAll('.vf-msg-pick').forEach((el) => {
        el.style.display = (!q || (el.getAttribute('data-wm-key') || '').includes(q)) ? '' : 'none';
      });
    });
    wmDock.querySelectorAll('.vf-msg-pick').forEach(btn => btn.addEventListener('click', () => {
      const uid = Number(btn.dataset.wmUid);
      if (s.recipientIds.has(uid)) s.recipientIds.delete(uid); else s.recipientIds.add(uid);
      // 체크 상태만 토글, full re-render 안 함 — input focus/IME 보존
      btn.classList.toggle('is-selected');
      const cb = btn.querySelector('input[type=checkbox]'); if (cb) cb.checked = s.recipientIds.has(uid);
      updateWmChips(s, people);
    }));
    // chip × 핸들러는 updateWmChips 안에서 재바인딩되므로 여기선 초기 1회만 — updateWmChips 가 처리.
    updateWmChips(s, people);
    wmDock.querySelector('[data-wm-title]')?.addEventListener('input', (e) => { s.title = e.target.value; });
    wmDock.querySelector('[data-wm-body]')?.addEventListener('input', (e) => { s.body = e.target.value; });
    const fileInput = wmDock.querySelector('[data-wm-files]');
    wmDock.querySelector('[data-wm-pick-file]')?.addEventListener('click', () => fileInput?.click());
    fileInput?.addEventListener('change', (e) => {
      const picked = Array.from(e.target.files || []);
      let total = s.files.reduce((a,f)=>a+f.size,0);
      for (const f of picked) {
        if (total + f.size > 100*1024*1024) { s.error = '첨부파일은 총 100MB까지만 가능합니다.'; break; }
        s.files.push(f); total += f.size;
      }
      e.target.value = '';
      renderWmCompose();
    });
    wmDock.querySelectorAll('[data-wm-file-x]').forEach(x => x.addEventListener('click', () => {
      s.files.splice(Number(x.dataset.wmFileX), 1);
      renderWmCompose();
    }));
    wmDock.querySelector('[data-wm-send]')?.addEventListener('click', () => sendWmCompose());
  };

  const sendWmCompose = () => {
    const s = wmState.compose;
    s.error = null;
    if (!s.title.trim()) { s.error = '제목을 입력하세요.'; return renderWmDock(); }
    if (s.recipientIds.size === 0) { s.error = '받는 사람을 한 명 이상 선택하세요.'; return renderWmDock(); }

    const fd = new FormData();
    fd.append('title', s.title.trim());
    if (s.body) fd.append('body', s.body);
    fd.append('importance', 'NORMAL');
    s.recipientIds.forEach(id => fd.append('recipientIds', String(id)));
    s.files.forEach(f => fd.append('files', f, f.name));

    s.uploading = true; s.progress = 0; renderWmDock();
    const xhr = new XMLHttpRequest();
    xhr.open('POST', '/api/work-memos', true);
    xhr.withCredentials = true;
    if (csrfHeader && csrfToken) xhr.setRequestHeader(csrfHeader, csrfToken);
    xhr.upload.onprogress = (e) => {
      if (e.lengthComputable) { s.progress = Math.round(e.loaded * 100 / e.total); renderWmDock(); }
    };
    xhr.onload = () => {
      s.uploading = false;
      if (xhr.status >= 200 && xhr.status < 300) {
        closeWmDock();
        wmState.box = 'sent';
        loadWorkMemos();
      } else {
        s.error = (xhr.status === 413) ? '첨부파일이 너무 큽니다.' : `전송 실패 (${xhr.status})`;
        renderWmDock();
      }
    };
    xhr.onerror = () => { s.uploading = false; s.error = '네트워크 오류'; renderWmDock(); };
    xhr.send(fd);
  };

  tabs.forEach((button) => button.addEventListener('click', () => openPanel(button.dataset.panelTab)));
  closeBtn?.addEventListener('click', () => {
    if (activeUploads.size > 0 && !confirm('현재 파일 업로드가 진행 중입니다. 중단하고 닫으시겠습니까?')) return;
    panel.classList.remove('open');
  });

  // 패널이 열려 있을 때 바깥 영역을 누르면 자동으로 닫는다.
  // [요청] 일단 비활성화 — 닫기 버튼/탭으로만 닫히게. 다시 켜려면 false→true.
  const AUTO_COLLAPSE_PANEL_ON_OUTSIDE_CLICK = false;
  document.addEventListener('mousedown', (event) => {
    if (!AUTO_COLLAPSE_PANEL_ON_OUTSIDE_CLICK) return;
    if (!panel.classList.contains('open')) return;
    const t = event.target;
    if (panel.contains(t)) return;
    if (chatDock && !chatDock.hidden && chatDock.contains(t)) return;
    if (!employeeModal.hidden && employeeModal.contains(t)) return;
    if (t.closest && t.closest('[data-panel-tab]')) return;
    panel.classList.remove('open');
  });

  const setCount = (name, value) => {
    const count = Number(value || 0);
    // 모바일 하단 FAB의 합산 NEW 뱃지 + 부채꼴 항목 뱃지 동기화(알림/메시지/자료)
    if (window.mSyncFanCount) window.mSyncFanCount(name, count);
    const el = panel.querySelector(`[data-panel-count="${name}"]`);
    if (!el) return;
    el.hidden = count <= 0;
    el.textContent = count > 99 ? '99+' : String(count);
  };

  const captureOpenNodes = () => {
    const view = panel.querySelector('[data-panel-view="contacts"]');
    const closed = new Set();
    let had = false;
    if (view) {
      view.querySelectorAll('.vf-org-node').forEach((d) => {
        had = true;
        if (!d.open && d.dataset.nodeId) closed.add(d.dataset.nodeId);
      });
    }
    return { closed, had };
  };

  const empRow = (user, currentUserId) => {
    const isMe = Number(user.userId) === currentUserId;
    const isContractor = Boolean(user.contractor);
    const online = presenceEnabled && Boolean(user.online);
    const away = online && Boolean(user.away);
    const onlineLabel = away ? '자리비움' : online ? '로그인 중' : '오프라인';
    const dotClass = away ? 'away' : online ? 'online' : 'offline';
    const presence = !attendanceEnabled && !presenceEnabled ? '' : isContractor ? (attendanceEnabled ? '<span class="vf-presence-wrap"><span class="vf-status vf-status-contractor">도급</span></span>' : '') : `
        <span class="vf-presence-wrap">
          ${presenceEnabled ? `<span class="vf-online-dot ${dotClass}" title="${onlineLabel}" aria-label="${onlineLabel}"></span>` : ''}
          ${attendanceEnabled ? `<span class="vf-status vf-status-${escapeHtml(user.statusClass || 'pending')}">${escapeHtml(user.statusLabel || '예정')}</span>` : ''}
        </span>`;
    return `
      <button class="vf-employee-row ${isMe ? 'self' : ''}${isContractor ? ' contractor' : ''}" type="button" data-employee-user-id="${escapeHtml(user.userId)}" ${presenceEnabled ? `data-emp-online="${online ? '1' : '0'}"` : ''} data-emp-search="${escapeHtml(`${user.name || ''} ${user.dept || ''} ${user.teamName || ''} ${user.mobileNumber || ''} ${user.extensionNumber || ''}`.toLowerCase())}" title="${escapeHtml(user.name)} 정보">
        ${avatar(user.profileImageFilename, 'vf-employee-avatar')}
        <span class="vf-employee-main">
          <b>${escapeHtml(user.name)}${isMe ? ' · 나' : ''}</b>
          <span>${escapeHtml(user.teamName || user.dept || '')}</span>
        </span>
        ${presence}
      </button>`;
  };

  const countMembers = (node) => (node.members || []).length
    + (node.children || []).reduce((s, c) => s + countMembers(c), 0);

  const renderContacts = () => {
    const view = panel.querySelector('[data-panel-view="contacts"]');
    if (!view) return;
    const tree = latestPayload?.orgTree || [];
    if (!tree.length) {
      view.innerHTML = '<div class="vf-panel-empty">표시할 조직이 없습니다</div>';
      return;
    }
    const { closed, had } = captureOpenNodes();
    // [E1] 재렌더 전에 검색어·온라인 칩·포커스 상태를 캡처해 복원한다(라이브 이벤트마다 초기화되던 문제).
    const prevSearchEl = view.querySelector('[data-contacts-search]');
    const prevQuery = prevSearchEl ? prevSearchEl.value : '';
    const hadFocus = prevSearchEl && document.activeElement === prevSearchEl;
    const prevPresence = view.querySelector('[data-presence-chips] .vf-chip.is-active')?.getAttribute('data-presence') || 'all';
    const currentUserId = Number(latestPayload?.currentUserId || 0);
    const nodeHtml = (node, depth) => {
      const total = countMembers(node);
      const childrenHtml = (node.children || []).map((c) => nodeHtml(c, depth + 1)).join('');
      const membersHtml = (node.members || []).map((u) => empRow(u, currentUserId)).join('');
      const typeCls = (node.nodeType || 'TEAM').toLowerCase();
      const isOpen = had ? !closed.has(String(node.teamId))
        : (node.nodeType === 'ROOT' || node.nodeType === 'DIVISION');
      return `
        <details class="vf-org-node vf-node-${typeCls}" data-node-id="${escapeHtml(node.teamId)}" data-depth="${depth}" ${isOpen ? 'open' : ''}>
          <summary>
            <span class="vf-node-name">${escapeHtml(node.name)}</span>
            <span class="vf-node-cnt">${total}</span>
          </summary>
          <div class="vf-node-body">
            <div class="vf-node-members">${membersHtml}</div>
            ${childrenHtml}
          </div>
        </details>`;
    };
    const meAway = Boolean(findEmployee(currentUserId)?.away);
    view.innerHTML = (presenceEnabled ? '<div class="vf-my-presence" data-my-presence role="group" aria-label="내 상태">'
      +   '<span class="vf-my-presence-label">내 상태</span>'
      +   `<button type="button" class="vf-chip${meAway ? '' : ' is-active'}" data-my-presence-value="ONLINE" aria-pressed="${!meAway}"><span class="vf-chip-dot online"></span>온라인</button>`
      +   `<button type="button" class="vf-chip${meAway ? ' is-active' : ''}" data-my-presence-value="AWAY" aria-pressed="${meAway}"><span class="vf-chip-dot away"></span>자리비움</button>`
      + '</div>' : '')
      + '<div class="vf-panel-search"><input type="search" data-contacts-search placeholder="직원 검색 (이름/팀/전화번호)" autocomplete="off"></div>'
      + (presenceEnabled ? '<div class="vf-presence-chips ws-segmented" data-presence-chips role="group" aria-label="직원 접속 상태 필터">'
      +   '<button type="button" class="vf-chip is-active" data-presence="all" aria-pressed="true">전체</button>'
      +   '<button type="button" class="vf-chip" data-presence="online" aria-pressed="false"><span class="vf-chip-dot online"></span>온라인</button>'
      +   '<button type="button" class="vf-chip" data-presence="offline" aria-pressed="false"><span class="vf-chip-dot offline"></span>오프라인</button>'
      + '</div>' : '')
      + '<div class="vf-contacts-list">' + tree.map((r) => nodeHtml(r, 0)).join('') + '</div>';
    const search = view.querySelector('[data-contacts-search]');
    const listWrap = view.querySelector('.vf-contacts-list');
    const chipsWrap = view.querySelector('[data-presence-chips]');
    let presence = presenceEnabled ? prevPresence : 'all';   // all | online | offline
    const applyFilter = () => {
      const q = (search ? search.value : '').trim().toLowerCase();
      listWrap.querySelectorAll('.vf-employee-row').forEach((row) => {
        const matchQ = !q || (row.getAttribute('data-emp-search') || '').includes(q);
        const on = row.getAttribute('data-emp-online') === '1';
        const matchP = presence === 'all' || (presence === 'online' ? on : !on);
        row.style.display = (matchQ && matchP) ? '' : 'none';
      });
      // 깊은 노드부터 접고/펴기 (자식에 보이는 행 없으면 숨김)
      [...listWrap.querySelectorAll('.vf-org-node')]
        .sort((a, b) => Number(b.dataset.depth) - Number(a.dataset.depth))
        .forEach((d) => {
          const any = [...d.querySelectorAll('.vf-employee-row')].some((r) => r.style.display !== 'none');
          d.style.display = any ? '' : 'none';
          if ((q || presence !== 'all') && any) d.open = true;
        });
    };
    if (search) search.addEventListener('input', applyFilter);
    // [PR] 내 상태(온라인/자리비움) — 바꾸면 서버가 웹소켓으로 모두에게 알린다.
    view.querySelector('[data-my-presence]')?.addEventListener('click', (e) => {
      const btn = e.target.closest('[data-my-presence-value]');
      if (!btn || btn.classList.contains('is-active')) return;
      const status = btn.getAttribute('data-my-presence-value');
      const wrap = btn.parentElement;
      wrap.querySelectorAll('[data-my-presence-value]').forEach((c) => {
        const on = c === btn;
        c.classList.toggle('is-active', on);
        c.setAttribute('aria-pressed', String(on));
      });
      fetch('/api/me/presence', {
        method: 'POST', credentials: 'same-origin',
        headers: csrfHeaders({ 'Content-Type': 'application/json' }),
        body: JSON.stringify({ status })
      }).then((r) => { if (!r.ok) throw new Error('presence'); })
        .catch(() => scheduleLoad()); // 실패하면 서버 상태로 되돌린다
    });
    if (chipsWrap) {
      chipsWrap.addEventListener('click', (e) => {
        const btn = e.target.closest('[data-presence]');
        if (!btn) return;
        presence = btn.getAttribute('data-presence');
        chipsWrap.querySelectorAll('.vf-chip').forEach((c) => {
          c.classList.toggle('is-active', c === btn);
          c.setAttribute('aria-pressed', String(c === btn));
        });
        applyFilter();
      });
      // [E1] 칩 활성 상태 복원
      if (presence !== 'all') {
        chipsWrap.querySelectorAll('.vf-chip').forEach((c) => {
          const active = c.getAttribute('data-presence') === presence;
          c.classList.toggle('is-active', active);
          c.setAttribute('aria-pressed', String(active));
        });
      }
    }
    // [E1] 검색어·필터·포커스 복원
    if (search && prevQuery) search.value = prevQuery;
    if (prevQuery || presence !== 'all') applyFilter();
    if (hadFocus && search) {
      search.focus();
      const len = search.value.length;
      try { search.setSelectionRange(len, len); } catch (e) { /* number 등 미지원 타입 무시 */ }
    }
  };

  const createDeleteProgress = (total) => {
    let toast = document.getElementById('vfDeleteProgress');
    if (!toast) {
      toast = document.createElement('div');
      toast.id = 'vfDeleteProgress';
      toast.className = 'vf-progress-toast';
      toast.innerHTML = `<div class="vf-progress-text">삭제 중 (0/${total})</div><div class="vf-progress-bar"><div class="vf-progress-fill" style="width:0%"></div></div>`;
      document.body.appendChild(toast);
    } else {
      toast.querySelector('.vf-progress-text').textContent = `삭제 중 (0/${total})`;
      toast.querySelector('.vf-progress-fill').style.width = '0%';
      toast.hidden = false;
    }
    return {
      update(n) {
        toast.querySelector('.vf-progress-text').textContent = `삭제 중 (${n}/${total})`;
        toast.querySelector('.vf-progress-fill').style.width = `${Math.round((n / total) * 100)}%`;
      },
      close() { toast.remove(); }
    };
  };

  const groupNotifications = (notifications) => {
    const groups = new Map();
    notifications.forEach((item) => {
      const key = item.linkUrl ? 'link:' + item.linkUrl : 'solo:' + item.id;
      if (!groups.has(key)) groups.set(key, { linkUrl: item.linkUrl || null, items: [] });
      groups.get(key).items.push(item);
    });
    const arr = [...groups.values()];
    arr.forEach((g) => g.items.sort((a, b) => Number(b.id) - Number(a.id)));
    arr.sort((a, b) => Number(b.items[0].id) - Number(a.items[0].id));
    return arr;
  };

  const renderNotifications = (notifications = []) => {
    const view = panel.querySelector('[data-panel-view="notifications"]');
    if (!view) return;
    if (!notifications.length) {
      view.innerHTML = '<div class="vf-panel-empty">새 알림이 없습니다</div>';
      return;
    }
    const groups = groupNotifications(notifications);
    // [E1] 재렌더 전 펼쳐져 있던 그룹(머리 알림 id 기준)을 캡처해 복원한다.
    const openHeadIds = new Set([...view.querySelectorAll('.vf-notice-group.is-open')]
      .map((g) => g.querySelector('.vf-notice-mini')?.getAttribute('data-notification-id'))
      .filter(Boolean));
    // [W9] 모두 읽음 — 패널에서 바로 전체 읽음 처리
    view.innerHTML = '<div class="vf-notif-actions">'
      + '<button class="vf-notif-readall" type="button" data-notification-read-all>모두 읽음</button>'
      + '</div>'
      + groups.map((g) => {
        const head = g.items[0];
        const isGroup = g.items.length > 1;
        const tail = g.items.slice(1);
        const headHtml = `
          <div class="vf-notice-row${isGroup ? ' is-group-head' : ''}">
            <a class="vf-notice-mini ${head.readAt ? '' : 'unread'}" href="${escapeHtml(head.linkUrl || '/notifications')}" data-notification-id="${escapeHtml(head.id)}">
              ${head.readAt ? '' : '<span class="vf-notice-new">NEW</span>'}
              <b>${escapeHtml(head.title)}</b>
              <span>${escapeHtml(head.body || '')}</span>
              <em>${formatDate(head.createdAt)}</em>
            </a>
            ${isGroup ? `<button class="vf-notice-badge" type="button" data-notice-toggle aria-label="펼치기">+${tail.length}<i class="vf-notice-chev" aria-hidden="true">▾</i></button>` : ''}
            <button class="vf-notice-delete" type="button"
                    ${isGroup
                      ? `data-notification-group-delete="${escapeHtml(g.items.map(i => i.id).join(','))}"`
                      : `data-notification-delete="${escapeHtml(head.id)}"`}
                    aria-label="알림 삭제">X</button>
          </div>`;
        const tailHtml = isGroup ? `
          <div class="vf-notice-tail">
            ${tail.map((t) => `
              <div class="vf-notice-row vf-notice-row-sub">
                <a class="vf-notice-mini ${t.readAt ? '' : 'unread'}" href="${escapeHtml(t.linkUrl || '/notifications')}" data-notification-id="${escapeHtml(t.id)}">
                  ${t.readAt ? '' : '<span class="vf-notice-new">NEW</span>'}
                  <b>${escapeHtml(t.title)}</b>
                  <em>${formatDate(t.createdAt)}</em>
                </a>
                <button class="vf-notice-delete" type="button" data-notification-delete="${escapeHtml(t.id)}" aria-label="알림 삭제">X</button>
              </div>`).join('')}
          </div>` : '';
        const headId = String(head.id);
        const reopen = isGroup && openHeadIds.has(headId) ? ' is-open' : '';
        return `<div class="vf-notice-group${isGroup ? ' is-group' : ''}${reopen}">${headHtml}${tailHtml}</div>`;
      }).join('');
  };

  const renderMessages = (rooms = []) => {
    const view = panel.querySelector('[data-panel-view="messages"]');
    if (!view) return;
    // [E1] 메시지 생성(수신자 선택) 중에 라이브 이벤트로 재렌더되면 작성 화면을 유지한다.
    if (messageCreateState) {
      renderMsgCreateBody(view, ensureMsgCreateState(), flattenEmployees());
      return;
    }
    const createBtn = '<button class="vf-panel-link" type="button" data-msg-create>' + wsIcon('add') + ' 메세지 생성</button>';
    if (!rooms.length) {
      view.innerHTML = createBtn + '<div class="vf-panel-empty">메시지방이 없습니다</div>';
      return;
    }
    view.innerHTML = `
      ${createBtn}
      ${rooms.map((room) => `
        <button class="vf-room-mini" type="button" data-room-id="${escapeHtml(room.id)}">
          <span class="vf-room-row">
            ${roomAvatarHtml(room.avatarMembers)}
            <span class="vf-room-main">
              <span class="vf-room-title">
                <span class="vf-room-title-left">
                  ${room.pinned ? roomFlagIcon('pin') : ''}
                  ${room.muted ? roomFlagIcon('muted') : ''}
                  <b>${escapeHtml(room.title)}</b>
                </span>
                ${room.unreadCount ? `<em>${room.unreadCount > 99 ? '99+' : room.unreadCount}</em>` : ''}
              </span>
              <small>${escapeHtml(room.memberNames || '')}</small>
              <span>${escapeHtml(room.lastMessageBody || '아직 메시지가 없습니다')}</span>
            </span>
          </span>
        </button>`).join('')}`;
  };

  const flattenEmployees = () => {
    const list = [];
    const me = Number(latestPayload?.currentUserId || 0);
    (latestPayload?.groups || []).forEach((g) => (g.teams || []).forEach((t) => (t.users || []).forEach((u) => {
      if (Number(u.userId) !== me) {
        list.push({ id: u.userId, name: u.name || '', team: u.teamName || u.dept || '',
          key: `${u.name || ''} ${u.teamName || ''} ${u.dept || ''} ${u.mobileNumber || ''}`.toLowerCase() });
      }
    })));
    return list;
  };

  const collectOrgMemberIds = (node) => {
    const ids = [];
    const me = Number(latestPayload?.currentUserId || 0);
    const walk = (current) => {
      (current.members || []).forEach((user) => {
        const id = Number(user.userId || 0);
        if (id && id !== me) ids.push(id);
      });
      (current.children || []).forEach(walk);
    };
    walk(node);
    return [...new Set(ids)];
  };

  const collectOrgCandidateIds = (node, candidateIds) => collectOrgMemberIds(node)
    .filter((id) => candidateIds.has(Number(id)));

  const ensureMsgCreateState = () => {
    if (!messageCreateState) {
      messageCreateState = { tab: 'employees', selectedIds: new Set(), checkedOrgs: new Set() };
    }
    if (!messageCreateState.checkedOrgs) messageCreateState.checkedOrgs = new Set();
    return messageCreateState;
  };

  const findOrgNode = (teamId) => {
    const target = Number(teamId);
    let found = null;
    const walk = (n) => { if (Number(n.teamId) === target) found = n; (n.children || []).forEach(walk); };
    (latestPayload?.orgTree || []).forEach(walk);
    return found;
  };

  // 조직 노드의 모든 조상(루트 → 직계 부모 순서).
  const orgAncestors = (teamId) => {
    const target = Number(teamId);
    const chain = [];
    const walk = (nodes, path) => {
      for (const n of nodes) {
        if (Number(n.teamId) === target) { chain.push(...path); return true; }
        if (walk(n.children || [], [...path, n])) return true;
      }
      return false;
    };
    walk(latestPayload?.orgTree || [], []);
    return chain;
  };

  // 조직 노드를 클릭했을 때의 캐스케이드: 하위 전체 토글 + 상위 자동 정합.
  const toggleOrgCascade = (node, checkedSet) => {
    const id = Number(node.teamId || 0);
    if (!id) return;
    const willCheck = !checkedSet.has(id);
    // 하위(자신 포함) 전체에 적용
    const subtree = [];
    const walk = (n) => { subtree.push(n); (n.children || []).forEach(walk); };
    walk(node);
    subtree.forEach((n) => {
      const nid = Number(n.teamId || 0);
      if (!nid) return;
      if (willCheck) checkedSet.add(nid);
      else checkedSet.delete(nid);
    });
    // 상위 전파: 직계 자식 전부 체크 ↔ 부모 체크
    const ancestors = orgAncestors(id);
    for (let i = ancestors.length - 1; i >= 0; i--) {
      const a = ancestors[i];
      const aid = Number(a.teamId || 0);
      if (!aid) continue;
      const children = a.children || [];
      if (children.length === 0) continue;
      const allChildrenChecked = children.every((c) => Number(c.teamId) && checkedSet.has(Number(c.teamId)));
      if (allChildrenChecked) checkedSet.add(aid);
      else checkedSet.delete(aid);
    }
  };

  // 노드가 "부분 체크" 상태인지 — 자기는 미체크지만 하위에 체크된 노드가 하나라도 있을 때.
  const toggleOrgSubtreeOnly = (node, checkedSet) => {
    const id = Number(node.teamId || 0);
    if (!id) return;
    const willCheck = !checkedSet.has(id);
    const subtree = [];
    const walk = (n) => { subtree.push(n); (n.children || []).forEach(walk); };
    walk(node);
    subtree.forEach((n) => {
      const nid = Number(n.teamId || 0);
      if (!nid) return;
      if (willCheck) checkedSet.add(nid);
      else checkedSet.delete(nid);
    });
  };

  const orgIsPartial = (node, checkedSet) => {
    let any = false;
    const walk = (n) => {
      if (any) return;
      const id = Number(n.teamId || 0);
      if (id && checkedSet.has(id)) { any = true; return; }
      (n.children || []).forEach(walk);
    };
    (node.children || []).forEach(walk);
    return any;
  };

  // 칩 표시용 "체크된 최대 부분트리 루트": 부모가 체크돼 있으면 자식은 칩에서 가린다.
  const orgChipRoots = (checkedSet) => {
    const roots = [];
    const walk = (nodes) => {
      for (const n of nodes) {
        const id = Number(n.teamId || 0);
        if (id && checkedSet.has(id)) { roots.push(n); continue; } // 자식 subsume
        walk(n.children || []);
      }
    };
    walk(latestPayload?.orgTree || []);
    return roots;
  };

  // 체크된 노드들의 direct members 합집합 (캐스케이드로 모든 후속 노드가 함께 체크돼 있어서 direct만 모아도 전체 subtree 멤버 = 전체 합집합).
  const orgCheckedUserIds = (checkedSet, candidateIds) => {
    const me = Number(latestPayload?.currentUserId || 0);
    const result = new Set();
    const walk = (nodes) => {
      for (const n of nodes) {
        const id = Number(n.teamId || 0);
        if (id && checkedSet.has(id)) {
          (n.members || []).forEach((u) => {
            const uid = Number(u.userId || 0);
            if (!uid || uid === me) return;
            if (candidateIds && !candidateIds.has(uid)) return;
            result.add(uid);
          });
        }
        walk(n.children || []);
      }
    };
    walk(latestPayload?.orgTree || []);
    return [...result];
  };

  // 선택된 대상의 최종 사용자 ID 목록 (조직 탭=체크된 조직들의 멤버 합집합).
  const selectedUserIds = (state, candidateIds) => {
    if (state.tab === 'orgs') return orgCheckedUserIds(state.checkedOrgs, candidateIds);
    return [...state.selectedIds];
  };

  // 탭 전환 시 선택 항목이 있으면 초기화 확인. true면 전환 진행.
  const confirmTabSwitch = async (state) => {
    const hasSel = state.tab === 'orgs' ? state.checkedOrgs.size > 0 : state.selectedIds.size > 0;
    if (!hasSel) return true;
    const msg = '탭을 바꾸면 선택된 대상이 모두 해제됩니다. 계속하시겠습니까?';
    if (window.vfConfirm) return await window.vfConfirm(msg, { okText: '변경', danger: true });
    return window.confirm(msg);
  };

  // 상단 선택 칩 (직원=이름 / 조직=조직명(최대 부분트리 루트), 각 칩에 × 제거 버튼).
  const renderSelectionChips = (state, nameById, prefix) => {
    const items = [];
    if (state.tab === 'orgs') {
      orgChipRoots(state.checkedOrgs).forEach((node) => {
        const teamId = Number(node.teamId || 0);
        items.push(`<span class="vf-sel-chip is-org"><span>${escapeHtml(node.name || '')}</span><button type="button" data-${prefix}-chip-org="${escapeHtml(teamId)}" aria-label="제거">${wsIcon('close')}</button></span>`);
      });
    } else {
      state.selectedIds.forEach((id) => items.push(
        `<span class="vf-sel-chip"><span>${escapeHtml(nameById.get(Number(id)) || ('#' + id))}</span><button type="button" data-${prefix}-chip-id="${escapeHtml(id)}" aria-label="제거">${wsIcon('close')}</button></span>`));
    }
    const body = items.length ? items.join('') : '<span class="vf-sel-empty">아래에서 대상을 선택하세요</span>';
    return `<div class="vf-sel-chips">${body}</div>`;
  };

  // 조직 탭: 멤버는 표시하지 않고 조직 노드만 선택 가능. 상위 클릭=하위 전체 캐스케이드.
  // 3상태: 체크(✓) / 부분(–) / 미체크. 부분=자기는 미체크지만 하위에 체크된 노드가 있음.
  const renderMsgCreateOrgPick = (node, state, depth) => {
    const ids = collectOrgMemberIds(node);
    const teamId = Number(node.teamId || 0);
    const checked = state.checkedOrgs.has(teamId);
    const partial = !checked && orgIsPartial(node, state.checkedOrgs);
    const stateClass = checked ? ' is-checked' : (partial ? ' is-partial' : '');
    const mark = checked ? wsIcon('check') : (partial ? wsIcon('remove') : '');
    const children = (node.children || []).map((child) => renderMsgCreateOrgPick(child, state, depth + 1)).join('');
    const self = ids.length ? `
      <button class="vf-org-pick${stateClass}" type="button" data-msg-org-pick="${escapeHtml(teamId)}" style="padding-left:${12 + depth * 16}px">
        <span class="vf-org-check">${mark}</span>
        <span><b>${escapeHtml(node.name || '')}</b><em>${ids.length}명</em></span>
      </button>` : '';
    return self + children;
  };

  const renderMsgCreateBody = (view, state, people) => {
    const restoreChoices = retainPanelChoices(view, '.vf-msg-create-tabs', 'data-msg-create-tab');
    const nameById = new Map(people.map((u) => [Number(u.id), u.name]));
    const selectedCount = selectedUserIds(state).length;
    const employeeList = people.map((u) => {
      const checked = state.selectedIds.has(Number(u.id));
      return `
        <button class="vf-msg-pick${checked ? ' is-selected' : ''}" type="button" data-msg-pick-id="${escapeHtml(u.id)}" data-msg-pick-key="${escapeHtml(u.key)}">
          <input type="checkbox" ${checked ? 'checked' : ''} tabindex="-1">
          <span><b>${escapeHtml(u.name)}</b><em>${escapeHtml(u.team)}</em></span>
        </button>`;
    }).join('') || '<div class="vf-panel-empty">표시할 직원이 없습니다</div>';
    const orgTree = latestPayload?.orgTree || [];
    const orgList = orgTree.map((node) => renderMsgCreateOrgPick(node, state, 0)).join('') || '<div class="vf-panel-empty">표시할 조직이 없습니다</div>';
    view.innerHTML = `
      <div class="vf-msg-create">
        <div class="vf-msg-create-head">
          <strong>새 메시지방</strong>
          <button class="vf-msg-create-x" type="button" data-msg-create-cancel aria-label="닫기">${wsIcon('close')}</button>
        </div>
        <div class="vf-msg-create-tabs ws-segmented" role="group" aria-label="받는 사람 종류">
          <button class="vf-msg-create-tab ${state.tab === 'employees' ? 'active' : ''}" type="button" data-msg-create-tab="employees" aria-pressed="${state.tab === 'employees'}">직원</button>
          <button class="vf-msg-create-tab ${state.tab === 'orgs' ? 'active' : ''}" type="button" data-msg-create-tab="orgs" aria-pressed="${state.tab === 'orgs'}">조직</button>
        </div>
        ${renderSelectionChips(state, nameById, 'msg')}
        ${state.tab === 'employees'
          ? `<input type="search" class="vf-panel-search-input" data-msg-create-search placeholder="직원 검색(이름/팀/전화번호)" autocomplete="off">
             <div class="vf-msg-create-list">${employeeList}</div>`
          : `<div class="vf-msg-create-org-list">${orgList}</div>`}
        <div class="vf-msg-create-actions">
          <button class="btn primary" type="button" data-msg-create-submit ${selectedCount ? '' : 'disabled'}>
            ${selectedCount ? `${selectedCount}명 선택 · 방 만들기` : '대상을 선택하세요'}
          </button>
        </div>
      </div>`;

    restoreChoices();
    const search = view.querySelector('[data-msg-create-search]');
    if (search) {
      // [E1] 재렌더 후에도 검색어 유지(state.filter)
      const applyFilter = () => {
        const q = (search.value || '').trim().toLowerCase();
        state.filter = q;
        view.querySelectorAll('.vf-msg-pick').forEach((el) => {
          el.style.display = (!q || (el.getAttribute('data-msg-pick-key') || '').includes(q)) ? '' : 'none';
        });
      };
      if (state.filter) {
        search.value = state.filter;
        applyFilter();
      }
      search.addEventListener('input', applyFilter);
    }
  };

  const renderMsgCreate = () => {
    const view = panel.querySelector('[data-panel-view="messages"]');
    if (!view) return;
    const people = flattenEmployees();
    messageCreateState = { tab: 'employees', selectedIds: new Set(), checkedOrgs: new Set() };
    renderMsgCreateBody(view, ensureMsgCreateState(), people);
    return;
    view.innerHTML = `
      <div class="vf-msg-create">
        <div class="vf-msg-create-bar">
          <button class="rbtn" type="button" data-msg-create-cancel>${wsIcon('arrow_back')} 목록</button>
          <button class="btn primary" type="button" data-msg-create-submit>생성</button>
        </div>
        <input type="search" class="vf-panel-search-input" data-msg-create-search placeholder="직원 검색 (이름/팀/전화번호)" autocomplete="off">
        <div class="vf-msg-create-list">
          ${people.map((u) => `
            <label class="vf-msg-pick" data-msg-pick-key="${escapeHtml(u.key)}">
              <input type="checkbox" value="${escapeHtml(u.id)}">
              <span><b>${escapeHtml(u.name)}</b><em>${escapeHtml(u.team)}</em></span>
            </label>`).join('') || '<div class="vf-panel-empty">표시할 직원이 없습니다</div>'}
        </div>
      </div>`;
    const s = view.querySelector('[data-msg-create-search]');
    if (s) s.addEventListener('input', () => {
      const q = s.value.trim().toLowerCase();
      view.querySelectorAll('.vf-msg-pick').forEach((el) => {
        el.style.display = (!q || (el.getAttribute('data-msg-pick-key') || '').includes(q)) ? '' : 'none';
      });
    });
  };

  const chatMemberSummary = (members = []) => {
    const count = members.length;
    if (!count) return '참여자 0명';
    const visibleCount = count >= 5 ? 3 : count;
    const names = members.slice(0, visibleCount).map((member) => member.userName).filter(Boolean);
    const nameText = names.join(', ');
    const restText = count >= 5 ? ` 외 ${count - visibleCount}명` : '';
    return `참여자 ${count}명${nameText ? ` · ${nameText}${restText}` : ''}`;
  };

  const renderChatParticipants = (members = []) => {
    const wrap = chatDock?.querySelector('[data-chat-participants]');
    if (!wrap) return;
    if (!members.length) {
      wrap.innerHTML = '<div class="vf-chat-invite-empty">참여자가 없습니다</div>';
      return;
    }
    wrap.innerHTML = `
      <div class="vf-chat-participants-head">
        <strong>참여 인원 ${members.length}명</strong>
        <button type="button" data-chat-participants-close aria-label="참여자 목록 닫기">${wsIcon('close')}</button>
      </div>
      <div class="vf-chat-participant-list">
        ${members.map((member) => `
          <button class="vf-chat-participant-row" type="button" data-chat-member-user-id="${escapeHtml(member.userId)}">
            ${avatar(member.profileImageFilename, 'vf-chat-avatar')}
            <span><b>${escapeHtml(member.userName || '')}</b><em>${escapeHtml(member.teamName || '미지정')}</em></span>
          </button>`).join('')}
      </div>`;
  };

  const clearChatSearchMarks = (root) => {
    root?.querySelectorAll('mark.vf-chat-hit').forEach((mark) => {
      mark.replaceWith(document.createTextNode(mark.textContent || ''));
    });
    root?.normalize();
  };

  const setActiveChatSearchMatch = (index = 0) => {
    const matches = chatSearchState.matches;
    matches.forEach((mark) => mark.classList.remove('is-active'));
    const counter = chatDock?.querySelector('[data-chat-search-count]');
    if (!matches.length) {
      if (counter) counter.textContent = '0/0';
      return;
    }
    chatSearchState.index = ((index % matches.length) + matches.length) % matches.length;
    const active = matches[chatSearchState.index];
    active.classList.add('is-active');
    if (counter) counter.textContent = `${chatSearchState.index + 1}/${matches.length}`;
    active.scrollIntoView({ block: 'center', behavior: 'smooth' });
  };

  const applyChatSearch = (resetIndex = false) => {
    const list = chatDock?.querySelector('[data-chat-messages]');
    const input = chatDock?.querySelector('[data-chat-search-input]');
    if (!list || !input) return;
    clearChatSearchMarks(list);
    const query = input.value.trim();
    chatSearchState.matches = [];
    if (!query) {
      const counter = chatDock?.querySelector('[data-chat-search-count]');
      if (counter) counter.textContent = '0/0';
      return;
    }

    const matcher = new RegExp(escapeRegExp(query), 'gi');
    const targets = [...list.querySelectorAll('.vf-chat-body, .chat-msg-system span')];
    targets.forEach((target) => {
      const walker = document.createTreeWalker(target, NodeFilter.SHOW_TEXT);
      const nodes = [];
      while (walker.nextNode()) nodes.push(walker.currentNode);
      nodes.forEach((node) => {
        const text = node.nodeValue || '';
        matcher.lastIndex = 0;
        if (!matcher.test(text)) return;
        matcher.lastIndex = 0;
        const frag = document.createDocumentFragment();
        let lastIndex = 0;
        text.replace(matcher, (match, offset) => {
          if (offset > lastIndex) frag.appendChild(document.createTextNode(text.slice(lastIndex, offset)));
          const mark = document.createElement('mark');
          mark.className = 'vf-chat-hit';
          mark.textContent = match;
          frag.appendChild(mark);
          chatSearchState.matches.unshift(mark);
          lastIndex = offset + match.length;
          return match;
        });
        if (lastIndex < text.length) frag.appendChild(document.createTextNode(text.slice(lastIndex)));
        node.replaceWith(frag);
      });
    });
    setActiveChatSearchMatch(resetIndex ? 0 : chatSearchState.index);
  };

  const submitMsgCreate = async () => {
    const view = panel.querySelector('[data-panel-view="messages"]');
    if (!view) return;
    if (messageCreateState) {
      const ids = selectedUserIds(ensureMsgCreateState());
      if (!ids.length) {
        const s = view.querySelector('[data-msg-create-search]');
        if (s) { s.placeholder = '한 명 이상 선택하세요'; s.focus(); }
        return;
      }
      try {
        const res = await fetch('/api/messages/rooms', {
          method: 'POST',
          headers: csrfHeaders({ 'Content-Type': 'application/json' }),
          body: JSON.stringify({ recipientIds: ids })
        });
        if (!res.ok) return;
        const payload = await res.json();
        messageCreateState = null;
        renderChat(payload, true);
        load();
      } catch (error) {
        // 보조 채팅 UI 실패는 페이지 흐름을 막지 않는다.
      }
      return;
    }
    const ids = [...view.querySelectorAll('.vf-msg-pick input:checked')].map((c) => Number(c.value));
    if (!ids.length) {
      const s = view.querySelector('[data-msg-create-search]');
      if (s) { s.placeholder = '한 명 이상 선택하세요'; s.focus(); }
      return;
    }
    try {
      const res = await fetch('/api/messages/rooms', {
        method: 'POST',
        headers: csrfHeaders({ 'Content-Type': 'application/json' }),
        body: JSON.stringify({ recipientIds: ids })
      });
      if (!res.ok) return;
      const payload = await res.json();
      renderChat(payload, true);
      load();
    } catch (error) {
      // 보조 채팅 UI 실패는 페이지 흐름을 막지 않는다.
    }
  };

  const render = () => {
    if (!latestPayload) return;
    setCount('notifications', latestPayload.unreadNotificationCount);
    setCount('messages', latestPayload.unreadMessageCount);
    renderContacts(latestPayload.groups || []);
    renderNotifications(visibleNotifications(latestPayload.notifications || []));
    renderMessages(latestPayload.rooms || []);
    // [1.0.89] 자료전송 미열람 카운트는 별도 엔드포인트 — 패널 새로고침 사이클에 같이 업데이트.
    if (messagingEnabled) fetch('/api/work-memos/unread-count', { credentials: 'same-origin', headers: { Accept: 'application/json' } })
      .then(r => r.ok ? r.json() : null)
      .then(d => { if (d && typeof d.count === 'number') setCount('workMemos', d.count); })
      .catch(() => {});
    // 자료전송 탭이 활성이면 목록도 같이 갱신 (다른 사용자가 보낸 메모 즉시 반영)
    if (messagingEnabled && activeTab === 'workMemos') loadWorkMemos();
  };

  const renderChat = (payload, force = false) => {
    if (!chatDock || !payload?.room) return;
    window.__lastChatPayload = payload;
    const nextRoomId = payload.room.id;
    const roomChanged = Number(currentChatRoomId || 0) !== Number(nextRoomId || 0);
    if (roomChanged) {
      pendingMessages = [];
      chatOlderMessages = [];
      chatHasMore = false;
      chatLoadingOlder = false;
      chatAnchorTop = false;
      markRoomNotificationsReadOnce(nextRoomId);
      const searchInput = chatDock.querySelector('[data-chat-search-input]');
      if (searchInput) searchInput.value = '';
      chatSearchState = { matches: [], index: 0 };
    } else {
      // 같은 방이라도 새 메시지 알림이 와있을 수 있으니 주기적으로 읽음 처리
      markRoomNotificationsReadOnce(nextRoomId);
    }
    currentChatRoomId = nextRoomId;
    chatDock.hidden = false;
    chatDock.classList.add('open');
    chatDock.dataset.roomId = String(payload.room.id);
    const titleNode = chatDock.querySelector('[data-chat-title]');
    if (titleNode) {
      titleNode.innerHTML = `
        ${payload.room.pinned ? roomFlagIcon('pin') : ''}
        ${payload.room.muted ? roomFlagIcon('muted') : ''}
        <span>${escapeHtml(payload.room.title || '메시지')}</span>`;
    }
    const avatarNode = chatDock.querySelector('[data-chat-avatar]');
    if (avatarNode) {
      avatarNode.outerHTML = roomAvatarHtml(payload.room.avatarMembers || []);
      chatDock.querySelector('.vf-room-avatar')?.setAttribute('data-chat-avatar', '');
    }
    const pinToggle = chatDock.querySelector('[data-chat-pin-toggle]');
    if (pinToggle) pinToggle.textContent = payload.room.pinned ? '채팅방 상단 고정 해제' : '채팅방 상단 고정';
    const muteToggle = chatDock.querySelector('[data-chat-mute-toggle]');
    if (muteToggle) muteToggle.textContent = payload.room.muted ? '채팅방 알림 켜기' : '채팅방 알림 끄기';
    currentChatMembers = payload.members || [];
    const membersLine = chatDock.querySelector('[data-chat-members]');
    if (membersLine) {
      membersLine.textContent = chatMemberSummary(currentChatMembers);
      membersLine.title = currentChatMembers.map((member) => member.userName).filter(Boolean).join(', ');
    }
    const participantPanel = chatDock.querySelector('[data-chat-participants]');
    if ((force || roomChanged) && participantPanel) {
      participantPanel.hidden = true;
    }
    renderChatParticipants(currentChatMembers);

    chatRecentMessages = payload.messages || [];
    // 최근 스트림만 도착했을 때(과거 미로드)에는 payload.hasMore가 기준. 과거 로드 후엔 /before 응답이 기준이라 유지.
    if (chatOlderMessages.length === 0 && Object.prototype.hasOwnProperty.call(payload, 'hasMore')) {
      chatHasMore = !!payload.hasMore;
    }
    // 과거 누적분 + 최근분을 id 기준 중복 제거하여 합친다(과거가 앞).
    const seenIds = new Set();
    const messages = [];
    for (const m of chatOlderMessages.concat(chatRecentMessages)) {
      const id = m && m.id != null ? Number(m.id) : null;
      if (id != null) { if (seenIds.has(id)) continue; seenIds.add(id); }
      messages.push(m);
    }
    const currentUserId = Number(payload.currentUserId || latestPayload?.currentUserId || 0);

    // 서버에 도착한 메시지 본문과 일치하는 pending은 제거 (id가 없어도 본문 + sender로 매칭)
    pendingMessages = pendingMessages.filter((p) =>
      p.uploading || !messages.some((m) => Number(m.senderUserId) === currentUserId && m.body === p.body)
    );

    const signature = JSON.stringify(messages.map((msg) => [msg.id, msg.body, msg.senderProfileImageFilename, (msg.attachments || []).map((a) => a.id).join(',')]))
      + '|p=' + pendingMessages.map((p) => p.tempId + ':' + (p.failed ? 'F' : 'P')).join(',');
    const list = chatDock.querySelector('[data-chat-messages]');
    if (force || signature !== currentChatSignature) {
      const prevH = list.scrollHeight;
      const prevT = list.scrollTop;
      const wasNearBottom = (prevH - prevT - list.clientHeight) < 120;
      currentChatSignature = signature;
      if (!messages.length && !pendingMessages.length) {
        list.innerHTML = '<div class="vf-chat-empty">아직 메시지가 없습니다</div>';
      } else {
        const renderRow = (msg, pending) => {
          if (msg.senderUserId == null && !pending) {
            return `<div class="chat-msg-system"><span>${escapeHtml(msg.body)}</span></div>`;
          }
          const mine = pending || Number(msg.senderUserId) === currentUserId;
          const isUploading = Boolean(pending && msg.uploading);
          const timeContent = (pending || isUploading)
            ? '<span class="vf-spinner" aria-label="전송 중..."></span>'
            : escapeHtml(formatTime(msg.createdAt));
          const nameLine = !mine ? `<div class="vf-chat-meta"><b>${escapeHtml(msg.senderName)}</b></div>` : '';
          const failTag = pending && msg.failed ? ' is-failed' : '';
          const attachments = msg.attachments || [];
          const imageOnly = !msg.body && attachments.length && attachments.every((a) => String(a.attachmentType || '').toUpperCase() === 'IMAGE');
          const bodyHtml = msg.body ? `<div class="vf-chat-body">${escapeHtml(msg.body)}</div>` : '';
          const uploadHtml = isUploading
            ? `<div class="vf-chat-upload">
                 <div class="vf-chat-upload-info">${escapeHtml(msg.uploadInfo || '업로드 중')}</div>
                 <div class="vf-chat-upload-bar"><div class="vf-chat-upload-fill" style="width:${msg.uploadPercent || 0}%"></div></div>
                 <button class="vf-chat-upload-cancel" type="button" data-chat-upload-cancel-id="${msg.tempId}" title="취소">${wsIcon('close')}</button>
               </div>`
            : '';
          const attachmentsHtml = renderAttachments(attachments);
          return `<article class="vf-chat-msg ${mine ? 'mine' : ''}${failTag}">
            ${!mine ? avatar(msg.senderProfileImageFilename, 'vf-chat-avatar') : ''}
            <div class="vf-chat-bubble${imageOnly ? ' image-only' : ''}">
              ${nameLine}
              ${bodyHtml}
              ${uploadHtml}
              ${attachmentsHtml}
            </div>
            <span class="vf-chat-time${pending ? ' is-pending' : ''}">${timeContent}</span>
          </article>`;
        };

        const allNormal = [
          ...messages.map((m) => ({ ...m, pending: false })),
          ...pendingMessages.filter((p) => !p.uploading).map((p) => ({
            senderUserId: currentUserId,
            senderName: '나',
            body: p.body,
            createdAt: p.sentAt,
            failed: p.failed,
            pending: true
          }))
        ];
        const getTime = (v) => {
          if (!v) return 0;
          if (Array.isArray(v)) return new Date(v[0], v[1] - 1, v[2], v[3] || 0, v[4] || 0, v[5] || 0).getTime();
          return new Date(v).getTime();
        };
        allNormal.sort((a, b) => getTime(a.createdAt) - getTime(b.createdAt));

        let lastDateKey = '';
        const normalHtml = allNormal.map((m) => {
          const key = chatDateKey(m.createdAt);
          const dateHtml = key && key !== lastDateKey
            ? `<div class="vf-chat-date"><span>${escapeHtml(chatDateLabel(m.createdAt))}</span></div>`
            : '';
          if (key) lastDateKey = key;
          return dateHtml + renderRow(m, m.pending);
        }).join('');

        const uploadingHtml = pendingMessages.filter((p) => p.uploading).map((p) => renderRow({
          tempId: p.tempId,
          senderUserId: currentUserId,
          senderName: '나',
          body: p.body,
          failed: p.failed,
          uploading: true,
          uploadInfo: `파일 ${p.fileCount}개 · ${p.sizeLabel} · ${p.percent || 0}%`,
          uploadPercent: p.percent || 0,
        }, true)).join('');

        list.innerHTML = normalHtml + uploadingHtml;
      }
      const stick = !chatAnchorTop && (roomChanged || force || wasNearBottom);
      if (chatAnchorTop) {
        // 과거 로드 직후: 늘어난 높이만큼 내려 보던 위치 유지
        list.scrollTop = prevT + (list.scrollHeight - prevH);
        chatAnchorTop = false;
      } else if (stick) {
        list.scrollTop = list.scrollHeight;
      }
      // 이미지가 늦게 로드돼 높이가 커져도, 하단 고정 상태일 때만 최하단 유지.
      list.querySelectorAll('img').forEach((img) => {
        if (img.complete) return;
        img.addEventListener('load', () => { if (stick) list.scrollTop = list.scrollHeight; }, { once: true });
      });
      applyChatSearch(true);
    }
  };

  const appendCreatedMessage = (payload) => {
    const roomId = Number(payload?.roomId || 0);
    const message = payload?.message;
    if (!roomId || !message || !window.__lastChatPayload) return false;
    if (!currentChatRoomId || Number(currentChatRoomId) !== roomId) return false;

    const messages = window.__lastChatPayload.messages || [];
    if (messages.some((m) => Number(m.id) === Number(message.id))) return true;
    window.__lastChatPayload.messages = [...messages, message];
    renderChat(window.__lastChatPayload, true);
    return true;
  };

  const updateOnlineUsers = (onlineUserIds = [], awayUserIds = []) => {
    if (!presenceEnabled) return;
    const online = new Set(onlineUserIds.map((id) => Number(id)));
    const awaySet = new Set(awayUserIds.map((id) => Number(id)));
    const mark = (user) => {
      user.online = online.has(Number(user.userId));
      user.away = user.online && awaySet.has(Number(user.userId));
    };
    if (latestPayload?.groups) {
      latestPayload.groups.forEach((group) => (group.teams || []).forEach((team) => (team.users || []).forEach(mark)));
    }
    const updateNodes = (nodes) => (nodes || []).forEach((node) => {
      (node.members || []).forEach(mark);
      updateNodes(node.children);
    });
    updateNodes(latestPayload?.orgTree);
    const view = panel.querySelector('[data-panel-view="contacts"]');
    if (!view) return;
    const rows = view.querySelectorAll('[data-employee-user-id]');
    if (!rows.length) {
      renderContacts(latestPayload?.groups || []);
      return;
    }
    rows.forEach((row) => {
      const isOnline = online.has(Number(row.dataset.employeeUserId));
      const isAway = isOnline && awaySet.has(Number(row.dataset.employeeUserId));
      row.dataset.empOnline = isOnline ? '1' : '0';
      const dot = row.querySelector('.vf-online-dot');
      if (!dot) return;
      dot.classList.toggle('online', isOnline && !isAway);
      dot.classList.toggle('away', isAway);
      dot.classList.toggle('offline', !isOnline);
      const label = isAway ? '자리비움' : isOnline ? '로그인 중' : '오프라인';
      dot.title = label;
      dot.setAttribute('aria-label', label);
    });
    view.querySelector('[data-contacts-search]')?.dispatchEvent(new Event('input'));
  };

  const findEmployee = (userId) => {
    const targetId = Number(userId);
    for (const group of latestPayload?.groups || []) {
      for (const team of group.teams || []) {
        const found = (team.users || []).find((user) => Number(user.userId) === targetId);
        if (found) return found;
      }
    }
    return null;
  };

  const findChatMember = (userId) => {
    const targetId = Number(userId);
    return currentChatMembers.find((member) => Number(member.userId) === targetId);
  };

  const memberToEmployee = (member) => member ? ({
    userId: member.userId,
    name: member.userName || '',
    teamName: member.teamName || '',
    profileImageFilename: member.profileImageFilename,
    statusLabel: '예정'
  }) : null;

  const infoValue = (value) => escapeHtml(value || '-');

  const showEmployeeModal = (user) => {
    if (!user) return;
    resetModalPosition();
    const isMe = Number(user.userId) === Number(latestPayload?.currentUserId || 0);
    const isContractor = Boolean(user.contractor);
    employeeModal.innerHTML = `
      <div class="vf-emp-head">
        ${avatar(user.profileImageFilename, 'vf-employee-avatar')}
        <div>
          <strong>${escapeHtml(user.name)}${isMe ? ' · 나' : ''}</strong>
          <span>${escapeHtml(user.teamName || '')}${user.dept ? ` · ${escapeHtml(user.dept)}` : ''}</span>
        </div>
      </div>
      <div class="vf-emp-body">
        ${user.orgRole ? `<div class="vf-emp-row"><span>조직역할</span><span>${escapeHtml(user.orgRole)}</span></div>` : ''}
        ${!attendanceEnabled ? '' : isContractor ? '<div class="vf-emp-row"><span>구분</span><span>도급</span></div>' : `<div class="vf-emp-row"><span>상태</span><span>${escapeHtml(user.statusLabel || '예정')}</span></div>`}
        <div class="vf-emp-row"><span>담당업무</span><span>${infoValue(user.jobTitle)}</span></div>
        <div class="vf-emp-row"><span>내선번호</span><span>${infoValue(user.extensionNumber)}</span></div>
        <div class="vf-emp-row"><span>핸드폰번호</span><span>${infoValue(user.mobileNumber)}</span></div>
        <div class="vf-emp-row"><span>이메일</span><span>${infoValue(user.email)}</span></div>
      </div>
      <div class="vf-emp-actions">
        ${(!messagingEnabled || isMe || isContractor) ? '' : `<button class="rbtn acc" type="button" data-employee-direct-id="${escapeHtml(user.userId)}">1:1 대화</button>`}
        <button class="rbtn" type="button" data-employee-close>닫기</button>
      </div>`;
    employeeModal.hidden = false;
  };

  const hideEmployeeModal = () => {
    employeeModal.hidden = true;
  };

  const resetModalPosition = () => {
    employeeModal.style.left = '';
    employeeModal.style.right = '';
    employeeModal.style.top = '';
    employeeModal.style.bottom = '';
  };

  // 클릭한 chip 기준으로, 화면 공간에 따라 chip 아래(공간 부족 시 위)에 띄움
  const positionModalNear = (anchor) => {
    if (!anchor || !anchor.getBoundingClientRect) { resetModalPosition(); return; }
    const r = anchor.getBoundingClientRect();
    const vw = window.innerWidth, vh = window.innerHeight;
    const mw = employeeModal.offsetWidth || 300;
    const mh = employeeModal.offsetHeight || 260;
    const gap = 8, pad = 12;
    let left = r.left;
    if (left + mw > vw - pad) left = vw - mw - pad;
    if (left < pad) left = pad;
    const spaceBelow = vh - r.bottom;
    const spaceAbove = r.top;
    let top;
    if (spaceBelow >= mh + gap || spaceBelow >= spaceAbove) {
      top = Math.min(r.bottom + gap, vh - mh - pad);
    } else {
      top = Math.max(r.top - mh - gap, pad);
    }
    employeeModal.style.right = 'auto';
    employeeModal.style.bottom = 'auto';
    employeeModal.style.left = left + 'px';
    employeeModal.style.top = top + 'px';
  };

  // 외부(공지 댓글/작성자 등)에서 서버가 내려준 직원 정보로 동일 모달 호출
  window.vfShowEmployee = (u, anchor) => {
    if (!u || !u.userId) return;
    showEmployeeModal(u);
    if (anchor) positionModalNear(anchor); else resetModalPosition();
  };

  const readNotificationAndGo = async (link) => {
    const notificationId = link.dataset.notificationId;
    const targetUrl = link.href || '/notifications';

    // 메시지 알림 → 채팅 dock만 오픈, 페이지 이동 없음
    let messageRoomId = null;
    try {
      const u = new URL(targetUrl, window.location.origin);
      messageRoomId = u.searchParams.get('messageRoomId');
    } catch { }

    const openTarget = () => {
      if (messageRoomId) {
        openChatRoom(messageRoomId);
        return;
      }
      if (window.vfIsDocumentPopupUrl?.(targetUrl) && window.vfOpenDocumentPopup) {
        window.vfOpenDocumentPopup(targetUrl);
        panel.classList.remove('open');
        load();
        return;
      }
      let internal = false;
      try { internal = new URL(targetUrl, window.location.href).origin === window.location.origin; } catch {}
      if (internal && typeof window.worksystemSoftNavigate === 'function') window.worksystemSoftNavigate(targetUrl);
      else window.location.href = targetUrl;
    };
    if (!notificationId) {
      openTarget();
      return;
    }
    try {
      await fetch(`/api/notifications/${encodeURIComponent(notificationId)}/read`, {
        method: 'POST',
        headers: csrfHeaders()
      });
    } finally {
      openTarget();
    }
  };

  const deleteNotification = async (button) => {
    const notificationId = button.dataset.notificationDelete;
    if (!notificationId) return;
    button.disabled = true;
    try {
      const response = await fetch(`/api/notifications/${encodeURIComponent(notificationId)}/delete`, {
        method: 'POST',
        headers: csrfHeaders()
      });
      if (response.ok) {
        latestPayload = await response.json();
        render();
      }
    } finally {
      button.disabled = false;
    }
  };

  const openChatRoom = async (roomId) => {
    if (!messagingEnabled) return;
    panel.classList.add('open');
    try {
      const response = await fetch(`/api/messages/${encodeURIComponent(roomId)}?limit=${CHAT_PAGE_SIZE}`, { headers: { Accept: 'application/json' } });
      if (!response.ok) return;
      const payload = await response.json();
      renderChat(payload, true);
      // 채팅방을 연 순간 해당 방의 모든 메시지 알림 자동 읽음 처리
      fetch(`/api/messages/${encodeURIComponent(roomId)}/read-notifications`, {
        method: 'POST',
        headers: csrfHeaders(),
        credentials: 'same-origin',
      }).catch(() => null).finally(() => load());
    } catch (error) {
      // 다음 클릭 또는 실시간 이벤트에서 복구한다.
    }
  };

  const loadOlderMessages = async () => {
    if (chatLoadingOlder || !chatHasMore || !currentChatRoomId) return;
    const all = chatOlderMessages.concat(chatRecentMessages).filter((m) => m && m.id != null);
    if (!all.length) return;
    const oldestId = all.reduce((min, m) => Math.min(min, Number(m.id)), Infinity);
    if (!isFinite(oldestId)) return;
    chatLoadingOlder = true;
    try {
      const res = await fetch(`/api/messages/${encodeURIComponent(currentChatRoomId)}/before?beforeId=${oldestId}&limit=${CHAT_PAGE_SIZE}`,
        { headers: { Accept: 'application/json' }, credentials: 'same-origin' });
      if (!res.ok) return;
      const page = await res.json();
      const older = page.messages || [];
      if (older.length) {
        const existing = new Set(chatOlderMessages.map((m) => Number(m.id)));
        const add = older.filter((m) => m && m.id != null && !existing.has(Number(m.id)));
        chatOlderMessages = add.concat(chatOlderMessages);
      }
      chatHasMore = !!page.hasMore;
      chatAnchorTop = true;
      if (window.__lastChatPayload) renderChat(window.__lastChatPayload, true);
    } catch (error) {
      // 무시 — 다음 스크롤에서 재시도
    } finally {
      chatLoadingOlder = false;
    }
  };

  chatDock?.querySelector('[data-chat-messages]')?.addEventListener('scroll', (event) => {
    const el = event.currentTarget;
    if (el.scrollTop <= 60 && chatHasMore && !chatLoadingOlder) {
      loadOlderMessages();
    }
  });

  const updateChatRoomSettings = async (changes) => {
    if (!currentChatRoomId) return;
    const room = currentRoom();
    if (!room) return;
    const payload = {
      customTitle: Object.prototype.hasOwnProperty.call(changes, 'customTitle') ? changes.customTitle : room.customTitle,
      pinned: Object.prototype.hasOwnProperty.call(changes, 'pinned') ? changes.pinned : Boolean(room.pinned),
      muted: Object.prototype.hasOwnProperty.call(changes, 'muted') ? changes.muted : Boolean(room.muted),
      opacityPercent: room.opacityPercent || 100
    };
    const response = await fetch(`/api/messages/${encodeURIComponent(currentChatRoomId)}/settings`, {
      method: 'POST',
      headers: csrfHeaders({ 'Content-Type': 'application/json', Accept: 'application/json' }),
      credentials: 'same-origin',
      body: JSON.stringify(payload)
    });
    if (!response.ok) return;
    const updatedRoom = await response.json();
    if (window.__lastChatPayload?.room) window.__lastChatPayload.room = updatedRoom;
    if (latestPayload?.rooms) {
      const found = latestPayload.rooms.find((item) => Number(item.id) === Number(updatedRoom.id));
      if (found) Object.assign(found, updatedRoom);
    }
    renderChat({ ...(window.__lastChatPayload || {}), room: updatedRoom }, true);
    load();
  };

  const openDirectRoom = async (userId) => {
    if (!messagingEnabled) return;
    panel.classList.add('open');
    try {
      const response = await fetch(`/api/messages/direct/${encodeURIComponent(userId)}`, {
        method: 'POST',
        headers: csrfHeaders()
      });
      if (!response.ok) return;
      const payload = await response.json();
      renderChat(payload, true);
      openPanel('messages');
      load();
    } catch (error) {
      // 보조 채팅 UI 실패는 페이지 흐름을 막지 않는다.
    }
  };

  panel.addEventListener('click', async (event) => {
    const noticeToggle = event.target.closest('[data-notice-toggle]');
    if (noticeToggle) {
      event.preventDefault();
      event.stopPropagation();
      const group = noticeToggle.closest('.vf-notice-group');
      if (group) group.classList.toggle('is-open');
      return;
    }
    const notificationGroupDelete = event.target.closest('[data-notification-group-delete]');
    if (notificationGroupDelete) {
      event.preventDefault();
      event.stopPropagation();
      const ids = String(notificationGroupDelete.dataset.notificationGroupDelete || '').split(',').map((s) => s.trim()).filter(Boolean);
      const total = ids.length;
      if (total === 0) return;
      const progress = createDeleteProgress(total);
      let done = 0;
      Promise.all(ids.map((id) => fetch('/api/notifications/' + encodeURIComponent(id) + '/delete', {
        method: 'POST',
        headers: csrfHeaders(),
        credentials: 'same-origin',
      }).catch(() => null).finally(() => {
        done += 1;
        progress.update(done);
      }))).finally(() => {
        progress.close();
        scheduleLoad();
      });
      return;
    }
    const notificationDelete = event.target.closest('[data-notification-delete]');
    if (notificationDelete) {
      event.preventDefault();
      event.stopPropagation();
      deleteNotification(notificationDelete);
      return;
    }
    // [W9] 알림 모두 읽음
    const notificationReadAll = event.target.closest('[data-notification-read-all]');
    if (notificationReadAll) {
      event.preventDefault();
      event.stopPropagation();
      notificationReadAll.disabled = true;
      fetch('/api/notifications/read-all', { method: 'POST', headers: csrfHeaders(), credentials: 'same-origin' })
        .catch(() => null)
        .finally(() => { notificationReadAll.disabled = false; scheduleLoad(); });
      return;
    }
    const notificationLink = event.target.closest('[data-notification-id]');
    if (notificationLink) {
      event.preventDefault();
      readNotificationAndGo(notificationLink);
      return;
    }
    if (event.target.closest('[data-msg-create-submit]')) {
      event.preventDefault();
      submitMsgCreate();
      return;
    }
    const msgCreateTab = event.target.closest('[data-msg-create-tab]');
    if (msgCreateTab) {
      event.preventDefault();
      const state = ensureMsgCreateState();
      const next = msgCreateTab.dataset.msgCreateTab || 'employees';
      if (next !== state.tab) {
        if (!(await confirmTabSwitch(state))) return;
        state.selectedIds.clear();
        state.checkedOrgs.clear();
        state.tab = next;
      }
      renderMsgCreateBody(panel.querySelector('[data-panel-view="messages"]'), state, flattenEmployees());
      return;
    }
    const msgOrgPick = event.target.closest('[data-msg-org-pick]');
    if (msgOrgPick && messageCreateState) {
      event.preventDefault();
      const teamId = Number(msgOrgPick.dataset.msgOrgPick || 0);
      const state = ensureMsgCreateState();
      const node = findOrgNode(teamId);
      if (node) toggleOrgSubtreeOnly(node, state.checkedOrgs);
      renderMsgCreateBody(panel.querySelector('[data-panel-view="messages"]'), state, flattenEmployees());
      return;
    }
    const msgChipOrg = event.target.closest('[data-msg-chip-org]');
    if (msgChipOrg && messageCreateState) {
      event.preventDefault();
      const teamId = Number(msgChipOrg.dataset.msgChipOrg || 0);
      const node = findOrgNode(teamId);
      const state = ensureMsgCreateState();
      if (node) toggleOrgSubtreeOnly(node, state.checkedOrgs); // 칩 ×: 해당 노드 + 하위 해제
      renderMsgCreateBody(panel.querySelector('[data-panel-view="messages"]'), state, flattenEmployees());
      return;
    }
    const msgChipId = event.target.closest('[data-msg-chip-id]');
    if (msgChipId && messageCreateState) {
      event.preventDefault();
      ensureMsgCreateState().selectedIds.delete(Number(msgChipId.dataset.msgChipId || 0));
      renderMsgCreateBody(panel.querySelector('[data-panel-view="messages"]'), ensureMsgCreateState(), flattenEmployees());
      return;
    }
    const msgPick = event.target.closest('[data-msg-pick-id]');
    if (msgPick && messageCreateState) {
      event.preventDefault();
      const id = Number(msgPick.dataset.msgPickId || 0);
      const state = ensureMsgCreateState();
      if (state.selectedIds.has(id)) state.selectedIds.delete(id);
      else if (id) state.selectedIds.add(id);
      renderMsgCreateBody(panel.querySelector('[data-panel-view="messages"]'), state, flattenEmployees());
      return;
    }
    if (event.target.closest('[data-msg-create-cancel]')) {
      event.preventDefault();
      messageCreateState = null;
      renderMessages(latestPayload?.rooms || []);
      return;
    }
    if (event.target.closest('[data-msg-create]')) {
      event.preventDefault();
      renderMsgCreate();
      const s = panel.querySelector('[data-msg-create-search]');
      if (s) s.focus();
      return;
    }
    const roomButton = event.target.closest('[data-room-id]');
    if (roomButton) {
      event.preventDefault();
      openChatRoom(roomButton.dataset.roomId);
      return;
    }
    const employeeButton = event.target.closest('[data-employee-user-id]');
    if (employeeButton) {
      if (event.target.closest('[data-file-viewer-url],[data-receipt-url]')) return;
      event.preventDefault();
      showEmployeeModal(findEmployee(employeeButton.dataset.employeeUserId));
    }
  });

  employeeModal.addEventListener('click', (event) => {
    const directButton = event.target.closest('[data-employee-direct-id]');
    if (directButton) {
      event.preventDefault();
      hideEmployeeModal();
      openDirectRoom(directButton.dataset.employeeDirectId);
      return;
    }
    if (event.target.closest('[data-employee-close]')) {
      event.preventDefault();
      hideEmployeeModal();
    }
  });

  chatDock?.querySelector('[data-chat-close]')?.addEventListener('click', () => {
    if (activeUploads.size > 0 && !confirm('현재 파일 업로드가 진행 중입니다. 중단하고 닫으시겠습니까?')) return;
    chatDock.hidden = true;
    chatDock.classList.remove('open');
    currentChatRoomId = null;
    currentChatSignature = '';
    currentChatMembers = [];
    const participants = chatDock.querySelector('[data-chat-participants]');
    if (participants) participants.hidden = true;
  });

  chatDock?.querySelector('[data-chat-members]')?.addEventListener('click', () => {
    const participants = chatDock.querySelector('[data-chat-participants]');
    if (participants) participants.hidden = !participants.hidden;
  });

  chatDock?.querySelector('[data-chat-participants]')?.addEventListener('click', (event) => {
    if (event.target.closest('[data-chat-participants-close]')) {
      event.preventDefault();
      event.currentTarget.hidden = true;
      return;
    }
    const row = event.target.closest('[data-chat-member-user-id]');
    if (!row) return;
    event.preventDefault();
    const userId = row.dataset.chatMemberUserId;
    const employee = findEmployee(userId) || memberToEmployee(findChatMember(userId));
    showEmployeeModal(employee);
    positionModalNear(row);
  });

  chatDock?.querySelector('[data-chat-invite-toggle]')?.addEventListener('click', async () => {
    const menu = chatDock.querySelector('[data-chat-menu]');
    if (menu) menu.hidden = true;
    if (!currentChatRoomId || !window.PeoplePicker) return;
    // [S-3] 초대 후보는 열 때만 fetch. 선택 UI 는 공통 사람 선택 모달(PeoplePicker)로 통일.
    try {
      const r = await fetch(`/api/messages/${encodeURIComponent(currentChatRoomId)}/invite-candidates`,
        { headers: { Accept: 'application/json' }, credentials: 'same-origin' });
      if (!r.ok) return;
      const candidates = await r.json();
      if (!candidates.length) { alert('초대할 직원이 없습니다.'); return; }
      const roomId = currentChatRoomId;
      window.PeoplePicker.open({
        title: '참여자 초대',
        confirmLabel: '초대하기',
        people: candidates.map((u) => ({ id: u.id || u.userId, name: u.name, teamName: u.teamName, teamId: u.teamId })),
        tree: latestPayload?.orgTree || null,
        multi: true,
        onConfirm: async (userIds) => {
          if (!userIds.length) return;
          try {
            const response = await fetch(`/api/messages/${encodeURIComponent(roomId)}/invite`, {
              method: 'POST',
              headers: csrfHeaders({ 'Content-Type': 'application/json' }),
              body: JSON.stringify({ userIds })
            });
            if (!response.ok) return;
            renderChat(await response.json(), true);
            load();
          } catch (e) { /* 초대 실패 시 무시 */ }
        }
      });
    } catch (e) { /* 무시 */ }
  });

  const chatMenu = chatDock?.querySelector('[data-chat-menu]');
  const chatMenuToggle = chatDock?.querySelector('[data-chat-menu-toggle]');
  chatMenuToggle?.addEventListener('click', (e) => {
    e.stopPropagation();
    if (chatMenu) chatMenu.hidden = !chatMenu.hidden;
  });
  document.addEventListener('click', (e) => {
    if (!chatMenu || chatMenu.hidden) return;
    if (chatMenu.contains(e.target) || chatMenuToggle.contains(e.target)) return;
    chatMenu.hidden = true;
  });

  chatDock?.addEventListener('click', async (event) => {
    const cancelBtn = event.target.closest('[data-chat-upload-cancel-id]');
    if (cancelBtn) {
      const cancelId = cancelBtn.dataset.chatUploadCancelId;
      const xhr = activeUploads.get(cancelId);
      if (xhr) xhr.abort();
      return;
    }
    const rename = event.target.closest('[data-chat-rename]');
    const pin = event.target.closest('[data-chat-pin-toggle]');
    const mute = event.target.closest('[data-chat-mute-toggle]');
    if (!rename && !pin && !mute) return;

    event.preventDefault();
    event.stopPropagation();
    if (chatMenu) chatMenu.hidden = true;

    const room = currentRoom();
    if (!room) return;
    if (rename) {
      const title = window.vfPrompt
        ? await window.vfPrompt('내 화면에 표시할 채팅방 이름을 입력하세요.', {
          title: '채팅방 이름 변경',
          initialValue: room.customTitle || room.title || '',
          okText: '저장'
        })
        : window.prompt('내 화면에 표시할 채팅방 이름을 입력하세요.', room.customTitle || room.title || '');
      if (title == null) return;
      await updateChatRoomSettings({ customTitle: title.trim() || null });
      return;
    }
    if (pin) {
      await updateChatRoomSettings({ pinned: !room.pinned });
      return;
    }
    if (mute) {
      await updateChatRoomSettings({ muted: !room.muted });
    }
  });

  chatDock?.querySelector('[data-chat-leave]')?.addEventListener('click', async () => {
    const menu = chatDock?.querySelector('[data-chat-menu]');
    if (menu) menu.hidden = true;
    if (!currentChatRoomId) return;
    const ok = window.vfConfirm
      ? await window.vfConfirm('이 메시지방에서 나가시겠습니까?', { okText: '나가기', danger: true })
      : false;
    if (!ok) return;
    try {
      const response = await fetch(`/api/messages/${encodeURIComponent(currentChatRoomId)}/leave`, {
        method: 'POST',
        headers: csrfHeaders()
      });
      if (!response.ok) return;
      chatDock.hidden = true;
      chatDock.classList.remove('open');
      currentChatRoomId = null;
      currentChatSignature = '';
      currentChatMembers = [];
      const participants = chatDock.querySelector('[data-chat-participants]');
      if (participants) participants.hidden = true;
      latestPayload = await response.json();
      render();
    } catch (error) {
      // 나가기 실패 시 현재 대화창을 유지한다.
    }
  });

  const sendChatMessage = async (form) => {
    if (!currentChatRoomId) return;
    const textarea = form.querySelector('textarea[name="body"]');
    const body = textarea.value.trim();
    const files = selectedChatFiles;
    if (!body && !files.length) return;
    if (files.length) {
      const total = files.reduce((sum, file) => sum + file.size, 0);
      if (total > 100 * 1024 * 1024) {
        alert('첨부파일은 한 번에 총 100MB까지 업로드할 수 있습니다.');
        return;
      }
      const sizeLabel = formatBytes(total);
      const fileCount = files.length;
      const fd = new FormData();
      if (body) fd.append('body', body);
      files.forEach((file) => fd.append('files', file));
      textarea.value = '';
      selectedChatFiles = [];
      renderSelectedChatFiles();

      // Optimistic: 업로드 진행 메시지(용량 + 진행바)를 즉시 표시. 입력창은 계속 사용 가능.
      const tempId = 'temp-' + Date.now() + '-' + Math.random().toString(36).slice(2, 6);
      const pendingItem = { tempId, body, uploading: true, percent: 0, sizeLabel, fileCount, failed: false, sentAt: Date.now() };
      pendingMessages.push(pendingItem);
      if (window.__lastChatPayload) renderChat(window.__lastChatPayload, true);

      const roomAtSend = currentChatRoomId;
      const xhr = new XMLHttpRequest();
      xhr.open('POST', `/api/messages/${encodeURIComponent(roomAtSend)}/attachments?limit=${CHAT_PAGE_SIZE}`);
      if (csrfHeader && csrfToken) xhr.setRequestHeader(csrfHeader, csrfToken);
      xhr.setRequestHeader('Accept', 'application/json');
      const setUploadInfo = (text, percent) => {
        // 전체 재렌더 없이 진행바/문구만 갱신 (깜빡임 방지)
        const list = chatDock?.querySelector('[data-chat-messages]');
        const fills = list?.querySelectorAll('.vf-chat-upload-fill');
        const infos = list?.querySelectorAll('.vf-chat-upload-info');
        if (fills && fills.length && percent != null) fills[fills.length - 1].style.width = percent + '%';
        if (infos && infos.length) infos[infos.length - 1].textContent = text;
      };
      xhr.upload.addEventListener('progress', (e) => {
        if (!e.lengthComputable) return;
        pendingItem.percent = Math.max(0, Math.min(100, Math.round((e.loaded / e.total) * 100)));
        setUploadInfo(`파일 ${fileCount}개 · ${sizeLabel} · ${pendingItem.percent}%`, pendingItem.percent);
      });
      // 전송 바이트는 다 나갔고 서버 저장/응답 대기 구간 — 멈춘 것처럼 보이지 않게 안내
      xhr.upload.addEventListener('load', () => {
        setUploadInfo(`파일 ${fileCount}개 · ${sizeLabel} · 서버 저장 중…`, 100);
      });
      xhr.addEventListener('load', () => {
        activeUploads.delete(tempId);
        pendingMessages = pendingMessages.filter((x) => x.tempId !== tempId);
        if (xhr.status >= 200 && xhr.status < 300) {
          try {
            const payload = JSON.parse(xhr.responseText);
            window.__lastChatPayload = payload;
            renderChat(payload, true);
          } catch (err) { /* 응답 파싱 실패는 다음 동기화에서 복구 */ }
          load();
        } else {
          pendingItem.failed = true;
          pendingItem.uploading = false;
          pendingMessages.push(pendingItem);
          if (window.__lastChatPayload) renderChat(window.__lastChatPayload, true);
        }
      });
      xhr.addEventListener('error', () => {
        activeUploads.delete(tempId);
        pendingMessages = pendingMessages.filter((x) => x.tempId !== tempId);
        pendingItem.failed = true;
        pendingItem.uploading = false;
        pendingMessages.push(pendingItem);
        if (window.__lastChatPayload) renderChat(window.__lastChatPayload, true);
      });
      activeUploads.set(tempId, xhr);
      xhr.send(fd);
      return;
    }
    textarea.value = '';

    // Optimistic: 즉시 pending 메시지 추가 후 다시 그리기
    const tempId = 'temp-' + Date.now() + '-' + Math.random().toString(36).slice(2, 6);
    pendingMessages.push({ tempId, body, sentAt: Date.now(), failed: false });
    if (latestPayload?.rooms || currentChatRoomId) {
      // 가짜 payload로 다시 그리기: 기존 메시지는 currentChatSignature가 갖고 있음
      // 강제로 force=true 호출
      if (typeof window.__lastChatPayload === 'object' && window.__lastChatPayload) {
        renderChat(window.__lastChatPayload, true);
      }
    }

    if (window.workSystemLive?.sendMessage?.(currentChatRoomId, body)) {
      return; // 소켓 응답이 오면 renderChat이 pending을 정리
    }
    try {
      const response = await fetch(`/api/messages/${encodeURIComponent(currentChatRoomId)}?limit=${CHAT_PAGE_SIZE}`, {
        method: 'POST',
        headers: csrfHeaders({ 'Content-Type': 'application/json' }),
        body: JSON.stringify({ body })
      });
      if (!response.ok) {
        const p = pendingMessages.find((x) => x.tempId === tempId);
        if (p) p.failed = true;
        if (window.__lastChatPayload) renderChat(window.__lastChatPayload, true);
        return;
      }
      const payload = await response.json();
      window.__lastChatPayload = payload;
      renderChat(payload, true);
      load();
    } catch (error) {
      const p = pendingMessages.find((x) => x.tempId === tempId);
      if (p) p.failed = true;
      if (window.__lastChatPayload) renderChat(window.__lastChatPayload, true);
    }
  };

  const chatForm = chatDock?.querySelector('[data-chat-form]');
  chatForm?.addEventListener('submit', async (event) => {
    event.preventDefault();
    await sendChatMessage(event.currentTarget);
  });
  chatForm?.querySelector('textarea[name="body"]')?.addEventListener('keydown', (event) => {
    if (event.key === 'Enter' && !event.shiftKey && !event.isComposing) {
      event.preventDefault();
      event.currentTarget.form?.requestSubmit();
    }
  });
  chatDock?.querySelector('[data-chat-attach-toggle]')?.addEventListener('click', () => {
    const menu = chatDock.querySelector('[data-chat-attach-menu]');
    if (menu) menu.hidden = !menu.hidden;
  });
  chatDock?.querySelector('[data-chat-attach-menu]')?.addEventListener('click', (event) => {
    const button = event.target.closest('[data-chat-attach-kind]');
    if (!button) return;
    const input = chatDock.querySelector('[data-chat-attach-input]');
    const kind = button.dataset.chatAttachKind;
    if (kind === 'image') input.accept = 'image/*';
    else if (kind === 'video') input.accept = 'video/*';
    else input.accept = '';
    input.click();
    event.currentTarget.hidden = true;
  });
  chatDock?.querySelector('[data-chat-attach-input]')?.addEventListener('change', (event) => {
    selectedChatFiles = selectedChatFiles.concat(Array.from(event.currentTarget.files || []));
    renderSelectedChatFiles();
    event.currentTarget.value = '';
  });
  chatDock?.querySelector('[data-chat-pending-files]')?.addEventListener('click', (event) => {
    const remove = event.target.closest('[data-chat-pending-remove]');
    if (!remove) return;
    const index = Number(remove.dataset.chatPendingRemove);
    if (!Number.isInteger(index)) return;
    selectedChatFiles.splice(index, 1);
    renderSelectedChatFiles();
  });
  chatForm?.querySelector('textarea[name="body"]')?.addEventListener('paste', async (event) => {
    const files = Array.from(event.clipboardData?.files || []).filter((file) => file.type.startsWith('image/'));
    if (!files.length) return;
    event.preventDefault();
    selectedChatFiles = selectedChatFiles.concat(files);
    renderSelectedChatFiles();
  });
  const chatSearchWrap = chatDock?.querySelector('[data-chat-search]');
  const chatSearchInput = chatDock?.querySelector('[data-chat-search-input]');
  let lastSearchQuery = null;
  chatDock?.querySelector('[data-chat-search-toggle]')?.addEventListener('click', () => {
    if (!chatSearchWrap) return;
    chatSearchWrap.hidden = !chatSearchWrap.hidden;
    if (!chatSearchWrap.hidden) {
      chatSearchInput?.focus();
      chatSearchInput?.select();
    } else if (chatSearchInput) {
      chatSearchInput.value = '';
      lastSearchQuery = null;
      chatSearchState.index = 0;
      applyChatSearch(true);
    }
  });
  // 입력만으로는 검색하지 않고, 엔터를 쳐야 검색한다. (같은 검색어에서 엔터 반복 = 다음 결과)
  chatSearchInput?.addEventListener('keydown', (event) => {
    if (event.key !== 'Enter') return;
    event.preventDefault();
    const query = chatSearchInput.value.trim();
    if (query && query === lastSearchQuery && chatSearchState.matches.length) {
      setActiveChatSearchMatch(chatSearchState.index + 1);
    } else {
      lastSearchQuery = query;
      chatSearchState.index = 0;
      applyChatSearch(true);
    }
  });
  // 검색창을 비우면 하이라이트/카운트를 즉시 해제한다.
  chatSearchInput?.addEventListener('input', () => {
    if (!chatSearchInput.value.trim()) {
      lastSearchQuery = null;
      chatSearchState.index = 0;
      applyChatSearch(true);
    }
  });
  chatDock?.querySelector('[data-chat-search-next]')?.addEventListener('click', () => {
    setActiveChatSearchMatch(chatSearchState.index + 1);
  });
  chatDock?.querySelector('[data-chat-search-prev]')?.addEventListener('click', () => {
    setActiveChatSearchMatch(chatSearchState.index - 1);
  });

  const refreshChat = async () => {
    if (!currentChatRoomId || chatDock?.hidden) return;
    try {
      const response = await fetch(`/api/messages/${encodeURIComponent(currentChatRoomId)}?limit=${CHAT_PAGE_SIZE}`, { headers: { Accept: 'application/json' } });
      if (!response.ok) return;
      renderChat(await response.json());
    } catch (error) {
      // 다음 실시간 이벤트 또는 폴백에서 복구한다.
    }
  };

  const load = async () => {
    try {
      const response = await fetch('/api/right-panel', { headers: { Accept: 'application/json' } });
      if (!response.ok) return;
      latestPayload = await response.json();
      render();
      if (!initialMessageRoomOpened) {
        initialMessageRoomOpened = true;
        const roomId = new URLSearchParams(window.location.search).get('messageRoomId');
        if (roomId) {
          openPanel('messages');
          openChatRoom(roomId);
        }
      }
    } catch (error) {
      // 우측 패널은 보조 UI라 네트워크 실패 시 화면 흐름을 막지 않는다.
    }
  };

  const scheduleLoad = () => {
    window.clearTimeout(loadTimer);
    loadTimer = window.setTimeout(load, 120);
  };

  const handleLiveEvent = (event) => {
    window.dispatchEvent(new CustomEvent('worksystem-live', { detail: event }));
    // 웹소켓 이벤트가 오면 종류와 무관하게 직원/본인 정보를 다시 받아온다.
    scheduleLoad();
    const payload = event?.payload || {};
    if (event.type === 'CONNECTED' || event.type === 'PRESENCE_CHANGED') {
      updateOnlineUsers(payload.onlineUserIds || [], payload.awayUserIds || []);
      return;
    }
    if (event.type === 'PROFILE_CHANGED') {
      // [O-11] 직원 트리/메신저의 같은 userId 사진만 in-place 갱신. DOM 통째 교체 X.
      const uid = String(payload.userId || '');
      const fn = String(payload.profileImageFilename || '');
      const ts = Date.now();
      document.querySelectorAll(`img[data-user-id="${uid}"]`).forEach((img) => {
        if (fn) {
          img.src = `/profile-images/${encodeURIComponent(fn)}?cb=${ts}`;
          img.removeAttribute('hidden');
        } else {
          img.src = '';
          img.setAttribute('hidden', 'hidden');
        }
      });
      return;
    }
    if (event.type === 'MESSAGE_CREATED') {
      lastCreatedMessageRoomId = Number(payload.roomId || 0);
      lastCreatedMessageAt = Date.now();
      if (!appendCreatedMessage(payload)) scheduleLoad();
      return;
    }
    if (event.type === 'MESSAGE_CHANGED') {
      const eventRoomId = Number(payload.roomId || 0);
      if (eventRoomId && eventRoomId === lastCreatedMessageRoomId && Date.now() - lastCreatedMessageAt < 2000) {
        return;
      }
      if (currentChatRoomId && Number(currentChatRoomId) === eventRoomId) {
        refreshChat().finally(scheduleLoad);
      } else {
        scheduleLoad();
      }
      return;
    }
    if (event.type === 'PANEL_CHANGED') {
      if (payload.reason === 'message' && Date.now() - lastCreatedMessageAt < 2000) {
        return;
      }
      scheduleLoad();
      return;
    }
    if (event.type === 'ERROR') {
      console.warn(payload.message || '실시간 이벤트 오류');
    }
  };

  const liveUrl = () => `${window.location.protocol === 'https:' ? 'wss' : 'ws'}://${window.location.host}/ws/live`;

  const connectLive = () => {
    if (!('WebSocket' in window)) return;
    if (liveSocket && [WebSocket.OPEN, WebSocket.CONNECTING].includes(liveSocket.readyState)) return;
    liveSocket = new WebSocket(liveUrl());
    liveSocket.addEventListener('open', () => {
      liveConnected = true;
      liveRetry = 0;
    });
    liveSocket.addEventListener('message', (message) => {
      try {
        handleLiveEvent(JSON.parse(message.data));
      } catch (error) {
        // 잘못된 이벤트는 버리고 다음 이벤트를 처리한다.
      }
    });
    liveSocket.addEventListener('close', () => {
      liveConnected = false;
      // [S-8] 첫 끊김은 즉시 재시도(200ms), 이후 지수 백오프 1→2→4→8→16→30s
      liveRetry += 1;
      const delay = liveRetry === 1 ? 200 : Math.min(30000, 1000 * Math.pow(2, Math.min(liveRetry - 2, 5)));
      window.clearTimeout(reconnectTimer);
      reconnectTimer = window.setTimeout(connectLive, delay);
    });
    liveSocket.addEventListener('error', () => {
      liveConnected = false;
    });
  };

  const redirectKicked = () => {
    if (authHeartbeatFailed) return;
    authHeartbeatFailed = true;
    // [E3] alert() 대신 시스템 톤 모달 + 3초 카운트다운 자동 이동
    const go = () => window.location.replace('/login?kicked=true');
    try {
      const bg = document.createElement('div');
      bg.style.cssText = 'position:fixed;inset:0;z-index:99999;background:rgba(15,18,22,.55);display:flex;align-items:center;justify-content:center;padding:16px';
      bg.innerHTML = `
        <div style="background:#fff;border-radius:14px;box-shadow:0 18px 50px rgba(0,0,0,.3);padding:22px 24px;max-width:360px;text-align:center">
          <div style="font-size:30px;margin-bottom:6px">🔒</div>
          <h3 style="margin:0 0 6px;font-size:16px;color:#111827">세션 종료</h3>
          <p style="margin:0 0 14px;color:#4b5563;font-size:13.5px">다른 브라우저에서 로그인되어 로그아웃되었습니다.<br><b data-kick-count>3</b>초 후 로그인 화면으로 이동합니다.</p>
          <button type="button" style="border:0;border-radius:8px;background:#4F46E5;color:#fff;font-weight:700;padding:8px 18px;cursor:pointer" data-kick-now>지금 이동</button>
        </div>`;
      document.body.appendChild(bg);
      bg.querySelector('[data-kick-now]').addEventListener('click', go);
      let remain = 3;
      const countEl = bg.querySelector('[data-kick-count]');
      const timerId = window.setInterval(() => {
        remain -= 1;
        if (countEl) countEl.textContent = String(Math.max(0, remain));
        if (remain <= 0) { window.clearInterval(timerId); go(); }
      }, 1000);
    } catch (e) {
      go();
    }
  };

  const checkSessionAlive = async () => {
    if (authHeartbeatBusy || authHeartbeatFailed) return;
    authHeartbeatBusy = true;
    try {
      const response = await fetch('/api/session/ping', {
        headers: { Accept: 'application/json' },
        credentials: 'same-origin',
        cache: 'no-store',
      });
      const redirectedToLogin = response.redirected && response.url && response.url.includes('/login');
      if (redirectedToLogin || response.status === 401 || response.status === 403) {
        redirectKicked();
      }
    } catch (error) {
      // 일시적인 네트워크 오류는 heartbeat 실패로 취급하지 않는다.
    } finally {
      authHeartbeatBusy = false;
    }
  };

  window.workSystemLive = {
    isConnected: () => liveConnected,
    refreshPanel: load,
    sendMessage: (roomId, body) => {
      if (!liveConnected || !liveSocket || liveSocket.readyState !== WebSocket.OPEN) {
        return false;
      }
      liveSocket.send(JSON.stringify({ type: 'SEND_MESSAGE', payload: { roomId: Number(roomId), body } }));
      return true;
    }
  };
  window.workSystemRightPanelRefresh = load;

  load();
  connectLive();
  checkSessionAlive();
  // [E2] 백그라운드 탭은 폴링을 멈추고, 세션 핑은 5초→30초로 완화(강퇴는 웹소켓·복귀 시 즉시 감지).
  window.setInterval(() => { if (!document.hidden && !liveConnected) load(); }, 10000);
  window.setInterval(() => { if (!document.hidden && !liveConnected) refreshChat(); }, 3000);
  window.setInterval(() => { if (!document.hidden) checkSessionAlive(); }, 30000);
  // 웹소켓이 연결돼 있어도 직원/본인 정보가 오래 묵지 않도록 주기적으로 다시 받아온다.
  window.setInterval(() => { if (!document.hidden) load(); }, 30000);
  document.addEventListener('visibilitychange', () => {
    if (!document.hidden) {
      checkSessionAlive();
      load();
    }
  });
})();
