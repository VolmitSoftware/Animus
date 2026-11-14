// Creates an NPC from the NPC list's New NPC, gives it a saved skin and a position,
// then turns its 3D preview.
// Fake server data: --data=test/fixtures/npc_browser (from animus-web/).
// The new NPC is not on the fake server yet, so the stage's terrain lookup
// (/animus/v1/terrain/position) answers 404 each time the stage asks (about ten
// times in a take); that is expected.
module.exports = async (L) => {
  const {film: f, page, h} = await L.pairedSession('02-create-npc', {path: '/npcs'});
  await page.getByRole('listbox', {name: 'NPCs'}).waitFor();
  await page.waitForTimeout(800);
  f.mark();
  await h.pause(300);
  await h.click(page.getByRole('button', {name: 'New NPC'}), {after: 800});
  const dialog = page.getByRole('dialog', {name: 'New NPC'});
  await h.type(dialog.getByRole('textbox', {name: 'Name'}), 'Harbor Lookout');
  await h.hover(dialog.getByRole('combobox', {name: 'Body'}), 900);
  await h.click(dialog.getByRole('button', {name: 'Create', exact: true}), {after: 1200});
  await page.getByRole('heading', {name: 'Harbor Lookout', level: 1}).waitFor();
  await h.result(1600);
  const basics = page.getByRole('tabpanel', {name: 'Basics'});
  await h.click(basics.getByRole('toolbar', {name: 'Skin'}).getByRole('button', {name: 'Change'}), {after: 900});
  const skins = page.getByRole('dialog', {name: 'Choose a skin'});
  await h.click(skins.getByRole('listbox', {name: 'Saved skins'}).getByRole('option').first(), {after: 1200, noReveal: true});
  await h.type(basics.getByRole('spinbutton', {name: 'X'}), '12', {clear: true, after: 300});
  await h.key('Tab', 900);
  await h.result(1600);
  const stage = page.getByRole('img', {name: 'Preview of Harbor Lookout'});
  await h.moveTo(stage, {ox: 0.5, oy: 0.55});
  const {x, y} = f.pos;
  await page.mouse.down();
  await h.glide(x + 140, y + 10);
  await h.glide(x - 140, y - 10);
  await h.glide(x + 20, y);
  await page.mouse.up();
  await h.result(2000);
  return L.finish(f, {closing: 'Harbor Lookout is new, wearing a saved skin, shown in the preview'});
};
