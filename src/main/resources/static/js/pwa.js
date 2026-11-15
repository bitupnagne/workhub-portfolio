/*
 * PWA 부트스트랩 — 45개 페이지 <head> 를 직접 수정하지 않고, sidebar 프래그먼트에서 로드되어
 * 매니페스트 링크·테마컬러·애플 메타·viewport-fit 를 <head> 에 주입하고 서비스워커를 등록한다.
 */
(function () {
  var head = document.head || document.getElementsByTagName('head')[0];
  if (!head) return;

  function ensure(selector, create) {
    if (document.querySelector(selector)) return;
    head.appendChild(create());
  }

  // 매니페스트
  ensure('link[rel="manifest"]', function () {
    var l = document.createElement('link');
    l.rel = 'manifest';
    l.href = '/manifest.webmanifest';
    return l;
  });

  // 테마 컬러(주소창/상태바) — 시스템 브랜드 teal
  ensure('meta[name="theme-color"]', function () {
    var m = document.createElement('meta');
    m.name = 'theme-color';
    m.content = '#4F46E5';
    return m;
  });

  // iOS 홈 화면 앱
  ensure('meta[name="apple-mobile-web-app-capable"]', function () {
    var m = document.createElement('meta');
    m.name = 'apple-mobile-web-app-capable';
    m.content = 'yes';
    return m;
  });
  ensure('meta[name="apple-mobile-web-app-status-bar-style"]', function () {
    var m = document.createElement('meta');
    m.name = 'apple-mobile-web-app-status-bar-style';
    m.content = 'default';
    return m;
  });
  ensure('meta[name="mobile-web-app-capable"]', function () {
    var m = document.createElement('meta');
    m.name = 'mobile-web-app-capable';
    m.content = 'yes';
    return m;
  });
  ensure('link[rel="apple-touch-icon"]', function () {
    var l = document.createElement('link');
    l.rel = 'apple-touch-icon';
    l.href = '/img/AppLogoMini.svg';
    return l;
  });

  // viewport-fit=cover (노치/홈 인디케이터 safe-area 사용)
  var vp = document.querySelector('meta[name="viewport"]');
  if (vp) {
    if (!/viewport-fit/.test(vp.content)) {
      vp.content = vp.content.replace(/\s*$/, '') + ', viewport-fit=cover';
    }
  } else {
    var m = document.createElement('meta');
    m.name = 'viewport';
    m.content = 'width=device-width, initial-scale=1, viewport-fit=cover';
    head.appendChild(m);
  }

  // 서비스워커 등록
  if ('serviceWorker' in navigator) {
    window.addEventListener('load', function () {
      navigator.serviceWorker.register('/sw.js').catch(function () { /* 무시 */ });
    });
  }
})();
