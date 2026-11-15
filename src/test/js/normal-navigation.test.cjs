const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const root = path.join(__dirname, '../../main/resources');
const panel = fs.readFileSync(path.join(root, 'static/js/worksystem-right-panel.js'), 'utf8');
const release = fs.readFileSync(path.join(root, 'templates/admin/releases.html'), 'utf8');
function notification(targetUrl, { soft = true, room = null, documentPopup = false } = {}) {
  const calls = [];
  const location = { href: 'https://worksystem.test/my-work', origin: 'https://worksystem.test' };
  const window = { location };
  if (soft) window.worksystemSoftNavigate = target => calls.push(['soft', target]);
  if (documentPopup) { window.vfIsDocumentPopupUrl = () => true; window.vfOpenDocumentPopup = target => calls.push(['document', target]); }
  const start = panel.indexOf('    const openTarget = () => {');
  assert.ok(start >= 0);
  const end = panel.indexOf('\n    };', start) + '\n    };'.length;
  const open = vm.runInNewContext(panel.slice(start, end) + '\nopenTarget;', {
    window, targetUrl, messageRoomId: room, URL,
    openChatRoom: id => calls.push(['chat', id]),
    panel: { classList: { remove: name => calls.push(['closePanel', name]) } },
    load: () => calls.push(['load']),
  });
  open();
  return { calls, href: location.href };
}
test('notification internal relative and absolute targets use the existing soft navigator', () => {
  for (const target of ['/hr/attendance?tab=leave', 'https://worksystem.test/admin/grades']) {
    const result = notification(target);
    assert.deepEqual(result.calls, [['soft', target]]);
    assert.equal(result.href, 'https://worksystem.test/my-work');
  }
});
test('notification external targets and missing helper retain hard-navigation fallback', () => {
  const foreign = notification('https://external.test/docs');
  assert.deepEqual(foreign.calls, []); assert.equal(foreign.href, 'https://external.test/docs');
  const fallback = notification('/my-work', { soft: false });
  assert.deepEqual(fallback.calls, []); assert.equal(fallback.href, '/my-work');
});
test('notification document popup and chat retain priority over navigation', () => {
  assert.deepEqual(notification('/hr/documents/7', { documentPopup: true }).calls, [['document','/hr/documents/7'],['closePanel','open'],['load']]);
  assert.deepEqual(notification('/ignored', { room: 'room-7' }).calls, [['chat','room-7']]);
});
test('notification malformed URL keeps its prior assignment fallback', () => {
  const result = notification('http://[');
  assert.deepEqual(result.calls, []); assert.equal(result.href, 'http://[');
});
function uploadLoad(responseURL, soft) {
  const calls = [], progress = { textContent: '' }, location = { href: '/old' };
  const window = { location };
  if (soft) window.worksystemSoftNavigate = target => calls.push(target);
  const start = release.indexOf("  xhr.addEventListener('load', () => {");
  assert.ok(start >= 0);
  const bodyStart = release.indexOf('{', start) + 1, end = release.indexOf('\n  });', bodyStart);
  vm.runInNewContext(release.slice(bodyStart, end), { window, xhr: { responseURL }, uploadProgressText: progress });
  return { calls, href: location.href, progress: progress.textContent };
}
test('release upload completion forwards the exact response destination to soft navigation', () => {
  const result = uploadLoad('https://worksystem.test/admin/releases?message=ready', true);
  assert.deepEqual(result.calls, ['https://worksystem.test/admin/releases?message=ready']);
  assert.equal(result.href, '/old'); assert.equal(result.progress, '처리 완료 100%/100%');
});
test('release completion retains empty-response and unavailable-helper fallbacks', () => {
  assert.deepEqual(uploadLoad('', true).calls, ['/admin/releases']);
  assert.equal(uploadLoad('/admin/releases', false).href, '/admin/releases');
});
test('release auth destination is delegated unchanged to the existing navigation guard', () => {
  assert.deepEqual(uploadLoad('https://worksystem.test/login', true).calls, ['https://worksystem.test/login']);
});
