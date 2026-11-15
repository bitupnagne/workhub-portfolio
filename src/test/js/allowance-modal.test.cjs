const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const read = name => fs.readFileSync(path.join(__dirname, '../../main/resources/static/js', name), 'utf8');

test('a ledger price editor keeps the clicked grade/year, distinguishes zero from inheritance and resets the previous row draft', () => {
  const handlers = [], fields = Object.fromEntries(['year','gradeId','amount','q','page','apage'].map(name => [name,{value:'old draft'}]));
  const labels = Object.fromEntries(['year','grade','effective'].map(name => ['[data-ll-allowance-'+name+']',{textContent:''}]));
  const form = {elements:{namedItem:name => fields[name]}};
  const modal = {querySelector:selector => selector === '[data-ll-allowance-form]' ? form : labels[selector]};
  const document = {addEventListener:(name,handler) => {if(name==='click')handlers.push(handler);},querySelector:selector => selector === '#modalAllowance' ? modal : null};
  vm.runInNewContext(read('worksystem-leave-ledger.js'),{document});
  const click = dataset => handlers.forEach(handler => handler({target:{closest:selector => selector==='[data-ll-allowance]'?{dataset}:null}}));
  click({year:'2026',gradeId:'5',ownAmount:'0',gradeName:'3급',effectiveAmount:'0원',query:'A & B',page:'2',apage:'3'});
  assert.equal(fields.amount.value,'0');assert.equal(fields.year.value,'2026');assert.equal(fields.gradeId.value,'5');assert.equal(fields.q.value,'A & B');
  fields.amount.value='unsaved';
  click({year:'2025',gradeId:'9',ownAmount:'',gradeName:'3급 › 1호봉',effectiveAmount:'90,000원',query:'',page:'1',apage:'1'});
  assert.equal(fields.amount.value,'');assert.equal(fields.year.value,'2025');assert.equal(fields.gradeId.value,'9');
  assert.equal(labels['[data-ll-allowance-grade]'].textContent,'3급 › 1호봉');assert.equal(labels['[data-ll-allowance-effective]'].textContent,'90,000원');
});

test('the retained allowance editor does not load the removed grade administration script', () => {
  const root = path.join(__dirname, '../../main/resources');
  assert.equal(fs.existsSync(path.join(root, 'static/js/worksystem-grades.js')), false);
  assert.equal(fs.existsSync(path.join(root, 'templates/admin/grades.html')), false);
  const shell = fs.readFileSync(path.join(root, 'templates/fragments/app.html'), 'utf8');
  assert.doesNotMatch(shell, /worksystem-grades\.js|data-nav-group="eval"|\/eval\/tasks/);
  assert.match(fs.readFileSync(path.join(root, 'templates/hr/leave-ledger.html'), 'utf8'), /\/hr\/leave\/ledger\/allowance/);
});
