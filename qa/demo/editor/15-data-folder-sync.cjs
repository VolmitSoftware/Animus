// Opens a data folder (the town stage's plugins/Animus) as a new workspace,
// then compares it with a paired server on Send to server (Advanced).
// Fake server data: --data=test/fixtures/npc_browser (from animus-web/); the
// browser is paired before filming. The folder pick goes through Playwright's
// file chooser, so no native dialog shows.
const path = require('node:path');

module.exports = async (L) => {
  const folder = path.join(L.REPO, 'qa', 'demo', 'stage', 'town', 'Animus');
  await L.resetFake();
  const f = await L.session({id: '15-data-folder-sync'});
  await L.pair(f.setup);
  const page = await f.start(`${L.SITE}/#/`);
  const h = L.human(f);
  const start = page.getByRole('toolbar', {name: 'Start'});
  await start.getByRole('button', {name: 'Open folder'}).waitFor();
  await page.waitForTimeout(1500);
  f.mark();
  await h.hover(page.getByRole('list', {name: 'Continue'}).getByText('Animus fake server'), 900);
  const chooser = page.waitForEvent('filechooser', {timeout: 20000});
  await h.click(start.getByRole('button', {name: 'Open folder'}), {after: 100});
  await (await chooser).setFiles(folder);
  await page.getByRole('heading', {name: 'Animus', level: 1}).waitFor();
  const toasts = page.getByRole('region', {name: 'Notifications'});
  const opened = toasts.getByText(/^Opened \d+ documents/);
  // Rest just left of each notice, so its words stay readable when the one below closes and it moves down.
  await h.moveTo(opened, {ox: -0.2, noReveal: true});
  await h.result(2000);
  // Simple says how many of the documents only Advanced lists.
  await h.moveTo(toasts.getByText(/imported documents? (is|are) in Advanced\.$/), {ox: -0.2, noReveal: true});
  await h.result(2200);
  await L.clearToasts(page);
  await h.mode('advanced');
  // Advanced's Home lists the files the browser upgraded while opening the folder.
  await h.moveTo(page.getByText(/files upgraded to schemaVersion/), {ox: 1.2});
  await h.pause(1500);
  await h.job('Send to server');
  const summary = page.getByRole('status').filter({hasText: /\d+ new, \d+ changed, \d+ same/});
  await summary.waitFor();
  await h.moveTo(summary, {ox: 1.15, noReveal: true});
  await h.result(1800);
  await h.click(page.getByRole('group', {name: 'Filters'}).getByRole('button', {name: 'Changed'}), {after: 900});
  await h.hover(page.getByRole('listbox', {name: 'Differences'}).getByRole('option').first(), 1200);
  // Rest just past Use mine so its label stays readable.
  await h.moveTo(page.getByRole('group', {name: 'Changed documents'}).getByRole('button', {name: 'Use mine'}), {ox: 1.4});
  await h.pause(1500);
  await h.result(1600);
  return L.finish(f, {closing: 'The town folder is a workspace; Send to server lists what is new'});
};
