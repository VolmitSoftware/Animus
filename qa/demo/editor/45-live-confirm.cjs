// Pushing a local workspace to a paired server: the server holds the mayor differently, so the push
// asks first and names what it replaces, then the server applies it.
// Needs a real server (ANIMUS_PAIR_LINK, default scopes) on the town stage. Before filming it makes a
// blank workspace, pulls town/mayor from the server and renames it there, so the copies differ.
module.exports = async (L) => {
  const f = await L.session({id: '45-live-confirm'});
  const page = await f.start();
  page.setDefaultTimeout(30000);
  await pairReal(L, page);
  await page.goto(`${L.SITE}/#/`);
  await page.getByLabel('Workspace name').fill('Town edits');
  await page.getByRole('button', {name: 'Create blank workspace'}).click();
  await page.getByRole('heading', {name: 'New document'}).waitFor();
  const ws = /#\/workspace\/([^/?]+)/.exec(page.url())[1];
  await page.goto(`${L.SITE}/#/workspace/${ws}/sync`);
  // The connection pill has no accessible name of its own; it is found by class and phase.
  await page.locator('.animus-pill[data-phase="online"]').waitFor();
  await page.getByRole('button', {name: 'Load the server catalog'}).click();
  await page.getByRole('checkbox', {name: 'npc town/mayor'}).check();
  await page.getByRole('button', {name: 'Compare 1 selected'}).click();
  await page.getByRole('button', {name: 'Pull 1 document'}).click();
  await page.getByRole('complementary', {name: 'Documents'}).getByText('town/mayor').first().waitFor();
  await page.goto(`${L.SITE}/#/workspace/${ws}/npc/${encodeURIComponent('town/mayor')}`);
  await page.getByRole('tab', {name: 'Code'}).click();
  // The code view is a plain textarea inside .animus-code with no label of its own.
  const area = page.locator('.animus-code textarea');
  const json = JSON.parse(await area.inputValue());
  await area.fill(JSON.stringify({...json, name: '&6Mayor Elena'}, null, 2));
  await page.getByRole('button', {name: 'Apply to draft'}).click();
  await page.getByRole('button', {name: 'Apply to draft'}).and(page.locator('[disabled]')).waitFor();
  await page.goto(`${L.SITE}/#/workspace/${ws}/sync`);
  await page.locator('.animus-pill[data-phase="online"]').waitFor();
  await dismissToasts(page);
  await page.waitForTimeout(1200);
  const h = L.human(f);
  f.mark();
  await h.pause(300);
  await h.click(page.getByRole('button', {name: /^Compare with/}), {after: 300});
  const differs = page.getByText('Different on the server', {exact: true});
  await differs.waitFor();
  await h.moveTo(differs, {ox: 1.15});
  await h.pause(400);
  await h.result(1800);
  await h.click(page.getByRole('radio', {name: 'Replace it with mine'}), {after: 300});
  await h.click(page.getByRole('button', {name: /^Validate \d+ on the server/}), {after: 300});
  const accepted = page.getByText('The server accepts this selection.');
  await accepted.waitFor();
  await h.moveTo(accepted, {ox: 1.06});
  await h.pause(300);
  await h.result(1800);
  await h.type(page.getByLabel('Change message', {exact: true}), 'Rename the mayor', {after: 400});
  await h.click(page.getByRole('button', {name: /^Push \d+ document/}), {after: 300});
  const confirm = page.getByRole('alertdialog');
  await confirm.getByText(/^Replace 1 server copy with yours\?/).waitFor();
  // Under the question, so the cursor does not cover what it replaces.
  await h.moveTo(confirm.getByText(/get your version on the server/), {ox: 0.3, oy: 2.2});
  await h.pause(400);
  await h.result(2500);
  await h.click(confirm.getByRole('button', {name: 'Replace and push'}), {after: 300});
  // The push result line has no role or label; it is the page's success line.
  const done = page.locator('.animus-ok', {hasText: /^Applied 1 document as change/});
  await done.waitFor();
  await h.moveTo(done, {ox: 1.04});
  await h.pause(300);
  await h.result(2500);
  return L.finish(f, {closing: 'The push asked first, then replaced the mayor on the server'});
};

/** Pairs with the real server from ANIMUS_PAIR_LINK (unfilmed) and waits until it is online. */
async function pairReal(L, page) {
  const link = (process.env.ANIMUS_PAIR_LINK || '').trim();
  if (!link) throw new Error('45-live-confirm needs a real server: pass --pair=<link>');
  await L.pair(page, {code: link.slice(link.indexOf('#/pair/') + 7), remember: false});
  await page.locator('.animus-live .animus-pill[data-phase="online"]').waitFor();
  await page.getByText(/documents on the server/).waitFor();
}

/** Closes the pairing and open toasts (L.dismiss can hit a document's own Close button). */
async function dismissToasts(page) {
  const close = page.getByRole('region', {name: 'Notifications'}).getByRole('button', {name: 'Dismiss'});
  while (await close.count()) await close.first().click();
}
