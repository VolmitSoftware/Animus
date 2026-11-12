// Equipment as inventory slots: armor down the left, hands below. Picks a
// helmet, a chestplate and a stack of bread in the main hand.
// Fake server data: --data=../qa/demo/editor/fixtures/town-deep (from animus-web/).
module.exports = async (L) => {
  const {film: f, page, h} = await L.pairedSession('23-npc-equipment-inventory');
  const side = page.getByRole('complementary', {name: 'Documents'});
  await page.waitForTimeout(1500);
  f.mark();
  await h.pause(300);
  await h.click(side.getByText('Baker', {exact: true}).first(), {after: 1200});
  const ap = L.section(page, 'appearance');
  const slots = ap.getByRole('group', {name: 'Equipment slots'});
  await h.reveal(slots);
  await h.hover(slots, 900);
  /** Clicks [slot], types [text] into its item field and picks [item] from the suggestions. */
  const equip = async (slot, field, text, item) => {
    await h.click(slots.getByRole('button', {name: new RegExp(`^${slot},`)}), {after: 600});
    const input = ap.getByRole('combobox', {name: field, exact: true});
    await h.click(input, {after: 200});
    await input.pressSequentially(text, {delay: L.slow(80)});
    await h.pause(700);
    await h.click(page.getByRole('option', {name: new RegExp(`^${item}`)}).first(), {after: 900});
  };
  await equip('Head', 'equipment.head', 'golden_hel', 'minecraft:golden_helmet');
  await h.result(1600);
  await equip('Chest', 'equipment.chest', 'leather_ches', 'minecraft:leather_chestplate');
  await h.result(1600);
  await equip('Main hand', 'equipment.main_hand', 'bread', 'minecraft:bread');
  const more = ap.getByRole('button', {name: 'One more'});
  await h.click(more, {after: 350});
  await h.click(more, {after: 900});
  await h.hover(slots, 600);
  await h.result(2400);
  return L.finish(f, {closing: 'Baker wears a golden helmet and a leather tunic, holding 3 bread'});
};
