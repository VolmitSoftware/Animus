// The workspace overview of a data folder opened as a zip: counts by kind, ways to try it, and the documents that need repair.
// No fake server: the zip is built from test/fixtures/npc_browser and test/fixtures/gui_editors as an Animus/ folder.
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const {execFileSync} = require('node:child_process');

/** Animus.zip holding both fixtures under Animus/, in a fresh temp folder. */
function dataZip(L) {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'animus-42-'));
  const root = path.join(dir, 'Animus');
  for (const name of ['npc_browser', 'gui_editors']) fs.cpSync(path.join(L.REPO, 'animus-web', 'test', 'fixtures', name), root, {recursive: true});
  execFileSync('zip', ['-qr', 'Animus.zip', 'Animus'], {cwd: dir});
  return path.join(dir, 'Animus.zip');
}

module.exports = async (L) => {
  const zip = dataZip(L);
  const f = await L.session({id: '43-workspace-overview'});
  const page = await f.start(`${L.SITE}/#/`);
  const h = L.human(f);
  // The counts and the error banner sit above h.reveal()'s middle band at the top of an unscrolled page; glide without scrolling.
  const glance = async (loc, ms) => { await h.moveTo(loc, {noReveal: true}); await h.pause(ms); };
  await page.getByRole('button', {name: 'Open zip'}).waitFor();
  await page.waitForTimeout(2000);
  f.mark();
  await h.pause(800);
  page.once('filechooser', (chooser) => chooser.setFiles(zip));
  await h.hover(page.getByRole('button', {name: 'Open zip'}), 900);
  await h.click(page.getByRole('button', {name: 'Open zip'}), {after: 1500});
  const main = page.getByRole('main');
  await main.getByRole('heading', {name: 'Animus', level: 1}).waitFor();
  await glance(main.getByText('NPCs', {exact: true}), 1000);
  await glance(main.getByText('Chest GUIs', {exact: true}), 1000);
  await h.result(1500);
  await h.hover(main.getByRole('link', {name: 'Preview Baker'}), 900);
  await h.hover(main.getByRole('link', {name: 'Dry-run harbor:ferryman'}), 900);
  await h.hover(main.getByRole('heading', {name: '2 documents need repair'}), 1200);
  await h.hover(main.getByText(/^\$\.keys\.X\.actions/), 1400);
  await h.click(main.getByRole('link', {name: 'Chest GUIs: town:gloss-only'}), {after: 1200});
  await glance(page.getByText("Type: action 'camera' is not available in Animus", {exact: true}), 800);
  await h.result(2000);
  fs.rmSync(path.dirname(zip), {recursive: true, force: true});
  return L.finish(f, {closing: 'The zip opened 20 documents; two need repair, each one link away'});
};
