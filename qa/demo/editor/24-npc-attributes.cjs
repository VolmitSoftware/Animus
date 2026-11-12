// Adds scale from the searchable attribute dropdown and sets it to 2; the
// stage draws the Baker twice as big.
// Fake server data: --data=../qa/demo/editor/fixtures/town-deep (from animus-web/).
module.exports = async (L) => {
  const {film: f, page, h} = await L.pairedSession('24-npc-attributes');
  const side = page.getByRole('complementary', {name: 'Documents'});
  await page.waitForTimeout(1500);
  f.mark();
  await h.pause(300);
  await h.click(side.getByText('Baker', {exact: true}).first(), {after: 1200});
  const at = L.section(page, 'attributes');
  const add = at.getByRole('combobox', {name: 'Attribute to add'});
  // Brings the part up the page so the dropdown opens downward.
  await h.reveal(L.section(page, 'behaviour').getByText('Add a behavior', {exact: true}));
  await h.hover(at.getByText(/No attributes set/), 900);
  await h.click(add, {after: 800});
  const list = await L.listboxOf(add);
  await h.hover(list.getByRole('option').nth(1), 700);
  await h.hover(list.getByRole('option').nth(3), 900);
  await page.keyboard.type('scale', {delay: L.slow(110)});
  await h.pause(900);
  await h.click(L.option(page, 'scale'), {after: 1000});
  // Attribute rows are keyed by data-attribute; the row itself has no name.
  const scale = at.locator('[data-attribute="scale"]');
  await h.hover(scale.getByText(/vanilla 1/), 900);
  await h.type(scale.getByLabel('scale value'), '2', {clear: true, after: 300});
  await page.keyboard.press('Tab');
  await h.pause(600);
  await h.moveTo(page.getByText('2 times the vanilla size'));
  await h.result(2400);
  return L.finish(f, {closing: 'Baker has scale 2 and the stage draws him twice as big'});
};
