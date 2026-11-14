// NPC folders in the Advanced NPC list: two guards moved to a new folder
// town/square, town/market renamed to bazaar, then harbor moved into town
// with its NPCs; the Folder filter lists the result.
// Fake server data: --data=../qa/demo/editor/fixtures/town-deep (from animus-web/).
module.exports = async (L) => {
  const {film: f, page, h} = await L.pairedSession('27-npc-folders', {path: '/npcs'});
  const list = page.getByRole('listbox', {name: 'NPCs'});
  await list.waitFor();
  await page.waitForTimeout(1200);
  f.mark();
  await h.pause(300);
  await h.mode('advanced');
  await h.hover(list.getByRole('option', {name: /^Baker/}), 900);
  // A folder exists while it holds NPCs: moving two guards to a typed path makes town/square.
  for (const name of [/^North Guard/, /^South Guard/]) {
    await h.click(list.getByRole('option', {name}).getByRole('checkbox'), {after: 400});
  }
  const picked = page.getByRole('toolbar', {name: 'Picked rows'});
  await h.click(picked.getByRole('button', {name: 'Folder', exact: true}), {after: 800});
  const move = page.getByRole('dialog', {name: /^Move 2 NPCs/});
  await h.type(move.getByRole('textbox', {name: 'Or a new folder'}), 'town/square', {after: 300});
  await h.click(move.getByRole('button', {name: 'Move', exact: true}), {after: 1000});
  await h.hover(list.getByRole('option', {name: /^North Guard/}), 900);
  await h.result(1600);
  const more = page.getByRole('toolbar', {name: 'Page actions'}).getByRole('button', {name: 'More actions'});
  await h.click(more, {after: 600});
  await h.click(page.getByRole('menuitem', {name: 'Rename folder'}), {after: 800, noReveal: true});
  const rename = page.getByRole('dialog', {name: 'Rename folder'});
  await h.pick(rename.getByRole('combobox', {name: 'Folder'}), 'town/market', {after: 600});
  await h.type(rename.getByRole('textbox', {name: 'New name'}), 'bazaar', {clear: true, after: 300});
  await h.click(rename.getByRole('button', {name: 'Rename', exact: true}), {after: 1000});
  await h.hover(list.getByRole('option', {name: /^Fishmonger/}), 900);
  await h.result(1600);
  await h.click(more, {after: 600});
  await h.click(page.getByRole('menuitem', {name: 'Move folder'}), {after: 800, noReveal: true});
  const moveFolder = page.getByRole('dialog', {name: 'Move folder'});
  await h.pick(moveFolder.getByRole('combobox', {name: 'Folder'}), 'harbor', {after: 600});
  await h.pick(moveFolder.getByRole('combobox', {name: 'Move into'}), 'town', {after: 600});
  await h.click(moveFolder.getByRole('button', {name: 'Move', exact: true}), {after: 1000});
  await h.hover(list.getByRole('option', {name: /^Ferryman/}), 900);
  const filter = page.getByRole('combobox', {name: 'Folder', exact: true});
  await h.click(filter, {after: 900});
  const folders = await L.listboxOf(filter);
  for (const value of ['town/square', 'town/bazaar', 'town/harbor']) {
    await h.hover(folders.locator(`[role="option"][data-arcane-value="${value}"]`), 700);
  }
  await h.click(folders.locator('[role="option"][data-arcane-value="town"]'), {after: 800, noReveal: true});
  // Rest beside the filter, off the rows.
  await h.moveTo(filter, {ox: 1.25});
  await h.result(2400);
  return L.finish(f, {closing: 'town/ now holds square/, bazaar/ and harbor/ with its NPCs'});
};
