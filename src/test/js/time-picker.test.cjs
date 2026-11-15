const test = require('node:test');
const assert = require('node:assert/strict');
const vm = require('node:vm');
const fs = require('node:fs');
const path = require('node:path');

// DOM/event boundary fixture: execute the shipped picker scripts without copying their logic.
class UiEvent {
  constructor(type, options = {}) { this.type = type; Object.assign(this, options); this.defaultPrevented = false; }
  preventDefault() { this.defaultPrevented = true; }
  stopPropagation() { this.stopped = true; }
  stopImmediatePropagation() { this.stopped = this.immediate = true; }
}
class Element {
  constructor(tag, owner) { this.tagName = tag.toLowerCase(); this.ownerDocument = owner; this.children = []; this.dataset = {}; this.attributes = {}; this.listeners = {}; this.style = {}; this._value = ''; this.className = ''; this.nodeType = 1; }
  get value() { return this._value; } set value(value) { this._value = String(value); }
  get classList() { return { contains: name => this.className.split(/\s+/).includes(name), add: name => { if (!this.classList.contains(name)) this.className = (this.className + ' ' + name).trim(); }, remove: name => { this.className = this.className.split(/\s+/).filter(x => x !== name).join(' '); }, toggle: (name, on) => { (on ?? !this.classList.contains(name)) ? this.classList.add(name) : this.classList.remove(name); } }; }
  setAttribute(name, value) { this.attributes[name] = String(value); if (name === 'class') this.className = String(value); else if (name.startsWith('data-')) this.dataset[name.slice(5).replace(/-([a-z])/g, (_, c) => c.toUpperCase())] = String(value); else this[name] = String(value); }
  getAttribute(name) { return name === 'class' ? this.className : this.attributes[name] ?? null; }
  hasAttribute(name) { return Object.hasOwn(this.attributes, name); }
  get form() { return this.closest('form'); }
  get isConnected() { return !!this.parentNode; }
  get nextSibling() { return this.parentNode?.children[this.parentNode.children.indexOf(this) + 1] || null; }
  get nextElementSibling() { return this.nextSibling; }
  appendChild(child) { if (child.parentNode) child.parentNode.children.splice(child.parentNode.children.indexOf(child), 1); child.parentNode = this; this.children.push(child); return child; }
  insertBefore(child, next) { if (!next) return this.appendChild(child); if (child.parentNode) child.parentNode.children.splice(child.parentNode.children.indexOf(child), 1); child.parentNode = this; this.children.splice(this.children.indexOf(next), 0, child); return child; }
  set innerHTML(html) {
    this.children.forEach(child => child.parentNode = null); this.children = []; const stack = [this];
    for (const token of html.matchAll(/<\/?[\w-]+\b[^>]*>/g)) {
      const text = token[0]; if (text.startsWith('</')) { stack.pop(); continue; }
      const tag = /^<([\w-]+)/.exec(text)[1]; const child = new Element(tag, this.ownerDocument);
      const attrs = text.slice(tag.length + 1, -1);
      for (const a of attrs.matchAll(/([\w:-]+)(?:="([^"]*)"|'([^']*)'|=([^\s>]+))?/g)) child.setAttribute(a[1], a[2] ?? a[3] ?? a[4] ?? '');
      stack.at(-1).appendChild(child); if (!['input', 'img', 'br', 'hr'].includes(tag)) stack.push(child);
    }
  }
  matches(selector) { return selector.split(',').some(s => { s = s.trim(); const tag = /^[\w-]+/.exec(s)?.[0]; if (tag && tag !== this.tagName) return false; for (const c of s.matchAll(/\.([\w-]+)/g)) if (!this.classList.contains(c[1])) return false; for (const a of s.matchAll(/\[([^=\]]+)(?:="?([^"\]]+)"?)?\]/g)) { const actual = a[1].startsWith('data-') ? this.dataset[a[1].slice(5).replace(/-([a-z])/g, (_, c) => c.toUpperCase())] : this[a[1]] ?? this.getAttribute(a[1]); if (actual == null || (a[2] != null && String(actual) !== a[2])) return false; } return true; }); }
  querySelectorAll(selector) { return this.children.flatMap(child => [...(child.matches(selector) ? [child] : []), ...child.querySelectorAll(selector)]); }
  querySelector(selector) { return this.querySelectorAll(selector)[0] || null; }
  closest(selector) { return this.matches(selector) ? this : this.parentNode?.closest(selector) || null; }
  addEventListener(type, listener, options) { (this.listeners[type] ||= []).push({ listener, capture: options === true }); }
  dispatchEvent(event) {
    event.target ||= this; const chain = []; for (let n = this; n; n = n.parentNode) chain.push(n);
    const fire = (node, capture) => { for (const l of node.listeners[event.type] || []) { if (l.capture === capture) l.listener(event); if (event.immediate) break; } };
    for (const n of [...chain].reverse()) { fire(n, true); if (event.stopped) return !event.defaultPrevented; }
    for (const n of event.bubbles ? chain : [this]) { fire(n, false); if (event.stopped) break; }
    return !event.defaultPrevented;
  }
  focus() { this.ownerDocument.activeElement = this; }
  click() { this.dispatchEvent(new UiEvent('click', { bubbles: true })); }
  getBoundingClientRect() { return { top: 100, bottom: 140, left: 100 }; }
  get offsetHeight() { return 320; } get offsetWidth() { return 300; }
}
function setup({ type = 'time', value = '09:00', combo = false } = {}) {
  const document = new Element('document'); document.ownerDocument = document; document.readyState = 'complete';
  document.createElement = tag => new Element(tag, document); document.body = document.appendChild(document.createElement('body')); document.documentElement = document.body;
  const form = document.body.appendChild(document.createElement('form')); const box = combo ? form.appendChild(document.createElement('span')) : form;
  const input = box.appendChild(document.createElement('input')); input.type = type; input.value = value;
  if (combo) { box.dataset.dtcombo = ''; input.dataset.dtcomboTime = ''; const date = box.appendChild(document.createElement('input')); date.type = 'hidden'; date.dataset.dtcomboDate = ''; date.value = '2026-10-19'; const combined = box.appendChild(document.createElement('input')); combined.type = 'hidden'; combined.dataset.dtcomboValue = ''; }
  const window = new Element('window', document); window.innerWidth = 1920; window.innerHeight = 1080;
  const context = { document, window, Event: UiEvent, CustomEvent: UiEvent, MutationObserver: class { observe() {} }, requestAnimationFrame: fn => fn(), alert() {} };
  const load = name => vm.runInNewContext(fs.readFileSync(path.join(__dirname, '../../main/resources/static/js/' + name), 'utf8'), context);
  if (combo) load('worksystem-dt-combo.js'); load(type === 'time' ? 'worksystem-time-picker.js' : 'worksystem-date-picker.js'); load('worksystem-ui-forms.js');
  if (type === 'datetime-local' || type === 'date') document.querySelector('.ws-dp-display').click();
  const prefix = type === 'time' ? '.vf-time-' : '.ws-dp-';
  return { document, form, box, input, window, load, hour: document.querySelector(prefix + 'h'), minute: document.querySelector(prefix + 'm'), period: document.querySelector(prefix + 'period'), popup: document.querySelector('.ws-dp-pop'), emit(node, type, options = {}) { const event = new UiEvent(type, { bubbles: type !== 'blur', ...options }); node.dispatchEvent(event); return event; } };
}
function edit(b, node, value) { node.value = value; b.emit(node, 'input'); }

test('time hour can be deleted and replaced without clamping or padding while focused', () => {
  const b = setup(); edit(b, b.hour, ''); assert.equal(b.hour.value, ''); assert.equal(b.input.value, '09:00');
  edit(b, b.hour, '6'); assert.equal(b.hour.value, '6'); assert.equal(b.input.value, '06:00');
  edit(b, b.hour, '0'); assert.equal(b.hour.value, '0'); edit(b, b.hour, '08'); assert.equal(b.hour.value, '08'); b.emit(b.hour, 'blur'); assert.equal(b.hour.value, '8'); assert.equal(b.input.value, '08:00');
});
test('hour and minute intermediate out-of-range text survives until blur', () => {
  const b = setup(); edit(b, b.hour, '16'); assert.equal(b.hour.value, '16'); assert.equal(b.input.value, '09:00'); b.emit(b.hour, 'blur'); assert.equal(b.hour.value, '12'); assert.equal(b.input.value, '00:00');
  edit(b, b.minute, '99'); assert.equal(b.minute.value, '99'); assert.equal(b.input.value, '00:00'); b.emit(b.minute, 'blur'); assert.equal(b.minute.value, '59'); assert.equal(b.input.value, '00:59');
});
test('minute typing stays unpadded and empty blur restores last committed component', () => {
  const b = setup({ value: '15:25' }); edit(b, b.minute, '1'); assert.equal(b.minute.value, '1'); edit(b, b.minute, '15'); assert.equal(b.minute.value, '15'); b.emit(b.minute, 'blur');
  edit(b, b.minute, ''); assert.equal(b.input.value, '15:15'); b.emit(b.minute, 'blur'); assert.equal(b.minute.value, '15');
  edit(b, b.hour, ''); assert.equal(b.input.value, '15:15'); b.emit(b.hour, 'blur'); assert.equal(b.hour.value, '3');
});
test('AM/PM preserves noon/midnight and commits pending range on explicit period change', () => {
  const b = setup({ value: '00:05' }); assert.equal(b.hour.value, '12'); assert.equal(b.minute.value, '05'); b.period.value = 'PM'; b.emit(b.period, 'change'); assert.equal(b.input.value, '12:05');
  edit(b, b.hour, '16'); b.period.value = 'AM'; b.emit(b.period, 'change'); assert.equal(b.hour.value, '12'); assert.equal(b.input.value, '00:05');
});
test('Enter and submit without blur commit valid HH:MM and keep datetime combo synchronized', () => {
  const b = setup({ combo: true }); edit(b, b.hour, '16'); edit(b, b.minute, '99'); const event = b.emit(b.hour, 'keydown', { key: 'Enter' }); assert.equal(event.defaultPrevented, false); assert.equal(b.input.value, '00:59');
  assert.equal(b.box.querySelector('[data-dtcombo-value]').value, '2026-10-19T00:59');
  edit(b, b.hour, ''); edit(b, b.minute, '-2'); const submit = b.emit(b.form, 'submit'); assert.equal(submit.defaultPrevented, false); assert.equal(b.hour.value, '12'); assert.equal(b.minute.value, '00'); assert.equal(b.box.querySelector('[data-dtcombo-value]').value, '2026-10-19T00:00');
});
test('time enhancement remains idempotent after content updates', () => {
  const b = setup(); b.emit(b.window, 'worksystem:content-updated'); b.emit(b.document, 'content-updated'); assert.equal(b.document.querySelectorAll('.vf-time-picker').length, 1);
});
test('internal picker changes reach datetime combo without ui-forms feedback padding visible drafts', () => {
  const b = setup({ combo: true }); const changes = []; b.input.addEventListener('change', event => changes.push(event.detail));
  edit(b, b.minute, ''); edit(b, b.minute, '8'); assert.equal(b.minute.value, '8'); assert.equal(b.input.value, '09:08'); assert.equal(b.box.querySelector('[data-dtcombo-value]').value, '2026-10-19T09:08');
  assert.ok(changes.at(-1).worksystemTimePickerSync); b.emit(b.minute, 'blur'); assert.equal(b.minute.value, '08');
});
test('external record change refreshes visible clock and committed fallback while internal drafts remain untouched', () => {
  const b = setup({ combo: true }); b.input.value = '12:03'; b.emit(b.input, 'change'); assert.equal(b.period.value, 'PM'); assert.equal(b.hour.value, '12'); assert.equal(b.minute.value, '03'); assert.equal(b.box.querySelector('[data-dtcombo-value]').value, '2026-10-19T12:03');
  edit(b, b.hour, ''); edit(b, b.minute, ''); assert.equal(b.input.value, '12:03'); b.emit(b.hour, 'blur'); b.emit(b.minute, 'blur'); assert.equal(b.hour.value, '12'); assert.equal(b.minute.value, '03'); assert.equal(b.input.value, '12:03');
  edit(b, b.minute, '8'); assert.equal(b.minute.value, '8'); b.emit(b.minute, 'blur'); assert.equal(b.input.value, '12:08');
});
test('datetime popup permits draft edits, normalizes on blur, and applies only on confirmation', () => {
  const b = setup({ type: 'datetime-local', value: '2026-10-19T09:25' }); edit(b, b.hour, ''); assert.equal(b.hour.value, ''); edit(b, b.hour, '6'); assert.equal(b.hour.value, '6'); assert.equal(b.input.value, '2026-10-19T09:25');
  edit(b, b.hour, '16'); assert.equal(b.hour.value, '16'); b.emit(b.hour, 'blur'); assert.equal(b.hour.value, '12'); assert.equal(b.input.value, '2026-10-19T09:25');
  edit(b, b.minute, '99'); assert.equal(b.minute.value, '99'); b.emit(b.minute, 'blur'); assert.equal(b.minute.value, '59'); b.document.querySelector('[data-dp-ok]').click(); assert.equal(b.input.value, '2026-10-19T00:59');
});
test('datetime Enter normalizes draft without form submission or implicit apply', () => {
  const b = setup({ type: 'datetime-local', value: '2026-10-19T15:25' }); edit(b, b.hour, '16'); edit(b, b.minute, '99'); const event = b.emit(b.minute, 'keydown', { key: 'Enter' }); assert.equal(event.defaultPrevented, true); assert.equal(b.hour.value, '12'); assert.equal(b.minute.value, '59'); assert.equal(b.input.value, '2026-10-19T15:25'); assert.ok(b.popup.classList.contains('open'));
});
test('datetime empty fallback, period conversion and cancel preserve original date/time', () => {
  const b = setup({ type: 'datetime-local', value: '2026-10-19T00:07' }); edit(b, b.hour, ''); edit(b, b.minute, ''); b.emit(b.hour, 'blur'); assert.equal(b.hour.value, '12'); assert.equal(b.minute.value, '07');
  b.period.value = 'PM'; b.emit(b.period, 'change'); b.document.querySelector('[data-dp-cancel]').click(); assert.equal(b.input.value, '2026-10-19T00:07'); assert.equal(b.popup.classList.contains('open'), false);
  b.document.querySelector('.ws-dp-display').click(); assert.equal(b.period.value, 'AM'); assert.equal(b.hour.value, '12');
});
test('datetime confirm without blur normalizes; Escape/outside close never commits draft', () => {
  const b = setup({ type: 'datetime-local', value: '2026-10-19T09:00' }); edit(b, b.hour, '16'); edit(b, b.minute, '99'); b.document.querySelector('[data-dp-ok]').click(); assert.equal(b.input.value, '2026-10-19T00:59');
  b.document.querySelector('.ws-dp-display').click(); edit(b, b.hour, '6'); b.hour.focus(); const escape = b.emit(b.hour, 'keydown', { key: 'Escape' }); assert.equal(escape.defaultPrevented, true); assert.equal(b.input.value, '2026-10-19T00:59');
  b.document.querySelector('.ws-dp-display').click(); edit(b, b.hour, '8'); b.emit(b.document, 'mousedown'); assert.equal(b.popup.classList.contains('open'), false); assert.equal(b.input.value, '2026-10-19T00:59');
});
test('date-only month navigation, day selection and confirmation remain operational', () => {
  const b = setup({ type: 'date', value: '2026-10-19' }); assert.equal(b.hour, null); b.document.querySelector('[data-dp-next]').click(); b.document.querySelectorAll('.ws-dp-day').find(day => day.textContent === 3).click(); b.document.querySelector('[data-dp-ok]').click(); assert.equal(b.input.value, '2026-11-03');
});
