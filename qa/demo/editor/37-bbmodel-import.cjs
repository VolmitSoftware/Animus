// Imports qa/demo/stage/assets/knight.bbmodel into a blank workspace: the
// rig opens with its bones and six animations, and the hitbox bone the
// import warns about is deleted so the stage shows the knight. Offline: the
// blank workspace is made unfilmed; no fake server needed. The file chooser
// is answered by Playwright, so no system dialog shows.
const R = require('./rig.cjs');

module.exports = async (L) => {
  const f = await L.session({id: '37-bbmodel-import'});
  await R.blankWorkspace(L, f.setup);
  const page = await f.start(f.setup.url());
  const importMenu = page.getByRole('group', {name: 'Files'}).getByRole('button', {name: 'Import'});
  await importMenu.waitFor();
  await R.clearToasts(page);
  await page.waitForTimeout(1000);
  const h = L.human(f);
  f.mark();
  await h.pause(500);
  await h.click(importMenu, {after: 700});
  const chooser = page.waitForEvent('filechooser');
  await h.click(page.getByRole('menuitem', {name: 'Import a Blockbench model'}), {after: 300});
  await h.pause(700);
  await (await chooser).setFiles(R.KNIGHT);
  await page.locator('.animus-rig-editor[data-rig="animus:knight"]').waitFor();
  await R.drawn(page);
  await h.hover(page.getByText(/^Imported knight\.bbmodel as animus:knight/), 400);
  await h.result(2300);
  await h.hover(page.getByText(/^Bone hitbox is larger than an item model allows/), 400);
  await h.result(2300);
  const sidebar = page.getByRole('complementary', {name: 'Documents'});
  await h.hover(sidebar.getByText('animus:knight/walk', {exact: true}), 400);
  await h.result(1600);
  const close = page.getByRole('button', {name: 'Close'}).or(page.getByRole('button', {name: '×'}));
  for (let i = 0; i < 4 && await close.count(); i++) await h.click(close.first(), {rest: 250, after: 400, noReveal: true});
  const tree = page.getByRole('tree', {name: /^Bones/});
  await h.click(tree.getByRole('treeitem').filter({has: page.getByText('hitbox', {exact: true})}), {after: 800});
  await h.click(page.getByRole('button', {name: 'Delete bone'}), {after: 900});
  await R.drawn(page);
  // Rests on the stage's grass, beside the knight rather than over it.
  await h.moveTo(page.getByRole('img', {name: /^Rig animus:knight/}), {ox: 0.22, oy: 0.82});
  await h.pause(300);
  await h.result(2500);
  return L.finish(f, {closing: 'knight.bbmodel is a rig with 6 animations, minus its hitbox bone'});
};
