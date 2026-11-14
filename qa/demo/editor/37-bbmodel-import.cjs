// Imports qa/demo/stage/assets/knight.bbmodel into a blank workspace from the
// top bar's More actions, in Advanced: the rig opens with its bones and six
// animations, and the import says it left the Model Engine hitbox out.
// Offline: the blank workspace is made unfilmed; no fake server needed. The
// file chooser is answered by Playwright, so no system dialog shows.
const R = require('./rig.cjs');

module.exports = async (L) => {
  const f = await L.session({id: '37-bbmodel-import'});
  const ws = await L.blankWorkspace(f.setup, 'Town');
  const page = await f.start(`${L.SITE}/${ws}`);
  await page.locator('#animus-main').waitFor();
  await L.clearToasts(page);
  await page.waitForTimeout(1000);
  const h = L.human(f);
  f.mark();
  await h.pause(500);
  await h.mode('advanced');
  await h.click(page.getByRole('banner').getByRole('button', {name: 'More actions'}), {after: 700, noReveal: true});
  const chooser = page.waitForEvent('filechooser');
  await h.click(page.getByRole('menuitem', {name: 'Import Blockbench model'}), {after: 300, noReveal: true});
  await h.pause(700);
  await (await chooser).setFiles(R.KNIGHT);
  // The rig editor names its rig only as data-rig.
  await page.locator('.animus-rig-editor[data-rig="animus:knight"]').waitFor();
  await R.drawn(page);
  const notes = page.getByRole('region', {name: 'Notifications'});
  // Each notice is pointed at from its left, so the cursor does not cover its text.
  await h.moveTo(notes.getByText(/^Imported knight\.bbmodel as animus:knight/), {ox: -0.14});
  await h.result(2300);
  await h.moveTo(notes.getByText(/^Group hitbox is the Model Engine hitbox/), {ox: -0.14});
  await h.result(2300);
  await h.hover(page.getByRole('tree', {name: /^Bones/}), 900);
  const pick = page.getByRole('toolbar', {name: 'Animation'}).getByRole('combobox', {name: 'Animation'});
  await h.click(pick, {after: 900});
  const list = await L.listboxOf(pick);
  await h.hover(list.getByRole('option').nth(3), 700);
  await h.hover(list.getByRole('option').nth(5), 900);
  await h.result(1800);
  await h.key('Escape', 500);
  // Rests on the stage's grass, beside the knight rather than over it.
  await h.moveTo(page.getByRole('img', {name: /^Rig animus:knight/}), {ox: 0.22, oy: 0.82});
  await h.pause(300);
  await h.result(2000);
  return L.finish(f, {closing: 'knight.bbmodel is a rig with 9 bones and 6 animations, without its hitbox'});
};
