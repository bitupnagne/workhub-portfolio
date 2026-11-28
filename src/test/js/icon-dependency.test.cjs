const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

// 아이콘은 window.wsIcon(worksystem-icons.js)으로 그린다. 공통 레이아웃(fragments/app)을 쓰지 않는
// 단독 화면(결재 문서 창 등)이 wsIcon 을 쓰는 스크립트만 싣고 아이콘 스크립트를 빠뜨리면
// 버튼이 동작하지 않는다 — 결재 확인 창이 안 열려 승인 자체가 막혔던 일이 있다.
const root = path.join(__dirname, '../../main/resources');
const jsDir = path.join(root, 'static/js');
const tplDir = path.join(root, 'templates');

function walk(dir) {
  return fs.readdirSync(dir, { withFileTypes: true }).flatMap((e) =>
    e.isDirectory() ? walk(path.join(dir, e.name)) : (e.name.endsWith('.html') ? [path.join(dir, e.name)] : []));
}

test('every page that loads a wsIcon script also loads worksystem-icons.js', () => {
  const dependents = fs.readdirSync(jsDir)
    .filter((f) => f.endsWith('.js') && f !== 'worksystem-icons.js')
    .filter((f) => /wsIcon\(/.test(fs.readFileSync(path.join(jsDir, f), 'utf8')));
  assert.ok(dependents.length > 0);
  const missing = [];
  for (const file of walk(tplDir)) {
    const html = fs.readFileSync(file, 'utf8');
    const viaLayout = /fragments\/app :: (scripts|sidebar)/.test(html) || html.includes('worksystem-icons.js');
    if (viaLayout) continue;
    for (const js of dependents) {
      if (html.includes('/js/' + js)) missing.push(path.relative(tplDir, file) + ' <- ' + js);
    }
  }
  assert.deepEqual(missing, []);
});

// @view-transition(navigation:auto) 이 든 worksystem.css 를 싣는 화면은 전환 오류 정리 스크립트를 <head> 에서 실어야 한다.
// 본문 끝에 두면 긴 화면은 스크립트보다 먼저 그려져 pagereveal 을 놓치고
// "Uncaught (in promise) InvalidStateError: Transition was aborted ... opt-in disabled" 가 다시 찍힌다.
test('every page using worksystem.css loads worksystem-view-transition.js in <head>', () => {
  const missing = [];
  for (const file of walk(tplDir)) {
    const html = fs.readFileSync(file, 'utf8');
    if (!html.includes('/css/worksystem.css')) continue;
    const head = html.slice(0, Math.max(0, html.indexOf('</head>')));
    if (!head.includes('/js/worksystem-view-transition.js')) missing.push(path.relative(tplDir, file));
  }
  assert.deepEqual(missing, []);
});
