// Exports an offline workspace as a data folder zip, from the top bar and
// from the home page's workspace list. Playwright catches each download and
// the clip fails unless the zip arrives.
// Offline: no fake server needed. The example packs workspace is made before filming.
const path = require('node:path');

module.exports = async (L) => {
  const f = await L.session({id: '17-export-workspace'});
  await f.setup.goto(`${L.SITE}/#/`);
  await f.setup.getByRole('button', {name: 'Create with example packs'}).click();
  await f.setup.waitForFunction(() => location.hash.startsWith('#/workspace/'));
  const ws = f.setup.url().split('#')[1].split('/').slice(0, 3).join('/');
  const page = await f.start(`${L.SITE}/#${ws}`);
  const h = L.human(f);
  await page.getByRole('heading', {name: 'Example packs', level: 1}).waitFor();
  await page.waitForTimeout(1500);
  const saved = async (download) => {
    const d = await download;
    await d.saveAs(path.join(f.dir, d.suggestedFilename()));
    return d.suggestedFilename();
  };
  f.mark();
  await h.click(page.getByRole('button', {name: /Export$/}), {noReveal: true, after: 700});
  let download = page.waitForEvent('download');
  await h.click(page.getByRole('menuitem', {name: 'Export the data folder zip'}), {noReveal: true, after: 300});
  const first = await saved(download);
  await page.getByText(`Saved ${first}`).waitFor();
  // The notice is fixed to the bottom of the viewport, so scrolling cannot move it.
  await h.moveTo(page.getByText(`Saved ${first}`), {noReveal: true});
  await h.result(2200);
  await h.click(page.getByRole('link', {name: 'Animus Editor'}), {after: 1000});
  const list = page.getByRole('region', {name: 'Workspaces in this browser'});
  await h.hover(list.getByText('25 documents, local'), 900);
  download = page.waitForEvent('download');
  await h.click(list.getByRole('button', {name: 'Export Example packs as a zip'}), {after: 300});
  const second = await saved(download);
  await page.getByText(`Saved ${second}`).last().waitFor();
  await h.moveTo(page.getByText(`Saved ${second}`).last(), {noReveal: true});
  await h.result(2200);
  return L.finish(f, {closing: `${first} holds all 25 documents, ready for plugins/Animus`});
};
