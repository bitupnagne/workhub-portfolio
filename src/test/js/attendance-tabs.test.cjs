const test = require('node:test');
const assert = require('node:assert/strict');
const vm = require('node:vm');
const fs = require('node:fs');
const path = require('node:path');
const script = fs.readFileSync(path.join(__dirname, '../../main/resources/static/js/worksystem-attendance-tabs.js'), 'utf8');
const flush = () => new Promise(setImmediate);

// Browser lifecycle/network interfaces are controlled here; real layout and actual server HTML are checked separately.
function fixture(start = 'http://localhost/hr/attendance?cal=2026-10&tab=attendance&lq=abc') {
  const env = { calls: [], assignments: [], emitted: [], focused: null, pending: null, root: null, calendar: {} };
  const listeners = new Map();
  const events = name => ({
    addEventListener(type, fn, options) { const key = name + ':' + type; const items = listeners.get(key) || []; items.push({ fn, capture: options === true }); listeners.set(key, items); },
    dispatchEvent(event) { env.emitted.push(event.type); this.fire(event.type, event); },
    fire(type, input = {}) {
      const event = { button: 0, defaultPrevented: false, preventDefault() { this.defaultPrevented = true; }, stopImmediatePropagation() { this.stopped = true; }, ...input };
      for (const { fn } of [...(listeners.get(name + ':' + type) || [])].sort((a, b) => Number(b.capture) - Number(a.capture))) { fn(event); if (event.stopped) break; }
      return event;
    }
  });
  const win = events('window'); const doc = events('document'); win.location = new URL(start); win.location.assign = href => env.assignments.push(href);
  win.scrollX = 0; win.scrollY = 640;
  win.scrollTo = (x, y) => { win.scrollX = x; win.scrollY = y; };
  const entries = [{ state: null, href: start }]; let index = 0;
  const history = { get state() { return entries[index].state; },
    replaceState(state, title, href) { entries[index] = { state, href: new URL(href, win.location.href).href }; win.location = Object.assign(new URL(entries[index].href), { assign: value => env.assignments.push(value) }); },
    pushState(state, title, href) { entries.splice(index + 1); entries.push({ state, href }); index++; win.location = Object.assign(new URL(href), { assign: value => env.assignments.push(value) }); },
  };
  function element(kind, root) {
    const attrs = {}; const classes = new Set();
    return { kind, root, attrs, dataset: {}, hidden: false, target: '', classList: { toggle(name, on) { if (on) classes.add(name); else classes.delete(name); }, contains: name => classes.has(name) },
      hasAttribute(name) { return name === 'data-attendance-' + kind || Object.hasOwn(attrs, name); },
      setAttribute(name, value) { attrs[name] = value; }, getAttribute: name => attrs[name], removeAttribute(name) { delete attrs[name]; },
      closest(selector) {
        if (selector === '.pagination') return kind === 'page' ? {} : null;
        return selector.includes('[data-attendance-' + kind + ']') || (selector.includes('a[href]') && ['tab', 'page', 'fallback', 'ordinary'].includes(kind)) ? this : null;
      },
      focus() { env.focused = this; }, click() { return doc.fire('click', { target: this }); },
    };
  }
  function makeRoot(selected, href) {
    const r = { nodeType:1,nodeName:'DIV',tagName:'DIV',childNodes:[],children:[], isConnected: true, attrs: {}, style: {}, dataset: { historyUrl: href, currentTab: selected },
      get attributes(){return [...Object.entries(this.attrs),...Object.entries(this.dataset).map(([k,v])=>['data-'+k.replace(/[A-Z]/g,c=>'-'+c.toLowerCase()),v])].map(([name,value])=>({name,value}));},
      setAttribute(k,v){if(k.startsWith('data-'))this.dataset[k.slice(5).replace(/-([a-z])/g,(_,c)=>c.toUpperCase())]=v;else this.attrs[k]=v;}, removeAttribute(k){if(k.startsWith('data-'))delete this.dataset[k.slice(5).replace(/-([a-z])/g,(_,c)=>c.toUpperCase())];else delete this.attrs[k];},
      contains(node) { return node?.root === this; },
      replaceWith(fresh) { this.isConnected = false; env.root = fresh; },
      dispatchEvent(event) { env.emitted.push(event.type); },
    };
    const message = { textContent: '' }; const retry = element('retry', r); const fallback = element('fallback', r);
    const status = element('status', r); status.hidden = true;
    status.querySelector = selector => ({ '[data-attendance-message]': message, '[data-attendance-retry]': retry, '[data-attendance-fallback]': fallback }[selector]);
    r.status = status; r.message = message; r.retry = retry; r.fallback = fallback;
    r.table = { scrollTop: 0, scrollLeft: 17 };
    r.page = number => { const page = element('page', r); page.href = 'http://localhost/hr/attendance?page=' + number; page.attrs.href = page.href; return page; };
    r.form = fields => ({ root: r, method: 'get', target: '', action: 'http://localhost/hr/attendance', fields,
      submit() { return doc.fire('submit', { target: this }); } });
    r.tabs = ['attendance', 'leave', 'flex', 'trip', 'correction'].map(id => { const tab = element('tab', r); tab.dataset.attendanceTab = id; tab.attrs['aria-selected'] = String(id === selected); tab.href = 'http://localhost/hr/attendance?tab=' + id; return tab; });
    r.querySelectorAll = selector => selector === '[data-attendance-tab]' ? r.tabs : [];
    r.querySelector = selector => selector === '[data-attendance-status]' ? status : selector === '.table-scroll' ? r.table : selector === '[role="tabpanel"]' ? {} : selector === '[aria-selected="true"]' ? r.tabs.find(t => t.getAttribute('aria-selected') === 'true') : selector === '.pagination .active' ? r.page(new URL(href).searchParams.get('page')) : null;
    return r;
  }
  env.root = makeRoot(new URL(start).searchParams.get('tab'), start);
  doc.readyState = 'complete'; doc.querySelector = () => env.root; doc.querySelectorAll = () => [];
  const response = (href, options = {}) => ({ ok: true, url: href, text: async () => new URL(href).searchParams.get('tab'), ...options });
  env.respond = response;
  const fetch = async (href, options) => { env.calls.push({ href, options }); return env.pending ? env.pending(href, options) : response(href); };
  class Parser { parseFromString(tab) { return { querySelector: () => tab === 'invalid' ? null : makeRoot(tab, env.calls.at(-1).href) }; } }
  const context = { window: win, document: doc, history, URL, AbortController, DOMParser: Parser,
    FormData: class { constructor(form) { this.fields = form.fields; } forEach(fn) { Object.entries(this.fields).forEach(([key, value]) => fn(value, key)); } },
    CustomEvent: class { constructor(type, options) { this.type = type; Object.assign(this, options); } }, fetch };
  win.history = history;
  vm.runInNewContext(script, context);
  return Object.assign(env, { win, doc, history, entries, rerun: () => vm.runInNewContext(script, context),
    loadSoft() { vm.runInNewContext(fs.readFileSync(path.join(__dirname, '../../main/resources/static/js/worksystem-soft-submit.js'), 'utf8'), context); },
    tab(id) { return env.root.tabs.find(t => t.dataset.attendanceTab === id); },
    back() { index--; win.location = Object.assign(new URL(entries[index].href), { assign: href => env.assignments.push(href) }); return win.fire('popstate', { state: entries[index].state }); },
    forward() { index++; win.location = Object.assign(new URL(entries[index].href), { assign: href => env.assignments.push(href) }); return win.fire('popstate', { state: entries[index].state }); },
  });
}

test('tab fetch swaps only history, retains query/calendar context, and reinitializes existing content', async () => {
  const b = fixture(); const calendar = b.calendar; b.tab('leave').click(); await flush();
  assert.equal(b.calls.length, 1); assert.equal(b.calls[0].options.headers['X-Worksystem-Fragment'], 'attendance-history');
  assert.equal(new URL(b.calls[0].href).searchParams.get('lq'), 'abc');
  assert.equal(b.root.dataset.currentTab, 'leave'); assert.equal(b.calendar, calendar);
  assert.equal(b.entries.length, 2); assert.equal(new URL(b.win.location).searchParams.get('tab'), 'leave');
  assert.ok(b.emitted.includes('ws:partial-reloaded') && b.emitted.includes('worksystem:content-updated'));
  assert.equal(b.focused.dataset.attendanceTab, 'leave'); assert.equal(b.assignments.length, 0);
});
test('back restores tab fragment without creating a history entry or invoking generic navigation', async () => {
  const b = fixture(); let generic = 0; b.win.addEventListener('popstate', () => generic++);
  b.tab('leave').click(); await flush(); b.tab('trip').click(); await flush();
  const count = b.entries.length; b.back(); await flush();
  assert.equal(b.root.dataset.currentTab, 'leave'); assert.equal(b.entries.length, count); assert.equal(generic, 0);
});
test('late previous response is ignored even when the transport ignores AbortSignal', async () => {
  const b = fixture(); const pending = [];
  b.pending = href => new Promise(resolve => pending.push({ href, resolve }));
  b.tab('leave').click(); b.tab('trip').click();
  assert.equal(b.calls[0].options.signal.aborted, true);
  pending[1].resolve(b.respond(pending[1].href)); await flush();
  pending[0].resolve(b.respond(pending[0].href)); await flush();
  assert.equal(b.root.dataset.currentTab, 'trip'); assert.equal(b.entries.length, 2);
});
test('failure retains content/URL, announces an error, and retry completes the original request', async () => {
  const b = fixture(); const old = b.root; b.pending = async href => b.respond(href, { ok: false });
  b.tab('flex').click(); await flush();
  assert.equal(b.root, old); assert.equal(b.entries.length, 1); assert.equal(old.attrs['aria-busy'], undefined);
  assert.equal(old.status.getAttribute('role'), 'alert'); assert.equal(old.retry.hidden, false);
  b.pending = null; old.retry.click(); await flush(); assert.equal(b.root.dataset.currentTab, 'flex');
});
test('invalid fragment keeps current data and exposes an explicit native fallback link', async () => {
  const b = fixture(); b.pending = async href => b.respond(href, { text: async () => 'invalid' });
  b.tab('correction').click(); await flush();
  assert.equal(b.root.dataset.currentTab, 'attendance'); assert.equal(b.root.fallback.hidden, false);
  const event = b.root.fallback.click(); assert.equal(event.defaultPrevented, false); assert.equal(event.stopped, true);
});
test('manual keyboard tab activation moves focus first and Space loads the focused tab', async () => {
  const b = fixture(); const tab = b.tab('attendance');
  b.doc.fire('keydown', { target: tab, key: 'ArrowRight' }); assert.equal(b.calls.length, 0);
  assert.equal(b.focused.dataset.attendanceTab, 'leave');
  b.doc.fire('keydown', { target: b.focused, key: ' ' }); await flush(); assert.equal(b.root.dataset.currentTab, 'leave');
});
test('modifier/new-window links keep native behavior and duplicate script loading does not bind twice', async () => {
  const b = fixture(); const leave = b.tab('leave');
  assert.equal(b.doc.fire('click', { target: leave, ctrlKey: true }).defaultPrevented, false);
  leave.target = '_blank'; assert.equal(leave.click().defaultPrevented, false); assert.equal(b.calls.length, 0);
  leave.target = ''; b.rerun(); leave.click(); await flush(); assert.equal(b.calls.length, 1);
});
test('authentication redirect navigates to login instead of rendering a login form in the tab', async () => {
  const b = fixture(); b.pending = async () => b.respond('http://localhost/login?expired=true');
  b.tab('trip').click(); await flush(); assert.equal(b.assignments[0], 'http://localhost/login?expired=true');
  assert.equal(b.root.dataset.currentTab, 'attendance');
});

test('back to another page or filter context cancels a pending tab before generic navigation', async () => {
  for (const href of ['http://localhost/mypage', 'http://localhost/hr/attendance?cal=2026-09']) {
    const b = fixture(); let resolve;
    b.pending = () => new Promise(done => { resolve = done; });
    b.tab('trip').click(); const request = b.calls[0]; const old = b.root;
    b.win.location = new URL(href);
    b.win.fire('popstate', { state: {} });
    assert.equal(request.options.signal.aborted, true);
    resolve(b.respond(request.href)); await flush();
    assert.equal(b.root, old); assert.equal(b.win.location.href, href); assert.equal(b.entries.length, 1);
  }
});

test('outside GET and business POST submissions cancel only pending history UI work', async () => {
  for (const method of ['get', 'post']) {
    const b = fixture(); let resolve;
    b.pending = () => new Promise(done => { resolve = done; }); b.tab('leave').click();
    const request = b.calls[0]; const event = b.doc.fire('submit', { target: { method } });
    assert.equal(event.defaultPrevented, false); assert.equal(request.options.signal.aborted, true);
    resolve(b.respond(request.href)); await flush(); assert.equal(b.root.dataset.currentTab, 'attendance');
  }
});

test('programmatic soft navigation cancels an old fragment even if its transport ignores abort', async () => {
  const b = fixture(); b.loadSoft(); const pending = [];
  b.pending = href => new Promise(resolve => pending.push({ href, resolve }));
  b.tab('trip').click(); b.win.worksystemSoftNavigate('/mypage');
  assert.equal(b.calls[0].options.signal.aborted, true);
  pending[0].resolve(b.respond(pending[0].href)); await flush();
  assert.equal(b.root.dataset.currentTab, 'attendance'); assert.equal(b.entries.length, 1);
});

test('a newer tab cancels generic GET, including response body already being read', async () => {
  const b = fixture(); b.loadSoft(); let resolveBody;
  b.pending = async href => b.respond(href, { text: () => new Promise(resolve => { resolveBody = resolve; }) });
  b.win.worksystemSoftNavigate('/hr/attendance?cal=2026-09'); await flush();
  const request = b.calls[0];
  b.pending = null; b.tab('flex').click(); await flush();
  assert.equal(request.options.signal.aborted, true);
  resolveBody('trip'); await flush();
  assert.equal(b.root.dataset.currentTab, 'flex'); assert.equal(b.assignments.length, 0);
  assert.equal(b.entries.length, 2); assert.equal(new URL(b.win.location).searchParams.get('cal'), '2026-10');
});

test('pagination owns only the history region and retains query, page and scroll', async () => {
  const b = fixture('http://localhost/hr/attendance?from=2026-01-01&to=2026-11-05&cal=2026-10&tab=attendance&q=late&lq=annual');
  let generic = 0; b.doc.addEventListener('click', () => generic++);
  const old = b.root; const calendar = b.calendar; const event = old.page(2).click(); await flush();
  assert.equal(event.stopped, true); assert.equal(generic, 0); assert.notEqual(b.root, old); assert.equal(b.calendar, calendar);
  const params = new URL(b.win.location).searchParams;
  for (const [key, value] of [['page', '2'], ['from', '2026-01-01'], ['to', '2026-11-05'], ['cal', '2026-10'], ['tab', 'attendance'], ['q', 'late'], ['lq', 'annual']]) assert.equal(params.get(key), value);
  assert.equal(b.win.scrollY, 640); assert.equal(b.root.table.scrollLeft, 17);
  assert.equal(b.calls[0].options.headers['X-Worksystem-Fragment'], 'attendance-history');
});

test('owned GET filter merges dates without losing calendar or other tab state and resets only its page', async () => {
  const b = fixture('http://localhost/hr/attendance?cal=2026-10&tab=attendance&page=3&lpage=4&lq=annual');
  let generic = 0; b.doc.addEventListener('submit', () => generic++);
  const event = b.root.form({ tab: 'attendance', from: '2026-01-01', to: '2026-11-05', q: 'absent' }).submit(); await flush();
  assert.equal(event.defaultPrevented, true); assert.equal(event.stopped, true); assert.equal(generic, 0);
  const params = new URL(b.win.location).searchParams;
  for (const [key, value] of [['page', '1'], ['lpage', '4'], ['lq', 'annual'], ['cal', '2026-10'], ['q', 'absent'], ['from', '2026-01-01']]) assert.equal(params.get(key), value);
});

test('Back and Forward restore different filter and page entries within the same calendar', async () => {
  const b = fixture('http://localhost/hr/attendance?cal=2026-10&tab=attendance&page=1');
  let generic = 0; b.win.addEventListener('popstate', () => generic++);
  b.root.page(2).click(); await flush(); b.win.scrollY = 870;
  b.root.form({ tab: 'attendance', q: 'none' }).submit(); await flush();
  b.back(); await flush();
  assert.equal(new URL(b.root.dataset.historyUrl).searchParams.get('page'), '2'); assert.equal(b.win.scrollY, 870);
  b.forward(); await flush();
  assert.equal(new URL(b.root.dataset.historyUrl).searchParams.get('q'), 'none'); assert.equal(new URL(b.win.location).searchParams.get('page'), '1');
  assert.equal(b.win.scrollY, 870); assert.equal(generic, 0); assert.equal(b.entries.length, 3);
});

test('saved history from a different calendar is left to full-page navigation', async () => {
  const b = fixture(); let generic = 0; b.win.addEventListener('popstate', () => generic++);
  b.win.location = new URL('http://localhost/hr/attendance?cal=2026-09&tab=leave');
  const event = b.win.fire('popstate', { state: { wsAttendanceContext: '/hr/attendance?cal=2026-09', wsAttendanceUrl: b.win.location.href } });
  assert.equal(event.stopped, undefined); assert.equal(generic, 1); assert.equal(b.calls.length, 0);
});

test('failed filter keeps the old URL and retry commits the same query once', async () => {
  const b = fixture(); const old = b.root; const href = b.win.location.href;
  b.pending = async target => b.respond(target, { ok: false });
  b.root.form({ tab: 'attendance', q: 'new' }).submit(); await flush();
  assert.equal(b.root, old); assert.equal(b.win.location.href, href); assert.equal(old.retry.hidden, false);
  b.pending = null; old.retry.click(); await flush();
  assert.equal(new URL(b.win.location).searchParams.get('q'), 'new'); assert.equal(b.entries.length, 2);
});

test('tab history does not invent a calendar parameter when initial URL omitted it', async () => {
  const b = fixture('http://localhost/hr/attendance?tab=attendance&page=2');
  b.tab('leave').href += '&cal=2026-10';
  b.tab('leave').click(); await flush(); b.back(); await flush();
  assert.equal(b.root.dataset.currentTab, 'attendance'); assert.equal(new URL(b.win.location).searchParams.get('page'), '2');
  assert.equal(new URL(b.win.location).searchParams.has('cal'), false);
});

test('relative canonical URLs do not create duplicate history for the active tab', async () => {
  const b = fixture(); b.root.dataset.historyUrl = b.win.location.pathname + b.win.location.search;
  b.tab('attendance').click(); await flush(); assert.equal(b.entries.length, 1);
});

test('post-widget layout anchoring is corrected once and saved to the same history entry', async () => {
  const b = fixture(), frames = []; b.win.requestAnimationFrame = fn => frames.push(fn); b.tab('leave').click(); await flush(); const y = b.win.scrollY; b.win.scrollY -= 4; frames.shift()(); frames.shift()(); assert.equal(b.win.scrollY, y); assert.equal(b.entries.at(-1).state.wsAttendanceScroll.y, y);
});
test('late scroll restoration yields to newer navigation and deliberate wheel input', async () => {
  const b = fixture(), frames = []; b.win.requestAnimationFrame = fn => frames.push(fn); b.tab('leave').click(); await flush(); b.win.scrollY = 321; b.win.fire('wheel', {}); frames.shift()(); frames.shift()(); assert.equal(b.win.scrollY, 321); b.tab('trip').click(); await flush(); const old = frames.shift(); b.tab('flex').click(); await flush(); b.win.scrollY = 444; old(); const stale = frames.pop(); stale(); assert.equal(b.win.scrollY, 444);
});
test('empty and subsequent shorter tabs retain the prior history height until leaving the page', async () => {
  const b = fixture(); b.root.getBoundingClientRect = () => ({ height: 920 });
  b.tab('correction').click(); await flush();
  assert.equal(b.root.style.minHeight, '920px'); assert.equal(b.win.scrollY, 640);
  b.root.getBoundingClientRect = () => ({ height: 120 }); b.tab('leave').click(); await flush();
  assert.equal(b.root.style.minHeight, '920px'); assert.equal(b.win.scrollY, 640);
});
test('equal wrapper height with reduced outer document geometry reserves only the missing document height before restoring scroll', async () => {
  const b = fixture(); b.doc.documentElement = { scrollHeight: 1233 }; b.root.getBoundingClientRect = () => ({ height: 420.28125 });
  b.win.addEventListener('worksystem:content-updated', () => { b.doc.documentElement.scrollHeight = 1219; b.root.getBoundingClientRect = () => ({ height: 420.28125 }); });
  b.tab('correction').click(); await flush();
  assert.equal(b.root.style.minHeight, '434.28125px'); assert.equal(b.win.scrollY, 640);
});
test('three short/long tab roundtrips preserve native bottom scroll without accumulating document or root height', async () => {
  const b = fixture(), base = 420.28125;
  const rect = () => ({ height: Math.max(base, parseFloat(b.root.style.minHeight) || 0) });
  b.root.getBoundingClientRect = rect;
  b.doc.documentElement = {};
  Object.defineProperty(b.doc.documentElement, 'scrollHeight', { get() { return Math.round(rect().height + (b.root.dataset.currentTab === 'correction' ? 798.71875 : 812.71875)); } });
  b.win.addEventListener('worksystem:content-updated', () => { b.root.getBoundingClientRect = rect; });
  b.win.scrollY = 233; b.win.scrollTo = (x, y) => { b.win.scrollY = Math.min(y, b.doc.documentElement.scrollHeight - 1000); };
  for (let index = 0; index < 3; index++) {
    b.tab('correction').click(); await flush();
    assert.equal(b.win.scrollY, 233); assert.equal(b.doc.documentElement.scrollHeight, 1233); assert.equal(b.root.style.minHeight, '434.28125px');
    b.tab('attendance').click(); await flush();
    assert.equal(b.win.scrollY, 233); assert.equal(b.doc.documentElement.scrollHeight, 1233); assert.equal(b.root.style.minHeight, '420.28125px');
  }
});
