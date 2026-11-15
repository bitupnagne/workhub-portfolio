const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const source = fs.readFileSync(path.join(__dirname, '../../main/resources/static/js/worksystem-soft-submit.js'), 'utf8');
function fixture() {
  const history = [], replacements = [], events = [], scripts = [], scroll = [], requests = [], closedDrawers = [], handlers = new Map();
  function node(name) {
    const classes = new Set([name]);
    classes.contains = name => classes.has(name);
    const attrs = new Map();
    return { name, nodeType:1, nodeName:'DIV', isConnected:true, tagName: 'DIV', style: {}, dataset: {}, className: name, children: [], childNodes: [], parentElement: null, scrollLeft: 31,
      get attributes() { return [...attrs].map(([name, value]) => ({name, value})); },
      getAttribute: key => attrs.get(key) ?? null, hasAttribute: key => attrs.has(key),
      setAttribute: (key,value) => attrs.set(key, value), removeAttribute: key => attrs.delete(key), dispatchEvent() {},
      classList: classes, closest: () => null, querySelectorAll: () => [], matches: () => false, remove() {},
      appendChild(child) { this.childNodes.push(child); child.parentElement=this; return child; }, cloneNode(){const cloned=node(this.name);for(const a of this.attributes)cloned.setAttribute(a.name,a.value);Object.assign(cloned,{tagName:this.tagName,nodeName:this.nodeName,type:this.type,value:this.value,checked:this.checked});cloned.childNodes=this.childNodes.slice();return cloned;}, replaceChildren(...children) { this.childNodes = children; }, replaceWith(other) { this.replaced = other; } };
  }
  const table = node('table-scroll'), nextTable = node('table-scroll'), filter = node('filter');
  const oldRow = node('old row'), newRow = node('new row'); table.childNodes = [oldRow]; nextTable.childNodes = [newRow];
  const content = node('content'), nextContent = node('content'); content.children = [filter, table];
  [content, nextContent].forEach(el => { el.setAttribute('data-ws-query-contract','members-v1'); el.setAttribute('data-ws-query-path','/hr/members'); el.setAttribute('data-ws-query-params','q page from to'); });
  [table, nextTable].forEach(el => el.setAttribute('data-ws-query-region','desktop'));
  const footer = node('pagination'), nextFooter = node('pagination'); footer.clientHeight = 48; footer.style = {}; footer.childNodes = [{nodeType:3,nodeName:'#text',nodeValue:'old controls'}]; nextFooter.childNodes = [{nodeType:3,nodeName:'#text',nodeValue:'new controls'}];
  let oldPages = false, nextPages = false;
  content.querySelectorAll = selector => selector.includes('data-ws-query-region') || selector.includes('.table-scroll') ? [table] : selector.includes('.pagination') && oldPages ? [footer] : [];
  nextContent.querySelectorAll = selector => selector.includes('data-ws-query-region') || selector.includes('.table-scroll') ? [nextTable] : selector.includes('.pagination') && nextPages ? [nextFooter] : [];
  const common = { textContent: '(function(){ /* shared app script */ })();' };
  const document = { readyState: 'complete', title: 'Before', addEventListener(type, handler) { if (!handlers.has(type)) handlers.set(type,[]); handlers.get(type).push(handler); },
    querySelector: selector => selector === '.content' ? content : null,
    querySelectorAll: selector => selector.includes('script:not') ? [common] : [],
    createElement: () => node('script'),
    body: { children: [], classList:{remove(name){closedDrawers.push(name);}}, appendChild(element) {
      if (element.name === 'script') { scripts.push(element.textContent); vm.runInContext(element.textContent, context); }
      else this.children.push(element);
    } } };
  let nextInline = [];
  const nextDoc = { title: 'After', body: { children: [] }, querySelector: selector => selector === '.content' ? nextContent : null,
    querySelectorAll: selector => selector.includes('script:not') ? nextInline : [] };
  const location = { href: 'http://local/hr/members?q=old', pathname: '/hr/members', origin: 'http://local' };
  const window = { location, scrollY: 730, scrollTo: (x, y) => scroll.push(y), addEventListener() {},
    dispatchEvent: event => events.push(event.type), history: { pushState: (state, title, url) => {history.push(url);Object.assign(location,{href:new URL(url,location.href).href,pathname:new URL(url,location.href).pathname});}, replaceState: (state,title,url) => {replacements.push({state,url});Object.assign(location,{href:new URL(url,location.href).href,pathname:new URL(url,location.href).pathname});} } };
  const context = vm.createContext({ document, window, URL, AbortController,
    FormData:class {constructor(form){this.fields=form.fields||[];}forEach(fn){this.fields.forEach(([key,value])=>fn(value,key));}},
    fetch: async href => { requests.push(href); return {ok:true,url:href,text:async ()=>'fixture'}; },
    CustomEvent: class { constructor(type) { this.type = type; } },
    DOMParser: class { parseFromString() { return nextDoc; } } });
  vm.runInContext(source.replace(/\}\)\(\);\s*$/, 'window.testNavigation = {replaceContentFromResponse,canSoftGetForm,canSoftSubmit,queryPlan,syncFilters,retainNavigation,softGet,queryOwners,requestOwner,ownerRequestUrl,syncState,applyState,flushDeferredStates,deferredStates,captureFilters};})();'), context);
  const apply = (href, queryOnly = true) => window.testNavigation.replaceContentFromResponse({ ok: true, url: href, text: async () => 'fixture' }, true, () => true, queryOnly);
  return { apply, node, table, nextTable, oldRow, newRow, filter, content, nextContent, footer, nextFooter, history, replacements, scroll, scripts, window, document, nextDoc, requests, closedDrawers,
    async submit(target) {const event={target,defaultPrevented:false,preventDefault(){this.defaultPrevented=true;}};for(const handler of handlers.get('submit')||[])await handler(event);return event;},
    failFetch() { context.fetch=async()=>{throw new Error('network failure');};content.prepend=()=>{}; },
    async click(target, extra={}) { const event={target,button:0,defaultPrevented:false,preventDefault(){this.defaultPrevented=true;},...extra}; for (const handler of handlers.get('click')||[]) await handler(event);return event; },
    pages(before, after) { oldPages = before; nextPages = after; },
    inline(code) { nextInline = [common, { textContent: code }]; },
    owned(oldNames, newNames) {
      const element = id => { const el = node(id); el.id = id; el.matches = selector => selector.includes('#' + id); el.remove = () => { document.body.children = document.body.children.filter(n => n !== el); }; return el; };
      document.body.children = oldNames.map(element); nextDoc.body.children = newNames.map(element);
    } };
}
test('query updates only result rows, retaining table/filter identity, horizontal and document scroll, and query history', async () => {
  const b = fixture(); await b.apply('http://local/hr/members?q=new&page=2');
  assert.equal(b.content.replaced, undefined); assert.equal(b.content.children[0], b.filter);
  assert.equal(b.table.childNodes[0], b.newRow); assert.equal(b.table.scrollLeft, 31);
  assert.deepEqual(b.scroll, [730]); assert.deepEqual(b.history, ['http://local/hr/members?q=new&page=2']);
  await b.apply('http://local/hr/members?q=newer&page=3');
  assert.equal(b.content.replaced, undefined); assert.equal(b.content.children[0], b.filter);
  assert.equal(b.table.scrollLeft, 31); assert.deepEqual(b.scroll, [730, 730]);
});
test('unknown inline initializer rejects before replacing current content or changing history', async () => {
  const b = fixture(); b.inline('window.unmappedInitializer = true;');
  await assert.rejects(b.apply('http://local/unmapped'), /초기화 방식/);
  assert.equal(b.content.replaced, undefined); assert.equal(b.table.childNodes[0], b.oldRow); assert.deepEqual(b.history, []);
});

function pageStyles(b) {
  const link=href=>{const n=b.node('link');n.setAttribute('href',href);return n;};
  const common=link('/css/worksystem.css'),vendor=link('/vendor/editor.css');
  const created=[];
  b.document.head={children:[common],insertBefore(n,before){const i=this.children.indexOf(before);this.children.splice(i<0?this.children.length:i,0,n);n.parentNode=this;created.push(n);},};
  common.parentNode=b.document.head;
  const currentQuery=b.document.querySelectorAll,nextQuery=b.nextDoc.querySelectorAll;
  b.document.querySelectorAll=s=>s==='link[rel="stylesheet"][href]'?b.document.head.children:currentQuery(s);
  b.nextDoc.querySelectorAll=s=>s==='head link[rel="stylesheet"][href]'?[vendor,common]:nextQuery(s);
  const create=b.document.createElement;
  b.document.createElement=tag=>{
    if(tag!=='link')return create(tag);
    const n=b.node('link');
    Object.defineProperty(n,'href',{get(){return this.getAttribute('href');},set(v){this.setAttribute('href',v);}});
    n.remove=()=>{b.document.head.children=b.document.head.children.filter(x=>x!==n);};
    return n;
  };
  return {common,created};
}

test('soft navigation waits for page CSS before rendering and preserves vendor-before-theme order without duplicate loads', async () => {
  const b=fixture(),styles=pageStyles(b);
  const pending=b.apply('http://local/admin/notices/1/edit');
  await new Promise(setImmediate);
  assert.equal(styles.created.length,1);
  assert.deepEqual(b.document.head.children,[styles.created[0],styles.common]);
  assert.equal(b.content.replaced,undefined);
  assert.deepEqual(b.history,[]);
  styles.created[0].onload();await pending;
  assert.ok(b.content.replaced);
  await b.apply('http://local/org');await b.apply('http://local/admin/notices/1/edit');
  assert.equal(styles.created.length,1);
});

test('page stylesheet failure preserves current content and allows retry', async () => {
  const b=fixture(),styles=pageStyles(b);
  const pending=b.apply('http://local/admin/notices/1/edit');
  await new Promise(setImmediate);styles.created[0].onerror();
  await assert.rejects(pending,/화면 스타일/);
  assert.equal(b.content.replaced,undefined);assert.deepEqual(b.history,[]);
  assert.deepEqual(b.document.head.children,[styles.common]);
  const retry=b.apply('http://local/admin/notices/1/edit');
  await new Promise(setImmediate);assert.equal(styles.created.length,2);
  styles.created[1].onload();await retry;assert.ok(b.content.replaced);
});

test('a superseded navigation cannot commit after its stylesheet finishes loading', async () => {
  const b=fixture(),styles=pageStyles(b);let current=true;
  const pending=b.window.testNavigation.replaceContentFromResponse({ok:true,url:'http://local/admin/notices/1/edit',text:async()=> 'fixture'},true,()=>current,false);
  await new Promise(setImmediate);current=false;styles.created[0].onload();
  assert.equal(await pending,false);assert.equal(b.content.replaced,undefined);assert.deepEqual(b.history,[]);
});
test('delegated page initializer runs once across reentry and shared app script never runs again', async () => {
  const b = fixture(); b.inline('window.onceRuns = (window.onceRuns || 0) + 1; /* data-cal-agenda */');
  await b.apply('http://local/dashboard'); await b.apply('http://local/dashboard');
  assert.equal(b.window.onceRuns, 1); assert.equal(b.scripts.length, 1);
});
test('direct DOM initializer runs for each mount with an isolated lexical scope', async () => {
  const b = fixture(); b.inline('const scope = 1; window.mountRuns = (window.mountRuns || 0) + scope; /* data-theme-color */');
  await b.apply('http://local/admin/system'); await b.apply('http://local/admin/system');
  assert.equal(b.window.mountRuns, 2); assert.equal(b.scripts.length, 2);
});
test('work-memo compose exports its existing inline submit contract while keeping recipient state private to each mount', async () => {
  const b = fixture(); b.inline('var pickerPeople = []; function submitMemo(){ return pickerPeople.length === 0; }');
  await b.apply('http://local/work-memos/compose'); assert.equal(b.window.submitMemo(), true);
  assert.equal(b.window.pickerPeople, undefined);
  await b.apply('http://local/work-memos/compose'); assert.equal(b.window.submitMemo(), true);
});
test('page-owned release dialogs and chain template/modal transfer on navigation, clean initial hard-load nodes and preserve global dialogs', async () => {
  const b = fixture(); b.owned(['releaseConfirmDialog', 'releasePatchDialog', 'globalOtp'], ['stepTpl', 'receiverModal']);
  await b.apply('http://local/admin/approval-routes/chain');
  assert.deepEqual(b.document.body.children.map(node => node.id), ['globalOtp', 'stepTpl', 'receiverModal']);
  b.nextDoc.body.children = [];
  await b.apply('http://local/org');
  assert.deepEqual(b.document.body.children.map(node => node.id), ['globalOtp']);
});
test('normal report GET pages use soft navigation while report POST ownership and printable output remain native', () => {
  const b = fixture(); const form = pathname => ({ method: 'get', target: '', action: 'http://local' + pathname, closest: selector => selector==='.content'?b.content:null });
  for (const pathname of ['/report/my', '/report/team', '/report/admin', '/report/search']) assert.equal(b.window.testNavigation.canSoftGetForm(form(pathname)), true);
  assert.equal(b.window.testNavigation.canSoftGetForm(form('/report/print')), false);
  const post = form('/report/my'); post.method = 'post';
  assert.equal(b.window.testNavigation.canSoftSubmit(post), false);
});
test('a paginated list becoming empty and then populated keeps the original result/filter and reserves footer height', async () => {
  const b = fixture(); b.pages(true, false);
  await b.apply('http://local/hr/members?q=empty');
  assert.equal(b.content.replaced, undefined); assert.equal(b.content.children[0], b.filter); assert.equal(b.table.childNodes[0], b.newRow);
  assert.deepEqual(b.footer.childNodes, []); assert.equal(b.footer.style.minHeight, '48px');
  b.pages(true, true); await b.apply('http://local/hr/members?q=populated');
  assert.equal(b.content.replaced, undefined); assert.equal(b.content.children[0], b.filter); assert.equal(b.footer.childNodes[0].nodeValue, 'new controls'); assert.equal(b.footer.style.minHeight, '48px');
});

test('uncontracted same-path type switching commits the complete server context rather than only table rows', async () => {
  const b = fixture();
  b.content.removeAttribute('data-ws-query-contract');
  b.nextContent.dataset = { type: 'LEAVE_CANCEL', historyUrl: '/hr/attendance?tab=trip&page=1' };
  b.nextContent.heading = '연차 취소 결재 관리'; b.nextContent.editHref = '/admin/approval-routes/chain?type=LEAVE_CANCEL';
  await b.apply('http://local/hr/members?type=LEAVE_CANCEL');
  assert.equal(b.content.replaced, b.nextContent);
  assert.equal(b.content.replaced.heading, '연차 취소 결재 관리');
  assert.equal(b.content.replaced.editHref, '/admin/approval-routes/chain?type=LEAVE_CANCEL');
  assert.equal(b.content.replaced.dataset.historyUrl, '/hr/attendance?tab=trip&page=1');
  assert.equal(b.table.childNodes[0], b.oldRow);
});

test('an explicit query contract rejects every unapproved changed context key, capability change and missing region', () => {
  const b = fixture(), plan = b.window.testNavigation.queryPlan;
  const old = new URL('http://local/hr/members?q=a');
  for (const key of ['type','year','team','project','cycle','tab','box','view']) {
    assert.equal(plan(b.content, b.nextContent, old, new URL('http://local/hr/members?q=b&'+key+'=different')), null, key);
  }
  assert.ok(plan(b.content, b.nextContent, old, new URL('http://local/hr/members?q=b&page=2')));
  b.nextContent.setAttribute('data-ws-query-context','permission changed');
  assert.equal(plan(b.content, b.nextContent, old, new URL('http://local/hr/members?q=b')), null);
  b.nextContent.removeAttribute('data-ws-query-context'); b.nextTable.setAttribute('data-ws-query-region','renamed');
  assert.equal(plan(b.content, b.nextContent, old, new URL('http://local/hr/members?q=b')), null);
});

test('query contracts synchronize explicit server summary state along with rows on each query', async () => {
  const b = fixture(), count = b.node('count'), fresh = b.node('count');
  count.setAttribute('data-ws-query-state','count'); fresh.setAttribute('data-ws-query-state','count');
  count.childNodes = [{nodeType:3,nodeName:'#text',nodeValue:'old count'}]; fresh.childNodes = [{nodeType:3,nodeName:'#text',nodeValue:'server count'}];
  const oldQuery = b.content.querySelectorAll, newQuery = b.nextContent.querySelectorAll;
  b.content.querySelectorAll = selector => selector === '[data-ws-query-state]' ? [count] : oldQuery(selector);
  b.nextContent.querySelectorAll = selector => selector === '[data-ws-query-state]' ? [fresh] : newQuery(selector);
  await b.apply('http://local/hr/members?q=new');
  assert.equal(count.childNodes[0].nodeValue,'server count'); assert.equal(b.content.replaced,undefined);
});

test('back/reset restores server filter values quietly, while an input edited during the pending request keeps its next draft', () => {
  const b = fixture(), field = {name:'q',type:'text',value:'old',dispatchEvent:event => events.push(event.type)}, events=[];
  const fresh = {name:'q',type:'text',value:'server'};
  const beforeForm=b.node('form'),afterForm=b.node('form');beforeForm.querySelectorAll=()=>[field];afterForm.querySelectorAll=()=>[fresh];const pairs=[[beforeForm,afterForm]];
  b.window.testNavigation.syncFilters(pairs,null);
  assert.equal(field.value,'server'); assert.deepEqual(events,['worksystem:query-filter-sync']);
  const snapshot = new Map([[field,'server']]); field.value='next draft';
  b.window.testNavigation.syncFilters(pairs,snapshot);
  assert.equal(field.value,'next draft'); assert.equal(events.length,1);
});

test('query owner merges only its own keys and reset retains explicit target context while clearing its old page', async () => {
  const b=fixture(), api=b.window.testNavigation;
  await b.apply('http://local/hr/members?q=old&page=9&cal=2026-11');
  b.content.setAttribute('data-ws-query-params','q page view');
  const formTarget=api.ownerRequestUrl('/hr/members?q=new',null,b.content,true);
  assert.equal(formTarget.searchParams.get('q'),'new');
  assert.equal(formTarget.searchParams.has('page'),false);
  assert.equal(formTarget.searchParams.get('cal'),'2026-11','calendar owner state must survive a list form submit');
  const reset=b.node('reset'); reset.setAttribute('data-ws-query-reset','');
  const resetTarget=api.ownerRequestUrl('/hr/members?view=list',reset,b.content);
  assert.equal(resetTarget.searchParams.get('view'),'list','reset links can explicitly select a semantic view');
  assert.equal(resetTarget.searchParams.has('q'),false);assert.equal(resetTarget.searchParams.has('page'),false);
  assert.equal(resetTarget.searchParams.get('cal'),'2026-11');
});

test('nested query contracts select the nearest owner and do not confuse matching region keys from the outer tab owner', () => {
  const b=fixture(),api=b.window.testNavigation;
  b.content.setAttribute('data-ws-query-params','tab'); b.nextContent.setAttribute('data-ws-query-params','tab');
  function inner(parent) {
    const owner=b.node('assignments'),result=b.node('assignment-results');
    owner.setAttribute('data-ws-query-contract','assignments-v1');owner.setAttribute('data-ws-query-path','self');owner.setAttribute('data-ws-query-params','q page');
    result.setAttribute('data-ws-query-region','desktop');
    owner.closest=selector=>selector==='.content'?parent:selector==='[data-ws-query-contract]'?owner:null;
    result.closest=selector=>selector==='[data-ws-query-contract]'?owner:null;
    owner.querySelectorAll=selector=>selector==='[data-ws-query-region]'?[result]:[];
    const original=parent.querySelectorAll;
    parent.querySelectorAll=selector=>selector==='[data-ws-query-contract]'?[owner]:selector==='[data-ws-query-region]'?[...original(selector),result]:original(selector);
    return {owner,result};
  }
  const old=inner(b.content),next=inner(b.nextContent);
  const plan=api.queryPlan(b.content,b.nextContent,new URL('http://local/hr/members?tab=assignments&q=a'),new URL('http://local/hr/members?tab=assignments&q=b'),'assignments-v1',b.nextDoc);
  assert.ok(plan);assert.equal(plan.beforeRoot,old.owner);assert.equal(plan.regions.length,1);assert.equal(plan.regions[0][0],old.result);assert.equal(plan.regions[0][1],next.result);
  const field=b.node('field');field.closest=selector=>selector==='[data-ws-query-contract]'?old.owner:null;
  assert.equal(api.requestOwner(field,b.content),old.owner);
});

test('an explicit detached modal owner accepts its inner dialog form, captures pending drafts and rejects unrelated application modals', () => {
  const b=fixture(),api=b.window.testNavigation,owner=b.node('modal-owner'),dialog=b.node('inner-dialog'),form=b.node('form');
  owner.setAttribute('data-ws-query-contract','lock-preview');owner.setAttribute('data-ws-query-detached-owner','');owner.contains=node=>node===dialog||node===form;
  dialog.contains=()=>false; form.method='get';form.action='http://local/hr/members';form.target='';
  form.closest=selector=>selector==='[data-ws-query-contract]'?owner:selector.includes('[role="dialog"]')?dialog:null;
  assert.equal(api.canSoftGetForm(form),true,'the explicit owner is outside the inner role=dialog');
  const field={name:'lockYm',type:'month',value:'2026-11'};form.querySelectorAll=()=>[field];owner.querySelectorAll=selector=>selector==='[data-ws-query-filter]'?[form]:[];form.setAttribute('data-ws-query-filter','preview');
  const snapshot=api.captureFilters(owner);assert.equal(snapshot.get(field),'2026-11');
  form.closest=selector=>selector==='[data-app-form-body]'?b.node('app-form'):selector==='[data-ws-query-contract]'?owner:null;
  assert.equal(api.canSoftGetForm(form),false);
});

test('server semantic state updates native hidden values and ARIA without replacing the state node', () => {
  const b=fixture(),old=b.node('input'),fresh=b.node('input');
  old.tagName=fresh.tagName='INPUT';old.type=fresh.type='hidden';old.value='2026';fresh.value='2027';
  old.setAttribute('data-year','2026');fresh.setAttribute('data-year','2027');fresh.setAttribute('aria-label','current year');
  b.window.testNavigation.syncState(old,fresh);
  assert.equal(old.value,'2027');assert.equal(old.getAttribute('data-year'),'2027');assert.equal(old.getAttribute('aria-label'),'current year');
});

test('filter SELECT keeps the server-selected value when moving its native options empties the response select', () => {
  const b=fixture(),events=[];
  function select(value) {
    const node=b.node('select');node.tagName='SELECT';node.name='year';node.type='select-one';
    node.childNodes=['2026','2027'].map(value=>({value,selected:false,parent:node}));
    Object.defineProperty(node,'value',{get(){return this.childNodes.find(option=>option.selected)?.value||'';},set(value){this.childNodes.forEach(option=>option.selected=option.value===value);}});
    node.value=value;
    node.replaceChildren=function(...options){
      this.childNodes=[];
      options.forEach(option=>{option.parent.childNodes=option.parent.childNodes.filter(item=>item!==option);option.parent=this;this.childNodes.push(option);});
    };
    node.dispatchEvent=event=>events.push(event.type);
    return node;
  }
  const current=select('2026'),fresh=select('2027'),beforeForm=b.node('form'),afterForm=b.node('form');
  beforeForm.querySelectorAll=()=>[current];afterForm.querySelectorAll=()=>[fresh];
  b.window.testNavigation.syncFilters([[beforeForm,afterForm]],null);
  assert.equal(fresh.value,'','moving options empties their former native SELECT owner');
  assert.equal(current.childNodes.length,2);assert.equal(current.value,'2027');
  assert.deepEqual(events,['worksystem:query-filter-sync']);
});

test('an open plain POST adjustment dialog defers hidden server context until it closes, preserving its draft target', () => {
  const b=fixture(),api=b.window.testNavigation,form=b.node('adjust-form'),dialog=b.node('dialog'),year=b.node('year'),fresh=b.node('year');
  form.setAttribute('method','post');form.method='post';dialog.hidden=false;dialog.closest=()=>null;
  year.tagName=fresh.tagName='INPUT';year.type=fresh.type='hidden';year.value='2026';fresh.value='2027';
  year.closest=selector=>selector==='form'?form:selector.includes('[role="dialog"]')?dialog:null;
  api.applyState(year,fresh);
  assert.equal(year.value,'2026','a draft must still target the year in which it was opened');assert.equal(api.deferredStates.size,1);
  dialog.hidden=true;api.flushDeferredStates();assert.equal(year.value,'2027');assert.equal(api.deferredStates.size,0);
});

test('dedicated fragment reconciliation retains the history root, navigation and indicator while replacing the changed panel', () => {
  const b=fixture(),root=b.node('history'),fresh=b.node('history'),nav=b.node('nav'),nextNav=b.node('nav'),indicator=b.node('ws-choice-indicator'),panel=b.node('panel'),nextPanel=b.node('panel');
  const oldLink=b.node('link'),newLink=b.node('link');oldLink.setAttribute('aria-selected','true');newLink.setAttribute('aria-selected','false');
  nav.childNodes=nav.children=[oldLink,indicator];nextNav.childNodes=nextNav.children=[newLink];indicator.classList.add('ws-choice-indicator');
  panel.childNodes=[b.node('old-panel')];nextPanel.childNodes=[b.node('new-panel')];
  panel.matches=selector=>selector==='[role="tabpanel"]';nextPanel.matches=panel.matches;
  root.childNodes=root.children=[nav,panel];fresh.childNodes=fresh.children=[nextNav,nextPanel];
  root.querySelectorAll=fresh.querySelectorAll=()=>[];
  const result=b.window.worksystemReconcileFragment(root,fresh,{panelChanged:true,preserveFilters:false});
  assert.equal(result,root);assert.equal(root.childNodes[0],nav);assert.equal(nav.childNodes[0],oldLink);assert.equal(nav.childNodes[1],indicator);
  assert.equal(oldLink.getAttribute('aria-selected'),'false');assert.equal(root.childNodes[1],panel);assert.equal(panel.childNodes[0],nextPanel.childNodes[0]);
});

test('same-panel fragment responses keep the native filter form and its pending next draft while updating only result rows', () => {
  const b=fixture(),root=b.node('history'),fresh=b.node('history'),form=b.node('form'),nextForm=b.node('form'),field=b.node('q'),nextField=b.node('q'),table=b.node('table'),nextTable=b.node('table');
  field.name=nextField.name='q';field.type=nextField.type='text';field.value='submitted';nextField.value='server';
  form.querySelectorAll=()=>[field];nextForm.querySelectorAll=()=>[nextField];
  root.querySelectorAll=selector=>selector.includes('[name]')?[field]:selector==='form[method="get"]'?[form]:[];
  fresh.querySelectorAll=selector=>selector==='form[method="get"]'?[nextForm]:[];
  const snapshot=b.window.worksystemCaptureFragmentFilters(root);field.value='next draft';
  table.matches=selector=>selector.includes('.table-scroll');nextTable.matches=table.matches;table.childNodes=[b.oldRow];nextTable.childNodes=[b.newRow];
  root.childNodes=root.children=[form,table];fresh.childNodes=fresh.children=[nextForm,nextTable];
  assert.equal(b.window.worksystemReconcileFragment(root,fresh,{panelChanged:false,preserveFilters:true,snapshot}),root);
  assert.equal(root.childNodes[0],form);assert.equal(field.value,'next draft');assert.equal(root.childNodes[1],table);assert.equal(table.childNodes[0],b.newRow);assert.equal(table.scrollLeft,31);
});

test('server-selected GET navigation reuses anchors and indicator, reflecting fresh active ARIA and action attributes', () => {
  const b = fixture(), old = b.node('nav'), incoming=b.node('nav'), indicator=b.node('ws-choice-indicator');
  indicator.classList.add('ws-choice-indicator');
  function anchor(href, active) { const a=b.node('a');a.tagName='A';a.setAttribute('href',href);a.setAttribute('aria-current',active?'page':'false');a.innerHTML=active?'selected':'other';return a; }
  const links=[anchor('?type=LEAVE',true),anchor('?type=LEAVE_CANCEL',false)];
  old.children=[...links,indicator];incoming.children=[anchor('?type=LEAVE',false),anchor('?type=LEAVE_CANCEL',true)];
  [old,incoming].forEach(nav=>nav.setAttribute('aria-label','결재 종류 필터'));
  b.window.testNavigation.retainNavigation({querySelectorAll:()=>[old]},{querySelectorAll:()=>[incoming]},'http://local/admin/approval-routes');
  assert.equal(incoming.replaced,old);assert.equal(old.children[1],links[1]);assert.equal(old.children[2],indicator);
  assert.equal(links[0].getAttribute('aria-current'),'false');assert.equal(links[1].getAttribute('aria-current'),'page');
});

test('popstate route classification follows the committed DOM even when browser location has already changed', async () => {
  const b=fixture();await b.apply('http://local/admin/system');
  // A browser Back event has already restored /hr/members; the mounted DOM is still /admin/system.
  b.window.location.href='http://local/hr/members?q=old';b.window.location.pathname='/hr/members';
  await b.apply('http://local/hr/members?q=old');
  assert.deepEqual(b.scroll,[0,0]);assert.equal(b.content.replaced,b.nextContent);
});

test('shell logo/profile/service and mobile-home links use soft GET while toggles, downloads and authentication remain native', async () => {
  for (const kind of ['logo','profile','desktop-service','mobile-home']) {
    const b=fixture(),link=b.node('link');link.href='http://local/'+(kind==='profile'?'mypage':'org');link.tagName='A';
    link.closest=selector=>selector==='a[href]' || (kind==='mobile-home'?selector.includes('a.m-tab'):selector.includes('.sidebar a')) ? link : null;
    if(kind==='desktop-service')link.setAttribute('data-set-service','hrself');
    const event=await b.click(link);
    assert.equal(event.defaultPrevented,true,kind);assert.deepEqual(b.requests,[link.href],kind);assert.deepEqual(b.closedDrawers,['nav-drawer-open']);
  }
  for (const variant of ['toggle','download','logout','modified']) {
    const b=fixture(),link=b.node('link');link.href='http://local/'+(variant==='logout'?'logout':'org');
    link.closest=selector=>selector==='a[href]' || selector.includes('.sidebar a')?link:null;
    if(variant==='download')link.setAttribute('download','');
    await b.click(link,variant==='toggle'?{defaultPrevented:true}:variant==='modified'?{ctrlKey:true}:{});
    assert.equal(b.requests.length,0,variant);assert.deepEqual(b.closedDrawers,[]);
  }
});

test('failed browser Back restores the committed DOM URL and discards the failed destination state', async () => {
  const b=fixture();b.failFetch();
  b.window.location.href='http://local/hr/members?q=back-target';
  await b.window.testNavigation.softGet(b.window.location.href,false);
  assert.deepEqual(b.replacements,[{state:null,url:'http://local/hr/members?q=old'}]);
  assert.equal(b.content.replaced,undefined);assert.equal(b.table.childNodes[0],b.oldRow);assert.deepEqual(b.history,[]);
});

test('every shipped automatic GET selector uses the submit-event soft lifecycle; manifest-pruned templates stay absent', async () => {
  const expected={'hr/loa.html':['year']};
  const names=Object.keys(expected);let checked=0,prunedControls=0;
  const manifestPath=path.join(__dirname,'../../../release-manifest.json');
  const pruned=fs.existsSync(manifestPath)?JSON.parse(fs.readFileSync(manifestPath,'utf8')).prunedFiles:[];
  for(const name of names){
    const file=path.join(__dirname,'../../main/resources/templates',name);
    if(pruned.includes('src/main/resources/templates/'+name)){
      assert.equal(fs.existsSync(file),false,'An edition-pruned template must remain physically absent');
      prunedControls+=expected[name].length;continue;
    }
    const html=fs.readFileSync(file,'utf8'),actualFields=[];
    for(const match of html.matchAll(/<form\b([^>]*)>([\s\S]*?)<\/form>/g)){
      if(!/method="get"/i.test(match[1]))continue;
      for(const select of match[2].matchAll(/<select\b[^>]*\bonchange="([^"]+)"[^>]*>/g)){
        const b=fixture(),fieldName=/\bname="([^"]+)"/.exec(select[0])[1];actualFields.push(fieldName);let pending,requested=0;
        const route=/th:action="@\{([^}(]+)/.exec(match[1])[1];
        const form={method:'get',target:'',action:'http://local'+route,fields:[[fieldName,'2027']],closest:selector=>selector==='form'?form:selector==='.content'?b.content:null,
          requestSubmit(){requested++;pending=b.submit(form);},submit(){throw new Error('Native document navigation bypassed the soft submit handler');}};
        vm.runInNewContext('(function(){'+select[1]+'}).call(field)',{field:{form}});await pending;
        assert.equal(requested,1,name);assert.equal(b.requests.length,1,name);assert.equal(new URL(b.requests[0]).searchParams.get(fieldName),'2027');checked++;
      }
    }
    assert.deepEqual(actualFields,expected[name],name+' automatic GET inventory');
  }
  assert.ok(checked>0);assert.equal(checked+prunedControls,1);
  const ledger=path.join(__dirname,'../../main/resources/templates/hr/leave-ledger.html');
  if(pruned.includes('src/main/resources/templates/hr/leave-ledger.html'))assert.equal(fs.existsSync(ledger),false);
  else {
    const html=fs.readFileSync(ledger,'utf8');assert.match(html,/<select[^>]*name="year"/);
    const b=fixture(),form={method:'get',target:'',action:'http://local/hr/leave/ledger',fields:[['year','2025'],['q','QA']],closest:selector=>selector==='form'?form:selector==='.content'?b.content:null};
    await b.submit(form);assert.equal(b.requests.length,1);assert.equal(new URL(b.requests[0]).searchParams.get('year'),'2025');assert.equal(new URL(b.requests[0]).searchParams.get('q'),'QA');
  }
});

test('settings add-row supports an external button scoped to its own form, with unique indexes and the legacy row anchor preserved', () => {
  const code=fs.readFileSync(path.join(__dirname,'../../main/resources/static/js/worksystem-settings-modal.js'),'utf8');
  for(const external of [true,false]){
    const handlers=[],rows=[],focus=[],target={tagName:'TBODY',append:row=>rows.push(row)};
    const anchor={parentElement:target,before:row=>rows.push(row)};
    const template={dataset:{},innerHTML:'<tr><td><input name="types[__IDX__].code"></td></tr>'};
    const button={dataset:{nextIndex:'3',...(external?{addTarget:'#leaveTypeRows'}:{})},form:{querySelector:selector=>selector==='#leaveTypeRows'?target:null},
      getAttribute:()=>'#tplLeaveTypeRow',closest:selector=>selector==='[data-add-row]'?button:selector==='tr'&&!external?anchor:null};
    const document={addEventListener:(type,fn)=>{if(type==='click')handlers.push(fn);},querySelector:selector=>selector==='#tplLeaveTypeRow'?template:null,
      createElement:()=>{const holder={};holder.querySelector=()=>({html:holder.innerHTML,querySelector:()=>({focus:()=>focus.push(true)})});return holder;}};
    vm.runInNewContext(code,{document});
    for(let count=0;count<2;count++)handlers.forEach(fn=>fn({target:button,preventDefault(){}}));
    assert.equal(rows.length,2);assert.match(rows[0].html,/types\[3\]\.code/);assert.match(rows[1].html,/types\[4\]\.code/);
    assert.equal(button.dataset.nextIndex,'5');assert.equal(focus.length,2);
  }
});

test('leave-type add control stays in its POST form outside the horizontal table and points to the same form tbody', () => {
  const file=path.join(__dirname,'../../main/resources/templates/hr/attendance-policy.html');
  const manifest=path.join(__dirname,'../../../release-manifest.json');
  if(fs.existsSync(manifest)&&JSON.parse(fs.readFileSync(manifest,'utf8')).prunedFiles.includes('src/main/resources/templates/hr/attendance-policy.html')){
    assert.equal(fs.existsSync(file),false);return;
  }
  const html=fs.readFileSync(file,'utf8');
  const modal=html.slice(html.indexOf('id="modalLeaveTypes"'));
  const form=/<form\b[^>]*>[\s\S]*?<\/form>/.exec(modal)[0];
  assert.match(form,/th:action="@\{\/hr\/leave-types\/save-all\}" method="post"/);
  assert.match(form,/<tbody id="leaveTypeRows">/);
  const scroller=/<div class="table-scroll">[\s\S]*?<\/table>\s*<\/div>/.exec(form)[0];
  assert.doesNotMatch(scroller,/data-add-row/);
  assert.match(form.slice(form.indexOf(scroller)+scroller.length),/data-add-row="#tplLeaveTypeRow" data-add-target="#leaveTypeRows"/);
  assert.equal((html.match(/id="leaveTypeRows"/g)||[]).length,1);
});
