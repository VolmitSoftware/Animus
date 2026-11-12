// The Appearance skin picker: a workspace skin by its face, the arm model,
// then a built-in skin, with the stage wearing each one.
// The new skin documents (the classic copy and Smith) are not on the fake server
// yet, so its lookup of each answers 404 once; that is expected.
// Fake server data: --data=../qa/demo/editor/fixtures/town-deep (from animus-web/).
module.exports = async (L) => {
  const {film: f, page, h} = await L.pairedSession('22-npc-appearance-skin');
  const side = page.getByRole('complementary', {name: 'Documents'});
  await page.waitForTimeout(1500);
  f.mark();
  await h.pause(300);
  await h.click(side.getByText('Baker', {exact: true}).first(), {after: 1200});
  const ap = L.section(page, 'appearance');
  const pick = ap.getByRole('combobox', {name: 'Skin', exact: true});
  await h.reveal(ap.getByRole('group', {name: 'Built-in skins'}));
  await h.click(pick, {after: 700});
  const skin = (await L.listboxOf(pick)).getByRole('option', {name: /^dc33741fb113/});
  await h.hover(skin, 1000);
  await h.click(skin, {after: 900});
  await h.result(2200);
  const slim = ap.getByRole('checkbox', {name: /Slim arms/});
  await h.hover(slim, 600);
  await h.click(slim, {after: 900});
  await h.result(2000);
  await h.click(ap.getByRole('button', {name: 'Wear the built-in Smith skin'}), {after: 1000});
  await h.result(2400);
  return L.finish(f, {closing: 'Baker wears a workspace skin, then the built-in Smith skin'});
};
