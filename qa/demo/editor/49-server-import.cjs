// Importing a Citizens save on a paired server: upload and stage, read the server's review,
// then Apply, which asks first, installs the import and spawns the NPCs.
// Needs a real server (ANIMUS_PAIR_LINK) paired with the import scope:
//   animus editor pair scopes=read,import,apply:npcs,apply:behaviors,apply:gui
// The save is the converter fixture schema/fixtures/citizens/looks.yml (20 NPCs in world "world").
// Roll the import back after a take (the panel's Roll back) before recording again.
const path = require('node:path');

module.exports = async (L) => {
  const save = path.join(L.REPO, 'schema', 'fixtures', 'citizens', 'looks.yml');
  const f = await L.session({id: '49-server-import'});
  const page = await f.start();
  page.setDefaultTimeout(30000);
  await pairReal(L, page);
  await page.goto(`${L.SITE}/#/citizens`);
  await page.getByRole('heading', {name: 'Import on a server'}).waitFor();
  await page.getByTestId('import-connection').filter({hasText: 'Online'}).waitFor();
  await dismissToasts(page);
  await page.waitForTimeout(1200);
  const h = L.human(f);
  f.mark();
  await h.pause(300);
  const chooser = page.waitForEvent('filechooser');
  await h.click(page.getByRole('button', {name: 'Choose saves.yml'}), {after: 100});
  await (await chooser).setFiles(save);
  await h.hover(page.getByText('looks.yml', {exact: true}), 300);
  await h.result(1500);
  const server = page.getByRole('region', {name: 'Import on a server'});
  await h.hover(server.getByTestId('import-connection'), 600);
  await h.click(server.getByRole('button', {name: /Upload and stage/}), {after: 300});
  await page.getByTestId('import-state').filter({hasText: 'Staged, not installed'}).waitFor();
  await h.hover(page.getByTestId('import-review'), 400);
  await h.result(2500);
  await h.click(server.getByRole('button', {name: /^\W*Apply$/}), {after: 300});
  const confirm = page.getByRole('alertdialog');
  await confirm.getByText('Install this import on the server?').waitFor();
  // Under the question, so the cursor does not cover what the install changes.
  await h.moveTo(confirm.getByText(/NPCs spawn as they were/), {ox: 0.3, oy: 2.2});
  await h.pause(400);
  await h.result(2500);
  await h.click(confirm.getByRole('button', {name: 'Install import'}), {after: 300});
  await page.getByTestId('import-state').filter({hasText: 'Installed and running'}).waitFor();
  await h.hover(page.getByTestId('import-state'), 300);
  await h.result(2000);
  await h.hover(page.getByTestId('import-next'), 400);
  await h.result(2000);
  return L.finish(f, {closing: 'Staged, reviewed and installed on the server: 20 NPCs'});
};

/** Pairs with the real server from ANIMUS_PAIR_LINK (unfilmed) and waits until it is online. */
async function pairReal(L, page) {
  const link = (process.env.ANIMUS_PAIR_LINK || '').trim();
  if (!link) throw new Error('49-server-import needs a real server: pass --pair=<link> with the import scope');
  await L.pair(page, {code: link.slice(link.indexOf('#/pair/') + 7), remember: false});
  // The connection pill has no accessible name of its own; it is found by class and phase.
  await page.locator('.animus-live .animus-pill[data-phase="online"]').waitFor();
}

/** Closes the pairing and open toasts (L.dismiss can hit a document's own Close button). */
async function dismissToasts(page) {
  const close = page.getByRole('region', {name: 'Notifications'}).getByRole('button', {name: 'Dismiss'});
  while (await close.count()) await close.first().click();
}
