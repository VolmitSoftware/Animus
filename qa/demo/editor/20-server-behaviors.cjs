// The Server behavior switch (Advanced): town:bells becomes server-wide and its
// trigger events change from NPC events to player and server events; then the
// server behaviors list is made from New document with every server behavior.
// Offline: no fake server needed. fixture: fixtures/harbor-server (opened as a
// data folder before filming).
const path = require('node:path');

module.exports = async (L) => {
  const f = await L.session({id: '20-server-behaviors'});
  await f.context.addInitScript(() => { try { localStorage.setItem('animus.mode', 'advanced'); } catch (e) {} });
  await f.setup.goto(`${L.SITE}/#/`);
  const chooser = f.setup.waitForEvent('filechooser', {timeout: 20000});
  await L.shell.startAction(f.setup, 'Open folder');
  await (await chooser).setFiles(path.join(__dirname, 'fixtures', 'harbor-server'));
  await L.shell.workspaceOpen(f.setup);
  const ws = await L.workspaceHash(f.setup);
  await f.setup.waitForTimeout(1200);
  const page = await f.start(`${L.SITE}/${ws}/behavior/town%3Abells`);
  const h = L.human(f);
  await page.getByRole('heading', {name: 'Bells', level: 1}).waitFor();
  await L.clearToasts(page);
  // Parts is a native disclosure (details); closing it before filming keeps the switch and the triggers on one screen.
  await page.getByRole('group').filter({hasText: /^Parts/}).locator('summary').first().click();
  await page.mouse.move(f.pos.x, f.pos.y);
  await page.waitForTimeout(1500);
  const event = page.getByRole('combobox', {name: 'Event of trigger 1'});
  f.mark();
  await h.click(event, {after: 900});
  await h.hover(L.optionLabelled(page, 'On click'), 900);
  await h.result(1500);
  await h.key('Escape', 500);
  const server = page.getByRole('switch', {name: 'Run on the server'});
  await h.hover(server, 900);
  await h.click(server, {after: 1000});
  await h.click(event, {after: 900});
  await h.hover(L.optionLabelled(page, 'Player joins'), 900);
  await h.result(1800);
  await h.key('Escape', 500);
  await page.evaluate(() => document.activeElement && document.activeElement.blur());
  await h.key('n', 900);
  await h.pick(page.getByRole('combobox', {name: 'Kind'}), 'server-behaviors', {after: 900});
  await h.hover(page.getByText(/^Starts with \d+ server behaviors/), 1500);
  await h.click(page.getByRole('main').getByRole('button', {name: 'Create', exact: true}), {after: 1200});
  const listed = page.getByRole('list', {name: 'Listed server behaviors'});
  await listed.waitFor();
  await h.moveTo(listed.getByText('town:bells'), {ox: 1.4});
  await h.result(2400);
  return L.finish(f, {closing: 'town:bells runs server-wide and is listed in server-behaviors.json'});
};
