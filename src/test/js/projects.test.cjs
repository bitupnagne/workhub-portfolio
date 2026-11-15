const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const source = fs.readFileSync(path.join(__dirname, '../../main/resources/static/js/worksystem-projects.js'), 'utf8');

function element(dataset = {}) {
  const classes = new Set();
  return {
    dataset, hidden: false, value: '', attrs: {}, parents: {}, selectors: new Set(),
    classList: { toggle(key, value) { value ? classes.add(key) : classes.delete(key); }, contains(key) { return classes.has(key); } },
    setAttribute(key, value) { this.attrs[key] = value; }, removeAttribute(key) { delete this.attrs[key]; },
    closest(selector) { return this.selectors.has(selector) ? this : this.parents[selector] || null; },
    matches(selector) { return this.selectors.has(selector); },
  };
}

function fixture({ mobile = false, savedView = null } = {}) {
  const events = { document: {}, window: {} };
  const viewRoot = element();
  const viewButtons = ['board', 'list'].map(value => {
    const button = element({ taskView: value }); button.selectors.add('[data-task-view]'); button.parents['[data-task-views]'] = viewRoot; return button;
  });
  const panels = ['board', 'list'].map(value => element({ taskViewPanel: value }));
  const taskRows = ['로그인 API 홍길동', '화면 디자인 김하나'].map(value => element({ taskSearchText: value }));
  const taskEmpty = element();
  viewRoot.querySelectorAll = selector => ({ '[data-task-view-panel]': panels, '[data-task-view]': viewButtons, '[data-task-search-text]': taskRows }[selector] || []);
  viewRoot.querySelector = selector => selector === '[data-task-search-empty]' ? taskEmpty : null;
  const search = element(); search.selectors.add('[data-task-search]'); search.parents['[data-task-views]'] = viewRoot;
  const form = element(); form.selectors.add('[data-project-form]');
  const status = element(); status.value = 'TODO'; status.selectors.add('[data-task-status]'); status.parents['[data-task-form]'] = form;
  const progress = element(); progress.value = '0';
  form.querySelector = selector => selector === '[data-task-status]' ? status : selector === '[data-task-progress]' ? progress : null;
  const projectRoot = element();
  const projectCards = ['ACTIVE', 'COMPLETED'].map(value => element({ projectStatus: value }));
  const projectButtons = ['ALL', 'ACTIVE', 'COMPLETED', 'ARCHIVED'].map(value => {
    const button = element({ projectFilter: value }); button.selectors.add('[data-project-filter]'); button.parents['[data-project-filter-root]'] = projectRoot; return button;
  });
  const projectEmpty = element();
  projectRoot.querySelectorAll = selector => selector === '[data-project-status]' ? projectCards : selector === '[data-project-filter]' ? projectButtons : [];
  projectRoot.querySelector = () => projectEmpty;
  const peopleRoot = element(); const people = [element({ personText: '홍길동 개발팀' }), element({ personText: '김하나 디자인팀' })];
  people[0].checked = true;
  const peopleEmpty = element();
  peopleRoot.querySelectorAll = () => people; peopleRoot.querySelector = () => peopleEmpty;
  const personSearch = element(); personSearch.selectors.add('[data-person-search]'); personSearch.parents['[data-person-picker]'] = peopleRoot;
  const listen = owner => (name, fn) => (events[owner][name] ||= []).push(fn);
  const document = { readyState: 'complete', addEventListener: listen('document'), querySelector: () => null,
    querySelectorAll(selector) { return selector === '[data-task-views]' ? [viewRoot] : ['[data-task-form]', '[data-project-form]'].includes(selector) ? [form] : []; } };
  const window = { addEventListener: listen('window'), matchMedia: () => ({ matches: mobile }) };
  const context = vm.createContext({ document, window, localStorage: { getItem: () => savedView, setItem(key, value) { savedView = value; } } });
  const load = () => vm.runInContext(source, context);
  load();
  function fire(owner, name, target, extras = {}) {
    const event = { target, defaultPrevented: false, preventDefault() { this.defaultPrevented = true; }, ...extras };
    (events[owner][name] || []).forEach(fn => fn(event)); return event;
  }
  return { form, status, progress, panels, viewButtons, taskRows, taskEmpty, search, projectCards, projectButtons, projectEmpty, people, peopleEmpty, personSearch, fire, load, events, stored: () => savedView };
}

test('narrow screens start with a usable list and an explicit board choice survives soft navigation', () => {
  const f = fixture({ mobile: true });
  assert.equal(f.panels[0].hidden, true); assert.equal(f.panels[1].hidden, false);
  assert.equal(f.viewButtons[1].attrs['aria-pressed'], 'true');
  f.fire('document', 'click', f.viewButtons[0]);
  assert.equal(f.panels[0].hidden, false); assert.equal(f.panels[1].hidden, true); assert.equal(f.stored(), 'board');
  f.fire('window', 'worksystem:content-updated');
  assert.equal(f.panels[0].hidden, false);
  assert.equal(f.viewButtons[0].attrs['aria-pressed'], 'true');
});

test('project status and task searches expose empty states and restore all rows', () => {
  const f = fixture();
  f.fire('document', 'click', f.projectButtons[3]);
  assert.ok(f.projectCards.every(card => card.hidden)); assert.equal(f.projectEmpty.hidden, false);
  f.fire('document', 'click', f.projectButtons[0]);
  assert.ok(f.projectCards.every(card => !card.hidden)); assert.equal(f.projectEmpty.hidden, true);
  f.search.value = '홍길동'; f.fire('document', 'input', f.search);
  assert.deepEqual(f.taskRows.map(row => row.hidden), [false, true]);
  f.search.value = '검색 결과 없음'; f.fire('document', 'input', f.search); assert.equal(f.taskEmpty.hidden, false);
  f.search.value = ''; f.fire('document', 'input', f.search); assert.ok(f.taskRows.every(row => !row.hidden));
});

test('member search preserves existing selections while filtering by name or team', () => {
  const f = fixture();
  f.personSearch.value = '디자인팀'; f.fire('document', 'input', f.personSearch);
  assert.deepEqual(f.people.map(person => person.hidden), [true, false]); assert.equal(f.people[0].checked, true);
  f.personSearch.value = ''; f.fire('document', 'input', f.personSearch); assert.equal(f.people[0].hidden, false);
});

test('status changes maintain editable progress bounds and fixed completed or planned values', () => {
  const f = fixture(); assert.equal(f.progress.value, '0'); assert.equal(f.progress.readOnly, true);
  f.status.value = 'DONE'; f.fire('document', 'change', f.status); assert.equal(f.progress.value, '100');
  f.status.value = 'REVIEW'; f.fire('document', 'change', f.status); assert.equal(f.progress.value, '99'); assert.equal(f.progress.readOnly, false); assert.equal(f.progress.max, '99');
  f.progress.value = '35'; f.fire('window', 'worksystem:content-updated'); assert.equal(f.progress.value, '35');
});

test('native file submission blocks duplicates and restores after browser back without duplicating handlers', () => {
  const f = fixture();
  assert.equal(f.fire('document', 'submit', f.form).defaultPrevented, false);
  assert.equal(f.form.attrs['aria-busy'], 'true');
  assert.equal(f.fire('document', 'submit', f.form).defaultPrevented, true);
  f.fire('window', 'pageshow'); assert.equal(f.form.dataset.projectSubmitting, undefined);
  f.load(); assert.equal(f.events.document.submit.length, 1);
  assert.equal(f.fire('document', 'submit', f.form, { defaultPrevented: true }).defaultPrevented, true);
  assert.equal(f.form.dataset.projectSubmitting, undefined);
});
