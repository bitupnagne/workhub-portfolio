const test = require('node:test');
const assert = require('node:assert/strict');
const vm = require('node:vm');
const fs = require('node:fs');
const path = require('node:path');

function classes(values = []) {
  const state = new Set(values);
  return { contains: (name) => state.has(name), add: (name) => state.add(name), remove: (name) => state.delete(name),
    toggle(name, on) { if (on) state.add(name); else state.delete(name); } };
}
function link(href, group, active = false) {
  return { href, classList: classes(active ? ['active'] : []), attrs: {},
    closest: () => ({ getAttribute: () => group }),
    setAttribute(name, value) { this.attrs[name] = value; }, removeAttribute(name) { delete this.attrs[name]; } };
}
function environment(links) {
  const document = {
    body: { classList: classes() }, readyState: 'loading', addEventListener() {}, querySelector: () => null,
    querySelectorAll(selector) { return selector.includes('.sidebar .nav-children a[href]') ? links : []; }
  };
  const window = { location: { href: 'http://localhost/people/102' }, addEventListener() {}, innerWidth: 1920 };
  const context = { document, window, URL, localStorage: { getItem: () => null }, requestAnimationFrame: (fn) => fn() };
  vm.runInNewContext(fs.readFileSync(path.join(__dirname, '../../main/resources/static/js/worksystem-nav.js'), 'utf8'), context);
  return window.worksystemSyncMenu;
}
function source(links) { return { querySelectorAll: () => links }; }

test('own numeric card retains the server-selected self menu instead of selecting the directory', () => {
  const list = link('http://localhost/people?view=list', 'people');
  const own = link('http://localhost/people/me', 'people', true);
  environment([list, own]);
  assert.equal(own.attrs['aria-current'], 'page');
  assert.equal(list.classList.contains('active'), false);
});

test('soft navigation distinguishes document boxes sharing the same pathname and clears stale selection', () => {
  const approval = link('http://localhost/hr/documents?box=all', 'documents', true);
  const io = link('http://localhost/hr/documents?box=ioall', 'documents');
  const sync = environment([approval, io]);
  sync(source([link(approval.href, 'documents'), link(io.href, 'documents', true)]));
  assert.equal(approval.classList.contains('active'), false);
  assert.equal(approval.attrs['aria-current'], undefined);
  assert.equal(io.attrs['aria-current'], 'page');
});

test('duplicate organization chart shortcuts in different groups do not both become active', () => {
  const primary = link('http://localhost/org', 'people', true);
  const alias = link('http://localhost/org', 'hr');
  environment([primary, alias]);
  assert.equal(primary.classList.contains('active'), true);
  assert.equal(alias.classList.contains('active'), false);
});

test('query order is normalized and repeated synchronization removes stale aria-current', () => {
  const first = link('http://localhost/hr/documents?box=all&view=list', 'documents', true);
  const second = link('http://localhost/hr/documents?box=ioall&view=list', 'documents');
  const sync = environment([first, second]);
  sync(source([link('http://localhost/hr/documents?view=list&box=ioall', 'documents', true)]));
  assert.equal(second.attrs['aria-current'], 'page');
  assert.equal(first.attrs['aria-current'], undefined);
  sync(source([link('http://localhost/hr/documents?view=list&box=all', 'documents', true)]));
  assert.equal(first.attrs['aria-current'], 'page');
  assert.equal(second.attrs['aria-current'], undefined);
});


test('organization home and mobile home follow server state through soft navigation', () => {
  const org = link('http://localhost/org', '', true);
  const mobile = link('http://localhost/org', '', true);
  const dashboard = link('http://localhost/dashboard', '');
  const sync = environment([org, mobile, dashboard]);
  assert.equal(org.attrs['aria-current'], 'page'); assert.equal(mobile.attrs['aria-current'], 'page');
  sync(source([link(org.href, ''), link(mobile.href, ''), link(dashboard.href, '', true)]));
  assert.equal(org.attrs['aria-current'], undefined); assert.equal(mobile.attrs['aria-current'], undefined);
  assert.equal(dashboard.attrs['aria-current'], 'page');
});
