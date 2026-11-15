/* 공통 OTP 모달. 서버 검증 및 검증 이후 이동은 호출 화면에서 담당합니다. */
(function () {
  'use strict';
  const assets = new URL('.', document.currentScript.src);
  const assetTimeout = 12000;
  let loading;

  async function loadMarkup() {
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), assetTimeout);
    try {
      const response = await fetch(new URL('modal.html', assets), {credentials:'same-origin', signal:controller.signal});
      if (!response.ok) throw new Error();
      return await response.text();
    } catch {
      throw new Error('OTP 화면을 불러오지 못했습니다. 다시 시도해 주세요.');
    } finally {
      clearTimeout(timer);
    }
  }
  async function initialize() {
    const html = await loadMarkup();
    if (!html.includes('id="modalLayer"')) throw new Error('OTP 화면을 불러오지 못했습니다.');
    const dialog = document.createElement('dialog');
    dialog.setAttribute('aria-label', '2차 인증');
    dialog.style.cssText = 'position:fixed;inset:0;width:100%;height:100%;max-width:none;max-height:none;margin:0;padding:0;border:0;overflow:hidden;background:transparent;color-scheme:light';
    const host = document.createElement('div');
    const root = host.attachShadow({mode:'open'});
    root.innerHTML = html;
    const style = document.createElement('link');
    style.rel = 'stylesheet';
    style.href = new URL('modal.css', assets).href;
    const styled = new Promise((resolve, reject) => {
      const fail = () => reject(new Error('OTP 화면 스타일을 불러오지 못했습니다. 다시 시도해 주세요.'));
      const timer = setTimeout(fail, assetTimeout);
      style.onload = () => { clearTimeout(timer); resolve(); };
      style.onerror = () => { clearTimeout(timer); fail(); };
    });
    root.prepend(style);
    dialog.append(host);
    document.body.append(dialog);
    try { await styled; } catch (error) { dialog.remove(); throw error; }
    if (window.FontFace && !Array.from(document.fonts).some(face => face.family === 'OTP Pretendard')) {
      const font = new FontFace('OTP Pretendard', 'url(' + new URL('otp-ui-font.woff2', assets).href + ')', {weight:'45 920', display:'swap'});
      document.fonts.add(font);
    }
    const OTP_PERIOD = 30;
    const kstClockFormatter = new Intl.DateTimeFormat('ko-KR', { timeZone: 'Asia/Seoul', hour: '2-digit', minute: '2-digit', second: '2-digit', hourCycle: 'h23' });
    // 다운로드 링크의 단일 소스: 공식 Google Authenticator 앱 페이지.
    const APP_DOWNLOAD_URLS = Object.freeze({
      android: 'https://play.google.com/store/apps/details?id=com.google.android.apps.authenticator2&hl=ko',
      ios: 'https://apps.apple.com/kr/app/google-authenticator/id388497605'
    });
    const DOWNLOAD_QR_IMAGES = {
      android: new URL('android-download.png', assets).href,
      ios: new URL('ios-download.png', assets).href
    };
    const layer = root.getElementById('modalLayer');
    const shell = root.getElementById('modalShell');
    const dialogs = { auth: root.getElementById('authModal'), setup: root.getElementById('setupModal') };
    const errors = { auth: root.getElementById('otpErr'), setup: root.getElementById('setupErr') };
    const states = { auth: 'idle', setup: 'idle' };
    const apis = {};
    const qrLayer = document.createElement('div');
    qrLayer.id = 'downloadQrLayer'; qrLayer.className = 'download-qr-layer'; qrLayer.hidden = true;
    qrLayer.innerHTML = '<section id="downloadQrDialog" class="download-qr-dialog" role="dialog" aria-modal="true" aria-labelledby="downloadQrTitle" aria-describedby="downloadQrSubtitle downloadQrDescription" tabindex="-1">'
      + '<button class="modal-close" type="button" aria-label="다운로드 QR 모달 닫기"><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" aria-hidden="true"><path d="M6 6l12 12M18 6L6 18"/></svg></button>'
      + '<h2 id="downloadQrTitle" class="download-qr-title"></h2><p id="downloadQrSubtitle" class="download-qr-subtitle"></p>'
      + '<img id="downloadQrImage" class="download-qr-image" width="256" height="256" alt="">'
      + '<p id="downloadQrDescription" class="download-qr-description"></p>'
      + '<a id="downloadQrStore" class="btn btn-primary download-qr-store" target="_blank" rel="noopener noreferrer"></a></section>';
    root.append(qrLayer);
    const qrDialog = root.getElementById('downloadQrDialog');
    let qrTrigger = null;
    let current = null, session = null, restoreFocus = null, timer = null, epoch = 0, screenTransition = 0, serverOffset = 0, bodyOverflow = '';
    let setupStep = 1, setupMethod = null, installationChoice = null;
    function mount(container, opts) {
      opts = opts || {};
      container.innerHTML = '';
      var wrap = document.createElement('div');
      wrap.className = 'otpx-wrap';
      wrap.setAttribute('role', 'group');
      wrap.setAttribute('aria-label', '6자리 인증 코드');
      var boxes = [];
      for (var i = 0; i < 6; i++) {
        var b = document.createElement('input');
        b.type = 'text';
        b.className = 'otpx-box';
        b.maxLength = 6; /* 모바일 자동완성으로 들어온 전체 코드를 분배합니다. */
        b.setAttribute('aria-describedby', opts.descriptionId || 'otpDescription');
        b.spellcheck = false;
        b.inputMode = 'numeric';
        b.autocomplete = i === 0 ? 'one-time-code' : 'off';
        b.setAttribute('aria-label', 'OTP ' + (i + 1) + '번째 자리');
        wrap.appendChild(b);
        boxes.push(b);
      }
      var spin = document.createElement('div');
      spin.className = 'otpx-ico';
      spin.innerHTML = '<div class="otpx-spin"></div>';
      var okIco = document.createElement('div');
      okIco.className = 'otpx-ico';
      okIco.innerHTML = '<svg class="otpx-check" width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="var(--success,#15803d)" stroke-width="3" stroke-linecap="round" stroke-linejoin="round"><path d="M4.5 12.75l6 6 9-13.5"/></svg>';
      var ngIco = document.createElement('div');
      ngIco.className = 'otpx-ico';
      ngIco.innerHTML = '<svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="var(--danger,#991b1b)" stroke-width="3" stroke-linecap="round" stroke-linejoin="round"><path d="M6 18L18 6M6 6l12 12"/></svg>';
      wrap.appendChild(spin); wrap.appendChild(okIco); wrap.appendChild(ngIco);
      container.appendChild(wrap);

      var result = document.createElement('div');
      result.className = 'otpx-result';
      result.setAttribute('aria-live', 'polite');
      container.appendChild(result);

      var pending = new Set();
      function later(fn, delay) {
        var id = window.setTimeout(function () { pending.delete(id); fn(); }, delay);
        pending.add(id);
        return id;
      }
      function cancelPending() {
        pending.forEach(function (id) { window.clearTimeout(id); });
        pending.clear();
      }
      var locked = false;
      var convergeAt = 0;
      var MIN_LOADING = 1500; /* 스피너(로딩)를 최소 1.5초 노출한 뒤 성공/실패 표시 */
      function afterLoading(fn) {
        var target = convergeAt + 620 + MIN_LOADING; /* 수렴→스피너 노출(620ms)→최소 1.5s 로딩 */
        later(fn, Math.max(0, target - Date.now()));
      }

      function value() {
        return boxes.map(function (b) { return b.value; }).join('');
      }
      function changed() { if (opts.onInput) opts.onInput(boxes.filter(function (b) { return b.value !== ''; }).length); }
      function fill(str) {
        if (locked) return;
        var digits = (str || '').replace(/\D/g, '').slice(0, 6).split('');
        boxes.forEach(function (b, i) { b.value = digits[i] || ''; });
        var next = Math.min(digits.length, 5);
        boxes[next].focus();
        changed();
        maybeComplete();
      }
      function maybeComplete() {
        if (locked) return;
        var v = value();
        if (v.length === 6 && /^\d{6}$/.test(v)) {
          locked = true;
          if (opts.onAction) opts.onAction('complete');
          if (!locked) return;
          container.setAttribute('aria-busy', 'true');
          if (opts.onStateChange) opts.onStateChange('loading');
          if (!locked) return;
          converge();
          if (opts.onComplete) opts.onComplete(v, api);
        }
      }
      /* 6칸이 중앙 1칸으로 수렴 — 수렴(0.55s)이 끝난 뒤에 스피너 시작 */
      function converge() {
        boxes.forEach(function (b) { b.blur(); b.readOnly = true; b.tabIndex = -1; });
        convergeAt = Date.now();
        /* 각 칸을 wrap 정중앙으로 정확히 이동 — 아이콘(체크/X/스피너)도 wrap 중앙이라 칸과 정렬된다.
           (기존엔 gap을 0으로 접으면서 step을 gap 8 기준으로 계산해 어긋났음) */
        var wrapRect = wrap.getBoundingClientRect();
        var wrapCenter = wrapRect.left + wrapRect.width / 2;
        boxes.forEach(function (b, idx) {
          var r = b.getBoundingClientRect();
          var dx = wrapCenter - (r.left + r.width / 2);
          b.style.transitionDelay = (idx * 0.03) + 's'; /* 칸별 미세 시차로 빨려드는 느낌 */
          b.style.transform = 'translateX(' + dx + 'px)';
          b.style.color = 'transparent';
          if (idx !== 2) { b.style.opacity = '0'; }
          else { b.style.borderColor = 'var(--primary-dark, #4338CA)'; }
        });
        later(function () {
          spin.classList.add('show');
        }, 620);
      }
      function success(cb) {
        afterLoading(function () {
          spin.classList.remove('show');
          boxes[2].style.borderColor = 'var(--success, #15803d)';
          later(function () { okIco.classList.add('show'); }, 100);
          container.setAttribute('aria-busy', 'false');
          if (opts.onStateChange) opts.onStateChange('success');
          if (!locked) return;
          result.textContent = '인증되었습니다.';
          result.className = 'otpx-result ok';
          later(function () { result.classList.add('show'); }, 160);
          if (cb) later(cb, 1000);
        });
      }
      function fail(afterReset) {
        afterLoading(function () {
          spin.classList.remove('show');
          boxes[2].style.borderColor = 'var(--danger, #991b1b)';
          wrap.classList.add('otpx-shake');
          ngIco.classList.add('show');
          container.setAttribute('aria-busy', 'false');
          if (opts.onStateChange) opts.onStateChange('failure');
          if (!locked) return;
          result.textContent = '';
          result.className = 'otpx-result ng';
          later(function () { result.classList.add('show'); }, 120);
          later(function () {
            wrap.classList.remove('otpx-shake');
            ngIco.classList.remove('show');
            result.classList.remove('show');
            reset();
            if (afterReset) afterReset();
          }, 1150);
        });
      }
      function reset(shouldFocus) {
        cancelPending();
        locked = false;
        container.setAttribute('aria-busy', 'false');
        wrap.classList.remove('otpx-shake');
        if (opts.onStateChange) opts.onStateChange('idle');
        wrap.style.gap = '';
        boxes.forEach(function (b) {
          b.value = ''; b.readOnly = false; b.tabIndex = 0; b.classList.remove('otpx-pop');
          b.style.transitionDelay = '';
          b.style.transform = 'translateX(0)';
          b.style.opacity = '1';
          b.style.color = '';
          b.style.borderColor = '';
        });
        spin.classList.remove('show'); okIco.classList.remove('show'); ngIco.classList.remove('show');
        result.classList.remove('show'); result.className = 'otpx-result'; result.textContent = ''; convergeAt = 0;
        if (shouldFocus !== false) later(function () { boxes[0].focus(); }, 120);
      }

      boxes.forEach(function (input, index) {
        input.addEventListener('input', function (e) {
          if (locked) return;
          var v = e.target.value.replace(/\D/g, '');
          if (v.length > 1) { e.target.value = ''; fill(value().slice(0, index) + v); return; }
          e.target.value = v;
          if (v !== '') {
            input.classList.add('otpx-pop');
            later(function () { input.classList.remove('otpx-pop'); }, 200);
            if (index < 5) boxes[index + 1].focus();
          }
          changed();
          maybeComplete();
        });
        input.addEventListener('focus', function () { if (!locked) input.select(); });
        input.addEventListener('keydown', function (e) {
          if (locked) { if (e.key !== 'Tab') e.preventDefault(); return; }
          if (e.key === 'Backspace') {
            if (input.value === '' && index > 0) { boxes[index - 1].value = ''; boxes[index - 1].focus(); }
            else { input.value = ''; }
            changed();
            e.preventDefault();
          } else if (e.key === 'ArrowLeft' && index > 0) { boxes[index - 1].focus(); }
          else if (e.key === 'ArrowRight' && index < 5) { boxes[index + 1].focus(); }
        });
        input.addEventListener('paste', function (e) {
          e.preventDefault();
          if (locked) return;
          if (opts.onAction) opts.onAction('paste');
          fill((e.clipboardData || window.clipboardData).getData('text'));
        });
      });

      var api = { value: value, reset: reset, success: success, fail: fail,
                  focus: function () { boxes[0].focus(); } };
      return api;
    }
    function stateChanged(mode, state) {
      states[mode] = state;
      if (state === 'loading') errors[mode].style.display = 'none';
      if (mode === 'setup') {
        root.getElementById('setupSuccess').hidden = state !== 'success';
        if (state === 'success') root.querySelectorAll('[data-wizard-step]').forEach(button => button.setAttribute('aria-disabled','true'));
        root.getElementById('setupTimer').hidden = state === 'success';
        root.getElementById('setupDescription').hidden = state === 'success';
        root.getElementById('setupStep3Title').textContent = state === 'success' ? '등록이 완료되었습니다' : '마지막으로 코드를 확인할게요';
        if (state === 'success') { window.clearInterval(timer); timer = null; }
      }
    }

    function syncTimer() {
      clearInterval(timer);
      if (current === 'auth' || (current === 'setup' && setupStep === 3)) {
        timerTick();
        timer = setInterval(timerTick, 250);
      }
    }
    function transitionGroup(selector, target) {
      const sequence = ++screenTransition;
      const panels = Array.from(root.querySelectorAll(selector));
      panels.forEach(panel => {
        panel.classList.remove('screen-exit','screen-enter');
        panel.inert = false; panel.removeAttribute('aria-hidden');
      });
      const outgoing = panels.find(panel => !panel.hidden && panel !== target);
      if (!outgoing) {
        panels.forEach(panel => { if (panel !== target) panel.hidden = true; });
        target.hidden = false; target.inert = false; target.removeAttribute('aria-hidden');
        target.classList.add('screen-enter'); requestAnimationFrame(() => target.classList.remove('screen-enter'));
        return sequence;
      }
      outgoing.inert = true; outgoing.setAttribute('aria-hidden','true'); outgoing.classList.add('screen-exit');
      window.setTimeout(() => {
        if (sequence !== screenTransition) return;
        outgoing.hidden = true; outgoing.classList.remove('screen-exit');
        target.hidden = false; target.inert = false; target.removeAttribute('aria-hidden');
        target.classList.add('screen-enter'); requestAnimationFrame(() => target.classList.remove('screen-enter'));
      },140);
      return sequence;
    }
    function setSetupStep(step, focus = true, animate = true) {
      if (current !== 'setup') return;
      if (step === 3 && !setupMethod) return;
      if (states.setup === 'loading' || states.setup === 'failure') return;
      closeDownloadQr(false);
      if (setupStep === 3 && step !== 3) resetMode('setup',false);
      epoch++; setupStep = step;
      root.getElementById('copyKey').disabled = false;
      const targetPane = root.querySelector(`[data-setup-step="${step}"]`);
      let transitionId;
      if (animate) transitionId = transitionGroup('[data-setup-step]',targetPane);
      else {
        screenTransition++;
        root.querySelectorAll('[data-setup-step]').forEach(pane => { pane.hidden = pane !== targetPane; pane.classList.remove('screen-enter','screen-exit'); pane.inert = pane !== targetPane; pane.removeAttribute('aria-hidden'); });
        transitionId = screenTransition;
      }
      root.querySelectorAll('[data-step-indicator]').forEach(item => {
        const n = Number(item.dataset.stepIndicator);
        if (n === step) item.setAttribute('aria-current','step'); else item.removeAttribute('aria-current');
        item.classList.toggle('done',n < step);
        const stepButton = item.querySelector('.wizard-step');
        const canAdvance = n <= step || (n === step + 1 && ((n === 2 && installationChoice === true) || (n === 3 && Boolean(setupMethod))));
        stepButton.setAttribute('aria-disabled',String(!canAdvance || states.setup === 'success'));
      });
      root.getElementById('setupNext').hidden = step !== 2;
      if (step === 2 && root.getElementById('chooseQr').disabled) showManualFallback();
      root.getElementById('setupNext').disabled = !setupMethod;
      layer.scrollTop = 0; syncTimer();
      if (focus) {
        const focusTarget = step === 3 ? null : root.getElementById(step === 1 ? (installationChoice === false ? 'installConfirm' : 'installedYes') : 'setupStep2Title');
        const moveFocus = () => { if (current === 'setup' && setupStep === step) { if (focusTarget) focusTarget.focus(); else apis.setup.focus(); } };
        if (transitionId !== screenTransition || targetPane.hidden) window.setTimeout(moveFocus,160); else moveFocus();
      }
    }
    let wizardFeedbackTimer = null;
    function showWizardFeedback(message) {
      const feedback = root.getElementById('wizardNavFeedback'); feedback.textContent = message; feedback.hidden = false; feedback.classList.remove('is-visible');
      requestAnimationFrame(() => feedback.classList.add('is-visible')); window.clearTimeout(wizardFeedbackTimer);
      wizardFeedbackTimer = window.setTimeout(() => { feedback.classList.remove('is-visible'); window.setTimeout(() => { if (!feedback.classList.contains('is-visible')) feedback.hidden = true; },180); },2200);
    }
    function navigateWizardStep(target) {
      if (current !== 'setup' || !Number.isInteger(target) || target < 1 || target > 3 || target === setupStep) return;
      let reason = '';
      if (states.setup === 'success') reason = '\ub4f1\ub85d\uc774 \uc644\ub8cc\ub418\uc5b4 \ub2e8\uacc4\ub97c \uc774\ub3d9\ud560 \uc218 \uc5c6\uc5b4\uc694.';
      else if (target < setupStep) reason = '';
      else if (target > setupStep + 1) reason = '\uc55e\uc120 \ub2e8\uacc4\ub97c \uba3c\uc800 \uc644\ub8cc\ud574 \uc8fc\uc138\uc694.';
      else if (target === 2 && installationChoice !== true) reason = installationChoice === false ? '\uc571 \uc124\uce58\ub97c \ub9c8\uce5c \ud6c4 \uc124\uce58 \uc644\ub8cc\ub97c \ub20c\ub7ec \uc8fc\uc138\uc694.' : '\uba3c\uc800 \uc571 \uc124\uce58 \uc5ec\ubd80\ub97c \uc120\ud0dd\ud574 \uc8fc\uc138\uc694.';
      else if (target === 3 && !setupMethod) reason = '\uc571 \uc124\uc815 \ubc29\ubc95\uc744 \uba3c\uc800 \uc120\ud0dd\ud574 \uc8fc\uc138\uc694.';
      if (reason) { showWizardFeedback(reason); return; }
      showWizardFeedback(`${target}\ub2e8\uacc4\ub85c \uc774\ub3d9\ud569\ub2c8\ub2e4.`); setSetupStep(target);
    }
    function restartWizard() {
      closeDownloadQr(false);
      resetMode('setup',false);
      setupStep = 1; setupMethod = null; installationChoice = null; screenTransition++;
      const installQuestion = root.getElementById('installQuestion');
      installQuestion.hidden = false;
      installQuestion.classList.remove('is-collapsed','screen-exit','screen-enter'); installQuestion.inert = false; installQuestion.removeAttribute('aria-hidden');
      const installHelp = root.getElementById('installHelp');
      installHelp.classList.remove('is-open','screen-exit','screen-enter'); installHelp.hidden = true; installHelp.inert = false; installHelp.removeAttribute('aria-hidden');
      root.getElementById('installedNo').setAttribute('aria-expanded','false');
      root.getElementById('qrMethod').hidden = true; root.getElementById('manualMethod').hidden = true;
      ['installedYes','installedNo','chooseQr','chooseManual'].forEach(id => root.getElementById(id).setAttribute('aria-pressed','false'));
      root.getElementById('copyFeedback').textContent = ''; root.getElementById('copyFeedback').className = 'copy-feedback';
      setSetupStep(1,true,false);
    }
    function chooseInstallation(installed) {
      if (current !== 'setup' || setupStep !== 1) return;
      installationChoice = installed;
      root.getElementById('installedYes').setAttribute('aria-pressed',String(installed));
      root.getElementById('installedNo').setAttribute('aria-pressed',String(!installed));
      if (installed) setSetupStep(2);
      else {
        const question = root.getElementById('installQuestion');
        const help = root.getElementById('installHelp');
        root.getElementById('installedNo').setAttribute('aria-expanded','true');
        transitionGroup('#installQuestion,#installHelp',help);
        question.inert = true; question.setAttribute('aria-hidden','true');
        window.setTimeout(() => { if (current === 'setup' && setupStep === 1 && !help.hidden) root.querySelector('#installHelp [data-download]').focus(); },160);
      }
    }
    function confirmInstallation() {
      installationChoice = true;
      setSetupStep(2);
    }
    function chooseMethod(method) {
      if (current !== 'setup' || setupStep !== 2) return;
      epoch++; setupMethod = method;
      transitionGroup('#qrMethod,#manualMethod',root.getElementById(method === 'qr' ? 'qrMethod' : 'manualMethod')); 
      root.getElementById('chooseQr').setAttribute('aria-pressed',String(method === 'qr'));
      root.getElementById('chooseManual').setAttribute('aria-pressed',String(method === 'manual'));
      root.getElementById('setupNext').disabled = false;
      root.querySelector('[data-wizard-step="3"]').setAttribute('aria-disabled','false');
      root.getElementById('copyKey').disabled = false;
      root.getElementById('copyFeedback').textContent = ''; root.getElementById('copyFeedback').className = 'copy-feedback';
    }
    function showManualFallback() {
      setupMethod = 'manual';
      root.getElementById('qrMethod').hidden = true;
      root.getElementById('manualMethod').hidden = false;
      root.getElementById('chooseQr').setAttribute('aria-pressed','false');
      root.getElementById('chooseManual').setAttribute('aria-pressed','true');
      root.getElementById('setupNext').disabled = false;
      root.querySelector('[data-wizard-step="3"]').setAttribute('aria-disabled','false');
    }
    function setQrAvailability(available) {
      root.getElementById('chooseQr').disabled = !available;
      root.getElementById('methodHint').textContent = available
        ? '지금 스마트폰 한 대로 보고 있다면 설정 키 입력이 편리합니다.'
        : 'QR 코드를 표시할 수 없습니다. 아래 설정 키를 앱에 직접 입력해 주세요.';
      if (!available && current === 'setup' && setupStep === 2) showManualFallback();
    }
    root.getElementById('setupQr').addEventListener('error', () => {
      if (current === 'setup') setQrAvailability(false);
    });
    function timerTick() {
      if (!current || (current === 'setup' && setupStep !== 3)) return;
      const now = Date.now() + serverOffset;
      const kstTime = kstClockFormatter.format(now);
      const remain = OTP_PERIOD - ((now/1000) % OTP_PERIOD);
      const sec = Math.ceil(remain), warn = sec <= 5;
      ['otp','setup'].forEach(prefix => {
        const clock = root.getElementById(prefix+'KstClock');
        clock.textContent = kstTime;
        const counter = root.getElementById(prefix+'TimerSec');
        const bar = root.getElementById(prefix+'TimerBar');
        counter.textContent = sec; counter.classList.toggle('warn',warn);
        bar.style.width = (remain / OTP_PERIOD * 100) + '%'; bar.classList.toggle('warn',warn);
      });
    }
    async function copyKey() {
      if (current !== 'setup' || setupStep !== 2 || setupMethod !== 'manual') return;
      const token = epoch, button = root.getElementById('copyKey'), input = root.getElementById('otpKey');
      const feedback = root.getElementById('copyFeedback');
      button.disabled = true; feedback.textContent = '';
      let copied = false;
      try { if (navigator.clipboard && navigator.clipboard.writeText) { await navigator.clipboard.writeText(input.value); copied = true; } } catch (_) {}
      if (token !== epoch || current !== 'setup' || setupStep !== 2 || setupMethod !== 'manual') return;
      if (!copied) { try { input.focus(); input.select(); copied = document.execCommand('copy'); } catch (_) {} }
      button.disabled = false;
      feedback.className = 'copy-feedback' + (copied ? '' : ' error');
      feedback.textContent = copied ? '수동 키를 복사했습니다.' : '자동 복사에 실패했습니다. 키를 선택해 직접 복사해 주세요.';
      if (copied) button.focus(); else { input.focus(); input.select(); }

    }
    root.querySelectorAll('[data-period]').forEach(node => node.textContent = OTP_PERIOD);
    root.querySelectorAll('.app-download-grid a.app-download-button[data-download]').forEach(link => {
      const platform = link.dataset.download, mode = link.dataset.mode;
      const option = document.createElement('div'); option.className = 'app-download-option'; option.dataset.odId = `download-card-${mode}-${platform}`;
      const toggle = document.createElement('button'); toggle.type = 'button'; toggle.className = 'qr-toggle';
      toggle.dataset.qrToggle = platform; toggle.dataset.mode = mode; toggle.dataset.odId = `download-qr-toggle-${mode}-${platform}`;
      toggle.setAttribute('aria-controls','downloadQrDialog'); toggle.setAttribute('aria-haspopup','dialog'); toggle.setAttribute('aria-expanded','false'); toggle.textContent = '다운로드 QR 보기';
      toggle.setAttribute('aria-label',`${platform === 'android' ? 'Android' : 'iOS'} 다운로드 QR 보기`);
      link.parentNode.insertBefore(option,link); option.append(link,toggle);
    });
    function openDownloadQr(toggle) {
      if (!current || toggle.dataset.mode !== current) return;
      closeDownloadQr(false);
      const platform = toggle.dataset.qrToggle, isAndroid = platform === 'android';
      const platformName = isAndroid ? 'Android' : 'iOS', storeName = isAndroid ? 'Google Play' : 'App Store';
      root.getElementById('downloadQrTitle').textContent = `${platformName} 앱 다운로드`;
      root.getElementById('downloadQrSubtitle').textContent = `Google Authenticator · ${storeName}`;
      const image = root.getElementById('downloadQrImage'); image.src = DOWNLOAD_QR_IMAGES[platform]; image.alt = `${platformName} Google Authenticator ${storeName} 다운로드 QR 코드`;
      root.getElementById('downloadQrDescription').replaceChildren(document.createTextNode('스마트폰 카메라로 QR코드를 스캔하면'),document.createElement('br'),document.createTextNode(`${storeName}의 앱 설치 화면으로 이동합니다.`));
      const store = root.getElementById('downloadQrStore'); store.href = APP_DOWNLOAD_URLS[platform]; store.textContent = `${storeName}에서 다운로드`; store.dataset.platform = platform;
      store.setAttribute('aria-label',`${storeName}에서 Google Authenticator 다운로드, 새 탭`);
      qrTrigger = toggle; toggle.setAttribute('aria-expanded','true'); qrLayer.hidden = false; qrLayer.scrollTop = 0;
      qrDialog.querySelector('.modal-close').focus();
      shell.inert = true; layer.setAttribute('aria-hidden','true'); dialogs[current].removeAttribute('aria-modal');
    }
    function closeDownloadQr(restore = true) {
      if (!qrTrigger) return;
      const trigger = qrTrigger; qrTrigger = null;
      qrLayer.hidden = true; trigger.setAttribute('aria-expanded','false'); shell.inert = false; layer.removeAttribute('aria-hidden');
      if (current) dialogs[current].setAttribute('aria-modal','true');
      if (restore && current && trigger.isConnected && trigger.getClientRects().length) trigger.focus();
    }
    root.querySelectorAll('[data-qr-toggle]').forEach(toggle => toggle.addEventListener('click',() => openDownloadQr(toggle)));
    qrDialog.querySelector('.modal-close').addEventListener('click',() => closeDownloadQr());
    qrLayer.addEventListener('click',event => { if (event.target === qrLayer) closeDownloadQr(); });
    root.querySelectorAll('[data-download]').forEach(link => {
      const platform = link.dataset.download;
      link.href = APP_DOWNLOAD_URLS[platform];

    });
    root.querySelectorAll('[data-close]').forEach(button => button.addEventListener('click',() => close('button')));
    root.querySelectorAll('[data-wizard-step]').forEach(button => button.addEventListener('click',() => navigateWizardStep(Number(button.dataset.wizardStep))));
    root.getElementById('installedYes').addEventListener('click',() => chooseInstallation(true));
    root.getElementById('installedNo').addEventListener('click',() => chooseInstallation(false));
    root.getElementById('installConfirm').addEventListener('click',confirmInstallation);
    root.getElementById('chooseQr').addEventListener('click',() => chooseMethod('qr'));
    root.getElementById('chooseManual').addEventListener('click',() => chooseMethod('manual'));
    root.getElementById('setupNext').addEventListener('click',() => setSetupStep(3));
    root.getElementById('copyKey').addEventListener('click',copyKey);
    root.getElementById('otpKey').addEventListener('click',event => event.target.select());
    layer.addEventListener('click',event => { if (event.target === layer) close('backdrop'); });

    ['auth','setup'].forEach(mode => {
      apis[mode] = mount(root.getElementById(mode === 'auth' ? 'otpBoxes' : 'otpSetupBoxes'), {
        descriptionId: mode === 'auth' ? 'otpDescription' : 'setupDescription',
        onStateChange: state => stateChanged(mode, state),
        onComplete: code => verifyCode(mode, code)
      });
    });
    function resetMode(mode, focus) {
      apis[mode].reset(focus !== false);
      errors[mode].textContent = '';
      errors[mode].style.display = 'none';
    }
    async function verifyCode(mode, code) {
      const active = session;
      let result;
      try {
        result = await active.verify(code);
      } catch (error) {
        if (active !== session) return;
        apis[mode].fail(() => {
          errors[mode].textContent = error?.message || '인증정보가 올바르지 않습니다.';
          errors[mode].style.display = 'block';
        });
        return;
      }
      if (active !== session) return;
      apis[mode].success(() => {
        if (active !== session) return;
        dismiss();
        active.onSuccess?.(result);
      });
    }
    function dismiss() {
      const mode = current;
      current = null;
      session = null;
      epoch++; screenTransition++;
      clearInterval(timer);
      closeDownloadQr(false);
      resetMode(mode, false);
      root.getElementById('setupQr').removeAttribute('src');
      root.getElementById('otpKey').value = '';
      root.querySelectorAll('[data-account]').forEach(label => label.textContent = '');
      layer.hidden = true;
      layer.classList.remove('open');
      dialog.close();
      document.body.style.overflow = bodyOverflow;
      if (restoreFocus?.isConnected) restoreFocus.focus();
    }
    function close(reason) {
      if (!session || states[current] === 'loading' || states[current] === 'success') return;
      const active = session;
      dismiss();
      active.onCancel?.(reason);
    }
    root.getElementById('cancelAuth').addEventListener('click', () => close('button'));
    root.getElementById('cancelSetup').addEventListener('click', () => close('button'));
    dialog.addEventListener('cancel', event => {
      event.preventDefault();
      if (qrTrigger) closeDownloadQr(); else close('escape');
    });
    root.addEventListener('keydown', event => {
      if (event.key !== 'Tab' || !current) return;
      const pane = qrTrigger ? qrDialog : dialogs[current];
      const nodes = [...pane.querySelectorAll('button,input,a[href]')]
        .filter(el => !el.disabled && el.tabIndex >= 0 && !el.closest('[inert]') && el.getClientRects().length);
      const first = nodes[0], last = nodes[nodes.length - 1];
      if (event.shiftKey && root.activeElement === first) { event.preventDefault(); last.focus(); }
      if (!event.shiftKey && root.activeElement === last) { event.preventDefault(); first.focus(); }
    });
    function open(options) {
      if (session) throw new Error('이미 OTP 인증이 진행 중입니다.');
      const {mode, data = {}, serverTime} = options;
      if (!['auth','setup'].includes(mode) || typeof options.verify !== 'function') throw new TypeError('OTP 모달 설정이 올바르지 않습니다.');
      if (mode === 'setup' && !data.secret) throw new Error('OTP 등록 정보를 불러오지 못했습니다.');
      current = mode; session = options;
      restoreFocus = document.activeElement;
      serverOffset = Number.isFinite(serverTime) ? serverTime - Date.now() : 0;
      if (mode === 'setup') {
        setQrAvailability(Boolean(data.qr));
        if (data.qr) root.getElementById('setupQr').src = data.qr;
        root.getElementById('otpKey').value = data.secret;
        root.querySelectorAll('[data-account]').forEach(label => label.textContent = data.account || '');
      }
      root.querySelectorAll('.otp-logo').forEach(img => { img.src = options.logo || new URL('logo.svg', assets).href; });
      root.getElementById('cancelAuth').textContent = options.cancelLabel || '취소';
      const cancelSetup = root.getElementById('cancelSetup');
      cancelSetup.hidden = !options.cancelLabel;
      cancelSetup.textContent = options.cancelLabel || '취소';
      dialogs[mode].querySelector('[data-close]').setAttribute('aria-label', options.cancelLabel ||
        (mode === 'setup' ? '등록 모달 닫기' : '인증 모달 닫기'));
      bodyOverflow = document.body.style.overflow;
      document.body.style.overflow = 'hidden';
      dialogs.auth.hidden = mode !== 'auth'; dialogs.setup.hidden = mode !== 'setup';
      shell.classList.toggle('setup', mode === 'setup');
      dialog.showModal();
      layer.hidden = false; layer.scrollTop = 0;
      requestAnimationFrame(() => { if (session === options) layer.classList.add('open'); });
      resetMode(mode,false);
      if (mode === 'setup') restartWizard();
      else { syncTimer(); apis.auth.focus(); }
    }
    return open;
  }
  window.SystemOtp = Object.freeze({
    async open(options) {
      if (!loading) loading = initialize().catch(error => { loading = null; throw error; });
      (await loading)(options);
    }
  });
})();
