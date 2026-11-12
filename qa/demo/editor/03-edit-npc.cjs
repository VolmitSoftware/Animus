// Edits an NPC's skin, equipment and attributes.
module.exports = async (L) => {
  const {film: f, page, h} = await L.pairedSession('03-edit-npc');
  await page.getByRole('navigation', {name: 'Documents by kind'}).getByRole('link', {name: /^Baker/}).click();
  await page.getByRole('heading', {name: 'Baker', level: 1}).waitFor();
  await page.waitForTimeout(1500);
  f.mark();
  await h.pause(300);
  const ap = L.section(page, 'appearance');
  const pick = ap.getByRole('combobox', {name: 'Skin', exact: true});
  await h.click(pick, {after: 800});
  // Skin option labels come from the fixture skins; the clip picks the workspace skin, the second one.
  const opts = (await L.listboxOf(pick)).getByRole('option');
  await h.click(opts.nth(1), {after: 900, noReveal: true});
  await h.result(1800);
  await h.click(ap.getByRole('button', {name: /^Head/}), {after: 600});
  await h.type(ap.getByLabel('equipment.head', {exact: true}), 'diamond_h', {after: 600});
  await h.click(page.getByText('minecraft:diamond_helmet', {exact: true}).first(), {after: 900, noReveal: true});
  await h.click(ap.getByRole('button', {name: /^Main hand/}), {after: 600});
  await h.type(ap.getByLabel('equipment.main_hand', {exact: true}), 'diamond_sw', {after: 600});
  await h.click(page.getByText('minecraft:diamond_sword', {exact: true}).first(), {after: 900, noReveal: true});
  await h.result(1800);
  const at = L.section(page, 'attributes');
  await h.click(at.getByRole('combobox', {name: 'Attribute to add'}), {after: 600});
  await page.keyboard.type('scale', {delay: L.slow(110)});
  await h.pause(700);
  await h.click(L.option(page, 'scale'), {after: 900, noReveal: true});
  // Attribute rows are keyed by data-attribute; the row itself has no name.
  const scale = at.locator('[data-attribute="scale"]');
  await h.type(scale.getByLabel('scale value'), '2', {clear: true, after: 300});
  await h.key('Tab', 600);
  await h.result(2200);
  return L.finish(f, {closing: 'Baker now wears the workspace skin, diamond gear and scale 2'});
};
