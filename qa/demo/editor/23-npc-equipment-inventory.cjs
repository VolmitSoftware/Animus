// Equipment as inventory slots on the Look tab: armor down the left, hands
// below. Picks a helmet, a chestplate and a stack of bread in the main hand.
// Fake server data: --data=../qa/demo/editor/fixtures/town-deep (from animus-web/).
module.exports = async (L) => {
  const {film: f, page, h} = await L.pairedSession('23-npc-equipment-inventory', {path: '/npcs'});
  const list = page.getByRole('listbox', {name: 'NPCs'});
  await list.waitFor();
  await page.waitForTimeout(1200);
  f.mark();
  await h.pause(300);
  await h.click(list.getByRole('option', {name: /^Baker/}), {after: 1400});
  await h.click(page.getByRole('tablist', {name: 'NPC sections'}).getByRole('tab', {name: 'Look'}), {after: 900});
  const slots = page.getByRole('group', {name: 'Equipment slots'});
  await h.hover(slots, 900);
  /** Clicks [slot], types [text] into its item field and picks [item] from the suggestions. */
  const equip = async (slot, field, text, item) => {
    await h.click(slots.getByRole('button', {name: new RegExp(`^${slot},`)}), {after: 600});
    const input = page.getByRole('combobox', {name: field, exact: true});
    await h.click(input, {after: 200});
    await input.pressSequentially(text, {delay: L.slow(80)});
    await h.pause(700);
    await h.click(page.getByRole('listbox', {name: `Items for ${field}`}).getByRole('option', {name: item, exact: true}), {after: 900, noReveal: true});
  };
  await equip('Head', 'equipment.head', 'golden_hel', 'Golden helmet');
  await h.result(1600);
  await equip('Chest', 'equipment.chest', 'leather_ches', 'Leather chestplate');
  await h.result(1600);
  await equip('Main hand', 'equipment.main_hand', 'bread', 'Bread');
  const more = page.getByRole('group', {name: 'Count'}).getByRole('button', {name: 'One more'});
  await h.click(more, {after: 350});
  await h.click(more, {after: 900});
  await h.hover(slots, 600);
  await h.moveTo(page.getByRole('img', {name: /^Preview of Baker/}), {ox: 0.82, oy: 0.7});
  await h.result(2400);
  return L.finish(f, {closing: 'Baker wears a golden helmet and a leather chestplate, holding 3 bread'});
};
