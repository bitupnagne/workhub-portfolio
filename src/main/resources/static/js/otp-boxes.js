/* OTP 6자리 박스 입력 — 개별 칸 자동 포커스/붙여넣기, 완성 시 중앙 수렴 + 스피너,
 * 성공 체크/실패 X 후 역재생. 시스템 테마(--brand-deeper 등) 사용. 전역: window.OtpBoxes.mount(el, opts) */
(function () {
  'use strict';
  var CSS = ''
    + '.otpx-wrap{position:relative;display:flex;gap:clamp(5px,2.2vw,8px);justify-content:center;align-items:center;height:54px;transition:gap .45s cubic-bezier(.25,1,.5,1)}'
    + '.otpx-box{width:clamp(34px,13vw,42px);height:52px;background:#fff;border:1.5px solid var(--border,#cbd2e0);border-radius:12px;'
    + 'text-align:center;font-size:21px;font-weight:700;color:var(--text,#1c2333);outline:none;'
    + 'transition:transform .55s cubic-bezier(.25,1,.5,1),border-color .35s ease,opacity .35s ease,color .25s ease,box-shadow .35s ease}'
    + '.otpx-box:focus{border-color:var(--brand-deeper,#3730A3);box-shadow:0 0 0 3px color-mix(in srgb,var(--brand-deeper,#3730A3) 18%,transparent)}'
    + '.otpx-box.otpx-pop{animation:otpxpop .18s cubic-bezier(.34,1.56,.64,1)}'
    + '@keyframes otpxpop{0%{transform:scale(1)}50%{transform:scale(1.12)}100%{transform:scale(1)}}'
    + '.otpx-ico{position:absolute;left:50%;top:50%;transform:translate(-50%,-50%);opacity:0;transition:opacity .3s ease;z-index:5;pointer-events:none}'
    + '.otpx-ico.show{opacity:1}'
    + '.otpx-spin{width:22px;height:22px;border:2.5px solid var(--border,#cbd2e0);border-top-color:var(--brand-deeper,#3730A3);border-radius:50%;animation:otpxspin .8s linear infinite}'
    + '@keyframes otpxspin{to{transform:rotate(360deg)}}'
    + '.otpx-shake{animation:otpxshake .4s cubic-bezier(.36,.07,.19,.97)}'
    + '@keyframes otpxshake{10%,90%{transform:translateX(-2px)}20%,80%{transform:translateX(3px)}30%,50%,70%{transform:translateX(-5px)}40%,60%{transform:translateX(5px)}}'
    + '.otpx-check path{stroke-dasharray:30;stroke-dashoffset:30;animation:otpxdraw .45s .1s ease forwards}'
    + '@keyframes otpxdraw{to{stroke-dashoffset:0}}'
    + '.otpx-result{margin-top:14px;font-size:16px;font-weight:800;text-align:center;letter-spacing:3px;opacity:0;transform:translateY(5px);transition:opacity .3s ease,transform .3s ease}'
    + '.otpx-result.show{opacity:1;transform:translateY(0)}'
    + '.otpx-result.ok{color:var(--success,#15803d)}.otpx-result.ng{color:var(--danger,#991b1b)}';

  function injectCss() {
    if (document.getElementById('otpx-css')) return;
    var s = document.createElement('style');
    s.id = 'otpx-css';
    s.textContent = CSS;
    document.head.appendChild(s);
  }

  function mount(container, opts) {
    injectCss();
    opts = opts || {};
    container.innerHTML = '';
    var wrap = document.createElement('div');
    wrap.className = 'otpx-wrap';
    var boxes = [];
    for (var i = 0; i < 6; i++) {
      var b = document.createElement('input');
      b.type = 'text';
      b.className = 'otpx-box';
      b.maxLength = 1;
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

    var locked = false;
    var convergeAt = 0;
    var MIN_LOADING = 1500; /* 스피너(로딩)를 최소 1.5초 노출한 뒤 성공/실패 표시 */
    function afterLoading(fn) {
      var target = convergeAt + 620 + MIN_LOADING; /* 수렴→스피너 노출(620ms)→최소 1.5s 로딩 */
      setTimeout(fn, Math.max(0, target - Date.now()));
    }

    function value() {
      return boxes.map(function (b) { return b.value; }).join('');
    }
    function fill(str) {
      var digits = (str || '').replace(/\D/g, '').slice(0, 6).split('');
      boxes.forEach(function (b, i) { b.value = digits[i] || ''; });
      var next = Math.min(digits.length, 5);
      boxes[next].focus();
      maybeComplete();
    }
    function maybeComplete() {
      if (locked) return;
      var v = value();
      if (v.length === 6 && /^\d{6}$/.test(v)) {
        locked = true;
        converge();
        if (opts.onComplete) opts.onComplete(v, api);
      }
    }
    /* 6칸이 중앙 1칸으로 수렴 — 수렴(0.55s)이 끝난 뒤에 스피너 시작 */
    function converge() {
      boxes.forEach(function (b) { b.blur(); b.readOnly = true; });
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
        else { b.style.borderColor = 'var(--brand-deeper, #3730A3)'; }
      });
      setTimeout(function () {
        spin.classList.add('show');
      }, 620);
    }
    function success(cb) {
      afterLoading(function () {
        spin.classList.remove('show');
        boxes[2].style.borderColor = 'var(--success, #15803d)';
        setTimeout(function () { okIco.classList.add('show'); }, 100);
        result.textContent = '성공';
        result.className = 'otpx-result ok';
        setTimeout(function () { result.classList.add('show'); }, 160);
        if (cb) setTimeout(cb, 1000);
      });
    }
    function fail(afterReset) {
      afterLoading(function () {
        spin.classList.remove('show');
        boxes[2].style.borderColor = 'var(--danger, #991b1b)';
        wrap.classList.add('otpx-shake');
        ngIco.classList.add('show');
        result.textContent = '실패';
        result.className = 'otpx-result ng';
        setTimeout(function () { result.classList.add('show'); }, 120);
        setTimeout(function () {
          wrap.classList.remove('otpx-shake');
          ngIco.classList.remove('show');
          result.classList.remove('show');
          reset();
          if (afterReset) afterReset();
        }, 1150);
      });
    }
    function reset() {
      locked = false;
      wrap.style.gap = '';
      boxes.forEach(function (b) {
        b.value = ''; b.readOnly = false;
        b.style.transitionDelay = '';
        b.style.transform = 'translateX(0)';
        b.style.opacity = '1';
        b.style.color = '';
        b.style.borderColor = '';
      });
      spin.classList.remove('show'); okIco.classList.remove('show'); ngIco.classList.remove('show');
      result.classList.remove('show'); result.className = 'otpx-result'; convergeAt = 0;
      setTimeout(function () { boxes[0].focus(); }, 120);
    }

    boxes.forEach(function (input, index) {
      input.addEventListener('input', function (e) {
        var v = e.target.value.replace(/\D/g, '');
        if (v.length > 1) { e.target.value = ''; fill(value().slice(0, index) + v); return; }
        e.target.value = v;
        if (v !== '') {
          input.classList.add('otpx-pop');
          setTimeout(function () { input.classList.remove('otpx-pop'); }, 200);
          if (index < 5) boxes[index + 1].focus();
        }
        maybeComplete();
      });
      input.addEventListener('keydown', function (e) {
        if (e.key === 'Backspace') {
          if (input.value === '' && index > 0) { boxes[index - 1].value = ''; boxes[index - 1].focus(); }
          else { input.value = ''; }
          e.preventDefault();
        } else if (e.key === 'ArrowLeft' && index > 0) { boxes[index - 1].focus(); }
        else if (e.key === 'ArrowRight' && index < 5) { boxes[index + 1].focus(); }
      });
      input.addEventListener('paste', function (e) {
        e.preventDefault();
        fill((e.clipboardData || window.clipboardData).getData('text'));
      });
    });

    var api = { value: value, reset: reset, success: success, fail: fail,
                focus: function () { boxes[0].focus(); } };
    return api;
  }

  window.OtpBoxes = { mount: mount };
})();
