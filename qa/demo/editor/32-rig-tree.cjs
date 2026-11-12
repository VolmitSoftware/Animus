// The rig's bone tree: adds a child bone under the left arm, renames it,
// then moves the name tag under the head. Offline: the knight is imported
// unfilmed into a blank workspace (see rig.cjs); no fake server needed.
const R = require('./rig.cjs');

module.exports = async (L) => {
  const {film: f, page, h} = await R.knightSession(L, '32-rig-tree');
  const tree = page.getByRole('tree', {name: /^Bones/});
  const bone = (name) => tree.getByRole('treeitem').filter({has: page.getByText(name, {exact: true})});
  f.mark();
  await h.pause(400);
  await h.click(bone('arm_l'), {after: 900});
  await h.click(page.getByRole('button', {name: 'Add child'}), {after: 600});
  await h.result(1800);
  await h.type(page.getByLabel('Bone name'), 'shield', {clear: true, after: 400});
  // The document header has its own Rename; the tree's sits in the tree pane, which has no name.
  await h.click(page.locator('.animus-rig-tree').getByRole('button', {name: 'Rename', exact: true}), {after: 600});
  await h.result(1800);
  await h.click(bone('tag_name'), {after: 900});
  const parent = page.getByRole('combobox', {name: 'Parent'});
  await h.click(parent, {after: 800});
  await h.click(L.option(page, 'head'), {after: 700});
  await h.hover(bone('tag_name'), 400);
  await h.result(2300);
  return L.finish(f, {closing: 'Added shield under arm_l and moved tag_name under head'});
};
