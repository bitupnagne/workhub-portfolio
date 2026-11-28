const test=require('node:test');
const assert=require('node:assert/strict');
const fs=require('node:fs');
const path=require('node:path');
const vm=require('node:vm');
const source=fs.readFileSync(path.join(__dirname,'../../main/resources/static/js/worksystem-calendar.js'),'utf8');

function fixture({initial=true}={}) {
  const handlers=new Map(),windowHandlers=new Map(),requests=[],ids=new Map();
  function node(id) {
    const classes=new Set(),attrs=new Map();
    return {id,dataset:{},style:{},isConnected:true,innerHTML:'',textContent:'',offsetWidth:300,
      classList:{add:x=>classes.add(x),remove:x=>classes.delete(x),contains:x=>classes.has(x)},
      setAttribute:(k,v)=>attrs.set(k,String(v)),getAttribute:k=>attrs.get(k),
      getBoundingClientRect:()=>({left:36,bottom:48}),focus(options){assert.equal(options.preventScroll,true);document.activeElement=this;},
      closest(selector){return selector==='#'+id?this:null;}};
  }
  const document={readyState:'complete',documentElement:{clientWidth:390},activeElement:null,
    getElementById:id=>ids.get(id)||null,addEventListener(type,fn){if(!handlers.has(type))handlers.set(type,[]);handlers.get(type).push(fn);}};
  const window={location:{origin:'http://local',pathname:'/report/my'},innerHeight:844,
    addEventListener(type,fn){if(!windowHandlers.has(type))windowHandlers.set(type,[]);windowHandlers.get(type).push(fn);},
    removeEventListener(type,fn){windowHandlers.set(type,(windowHandlers.get(type)||[]).filter(h=>h!==fn));},
    worksystemSoftNavigate:(href,options)=>requests.push({href,source:options.source})};
  const context=vm.createContext({window,document,URL,Date,Set});
  let button,overlay,grid,label,popup;
  function mount(week='2026-W45',route='/report/my') {
    if(button)button.isConnected=false;
    button=node('weekPickerBtn');Object.assign(button.dataset,{week,currentWeek:'2026-W45',currentPath:route,weeks:week});
    overlay=node('weekCalOverlay');grid=node('weekCalGrid');label=node('weekCalMonth');popup=node('popup');
    overlay.querySelector=selector=>selector==='.cal-popup'?popup:selector==='#weekCalGrid'?grid:selector==='#weekCalMonth'?label:null;
    overlay.contains=target=>target===grid||target.insideOverlay===true;
    for(const n of [button,overlay,grid,label])ids.set(n.id,n);
  }
  function fireWindow(type){for(const handler of windowHandlers.get(type)||[])handler({type});}
  function click(target){const event={target,preventDefault(){this.defaultPrevented=true;}};for(const h of handlers.get('click')||[])h(event);return event;}
  function control(selector,dataset={}) {return {dataset,insideOverlay:true,closest:query=>query===selector?{dataset}:null};}
  if(initial)mount();
  vm.runInContext(source,context);
  return {handlers,windowHandlers,requests,document,window,ids,mount,fireWindow,click,control,
    reloadScript:()=>vm.runInContext(source,context),get button(){return button;},get overlay(){return overlay;},get grid(){return grid;},get label(){return label;},get popup(){return popup;},
    escape(){for(const h of handlers.get('keydown')||[])h({key:'Escape',preventDefault(){}});}};
}

test('week picker binds once, opens from current state, changes months locally and selects exactly one soft GET',()=>{
  const b=fixture();b.reloadScript();assert.equal(b.handlers.get('click').length,1);assert.equal(b.handlers.get('keydown').length,1);
  b.click(b.button);assert.equal(b.button.getAttribute('aria-expanded'),'true');assert.equal(b.label.textContent,'2026년 11월');
  assert.match(b.grid.innerHTML,/cal-selected[^>]*data-week="2026-W45"/);assert.equal(b.requests.length,0);
  b.click(b.control('[data-cal-next]'));assert.equal(b.label.textContent,'2026년 12월');
  b.click(b.control('[data-cal-prev]'));assert.equal(b.label.textContent,'2026년 11월');assert.equal(b.requests.length,0);
  b.click(b.control('#weekCalGrid [data-week]',{week:'2026-W46'}));
  assert.equal(b.requests.length,1);assert.equal(b.requests[0].href,'/report/my?week=2026-W46');assert.equal(b.requests[0].source,b.button);
  assert.equal(b.button.getAttribute('aria-expanded'),'false');assert.equal(b.document.activeElement,b.button);
});

test('reopening and cross-route replacement read the latest week/path/data dots rather than initial captured state',()=>{
  const b=fixture();b.click(b.button);b.escape();
  Object.assign(b.button.dataset,{week:'2026-W48',weeks:'2026-W48'});b.click(b.button);
  assert.match(b.grid.innerHTML,/cal-selected[^>]*data-week="2026-W48"/);assert.equal((b.grid.innerHTML.match(/class="cal-dot"/g)||[]).length,1);
  b.fireWindow('worksystem:navigation-start');assert.equal(b.overlay.classList.contains('open'),false);
  b.mount('2027-W01','/report/team');b.fireWindow('worksystem:content-updated');b.click(b.button);
  assert.equal(b.label.textContent,'2027년 1월');assert.match(b.grid.innerHTML,/cal-selected[^>]*data-week="2027-W01"/);
  b.click(b.control('#weekCalGrid [data-week]',{week:'2027-W02'}));assert.equal(b.requests.at(-1).href,'/report/team?week=2027-W02');
  assert.equal(b.handlers.get('click').length,1);
});

test('a conditional picker inserted after initial load works, and absent/replaced overlays close without stale navigation',()=>{
  const b=fixture({initial:false});b.mount();b.fireWindow('worksystem:content-updated');b.click(b.button);
  assert.equal(b.overlay.classList.contains('open'),true);assert.equal(b.button.getAttribute('aria-controls'),'weekCalOverlay');
  b.ids.delete('weekCalOverlay');b.fireWindow('worksystem:content-updated');assert.equal(b.button.getAttribute('aria-expanded'),'false');
  b.click(b.button);assert.equal(b.requests.length,0);
  b.mount('2026-W49');b.fireWindow('worksystem:content-updated');b.click(b.button);b.click(b.overlay);
  assert.equal(b.overlay.classList.contains('open'),false);assert.equal(b.document.activeElement,b.button);assert.equal(b.requests.length,0);
  assert.equal(parseFloat(b.popup.style.left)>=8,true);assert.equal(parseFloat(b.popup.style.left)+300<=390,true);
});
