// Edits an NPC: a saved skin on Basics, a helmet and a sword in the Look tab's
// equipment slots, then scale 2 under Advanced's Attributes.
// Fake server data: --data=test/fixtures/npc_browser (from animus-web/).
module.exports = async (L) => {
  const {film: f, page, h} = await L.pairedSession('03-edit-npc', {path: '/npcs'});
  const list = page.getByRole('listbox', {name: 'NPCs'});
  await list.waitFor();
  await page.waitForTimeout(800);
  f.mark();
  await h.pause(300);
  await h.click(list.getByRole('option', {name: /^Baker/}), {after: 1200});
  await page.getByRole('heading', {name: 'Baker', level: 1}).waitFor();
  await h.result(1500);
  const basics = page.getByRole('tabpanel', {name: 'Basics'});
  await h.click(basics.getByRole('toolbar', {name: 'Skin'}).getByRole('button', {name: 'Change'}), {after: 900});
  const skins = page.getByRole('dialog', {name: 'Choose a skin'});
  await h.click(skins.getByRole('listbox', {name: 'Saved skins'}).getByRole('option').first(), {after: 1400, noReveal: true});
  const tabs = page.getByRole('tablist', {name: 'NPC sections'});
  await h.click(tabs.getByRole('tab', {name: 'Look'}), {after: 900});
  const look = page.getByRole('tabpanel', {name: 'Look'});
  const slots = look.getByRole('group', {name: 'Equipment slots'});
  // The item suggestions are the editor's own list; each option is named by the item.
  await h.click(slots.getByRole('button', {name: /^Head/}), {after: 600});
  await h.type(look.getByRole('combobox', {name: 'equipment.head'}), 'diamond_h', {after: 600});
  await h.click(page.getByRole('option', {name: 'Diamond helmet', exact: true}), {after: 1000, noReveal: true});
  await h.click(slots.getByRole('button', {name: /^Main hand/}), {after: 600});
  await h.type(look.getByRole('combobox', {name: 'equipment.main_hand'}), 'diamond_sw', {after: 600});
  await h.click(page.getByRole('option', {name: 'Diamond sword', exact: true}), {after: 1000, noReveal: true});
  await h.result(1800);
  await h.mode('advanced');
  await h.click(tabs.getByRole('tab', {name: 'Attributes'}), {after: 900});
  const attrs = page.getByRole('tabpanel', {name: 'Attributes'});
  await h.click(attrs.getByRole('combobox', {name: 'Add attribute'}), {after: 600});
  await page.keyboard.type('scale', {delay: L.slow(110)});
  await h.pause(700);
  await h.click(L.option(page, 'scale'), {after: 900, noReveal: true});
  await h.type(attrs.getByRole('spinbutton').first(), '2', {clear: true, after: 300});
  // Rest beside the field so the value and the preview read clearly.
  await h.moveTo(attrs.getByRole('spinbutton').first(), {ox: 1.9, noReveal: true});
  await h.result(2200);
  return L.finish(f, {closing: 'Baker wears a saved skin and diamond gear, at scale 2'});
};
