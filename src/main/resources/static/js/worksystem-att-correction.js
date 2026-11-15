/* [UX23/UX25] 근태 정정 신청 — 대상 선택을 스크롤 목록 대신 캘린더 픽커로.
   단독 페이지 진입과 신청 모달([data-app-form-modal]) 주입 모두 지원: wrap 단위 init 가드 +
   worksystem:content-updated 재초기화. 필터 칩은 document 위임 1회 등록. */
(() => {
  const KOR_DOW = ['일', '월', '화', '수', '목', '금', '토'];

  const init = () => {
    const wrap = document.querySelector('[data-att-pick]');
    if (!wrap || wrap.dataset.acnInit) return;
    wrap.dataset.acnInit = '1';
    const scope = wrap.closest('form') || document;
    const cin = scope.querySelector('input[name=requestedCheckIn]');
    const cout = scope.querySelector('input[name=requestedCheckOut]');
    const preview = scope.querySelector('[data-acn-preview]');

    const text = (sel, v) => {
      const el = scope.querySelector(sel);
      if (el) el.textContent = (v && v !== '') ? v : '-';
    };
    const selectedRadio = () => wrap.querySelector('.att-pick-radio:checked');

    const updatePreview = () => {
      const r = selectedRadio();
      if (!preview) return;
      if (!r) { preview.hidden = true; return; }
      preview.hidden = false;
      text('[data-acn-p-date]', r.dataset.date);
      text('[data-acn-p-status]', r.dataset.status);
      text('[data-acn-p-before-in]', r.dataset.cin);
      text('[data-acn-p-before-out]', r.dataset.cout);
      text('[data-acn-p-after-in]', (cin && cin.value) || r.dataset.cin);
      text('[data-acn-p-after-out]', (cout && cout.value) || r.dataset.cout);
    };

    /* ── [UX25] 캘린더 픽커 구성 ── */
    const radios = Array.from(wrap.querySelectorAll('.att-pick-radio'));
    const byIso = new Map(radios.map((r) => [r.value, r]));
    let cal = null;

    const syncCal = () => {
      if (!cal) return;
      cal.querySelectorAll('.hcal-day.acn-sel').forEach((el) => el.classList.remove('acn-sel'));
      const r = selectedRadio();
      if (r) {
        const day = cal.querySelector(`[data-acn-day="${r.value}"]`);
        if (day) day.classList.add('acn-sel');
      }
      updatePreview();
    };

    if (radios.length) {
      const months = [...new Set(radios.map((r) => r.value.slice(0, 7)))].sort();
      cal = document.createElement('div');
      cal.className = 'hcal-grid acn-cal';
      months.forEach((ym) => {
        const [y, m] = ym.split('-').map(Number);
        const daysInMonth = new Date(y, m, 0).getDate();
        let col = new Date(y, m - 1, 1).getDay();
        let html = `<h4>${y}년 ${m}월</h4><table class="hcal-table"><thead><tr>`
          + KOR_DOW.map((d) => `<th>${d}</th>`).join('') + '</tr></thead><tbody><tr>'
          + '<td></td>'.repeat(col);
        for (let d = 1; d <= daysInMonth; d++) {
          const iso = `${ym}-${String(d).padStart(2, '0')}`;
          const r = byIso.get(iso);
          let cls = 'hcal-day';
          let attrs = '';
          if (r) {
            cls += ' acn-rec';
            const label = r.closest('.att-pick');
            if (label && label.getAttribute('data-acn-issue') === 'true') cls += ' acn-issue';
            if (r.disabled) cls += ' acn-pending';
            else attrs = ` data-acn-day="${iso}" role="button" tabindex="0"`;
            if (r.checked) cls += ' acn-sel';
            const tip = `${r.dataset.status || '-'} · 출근 ${r.dataset.cin || '-'} · 퇴근 ${r.dataset.cout || '-'}${r.disabled ? ' · 정정 진행 중' : ''}`;
            attrs += ` title="${tip}"`;
          } else if (col === 0 || col === 6) {
            cls += ' weekend';
          }
          html += `<td><span class="${cls}"${attrs}>${d}</span></td>`;
          col = (col + 1) % 7;
          if (col === 0 && d < daysInMonth) html += '</tr><tr>';
        }
        if (col !== 0) html += '<td></td>'.repeat(7 - col);
        html += '</tr></tbody></table>';
        const monthEl = document.createElement('div');
        monthEl.className = 'hcal-month';
        monthEl.innerHTML = html;
        cal.appendChild(monthEl);
      });
      wrap.parentElement.insertBefore(cal, wrap);
      wrap.classList.add('acn-list-hidden');

      cal.addEventListener('click', (e) => {
        const day = e.target.closest('[data-acn-day]');
        if (!day) return;
        const r = byIso.get(day.getAttribute('data-acn-day'));
        if (!r || r.disabled) return;
        r.checked = true;
        r.dispatchEvent(new Event('change', { bubbles: true }));
      });
      cal.addEventListener('keydown', (e) => {
        if (e.key !== 'Enter' && e.key !== ' ') return;
        const day = e.target.closest('[data-acn-day]');
        if (!day) return;
        e.preventDefault();
        day.click();
      });
    }

    const syncSelected = () => {
      wrap.querySelectorAll('.att-pick').forEach((el) => {
        const radio = el.querySelector('.att-pick-radio');
        el.classList.toggle('is-selected', !!(radio && radio.checked));
      });
      syncCal();
      // [UX40] '휴가/출장으로 처리' 링크에 선택 날짜 전달(프리필) — 링크는 form 밖(form-head 아래)
      const sel = selectedRadio();
      const panel = wrap.closest('.form-panel') || document;
      panel.querySelectorAll('[data-acn-goto]').forEach((a) => {
        if (!a.dataset.acnBase) a.dataset.acnBase = a.getAttribute('href');
        a.setAttribute('href', sel
          ? `${a.dataset.acnBase}?start=${sel.value}&end=${sel.value}`
          : a.dataset.acnBase);
      });
    };

    /* [UX6] 문제 항목만 / 전체 — 캘린더 모드에선 문제 아닌 날짜를 흐리게(클릭은 유지) */
    const applyFilter = (mode) => {
      let anyIssue = false;
      wrap.querySelectorAll('.att-pick').forEach((el) => {
        const issue = el.getAttribute('data-acn-issue') === 'true';
        if (issue) anyIssue = true;
        const radio = el.querySelector('.att-pick-radio');
        el.hidden = !(mode === 'all' || issue || !!(radio && radio.checked));
      });
      const empty = wrap.querySelector('[data-acn-noissue]');
      if (empty) empty.hidden = !(mode === 'issue' && !anyIssue);
      scope.querySelectorAll('[data-acn-filter]').forEach((b) => {
        b.classList.toggle('is-active', b.getAttribute('data-acn-filter') === mode);
        b.setAttribute('aria-pressed', String(b.getAttribute('data-acn-filter') === mode));
      });
      if (cal) cal.classList.toggle('acn-mode-issue', mode === 'issue');
    };
    window.__acnApplyFilter = applyFilter; // 최신 인스턴스가 칩 클릭을 받는다

    wrap.addEventListener('change', (e) => {
      const r = e.target;
      if (!r || r.name !== 'workDate') return;
      if (cin) {
        cin.value = r.dataset.cin || '';
        cin.dispatchEvent(new Event('change', { bubbles: true }));
      }
      if (cout) {
        cout.value = r.dataset.cout || '';
        cout.dispatchEvent(new Event('change', { bubbles: true }));
      }
      syncSelected();
    });
    if (cin) cin.addEventListener('input', updatePreview);
    if (cout) cout.addEventListener('input', updatePreview);

    // 초기: 특정 일자 지정(캘린더/목록에서 진입) 시 전체 필터로 시작해 해당 날짜 노출
    applyFilter(selectedRadio() ? 'all' : 'issue');
    syncSelected();
  };

  if (!window.__acnChipBound) {
    window.__acnChipBound = true;
    document.addEventListener('click', (e) => {
      const btn = e.target.closest('[data-acn-filter]');
      if (!btn) return;
      e.preventDefault();
      if (window.__acnApplyFilter) window.__acnApplyFilter(btn.getAttribute('data-acn-filter'));
    });
  }

  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', init);
  else init();
  window.addEventListener('worksystem:content-updated', init);
})();
