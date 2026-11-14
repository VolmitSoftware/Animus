// A data folder opened as a zip: Home's counts by job, then in Advanced the
// GUI counts and Needs attention, which opens a document that needs repair.
// No fake server: the zip is built from test/fixtures/npc_browser and test/fixtures/gui_editors as an Animus/ folder.
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const {execFileSync} = require('node:child_process');

/** Animus.zip holding both fixtures under Animus/, in a fresh temp folder. */
function dataZip(L) {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'animus-43-'));
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
  const open = page.getByRole('button', {name: 'Open zip'});
  await open.waitFor();
  await page.waitForTimeout(2000);
  f.mark();
  await h.pause(800);
  const chooser = page.waitForEvent('filechooser', {timeout: 20000});
  await h.hover(page.getByRole('heading', {name: /^Open my files/}), 900);
  await h.click(open, {after: 300});
  await (await chooser).setFiles(zip);
  const main = page.getByRole('main');
  await main.getByRole('heading', {name: 'Animus', level: 1}).waitFor();
  await h.pause(900);
  const toasts = page.getByRole('region', {name: 'Notifications'});
  // Rest just left of each notice, so its words stay readable when the one below closes and it moves down.
  await h.moveTo(toasts.getByText(/^Opened \d+ documents/), {ox: -0.2, noReveal: true});
  await h.result(1800);
  // Simple says how many of the documents only Advanced lists.
  await h.moveTo(toasts.getByText(/imported documents? (is|are) in Advanced\.$/), {ox: -0.2, noReveal: true});
  await h.result(2000);
  const library = main.getByRole('navigation', {name: 'Library'});
  await h.hover(library.getByRole('link', {name: /^NPCs/}), 900);
  await h.mode('advanced');
  await h.hover(library.getByRole('link', {name: /^GUIs/}), 1000);
  const attention = main.getByRole('region', {name: /^Needs attention/});
  await h.hover(attention.getByRole('heading'), 1200);
  await h.click(attention.getByRole('link', {name: /^town:gloss-only/}), {after: 1500});
  await page.getByRole('heading', {name: 'Gloss only', level: 1}).waitFor();
  await h.click(main.getByRole('button', {name: '1 problem'}), {after: 1200, noReveal: true});
  // The problem line spans the page; rest just under its start.
  await h.moveTo(page.getByText(/is not available in Animus/).first(), {ox: 0.12, oy: 1.9});
  await h.result(2200);
  fs.rmSync(path.dirname(zip), {recursive: true, force: true});
  return L.finish(f, {closing: 'The zip opened 22 documents; two need repair, each one link away'});
};
