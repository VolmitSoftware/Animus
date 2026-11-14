// Exports a browser workspace as a data folder zip, from the top bar's menu and
// from its row on the start page. Playwright catches each download and the
// clip fails unless the zip arrives.
// Offline: no fake server needed. The examples workspace is made before filming.
const path = require('node:path');

module.exports = async (L) => {
  const f = await L.session({id: '17-export-workspace'});
  const ws = await L.examplesWorkspace(f.setup);
  const page = await f.start(`${L.SITE}/${ws}`);
  const h = L.human(f);
  await page.getByRole('heading', {name: 'Example packs', level: 1}).waitFor();
  await page.waitForTimeout(1500);
  await L.clearToasts(page);
  const saved = async (download) => {
    const d = await download;
    await d.saveAs(path.join(f.dir, d.suggestedFilename()));
    return d.suggestedFilename();
  };
  const notice = (name) => page.getByRole('region', {name: 'Notifications'}).getByText(`Saved ${name}`).last();
  f.mark();
  await h.click(page.getByRole('banner').getByRole('button', {name: 'More actions'}), {noReveal: true, after: 700});
  let download = page.waitForEvent('download');
  await h.click(page.getByRole('menuitem', {name: 'Export zip'}), {noReveal: true, after: 300});
  const first = await saved(download);
  await notice(first).waitFor();
  // The notice is fixed to the bottom of the viewport, so scrolling cannot move it.
  await h.moveTo(notice(first), {noReveal: true, ox: 0.5, oy: 2.2});
  await h.result(2200);
  await L.clearToasts(page);
  await h.click(page.getByRole('link', {name: 'Animus, all workspaces'}), {after: 1000, noReveal: true});
  const row = page.getByRole('list', {name: 'Continue'}).getByRole('toolbar', {name: 'Example packs', exact: true});
  await h.hover(page.getByRole('list', {name: 'Continue'}).getByText(/25 documents/).first(), 900);
  await h.click(row.getByRole('button', {name: 'More actions'}), {after: 500});
  download = page.waitForEvent('download');
  await h.click(page.getByRole('menuitem', {name: 'Export zip'}), {noReveal: true, after: 300});
  const second = await saved(download);
  await notice(second).waitFor();
  await h.moveTo(notice(second), {noReveal: true, ox: 0.5, oy: 2.2});
  await h.result(2200);
  // The notice closes on its own; end beside the workspace that was exported.
  await h.moveTo(page.getByRole('list', {name: 'Continue'}).getByText(/25 documents/).first(), {ox: 1.3});
  await h.pause(600);
  return L.finish(f, {closing: `${first} holds all 25 documents, ready for plugins/Animus`});
};
