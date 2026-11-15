/*
 * WorkHub service worker.
 * 보안/정합성 우선 전략:
 *  - 정적 자산(/css,/js,/img,/vendor,manifest)만 cache-first 로 캐시한다.
 *  - HTML 내비게이션·API(JSON)·인증 관련 요청은 캐시하지 않는다(다른 사용자/만료 콘텐츠 노출 방지).
 *  - 오프라인 내비게이션은 최소 오프라인 안내(offline.html)로 폴백한다.
 */
// 정적 자산(css/js/img)이 바뀌는 배포에서는 반드시 VERSION 을 올린다 —
// 구버전 캐시를 activate 단계에서 즉시 폐기해 새 HTML + 낡은 CSS 조합(런처 라벨 미표시 등)을 막는다.
const VERSION = 'portfolio-workhub-v1';
const STATIC_CACHE = 'ws-static-' + VERSION;
const OFFLINE_URL = '/offline.html';

const PRECACHE = [
  OFFLINE_URL,
  '/css/worksystem.css',
  '/img/AppLogoMini.svg',
  '/manifest.webmanifest'
];

self.addEventListener('install', (event) => {
  event.waitUntil(
    // cache:'reload' — 프리캐시가 브라우저 HTTP 캐시의 낡은 사본을 다시 담지 않도록 서버에서 직접 받는다.
    caches.open(STATIC_CACHE)
      .then((cache) => cache.addAll(PRECACHE.map((u) => new Request(u, { cache: 'reload' }))).catch(() => {}))
      .then(() => self.skipWaiting())
  );
});

self.addEventListener('activate', (event) => {
  event.waitUntil(
    caches.keys().then((keys) => Promise.all(
      keys.filter((k) => k.startsWith('ws-static-') && k !== STATIC_CACHE).map((k) => caches.delete(k))
    )).then(() => self.clients.claim())
  );
});

function isStaticAsset(url) {
  return /\/(css|js|img|vendor)\//.test(url.pathname)
    || url.pathname === '/manifest.webmanifest'
    || url.pathname === '/favicon.ico';
}

self.addEventListener('fetch', (event) => {
  const req = event.request;
  if (req.method !== 'GET') return;
  const url = new URL(req.url);
  if (url.origin !== self.location.origin) return;

  // 정적 자산: cache-first, 백그라운드 갱신
  if (isStaticAsset(url)) {
    event.respondWith(
      caches.open(STATIC_CACHE).then((cache) => cache.match(req)).then((cached) => {
        // cache:'no-cache' — 백그라운드 갱신이 HTTP 캐시 신선도(휴리스틱)를 건너뛰고
        // 항상 서버에 조건부 검증하도록. 낡은 사본이 SW 캐시에 다시 고정되는 것을 방지.
        const network = fetch(req, { cache: 'no-cache' }).then((res) => {
          if (res && res.status === 200) {
            const copy = res.clone();
            caches.open(STATIC_CACHE).then((c) => c.put(req, copy));
          }
          return res;
        }).catch(() => cached);
        return cached || network;
      })
    );
    return;
  }

  // 내비게이션(HTML): network-only + 오프라인 폴백 (캐시 안 함)
  if (req.mode === 'navigate') {
    event.respondWith(
      fetch(req).catch(() => caches.open(STATIC_CACHE).then((cache) => cache.match(OFFLINE_URL)))
    );
    return;
  }
  // 그 외(API/JSON 등): 네트워크 그대로(캐시 안 함)
});
