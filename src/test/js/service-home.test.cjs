const test = require('node:test');
const assert = require('node:assert/strict');
const vm = require('node:vm');
const fs = require('node:fs');
const path = require('node:path');
function node(tag, attrs = {}) {
  const n = { tagName: tag.toUpperCase(), attrs: { ...attrs }, children: [], style: {}, parentNode: null, innerHTML: '', textContent: '',
    getAttribute(key) { return this.attrs[key] ?? null; }, setAttribute(key, value) { this.attrs[key] = value; },
    appendChild(child) { return this.insertBefore(child, null); },
    insertBefore(child, next) { if (child === next) return child; if (child.parentNode) child.parentNode.children.splice(child.parentNode.children.indexOf(child), 1); child.parentNode = this; const index = next ? this.children.indexOf(next) : this.children.length; this.children.splice(index, 0, child); return child; },
    remove() { if (this.parentNode) this.parentNode.children.splice(this.parentNode.children.indexOf(this), 1); this.parentNode = null; },
    contains(child) { for (let p = child; p; p = p.parentNode) if (p === this) return true; return false; },
    addEventListener() {}, getBoundingClientRect() { return { top: 100, right: 230 }; }, offsetHeight: 200,
    cloneNode() { const copy = node(tag, this.attrs); copy.href = this.href; copy.textContent = this.textContent; copy.innerHTML = this.innerHTML; this.children.forEach(child => copy.appendChild(child.cloneNode(true))); return copy; },
    closest(selector) { if (selector.includes('[data-set-service]') && this.attrs['data-set-service']) return this; return this.parentNode?.closest(selector) || null; },
  };
  const classes = new Set((attrs.class || '').split(/\s+/).filter(Boolean));
  n.classList = { contains: value => classes.has(value), add: (...values) => values.forEach(value => classes.add(value)), remove: (...values) => values.forEach(value => classes.delete(value)), toggle(value, on) { (on ?? !classes.has(value)) ? classes.add(value) : classes.delete(value); } };
  Object.defineProperty(n, 'className', { get: () => [...classes].join(' '), set: value => { classes.clear(); value.split(/\s+/).filter(Boolean).forEach(c => classes.add(c)); } });
  Object.defineProperty(n, 'nextSibling', { get: () => n.parentNode?.children[n.parentNode.children.indexOf(n) + 1] || null });
  return n;
}
function descendants(n) { return n.children.flatMap(child => [child, ...descendants(child)]); }
function fixture({ pathname = '/org', stored = 'reports', mobile = false, active = null, permitted = ['projects', 'reports', 'hr', 'documents', 'organization', 'communication', 'super-admin', 'members', 'account'] } = {}) {
  const listeners = {}; const location = { pathname }; const body = node('body'); const nav = body.appendChild(node('nav', { class: 'nav nav-init' })); const services = nav.appendChild(node('div', { class: 'nav-services' }));
  const home = services.appendChild(node('a', { class: 'nav-svc active', 'data-shell-link': '', 'aria-current': 'page' })); home.href = '/org';
  const buttons = ['projects', 'reports', 'hrself', 'approval', 'admin'].map(service => { const button = services.appendChild(node('a', { class: 'nav-svc', 'data-set-service': service })); button.href = '/service/' + service; return button; });
  const label = nav.appendChild(node('div')); const groups = permitted.map(name => {
    const group = nav.appendChild(node('details', { class: 'nav-group', 'data-nav-group': name })); const title = node('span'); title.innerHTML = name;
    const links = [1, 2].map(id => { const link = node('a'); link.href = '/' + name + '/' + id; return link; });
    group.querySelector = selector => selector === '.nav-children a' ? links[0] : selector === 'summary > span' ? title : null;
    group.querySelectorAll = selector => selector.startsWith('.nav-children >') ? links : [];
    if (active === name) group.classList.add('active-group'); return group;
  });
  nav.querySelector = selector => selector === '.nav-label' ? label : null;
  const orderedGroups = () => descendants(nav).filter(n => n.attrs['data-nav-group']);
  const document = { body,
    querySelector(selector) { if (selector === '.sidebar .nav') return nav; const service = /data-set-service="([^"]+)"/.exec(selector)?.[1]; return buttons.find(b => b.attrs['data-set-service'] === service) || null; },
    querySelectorAll(selector) {
      if (selector.includes('.nav-group.active-group')) return orderedGroups().filter(g => g.classList.contains('active-group'));
      if (selector.includes('.nav-group[data-nav-group]')) return orderedGroups();
      if (selector.includes('.nav-svc.svc-open')) return buttons.filter(b => b.classList.contains('svc-open'));
      if (selector.includes('.nav-svc[data-set-service]')) return buttons;
      if (selector.includes('.nav.nav-init')) return nav.classList.contains('nav-init') ? [nav] : [];
      return [];
    }, createElement: tag => node(tag), addEventListener(type, fn) { (listeners['document:' + type] ||= []).push(fn); },
  };
  const window = { location, innerHeight: 900, matchMedia: query => ({ matches: query.includes('max-width') ? mobile : !mobile }), addEventListener(type, fn) { (listeners['window:' + type] ||= []).push(fn); } };
  vm.runInNewContext(fs.readFileSync(path.join(__dirname, '../../main/resources/static/js/worksystem-service-nav.js'), 'utf8'), { document, window, location, Node: Object, localStorage: { getItem: () => stored, setItem: (key, value) => stored = value }, setTimeout, clearTimeout });
  return { body, nav, services, groups, buttons, home, location, label, listeners, group: name => groups.find(g => g.attrs['data-nav-group'] === name), button: name => buttons.find(b => b.attrs['data-set-service'] === name),
    update(pathname, active = null) { location.pathname = pathname; groups.forEach(g => g.classList.toggle('active-group', g.attrs['data-nav-group'] === active)); listeners['window:worksystem:content-updated'].forEach(fn => fn()); },
    fire(type, target, options = {}) { const event = { target, defaultPrevented: false, ...options, preventDefault() { this.defaultPrevented = true; } }; (listeners['document:' + type] || []).forEach(fn => fn(event)); return event; },
    stored: () => stored, visible: () => groups.filter(g => g.style.display !== 'none').map(g => g.attrs['data-nav-group']), popout: () => body.children.find(n => n.classList.contains('nav-popout')),
  };
}
test('organization home shows only common account with every saved service and fresh storage', () => {
  for (const stored of [null, 'all', 'projects', 'reports', 'hrself', 'approval', 'admin', 'eval', 'obsolete']) {
    const b = fixture({ stored }); assert.deepEqual(b.visible(), ['account']); assert.equal(b.stored(), stored); assert.equal(b.buttons.length, 5); assert.equal(b.home.href, '/org'); assert.ok(b.home.classList.contains('active')); assert.equal(b.home.getAttribute('aria-current'), 'page'); assert.ok(b.buttons.every(button => !button.classList.contains('active'))); assert.equal(b.nav.classList.contains('nav-init'), false);
  }
});
test('home ignores stale active groups and preserves role-limited server-rendered groups', () => {
  const b = fixture({ stored: 'admin', active: 'hr', permitted: ['hr', 'documents', 'account'] }); assert.deepEqual(b.visible(), ['account']); assert.equal(b.stored(), 'admin'); assert.equal(b.groups.length, 3);
});
test('HR soft navigation keeps one inline context and home hides relocated groups before return/back', () => {
  const b = fixture(); b.update('/hr/attendance', 'hr'); assert.deepEqual(b.visible(), ['hr', 'account']); assert.equal(b.group('hr').parentNode, b.services); assert.equal(b.button('hrself').nextSibling, b.group('hr')); assert.ok(b.group('hr').classList.contains('in-svc'));
  b.update('/org'); assert.deepEqual(b.visible(), ['account']); assert.equal(b.group('hr').parentNode, b.services); assert.equal(b.group('hr').classList.contains('in-svc'), false);
  b.update('/hr/attendance', 'hr'); assert.deepEqual(b.visible(), ['hr', 'account']); assert.equal(b.button('hrself').nextSibling, b.group('hr')); assert.equal(b.services.children.filter(n => n === b.group('hr')).length, 1);
});
test('admin/home/back-forward keeps admin group anchors together without duplicating other services', () => {
  const b = fixture(); for (let i = 0; i < 3; i++) { b.update('/admin/org', 'organization'); assert.deepEqual(new Set(b.visible()), new Set(['organization', 'communication', 'super-admin', 'members', 'account'])); assert.equal(b.button('admin').nextSibling, b.group('organization')); b.update('/org'); assert.deepEqual(b.visible(), ['account']); }
  assert.equal(new Set(descendants(b.nav).filter(n => n.attrs['data-nav-group'])).size, b.groups.length);
});
test('mobile home permits explicit one-service expansion and second tap collapse', () => {
  const b = fixture({ mobile: true }); assert.deepEqual(b.visible(), ['account']); assert.equal(b.fire('click', b.button('hrself')).defaultPrevented, true); assert.deepEqual(b.visible(), ['hr', 'account']); assert.ok(b.button('hrself').classList.contains('svc-open'));
  b.fire('click', b.button('admin')); assert.equal(b.group('hr').style.display, 'none'); assert.equal(b.button('hrself').classList.contains('svc-open'), false); assert.equal(b.group('organization').style.display, ''); b.fire('click', b.button('admin')); assert.deepEqual(b.visible(), ['account']);
});
test('returning home clears mobile accordion remnants so the first service tap opens again', () => {
  const b = fixture({ pathname: '/hr/attendance', active: 'hr', mobile: true }); assert.ok(b.button('hrself').classList.contains('svc-open')); b.group('hr').classList.add('m-open'); b.update('/org'); assert.deepEqual(b.visible(), ['account']); assert.ok(b.buttons.every(button => !button.classList.contains('svc-open'))); assert.ok(b.groups.every(group => !group.classList.contains('m-open')));
  b.fire('click', b.button('hrself')); assert.deepEqual(b.visible(), ['hr', 'account']); assert.ok(b.group('hr').classList.contains('m-open'));
});
test('plain house link retains native keyboard-activated navigation and is never an accordion', () => {
  const b = fixture({ mobile: true }); const event = b.fire('click', b.home, { detail: 0 }); assert.equal(event.defaultPrevented, false); assert.equal(b.home.href, '/org'); assert.ok(b.home.classList.contains('active'));
});
test('desktop hover and collapsed popouts retain link targets and close on content replacement', () => {
  const b = fixture(); b.fire('mouseover', b.button('hrself')); const first = b.popout(); assert.ok(first); assert.deepEqual(descendants(first).filter(n => n.tagName === 'A').map(n => n.href), ['/hr/1', '/hr/2']); b.update('/hr/attendance', 'hr'); assert.equal(b.popout(), undefined);
  b.body.classList.add('nav-collapsed'); b.fire('mouseover', b.button('hrself')); assert.ok(b.popout()); b.update('/org'); assert.equal(b.popout(), undefined); assert.deepEqual(b.visible(), ['account']);
});
test('non-home dashboard and unselected-page policies remain unchanged', () => {
  const dashboard = fixture({ pathname: '/dashboard', stored: 'all' }); assert.equal(dashboard.visible().length, dashboard.groups.length); assert.ok(dashboard.buttons.every(button => !button.classList.contains('active')));
  const account = fixture({ pathname: '/mypage', stored: 'hrself' }); assert.deepEqual(account.visible(), ['hr', 'account']); assert.equal(account.stored(), 'hrself');
});


test('project pages keep their own menu context across soft navigation and mobile expansion', () => {
  const b = fixture({ pathname: '/projects', active: 'projects' });
  assert.deepEqual(b.visible(), ['projects', 'account']);
  assert.equal(b.stored(), 'projects');
  assert.equal(b.button('projects').nextSibling, b.group('projects'));
  b.update('/projects/4/tasks/8', 'projects');
  assert.deepEqual(b.visible(), ['projects', 'account']);
  b.update('/report/my', 'reports');
  assert.deepEqual(b.visible(), ['reports', 'account']);
  b.update('/projects/4', 'projects');
  assert.equal(b.button('projects').nextSibling, b.group('projects'));
  const mobile = fixture({ mobile: true });
  mobile.fire('click', mobile.button('projects'));
  assert.deepEqual(mobile.visible(), ['projects', 'account']);
  assert.ok(mobile.button('projects').classList.contains('svc-open'));
});
