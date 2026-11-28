const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const read = name => fs.readFileSync(path.join(__dirname, '../../main/resources/static/js', name), 'utf8');
const flush = () => new Promise(setImmediate);
class Element {
  constructor(name = '') { this.name = name; this.attrs = new Map(); this.style = { position: '' }; this.children = []; this.isConnected = true; this.scrollLeft = 17; this.scrollTop = 41; }
  hasAttribute(name) {return this.attrs.has(name);}
  closest(){return null;}
  getAttribute(name) { return this.attrs.has(name) ? this.attrs.get(name) : null; }
  setAttribute(name, value) { this.attrs.set(name, String(value)); }
  removeAttribute(name) { this.attrs.delete(name); }
  appendChild(child) { child.remove(); child.parent = this; child.isConnected = true; this.children.push(child); }
  replaceChildren(...children) { this.children.forEach(child => { child.parent = null; child.isConnected = false; }); this.children = []; children.forEach(child => this.appendChild(child)); }
  remove() { if (this.parent) this.parent.children = this.parent.children.filter(child => child !== this); this.parent = null; this.isConnected = false; }
  get firstChild() { return this.children[0]; }
  set innerHTML(value) { this.html = value; this.replaceChildren(); }
  get innerHTML() { return this.html || ''; }
  querySelectorAll() { return []; }
}
function loadingFixture() {
  const events = new Map();
  const timers = new Map(); let timerId = 0;
  const win = { setTimeout: fn => { timers.set(++timerId, fn); return timerId; }, clearTimeout: id => timers.delete(id),
    addEventListener(name, fn) { events.set(name, fn); }, getComputedStyle: element => ({ position: element.style.position || 'static' }) };
  const doc = { createElement: () => new Element(), getElementById: () => null };
  vm.runInNewContext(read('worksystem-loading.js'), { window: win, document: doc });
  return { api: win.wsLoading, advance: () => { Array.from(timers.values()).forEach(fn => fn()); timers.clear(); }, fire: (name, event = {}) => events.get(name)?.(event) };
}
test('row skeleton preserves table identity/scroll and restores prior ARIA, positioning and inert values', () => {
  const b = loadingFixture(), root = new Element(), filter = new Element('filter'), table = new Element('table'), rows = new Element('rows');
  root.appendChild(filter); root.appendChild(table); table.appendChild(rows); root.setAttribute('aria-busy', 'false'); rows.setAttribute('inert', 'existing');
  const stop = b.api.start(root, { surface: table, variant: 'rows' });
  assert.equal(root.getAttribute('aria-busy'), 'true'); assert.equal(table.children[0], rows); assert.equal(table.scrollLeft, 17); assert.equal(table.scrollTop, 41);
  assert.equal(filter.getAttribute('inert'), null); assert.equal(rows.getAttribute('inert'), ''); assert.equal(table.children[1].getAttribute('aria-hidden'), 'true');
  stop(); assert.equal(root.getAttribute('aria-busy'), 'false'); assert.equal(rows.getAttribute('inert'), 'existing'); assert.equal(table.style.position, ''); assert.equal(table.children.length, 1);
});
test('stale cleanup cannot remove the next request skeleton or unlock its content', () => {
  const b = loadingFixture(), target = new Element(), row = new Element(); target.appendChild(row);
  const first = b.api.start(target), second = b.api.start(target); first();
  assert.equal(target.getAttribute('aria-busy'), 'true'); assert.equal(row.getAttribute('inert'), ''); assert.equal(target.children.length, 2);
  second(); assert.equal(target.getAttribute('aria-busy'), null); assert.equal(row.getAttribute('inert'), null); assert.equal(target.children.length, 1);
});
test('quick queries never paint a skeleton, while a continuing request reveals its existing mask after the display delay', () => {
  const b = loadingFixture(), host = new Element();
  let stop = b.api.start(host), mask = host.children[0]; assert.equal(mask.style.opacity, '0'); stop(); b.advance(); assert.equal(mask.style.opacity, '0');
  stop = b.api.start(host); mask = host.children[0]; assert.equal(host.getAttribute('aria-busy'), 'true'); b.advance(); assert.equal(mask.style.opacity, '1'); stop();
});
test('successful replacement and pagehide clean only temporary state, retaining original busy and sticky positioning', () => {
  const b = loadingFixture(), target = new Element(); target.style.position = 'sticky'; target.setAttribute('aria-busy', 'true');
  const stop = b.api.start(target, { replace: true }), content = new Element('response'); target.replaceChildren(content); stop();
  assert.equal(target.children[0], content); assert.equal(target.style.position, 'sticky'); assert.equal(target.getAttribute('aria-busy'), 'true');
  target.removeAttribute('aria-busy'); b.api.start(target); b.fire('pagehide'); assert.equal(target.getAttribute('aria-busy'), null); assert.equal(target.children.length, 1);
});
test('partial navigation cleans detached hosts without cancelling another live region', () => {
  const b = loadingFixture(), detached = new Element(), live = new Element(); b.api.start(detached); const stop = b.api.start(live); detached.isConnected = false;
  b.fire('worksystem:content-updated'); assert.equal(detached.getAttribute('aria-busy'), null); assert.equal(live.getAttribute('aria-busy'), 'true'); stop();
});
function modalFixture() {
  const handlers = new Map(), windowHandlers = new Map(), requests = [], active = new Set(), events = [];
  const body = new Element('body'), modal = new Element('modal'), title = new Element('title'); modal.hidden = true;
  modal.querySelector = selector => selector === '[data-app-form-body]' ? body : title;
  const document = { querySelector: selector => selector === '[data-app-form-modal]' ? modal : null, querySelectorAll: () => [], addEventListener(name, fn) { handlers.set(name, fn); } };
  const window = { location: { href: 'http://localhost/host', origin: 'http://localhost' }, addEventListener(name, fn) { windowHandlers.set(name, fn); }, dispatchEvent: event => events.push(event.type),
    wsLoading: { start() { const token = {}; active.add(token); return () => active.delete(token); } } };
  const fetch = (url, options) => new Promise(resolve => requests.push({ url, options, resolve }));
  const DOMParser = class { parseFromString(name) { const content = new Element(); content.appendChild(new Element(name)); return { querySelector: () => content }; } };
  vm.runInNewContext(read('worksystem-app-form-modal.js'), { window, document, fetch, AbortController, URL, DOMParser, requestAnimationFrame: fn => fn(), CustomEvent: class { constructor(type) { this.type = type; } } });
  const click = type => handlers.get('click')({ preventDefault() {}, target: { textContent: type, closest: selector => selector === '[data-app-form]' && type !== 'close' ? { getAttribute: name => name === 'href' ? 'http://localhost/' + type : null, textContent: type } : selector === '[data-app-form-close]' && type === 'close' ? {} : null } });
  const respond = (index, ok = true) => { const r = requests[index]; r.resolve({ ok, url: r.url, text: async () => r.url }); };
  return { window, modal, body, requests, active, events, click, respond, navigate: () => windowHandlers.get('worksystem:navigation-start')({ detail: { owner: 'soft' } }) };
}
test('application modal latest request wins when old transport ignores abort', async () => {
  const b = modalFixture(); b.click('a'); b.click('b'); assert.equal(b.requests[0].options.signal.aborted, true);
  b.respond(1); await flush(); b.respond(0); await flush(); assert.equal(b.body.children[0].name, 'http://localhost/b'); assert.equal(b.active.size, 0); assert.equal(b.events.length, 1);
});
test('closing or leaving a loading application modal prevents stale rendering and fallback navigation', async () => {
  for (const action of ['close', 'navigate']) { const b = modalFixture(); b.click('a'); action === 'close' ? b.click('close') : b.navigate(); b.respond(0, false); await flush(); assert.equal(b.modal.hidden, true); assert.equal(b.window.location.href, 'http://localhost/host'); assert.equal(b.active.size, 0); assert.equal(b.events.length, 0); }
});
test('current application-modal failure retains its existing native recovery route and clears loading', async () => {
  const b = modalFixture(); b.click('a'); b.respond(0, false); await flush(); assert.equal(b.window.location.href, 'http://localhost/a'); assert.equal(b.active.size, 0);
});

test('initial pageshow retains pending request; bfcache pageshow resets abandoned loading', () => {
  const b = loadingFixture(), target = new Element(); b.api.start(target); b.fire('pageshow', { persisted: false }); assert.equal(target.getAttribute('aria-busy'), 'true'); b.fire('pageshow', { persisted: true }); assert.equal(target.getAttribute('aria-busy'), null);
});

function workMemoFixture() {
  const source = read('worksystem-right-panel.js');
  const loading = source.slice(source.indexOf('  let wmListRequest = null;'), source.indexOf('  const fetchWmDetail ='));
  const render = source.slice(source.indexOf('  const render = () => {'), source.indexOf('  const renderChat ='));
  const calls = [], rendered = [], view = { scrollTop: 53, querySelector: () => ({}) }, state = { box: 'received', list: null, listError: '' };
  const context = { AbortController, encodeURIComponent, wmState: state, messagingEnabled: true, activeTab: 'workMemos', latestPayload: {}, panel: { querySelector: () => view },
    window: { wsLoading: { start: () => () => {} } }, renderWorkMemos: () => rendered.push(state.list), renderWmList() {},
    setCount() {}, renderContacts() {}, renderNotifications() {}, visibleNotifications: () => [], renderMessages() {},
    fetch(url, options) { if (url.includes('unread-count')) return Promise.resolve({ ok: true, json: async () => ({ count: 0 }) }); return new Promise(resolve => calls.push({ url, options, resolve })); } };
  vm.runInNewContext(loading + render + '\nglobalThis.refresh = render; globalThis.selectBox = box => { wmState.box = box; loadWorkMemos(); };', context);
  return { context, calls, state, rendered, respond(index, box) { calls[index].resolve({ ok: true, json: async () => ({ box, items: [] }) }); } };
}
test('periodic work-memo refresh and box clicks share ownership, so late received cannot overwrite sent', async () => {
  const b = workMemoFixture(); b.context.refresh(); b.context.selectBox('sent'); assert.equal(b.calls.length, 2); assert.equal(b.calls[0].options.signal.aborted, true);
  b.respond(1, 'sent'); await flush(); b.respond(0, 'received'); await flush(); assert.equal(b.state.list.box, 'sent'); assert.equal(b.rendered.length, 1);
});
test('work-memo failure does not also claim a successful empty result; successful empty remains visible', () => {
  const source = read('worksystem-right-panel.js'); const render = source.slice(source.indexOf('  const retainPanelChoices ='), source.indexOf('  const renderWmDetail ='));
  const view = { innerHTML: '', querySelectorAll: () => [], querySelector: () => null }, state = { list: { items: [] }, listError: 'network failure', box: 'received' };
  const context = { wmState: state, wmEsc: x => x, window: { wsLoading: { markup: () => '<skeleton>' } }, Number, Set };
  vm.runInNewContext(render + '\nglobalThis.renderList = renderWmList;', context); context.renderList(view);
  assert.match(view.innerHTML, /network failure/); assert.doesNotMatch(view.innerHTML, /표시할 자료전송이 없습니다/);
  state.listError = ''; context.renderList(view); assert.match(view.innerHTML, /표시할 자료전송이 없습니다/);
});

test('work-memo response rerenders preserve filter buttons, indicator and focus without accumulating click listeners', () => {
  const source=read('worksystem-right-panel.js');
  const render=source.slice(source.indexOf('  const retainPanelChoices ='),source.indexOf('  const renderWmDetail ='));
  const doc={body:{},activeElement:null},state={box:'received',list:{items:[]},listError:''};let loads=0,wholeRenders=0;
  const body={innerHTML:'',setAttribute(){},removeAttribute(){}};
  const view={group:null,querySelector(selector){return selector==='[data-wm-list-body]'?this.group&&body:selector==='.vf-wm-list-tools'?this.group:null;},querySelectorAll(selector){return selector==='[data-wm-box]'?this.group.buttons:[];}};
  function group(){
    const g={indicator:{id:'indicator'},buttons:[],contains:node=>g.buttons.includes(node),querySelectorAll:()=>g.buttons,
      replaceWith(kept){view.group=kept;kept.buttons.forEach(button=>button.isConnected=true);}};
    g.buttons=['received','sent','starred'].map(box=>{
      const attrs=new Map([['data-wm-box',box],['aria-pressed',String(state.box===box)]]),handlers=[];
      const button={dataset:{wmBox:box},className:state.box===box?'btn primary':'btn',isConnected:true,
        getAttribute:name=>attrs.get(name),setAttribute:(name,value)=>attrs.set(name,value),
        classList:{toggle(){}},addEventListener:(type,fn)=>handlers.push(fn),handlers,
        focus(options){assert.equal(options.preventScroll,true);doc.activeElement=button;}};
      return button;
    });return g;
  }
  Object.defineProperty(view,'innerHTML',{set(html){wholeRenders++;if(this.group){if(this.group.contains(doc.activeElement))doc.activeElement=doc.body;this.group.buttons.forEach(button=>button.isConnected=false);}this.group=group();}});
  const context={wmState:state,wmEsc:x=>x,window:{},document:doc,loadWorkMemos:()=>loads++,Number,Set};
  vm.runInNewContext(render+'\nglobalThis.renderList=renderWmList;',context);
  context.renderList(view);const original=view.group,buttons=original.buttons,indicator=original.indicator;
  for(const index of [1,2,0]){
    doc.activeElement=buttons[index];buttons[index].handlers[0]();context.renderList(view);
    assert.equal(view.group,original);assert.equal(view.group.indicator,indicator);assert.equal(doc.activeElement,buttons[index]);
    assert.equal(buttons[index].getAttribute('aria-pressed'),'true');assert.ok(buttons.every(button=>button.handlers.length===1));
  }
  assert.equal(loads,3);assert.equal(wholeRenders,1,'subsequent result responses must never detach the tools/indicator');
});

function softGetFixture({ card = false } = {}) {
  const source = read('worksystem-soft-submit.js'), handlers = new Map(), calls = [], commits = [], assignments = [], banners = [];
  const table = new Element('result-table'), row = new Element('row'), filter = new Element('filter'); table.appendChild(row); table.closest = () => null; table.getClientRects = () => [{}];
  const win = { wsLoading: loadingFixture().api, location: { href:'http://localhost/list',pathname:'/list',origin:'http://localhost', assign: href => assignments.push(href) }, addEventListener: (name, fn) => handlers.set(name, fn), dispatchEvent: event => handlers.get(event.type)?.(event) };
  const context = { contentSelector: '.content', URL, AbortController, queueMicrotask, window: win, document: { addEventListener() {}, querySelectorAll: () => [], querySelector: () => ({ querySelectorAll: selector => selector.includes(card ? '.m-emp-list' : '.table-scroll') ? [table] : [] }) }, CustomEvent: class { constructor(type, options) { this.type = type; Object.assign(this, options); } },
    prependBanner: (kind, message) => banners.push({ kind, message }),
    fetch: (href, options) => new Promise(resolve => calls.push({ href, options, resolve })),
    replaceContentFromResponse: async (response, history, isCurrent) => { if (!response.ok) throw new Error('HTTP'); const body = await response.text(); if (isCurrent()) commits.push(body); } };
  const cancel = source.slice(source.indexOf('  let getSequence ='), source.indexOf('  const canSoftSubmit'));
  const filterSnapshot = source.slice(source.indexOf('  const queryOwners ='), source.indexOf('  const replaceContentFromResponse ='));
  const load = source.slice(source.indexOf('  const softGet ='), source.indexOf('  const canSoftGetForm'));
  vm.runInNewContext("let committedHref='http://localhost/list';\n" + cancel + filterSnapshot + load + '\nglobalThis.navigate = softGet;', context);
  return { context, calls, commits, assignments, banners, table, row, filter, respond(index, ok = true) { const call = calls[index]; call.resolve({ ok, text: async () => call.href }); } };
}
test('ordinary soft GET masks only its result table and stale cleanup cannot release the next query', async () => {
  const b = softGetFixture(); const first = b.context.navigate('/list?q=a'), second = b.context.navigate('/list?q=b');
  assert.equal(b.calls[0].options.signal.aborted, true); assert.equal(b.table.getAttribute('aria-busy'), 'true'); assert.equal(b.row.getAttribute('inert'), ''); assert.equal(b.filter.getAttribute('inert'), null);
  b.respond(0); await first; assert.equal(b.table.getAttribute('aria-busy'), 'true');
  b.respond(1); await second; assert.deepEqual(b.commits, ['http://localhost/list?q=b']); assert.equal(b.table.getAttribute('aria-busy'), null); assert.equal(b.row.getAttribute('inert'), null); assert.equal(b.table.scrollTop, 41);
});
test('ordinary query failure keeps existing result, reports error without document reload and clears skeleton state', async () => {
  const b = softGetFixture(), pending = b.context.navigate('/list?q=failure'); b.respond(0, false); await pending;
  assert.deepEqual(b.assignments, []); assert.equal(b.banners.length, 1); assert.match(b.banners[0].message, /기존 결과/); assert.equal(b.table.children[0], b.row); assert.equal(b.row.getAttribute('inert'), null); assert.equal(b.table.getAttribute('aria-busy'), null);
});

test('tall row regions receive enough visible bars with a bounded60-row cost', () => {
  const b = loadingFixture(), target = new Element(); target.clientHeight = 1200; let stop = b.api.start(target, { variant: 'rows' }); assert.equal((target.children[0].innerHTML.match(/<span>/g) || []).length, 30); stop(); target.clientHeight = 50000; stop = b.api.start(target, { variant: 'rows' }); assert.equal((target.children[0].innerHTML.match(/<span>/g) || []).length, 60); stop();
});

test('mobile employee cards receive a skeleton without blocking their separate search filter', async () => {
  const b = softGetFixture({ card: true }), pending = b.context.navigate('/hr/members?q=mobile'); assert.equal(b.table.getAttribute('aria-busy'), 'true'); assert.equal(b.row.getAttribute('inert'), ''); assert.equal(b.filter.getAttribute('inert'), null); b.respond(0); await pending; assert.equal(b.table.getAttribute('aria-busy'), null); assert.equal(b.row.getAttribute('inert'), null);
});
