// The skin picker behind Basics' Change: a saved skin by its face, then a
// built-in skin, with the stage wearing each one.
// The built-in skin makes a skin document the fake server does not have yet,
// so its lookup answers 404 once; that is expected.
// Fake server data: --data=../qa/demo/editor/fixtures/town-deep (from animus-web/).
module.exports = async (L) => {
  const {film: f, page, h} = await L.pairedSession('22-npc-appearance-skin', {path: '/npcs'});
  const list = page.getByRole('listbox', {name: 'NPCs'});
  await list.waitFor();
  await page.waitForTimeout(1200);
  f.mark();
  await h.pause(300);
  await h.click(list.getByRole('option', {name: /^Baker/}), {after: 1500});
  const skin = page.getByRole('toolbar', {name: 'Skin'});
  const stage = page.getByRole('img', {name: /^Preview of Baker/});
  const dialog = page.getByRole('dialog', {name: 'Choose a skin'});
  await h.hover(skin, 900);
  await h.click(skin.getByRole('button', {name: 'Change'}), {after: 900});
  const saved = dialog.getByRole('listbox', {name: 'Saved skins'}).getByRole('option').first();
  await h.hover(saved, 900);
  await h.click(saved, {after: 1200, noReveal: true});
  // Rest on the stage beside the model, not over it.
  await h.moveTo(stage, {ox: 0.82, oy: 0.7});
  await h.result(2200);
  await h.click(skin.getByRole('button', {name: 'Change'}), {after: 900});
  await h.click(dialog.getByRole('button', {name: 'Built-in', exact: true}), {after: 800, noReveal: true});
  const smith = dialog.getByRole('option', {name: 'Smith', exact: true});
  await h.hover(smith, 900);
  await h.click(smith, {after: 1200, noReveal: true});
  await h.hover(skin, 600);
  await h.moveTo(stage, {ox: 0.82, oy: 0.7});
  await h.result(2400);
  return L.finish(f, {closing: 'Baker wears a saved skin, then the built-in Smith skin'});
};
