// Adds scale (named Size) from the searchable attribute list on the Advanced
// Attributes tab and sets it to 2; the stage draws the Baker twice as big.
// Fake server data: --data=../qa/demo/editor/fixtures/town-deep (from animus-web/).
module.exports = async (L) => {
  const {film: f, page, h} = await L.pairedSession('24-npc-attributes', {path: '/npcs'});
  const list = page.getByRole('listbox', {name: 'NPCs'});
  await list.waitFor();
  await page.waitForTimeout(1200);
  f.mark();
  await h.pause(300);
  await h.click(list.getByRole('option', {name: /^Baker/}), {after: 1400});
  await h.mode('advanced');
  await h.click(page.getByRole('tablist', {name: 'NPC sections'}).getByRole('tab', {name: 'Attributes'}), {after: 900});
  const panel = page.getByRole('tabpanel', {name: 'Attributes'});
  await h.hover(panel.getByText(/^None set/), 900);
  const add = panel.getByRole('combobox', {name: 'Add attribute'});
  await h.click(add, {after: 900});
  const listbox = await L.listboxOf(add);
  await h.hover(listbox.getByRole('option').nth(1), 700);
  await h.hover(listbox.getByRole('option').nth(4), 900);
  await page.keyboard.type('scale', {delay: L.slow(110)});
  await h.pause(900);
  await h.click(L.option(page, 'scale'), {after: 1000, noReveal: true});
  const size = panel.getByRole('spinbutton', {name: 'Size'});
  await h.hover(panel.getByRole('paragraph').filter({hasText: 'vanilla 1'}), 900);
  await h.type(size, '2', {clear: true, after: 300});
  // Enter commits the value; Tab would focus the reset button and show its tooltip over the range.
  await page.keyboard.press('Enter');
  await h.pause(600);
  await h.moveTo(page.getByRole('img', {name: /^Preview of Baker/}), {ox: 0.85, oy: 0.75});
  await h.result(2400);
  return L.finish(f, {closing: 'Baker has scale 2 and the stage draws him twice as big'});
};
