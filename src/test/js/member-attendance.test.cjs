const test = require('node:test');
const assert = require('node:assert/strict');
const vm = require('node:vm');
const fs = require('node:fs');
const path = require('node:path');
const script = fs.readFileSync(path.join(__dirname, '../../main/resources/static/js/worksystem-member-attendance.js'), 'utf8');
const flush = () => new Promise(setImmediate);

function fixture({ modal = false } = {}) {
  const canonical = 'http://localhost/hr/members/9?from=2026-01-01&to=2026-11-05&astatus=&lpage=2&fpage=3&tpage=4&apage=1';
  const host = modal ? 'http://localhost/hr/members?from=2026-01-01' : canonical;
  const env = { calls: [], assignments: [], pending: null, focused: null, outside: {}, modal: modal ? { scrollTop: 430, scrollLeft: 0 } : null };
  const listeners = new Map();
  const events = name => ({
    addEventListener(type, fn, options) { const key = name + ':' + type; const list = listeners.get(key) || []; list.push({ fn, capture: options === true }); listeners.set(key, list); },
    dispatchEvent(event) { return this.fire(event.type, event); },
    fire(type, input = {}) {
      const event = { button: 0, defaultPrevented: false, preventDefault() { this.defaultPrevented = true; }, stopImmediatePropagation() { this.stopped = true; }, ...input };
      for (const { fn } of [...(listeners.get(name + ':' + type) || [])].sort((a, b) => Number(b.capture) - Number(a.capture))) { fn(event); if (event.stopped) break; }
      return event;
    },
  });
  const win = events('window'); const doc = events('document');
  const locate = href => { win.location = Object.assign(new URL(href), { assign: value => env.assignments.push(value) }); };
  locate(host); win.scrollX = 0; win.scrollY = 900; win.scrollTo = (x, y) => { win.scrollX = x; win.scrollY = y; };
  const entries = [{ href: host, state: null }]; let index = 0;
  const history = { get state() { return entries[index].state; },
    replaceState(state, title, href) { entries[index] = { state, href }; locate(href); },
    pushState(state, title, href) { entries.splice(index + 1); entries.push({ state, href }); index++; locate(href); },
  };
  function control(kind, root) {
    const attrs = {}; const classes = new Set();
    return { kind, root, attrs, hidden: false, target: '', textContent: '',
      classList: { contains: value => classes.has(value), toggle(value, on) { if (on) classes.add(value); else classes.delete(value); } },
      hasAttribute: name => name === 'data-member-attendance-' + kind || Object.hasOwn(attrs, name),
      getAttribute: name => attrs[name], setAttribute(name, value) { attrs[name] = value; }, removeAttribute(name) { delete attrs[name]; },
      closest(selector) {
        if (selector === '#member-attendance-history') return root;
        if (selector.includes('a[href]') || selector.includes('[data-member-attendance-retry]')) return this;
        if (selector.includes('.pagination')) return kind === 'page' ? {} : null;
        return null;
      },
      focus() { env.focused = this; }, click(extra) { return doc.fire('click', { target: this, ...extra }); },
    };
  }
  function makeRoot(href) {
    const r = { dataset: { memberAttendanceUrl: href }, isConnected: true, attrs: {}, table: { scrollLeft: 15, scrollTop: 0 },
      closest(selector) { return selector === '[data-app-form-body]' ? env.modal : null; },
      setAttribute(name, value) { this.attrs[name] = value; }, removeAttribute(name) { delete this.attrs[name]; },
      replaceWith(fresh) { this.isConnected = false; env.root = fresh; }, dispatchEvent() {},
    };
    r.message = { textContent: '' }; r.retry = control('retry', r); r.fallback = control('fallback', r); r.status = control('status', r);
    r.status.querySelector = selector => ({ '[data-member-attendance-message]': r.message, '[data-member-attendance-retry]': r.retry, '[data-member-attendance-fallback]': r.fallback }[selector]);
    r.querySelector = selector => selector === '[data-member-attendance-status]' ? r.status : selector === '.table-scroll' ? r.table : selector === 'table' ? {} : selector.includes('.active') ? r.page(2) : null;
    r.page = number => { const p = control('page', r); const u = new URL(href); u.searchParams.set('apage', number); p.href = u.href; p.attrs.href = p.href; return p; };
    return r;
  }
  env.root = makeRoot(canonical); doc.readyState = 'complete'; doc.querySelectorAll = () => [env.root];
  const response = (href, options = {}) => ({ ok: true, url: href, text: async () => href, ...options });
  const sandbox = { window: win, document: doc, history, URL, AbortController, CustomEvent: class { constructor(type, options) { this.type = type; Object.assign(this, options); } },
    DOMParser: class { parseFromString(href) { return { querySelector: () => href === 'invalid' ? null : makeRoot(href) }; } },
    fetch: (href, options) => { env.calls.push({ href, options }); return env.pending ? env.pending(href, options) : Promise.resolve(response(href)); },
  };
  Object.defineProperty(sandbox, 'location', { get: () => win.location });
  vm.runInNewContext(script, sandbox);
  return Object.assign(env, { win, doc, history, entries, response,
    rerun: () => vm.runInNewContext(script, sandbox),
    back() { index--; locate(entries[index].href); return win.fire('popstate', { state: entries[index].state }); },
  });
}

test('pagination owns its event, preserves query and scroll, and replaces only its own region', async () => {
  const b = fixture(); let generic = 0; b.doc.addEventListener('click', () => generic++);
  const outside = b.outside; const old = b.root; const event = old.page(2).click(); await flush();
  assert.equal(event.defaultPrevented, true); assert.equal(generic, 0); assert.notEqual(b.root, old); assert.equal(b.outside, outside);
  assert.equal(b.calls[0].options.headers['X-Worksystem-Fragment'], 'member-attendance');
  for (const [key, value] of [['apage', '2'], ['lpage', '2'], ['fpage', '3'], ['tpage', '4'], ['from', '2026-01-01']]) assert.equal(new URL(b.win.location).searchParams.get(key), value);
  assert.equal(b.win.scrollY, 900); assert.equal(b.root.table.scrollLeft, 15); assert.equal(b.entries.length, 2);
});

test('Back restores the attendance fragment and suppresses generic full-content navigation', async () => {
  const b = fixture(); let generic = 0; b.win.addEventListener('popstate', () => generic++);
  b.root.page(2).click(); await flush(); b.root.page(3).click(); await flush();
  b.back(); await flush();
  assert.equal(new URL(b.root.dataset.memberAttendanceUrl).searchParams.get('apage'), '2');
  assert.equal(b.entries.length, 3); assert.equal(generic, 0);
});

test('nested card modal keeps the list URL while Back restores its own page and scroll', async () => {
  const b = fixture({ modal: true }); const outer = b.win.location.href;
  b.root.page(2).click(); await flush(); b.root.page(3).click(); await flush(); b.back(); await flush();
  assert.equal(b.win.location.href, outer); assert.equal(new URL(b.root.dataset.memberAttendanceUrl).searchParams.get('apage'), '2');
  assert.equal(b.modal.scrollTop, 430); assert.equal(b.history.state.wsMemberAttendance.modal, true);
});

test('newer navigation wins even if an old transport ignores abort', async () => {
  const b = fixture(); const pending = []; b.pending = href => new Promise(resolve => pending.push({ href, resolve }));
  b.root.page(2).click(); b.root.page(3).click();
  assert.equal(b.calls[0].options.signal.aborted, true);
  pending[1].resolve(b.response(pending[1].href)); await flush(); pending[0].resolve(b.response(pending[0].href)); await flush();
  assert.equal(new URL(b.root.dataset.memberAttendanceUrl).searchParams.get('apage'), '3'); assert.equal(b.entries.length, 2);
});

test('HTTP errors and invalid fragments keep existing content and offer retry/native fallback', async () => {
  for (const options of [{ ok: false }, { text: async () => 'invalid' }]) {
    const b = fixture(); const old = b.root; b.pending = href => Promise.resolve(b.response(href, options));
    old.page(2).click(); await flush();
    assert.equal(b.root, old); assert.equal(b.entries.length, 1); assert.equal(old.status.attrs.role, 'alert');
    assert.equal(old.fallback.hidden, false); const event = old.fallback.click(); assert.equal(event.defaultPrevented, false); assert.equal(event.stopped, true);
    b.pending = null; old.retry.click(); await flush(); assert.equal(new URL(b.root.dataset.memberAttendanceUrl).searchParams.get('apage'), '2');
  }
});

test('login redirect navigates to authentication while other redirects cannot replace the table', async () => {
  for (const path of ['/login?expired=true', '/mypage']) {
    const b = fixture(); const old = b.root; b.pending = () => Promise.resolve(b.response('http://localhost' + path));
    old.page(2).click(); await flush(); assert.equal(b.root, old);
    assert.equal(b.assignments.length, path.startsWith('/login') ? 1 : 0);
  }
});

test('modifier links and duplicate loading retain native behavior and bind only once', async () => {
  const b = fixture(); assert.equal(b.root.page(2).click({ ctrlKey: true }).defaultPrevented, false);
  const blank = b.root.page(2); blank.target = '_blank'; assert.equal(blank.click().defaultPrevented, false);
  b.rerun(); b.root.page(2).click(); await flush(); assert.equal(b.calls.length, 1);
});

test('leaving for another context cancels an old request and allows normal navigation', async () => {
  const b = fixture(); let resolve; b.pending = () => new Promise(done => { resolve = done; });
  b.root.page(2).click(); const old = b.root;
  b.win.dispatchEvent({ type: 'worksystem:navigation-start', detail: { owner: 'soft' } });
  assert.equal(b.calls[0].options.signal.aborted, true);
  resolve(b.response(b.calls[0].href)); await flush(); assert.equal(b.root, old); assert.equal(b.entries.length, 1);
});
