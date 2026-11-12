// The Server behavior switch: town:bells becomes server-wide and its trigger
// list changes to player and server events; then server-behaviors.json is
// created from the New page with every server behavior in the workspace.
// Offline: no fake server needed. fixture: fixtures/harbor-server (opened as a
// data folder before filming).
const path = require('node:path');

module.exports = async (L) => {
  const f = await L.session({id: '20-server-behaviors'});
  await f.setup.goto(`${L.SITE}/#/`);
  const chooser = f.setup.waitForEvent('filechooser');
  await f.setup.getByRole('button', {name: 'Open folder'}).click();
  await (await chooser).setFiles(path.join(__dirname, 'fixtures', 'harbor-server'));
  await f.setup.waitForFunction(() => location.hash.startsWith('#/workspace/'));
  const ws = f.setup.url().split('#')[1].split('/').slice(0, 3).join('/');
  const page = await f.start(`${L.SITE}/#${ws}/behavior/town%3Abells`);
  const h = L.human(f);
  await page.getByRole('tab', {name: 'Visual'}).click();
  await page.getByRole('region', {name: 'Behavior editor'}).waitFor();
  // Section headers are <summary> elements with no accessible name. Closing
  // Routines before filming keeps the switch and the triggers on one screen.
  await page.locator('summary', {hasText: /^Routines/}).click();
  await page.evaluate(() => window.scrollTo(0, 0));
  await page.waitForTimeout(1500);
  const trigger = page.getByRole('combobox', {name: 'Add a trigger'});
  f.mark();
  await h.click(trigger, {after: 900});
  await h.result(1600);
  await page.keyboard.press('Escape');
  await h.pause(400);
  const server = page.getByRole('checkbox', {name: 'Run on the server with no NPC'});
  await h.hover(server, 900);
  await h.click(server, {after: 1000});
  await h.click(trigger, {after: 900});
  await h.hover(page.getByRole('option', {name: 'player_join', exact: true}), 600);
  await h.result(1800);
  await page.keyboard.press('Escape');
  await h.pause(400);
  await h.click(page.getByRole('link', {name: 'New document'}), {after: 900});
  await h.click(page.getByRole('link', {name: /^Server behaviors list/}), {after: 900});
  await h.hover(page.getByText(/^Creates server-behaviors\.json with/), 1500);
  await h.click(page.getByRole('button', {name: 'Create server behaviors list'}), {after: 1200});
  await h.result(2400);
  return L.finish(f, {closing: 'town:bells runs server-wide and is listed in server-behaviors.json'});
};
