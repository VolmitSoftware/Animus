// The rig's bone tree: adds a child bone under the left arm, renames it,
// then moves the name tag under the head. Offline: the knight is imported
// unfilmed into a blank workspace (see rig.cjs); no fake server needed.
const R = require('./rig.cjs');

module.exports = async (L) => {
  const {film: f, page, h} = await R.knightSession(L, '32-rig-tree');
  f.mark();
  await h.pause(400);
  await h.click(R.bone(page, 'arm_l'), {after: 900});
  await h.click(page.getByRole('toolbar', {name: 'Bones'}).getByRole('button', {name: 'Add child'}), {after: 900});
  await h.result(1600);
  await h.type(page.getByRole('textbox', {name: 'Name', exact: true}), 'shield', {clear: true, after: 300});
  await h.key('Enter', 900);
  await h.hover(R.bone(page, 'shield'), 600);
  await h.result(1600);
  await h.click(R.bone(page, 'tag_name'), {after: 900});
  // The parent list is long, so its search narrows it first.
  await h.click(page.getByRole('combobox', {name: 'Parent'}), {after: 700});
  await page.keyboard.type('head', {delay: L.slow(110)});
  await h.pause(600);
  await h.click(L.option(page, 'head'), {after: 900, noReveal: true});
  // Rest on the row between the bone's name and its kind.
  await h.moveTo(R.bone(page, 'tag_name'), {ox: 0.6});
  await h.pause(400);
  await h.result(2300);
  return L.finish(f, {closing: 'Added shield under arm_l and moved tag_name under head'});
};
