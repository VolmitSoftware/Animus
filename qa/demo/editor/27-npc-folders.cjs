// The NPC Browse folder tree: a new folder inside town/, market/ renamed to
// bazaar/, then harbor/ moved into town/ with its NPCs.
// Fake server data: --data=../qa/demo/editor/fixtures/town-deep (from animus-web/).
module.exports = async (L) => {
  const {film: f, page, h} = await L.pairedSession('27-npc-folders');
  const side = page.getByRole('complementary', {name: 'Documents'});
  await page.waitForTimeout(1500);
  f.mark();
  await h.pause(300);
  await h.click(side.getByRole('link', {name: 'Browse'}).first(), {after: 1000});
  const tree = page.getByRole('tree', {name: 'Folders'});
  const acts = page.getByRole('group', {name: 'Folder actions'});
  const folder = (path) => tree.getByRole('treeitem', {name: new RegExp(`^npcs/${path}/,`)});
  await h.click(folder('town'), {after: 1000});
  await h.click(acts.getByRole('button', {name: 'New folder'}), {after: 700});
  await h.type(acts.getByRole('textbox', {name: 'New folder in npcs/town/'}), 'square', {after: 300});
  await h.click(acts.getByRole('button', {name: 'Make folder'}), {after: 900});
  await h.hover(folder('town/square'), 1200);
  await h.click(folder('town/market'), {after: 900});
  await h.click(acts.getByRole('button', {name: 'Rename'}), {after: 700});
  await h.type(acts.getByRole('textbox', {name: 'New name for npcs/town/market/'}), 'bazaar', {clear: true, after: 300});
  await h.click(acts.getByRole('button', {name: 'Rename folder'}), {after: 900});
  await h.result(1800);
  await h.click(folder('harbor'), {after: 900});
  await h.click(acts.getByRole('button', {name: 'Move'}), {after: 700});
  const into = acts.getByRole('combobox', {name: 'Move npcs/harbor/ into'});
  await h.click(into, {after: 700});
  await h.click((await L.listboxOf(into)).getByRole('option', {name: 'town/', exact: true}), {after: 700});
  await h.click(acts.getByRole('button', {name: 'Move folder'}), {after: 1000});
  await h.hover(folder('town/harbor'), 600);
  await h.result(2400);
  return L.finish(f, {closing: 'town/ now holds square/, bazaar/ and harbor/ with its NPCs'});
};
