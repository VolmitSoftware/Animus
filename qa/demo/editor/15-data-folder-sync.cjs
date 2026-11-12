// Opens a data folder (the town stage's plugins/Animus) as a new workspace,
// then compares it with a paired server on the Send to a server page.
// Fake server: any --data (README pattern, test/fixtures/npc_browser); the
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
  await page.getByRole('heading', {name: 'Start a workspace'}).waitFor();
  await page.waitForTimeout(1500);
  f.mark();
  await h.hover(page.getByRole('heading', {name: 'From a data folder'}), 900);
  const chooser = page.waitForEvent('filechooser');
  await h.click(page.getByRole('button', {name: 'Open folder'}), {after: 100});
  await (await chooser).setFiles(folder);
  await page.getByRole('heading', {name: 'Animus', level: 1}).waitFor();
  await h.result(2200);
  await h.hover(page.getByText(/files were upgraded to schemaVersion/), 1800);
  await h.click(page.getByRole('link', {name: 'Send to a server'}), {after: 900});
  await h.click(page.getByRole('button', {name: 'Compare with Animus fake server'}), {after: 900});
  await page.getByText(/\d+ new, \d+ different/).waitFor();
  await h.hover(page.getByText(/\d+ new, \d+ different/), 1800);
  await h.hover(page.getByRole('group', {name: 'Different on the server'}).getByRole('radio', {name: 'Keep the server copy'}), 1800);
  return L.finish(f, {closing: 'The town folder is a workspace; Compare lists what is new'});
};
