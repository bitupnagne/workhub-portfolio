/* Run this function in Playwright with the current ui-forms source and CSS.
 * Native DOM/form APIs are intentional: mock controls cannot prove form ownership or validation.
 */
async (page, source, css) => {
  const results = [];
  const check = (condition, message) => { if (!condition) throw new Error(message); };
  const inspect = fn => page.evaluate(fn);
  await page.setViewportSize({ width: 1440, height: 1000 });
  await page.route('http://worksystem.test/**', route => route.fulfill({ status: 200, contentType: 'text/html', body: '<!doctype html>' }));
  await page.goto('http://worksystem.test/fixture');
  await page.setContent(`<!doctype html><html><head><base href="http://worksystem.test/"></head><body><div class="content">
    <div id="ws-edit-form-1"></div>
    <section class="form-panel"><div class="section-head"><h2>Outside heading</h2></div>
      <form data-edit-form data-edit-restore-dom action="/save" method="post" data-fixture="main">
        <input type="hidden" name="id" value="43"><input name="name" value="Alice" required>
        <div class="form-actions"><a href="#kept">Keep navigation</a><button id="mainSave" type="submit" name="intent" value="update" formaction="/special" formmethod="post">Original save</button></div>
      </form>
    </section>
    <table><tbody><tr><td><input name="rowName" value="Original" required form="rowForm"></td><td><button id="rowSave" type="submit" name="action" value="row" form="rowForm">Row save</button></td></tr></tbody></table>
    <form data-edit-form id="rowForm" action="/row" method="post"><input type="hidden" name="id" value="7"></form>
    <section><div class="section-head"><h2>Upload</h2></div><form data-edit-form id="uploadForm" action="/upload" method="post" enctype="multipart/form-data"><input type="file" name="photo"><div class="form-actions"><button type="submit" id="uploadSave">Upload</button></div></form></section>
    <form data-edit-form id="multiForm" action="/multi" method="post"><input name="requiredValue" required><button id="normalSave" type="submit" name="mode" value="save">Save</button><button id="draftSave" type="submit" name="mode" value="draft" formnovalidate formaction="/draft" formmethod="get">Draft</button><button id="hiddenSave" type="submit" hidden name="mode" value="hidden">Hidden</button></form>
    <form data-edit-form id="inputForm" action="/input" method="post"><input id="inputSave" type="submit" name="commit" value="original-payload"></form>
    <form data-edit-form id="disabledForm" action="/disabled" method="post"><input name="locked" value="Keep" readonly><button id="disabledSave" type="submit" disabled>Disabled save</button></form>
    <div class="schedule-edit-modal" id="editModal" hidden><div class="schedule-edit-dialog" role="dialog"><div class="schedule-edit-head"><strong>Modal edit</strong><button type="button" data-modal-close>Close</button></div><form data-edit-form id="modalForm" action="/modal" method="post"><input name="value" value="Modal original"><div class="schedule-edit-foot"><button type="button" data-modal-close>Close</button><button type="submit" id="modalSave">Modal save</button></div></form></div></div>
  </div></body></html>`);
  await page.addStyleTag({ content: css });
  await inspect(() => {
    window.__submissions = []; window.__originalMain = document.querySelector('[data-fixture=main]').outerHTML;
    window.__mainSave = document.querySelector('#mainSave'); window.__clicks = 0;
    window.__mainSave.addEventListener('click', () => window.__clicks++);
    document.addEventListener('submit', e => { e.preventDefault(); window.__submissions.push({ id: e.target.getAttribute('id'), submitter: e.submitter?.id, values: [...new FormData(e.target, e.submitter)] }); });
  });
  await page.addScriptTag({ content: source });
  const main = page.locator('[data-fixture=main]');
  const mainToolbar = page.locator('.section-head').first().locator('.ws-edit-actions');
  check(await inspect(() => document.querySelector('#mainSave') === window.__mainSave), 'actual node must be retained');
  check(await inspect(() => { const f = document.querySelector('[data-fixture=main]'); return f.getAttribute('id') !== 'ws-edit-form-1' && document.querySelector('#mainSave').form === f && !f.contains(document.querySelector('#mainSave')); }), 'generated owner ID must avoid collisions and survive named id fields');
  check(await page.locator('#mainSave').isHidden(), 'save must be hidden in view mode');
  await mainToolbar.locator('[data-edit-start]').click();
  await main.locator('input[name=name]').fill('');
  await page.locator('#mainSave').click();
  check(await inspect(() => window.__submissions.length === 0), 'required validation must block invalid native submit');
  check(await page.locator('#mainSave').isVisible(), 'invalid submit must retain edit/save mode');
  await main.locator('input[name=name]').fill('Changed');
  await page.locator('#mainSave').click();
  check(await inspect(() => window.__submissions[0].submitter === 'mainSave' && window.__submissions[0].values.some(([k,v]) => k === 'intent' && v === 'update') && document.querySelector('#mainSave').getAttribute('formaction') === '/special' && document.querySelector('#mainSave').getAttribute('formmethod') === 'post' && window.__clicks === 2), 'native submitter payload, overrides and original click handlers must survive');
  await main.evaluate(form => { const input = document.createElement('input'); input.name = 'temporary'; form.append(input); });
  await mainToolbar.locator('[data-edit-cancel]').click();
  check(await main.locator('input[name=name]').inputValue() === 'Alice' && await main.locator('[name=temporary]').count() === 0, 'DOM-restoring cancel must restore values and remove added rows');
  check(await main.locator('a[href="#kept"]').isVisible(), 'navigation in the old footer must remain');
  results.push('outside heading: node identity, owner, payload, validation, cancel, navigation');
  const placement = [];
  for (const width of [1440, 390]) {
    await page.setViewportSize({ width, height: 1000 });
    const before = await mainToolbar.locator('[data-edit-start]').boundingBox();
    await mainToolbar.locator('[data-edit-start]').click();
    const after = await page.locator('#mainSave').boundingBox();
    const cancel = await mainToolbar.locator('[data-edit-cancel]').boundingBox();
    check(Math.abs(before.x + before.width - after.x - after.width) < 1 && Math.abs(after.y + after.height / 2 - cancel.y - cancel.height / 2) < 1, 'save must replace edit at the same right edge with cancel alongside');
    await page.waitForFunction(() => getComputedStyle(document.querySelector('#mainSave')).color === 'rgb(255, 255, 255)');
    placement.push({ width, rightDelta: before.x + before.width - after.x - after.width, rowCenterDelta: after.y + after.height / 2 - cancel.y - cancel.height / 2 });
    await mainToolbar.locator('[data-edit-cancel]').click();
  }
  await page.setViewportSize({ width: 1440, height: 1000 });
  results.push('toolbar PC/mobile: same edit/save right edge, cancel row, white solid text');
  await main.locator('input[name=name]').evaluate(n => { n.style.marginTop = '2000px'; });
  await mainToolbar.scrollIntoViewIfNeeded();
  const scrollBefore = await inspect(() => scrollY);
  await mainToolbar.locator('[data-edit-start]').click();
  check(await inspect(() => scrollY) === scrollBefore, 'edit entry must focus without jumping the document to the first field');
  await mainToolbar.locator('[data-edit-cancel]').click();
  await main.locator('input[name=name]').evaluate(n => { n.style.marginTop = ''; });
  results.push('edit entry: first-field focus preserves the clicked toolbar scroll position');



  const rowToolbar = page.locator('#rowSave').locator('xpath=../..');
  await rowToolbar.locator('[data-edit-start]').click();
  await page.locator('input[name=rowName]').fill('Row draft');
  await page.locator('#rowSave').click();
  check(await inspect(() => window.__submissions.at(-1).id === 'rowForm' && window.__submissions.at(-1).submitter === 'rowSave'), 'external row controls must submit the original form');
  await rowToolbar.locator('[data-edit-cancel]').click();
  check(await page.locator('input[name=rowName]').inputValue() === 'Original' && await page.locator('input[name=rowName]').getAttribute('data-edit-locked') === 'true', 'external fields must restore and relock');
  results.push('external row fields: submitter, draft/cancel, original ID and lock');

  const uploadToolbar = page.locator('#uploadSave').locator('xpath=../..');
  await uploadToolbar.locator('[data-edit-start]').click();
  await inspect(() => { const dt = new DataTransfer(); dt.items.add(new File(['abc'], 'photo.png', { type: 'image/png' })); document.querySelector('#uploadForm input').files = dt.files; });
  await page.locator('#uploadSave').click();
  check(await inspect(() => document.querySelector('#uploadSave').form.enctype === 'multipart/form-data' && [...new FormData(document.querySelector('#uploadForm')).values()].some(v => v instanceof File && v.name === 'photo.png')), 'file and multipart ownership must survive');
  await uploadToolbar.locator('[data-edit-cancel]').click();
  check(await inspect(() => document.querySelector('#uploadForm input').files.length === 0 && document.querySelector('#uploadForm input').disabled), 'file cancel must clear and disable the source control');
  check(await page.locator('#uploadForm [data-edit-empty-footer]').count() === 1, 'only empty original footer should be marked hidden');
  results.push('file upload: original multipart form, File payload, cancel clearing');

  const multi = page.locator('#multiForm');
  await multi.locator('[data-edit-start]').click();
  check(await page.locator('#hiddenSave').isHidden(), 'originally hidden submitter must stay hidden');
  await page.locator('#draftSave').click();
  check(await inspect(() => { const s = window.__submissions.at(-1); return s.submitter === 'draftSave' && s.values.some(([k,v]) => k === 'mode' && v === 'draft') && document.querySelector('#draftSave').formNoValidate && document.querySelector('#draftSave').formMethod === 'get'; }), 'alternate submitter must retain name/value and formnovalidate');
  await multi.locator('[data-edit-cancel]').click();
  await page.locator('#inputForm [data-edit-start]').click();
  await page.locator('#inputSave').click();
  check(await inspect(() => window.__submissions.at(-1).values.some(([k,v]) => k === 'commit' && v === 'original-payload')), 'input-submit value must not be overwritten as a label');
  await page.locator('#disabledForm [data-edit-start]').click();
  check(await page.locator('#disabledSave').isDisabled() && await page.locator('#disabledForm input').getAttribute('data-edit-locked') === 'true', 'originally disabled submitter and permanent readonly must remain');
  results.push('multiple/input submitters: branch semantics, overrides, hidden/disabled');

  await inspect(() => { document.querySelector('#editModal').hidden = false; });
  await page.locator('#editModal [data-edit-start]').click();
  await page.locator('#modalForm input').fill('Modal draft');
  await inspect(() => { document.querySelector('#editModal').hidden = true; });
  await page.waitForFunction(() => document.querySelector('#modalForm').dataset.editState === 'view');
  check(await page.locator('#modalForm input').inputValue() === 'Modal original' && await page.locator('#editModal [data-modal-close]').count() === 2, 'modal close must restore edits and preserve close actions');
  results.push('modal header: native owner and close/cancel restoration');

  await mainToolbar.locator('[data-edit-start]').click();
  await main.locator('input[name=name]').fill('Retained draft');
  await page.locator('#mainSave').click();
  await inspect(() => { const banner = document.createElement('div'); banner.className = 'banner err'; banner.textContent = 'Validation error'; document.querySelector('.content').prepend(banner); document.querySelector('[data-fixture=main]').outerHTML = window.__originalMain; window.dispatchEvent(new CustomEvent('worksystem:content-updated')); });
  await page.waitForFunction(() => document.querySelector('[data-fixture=main]').dataset.editState === 'editing');
  check(await page.locator('.section-head').first().locator('.ws-edit-actions').count() === 1 && await page.locator('#mainSave').count() === 1, 'partial replacement must remove stale toolbar and avoid duplicate save');
  check(await page.locator('[data-fixture=main] input[name=name]').inputValue() === 'Retained draft', 'server validation error must reopen retained draft');
  check(await inspect(() => document.querySelector('#mainSave').form === document.querySelector('[data-fixture=main]')), 'fresh save must own the replacement form');
  results.push('partial/error replacement: one toolbar/save, retained draft, new form owner');
  return { passed: true, tests: results.length, results, placement, sourceInjection: true, serverWrites: 0 };
}
