// A hologram's lines beside the stage, then a new text line that plays the
// town:sparkle text animation on both sides of its text.
// Fake server data: --data=test/fixtures/gui_editors (from animus-web/).
module.exports = async (L) => {
  const {film: f, page, h} = await L.pairedSession('39-hologram-animation', {path: '/list/hologram'});
  await page.getByRole('listbox', {name: 'Holograms'}).waitFor();
  await page.waitForTimeout(1200);
  f.mark();
  await h.pause(300);
  await h.click(page.getByRole('listbox', {name: 'Holograms'}).getByRole('option', {name: /^Welcome to town/}), {after: 1500});
  const holo = page.getByRole('region', {name: 'Hologram'});
  const stage = page.getByRole('img', {name: 'Hologram town:welcome'});
  await h.moveTo(stage, {ox: 0.8, oy: 0.3});
  await h.pause(1300);
  await h.hover(holo.getByRole('combobox', {name: 'Line 1'}), 900);
  await h.hover(holo.getByRole('combobox', {name: 'Line 3 item'}), 900);
  // A block line picks its block by name, like an item.
  await h.hover(holo.getByRole('combobox', {name: 'Line 4 block'}), 900);
  await h.click(holo.getByRole('toolbar', {name: 'Add line'}).getByRole('button', {name: 'Add line'}), {after: 900});
  const line = holo.getByRole('combobox', {name: 'Line 6'});
  await h.type(line, '|animation.sp', {clear: true, after: 600});
  await h.click(page.getByRole('listbox').getByText('sparkle', {exact: true}).first(), {after: 900, noReveal: true});
  await line.first().press('End');
  await line.first().pressSequentially(' &aMarket open |animation.sparkle|', {delay: L.slow(60)});
  await h.pause(L.TYPED_HOLD_MS);
  await h.key('Escape', 300);
  await h.moveTo(stage, {ox: 0.8, oy: 0.3});
  await h.result(2500);
  await h.moveTo(stage, {ox: 0.85, oy: 0.5});
  await h.result(2000);
  return L.finish(f, {closing: 'The new line plays town:sparkle on both sides of Market open'});
};
