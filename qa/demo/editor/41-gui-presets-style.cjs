// The presets file's defaults and named presets, then Copy fields in on a
// text animation that names a preset, so Animus can read it.
// Fake server data: --data=test/fixtures/gui_editors (from animus-web/).
module.exports = async (L) => {
  const {film: f, page, h} = await L.pairedSession('41-gui-presets-style', {mode: 'advanced', path: '/list/inventory'});
  await page.getByRole('tab', {name: /^Presets/}).click();
  await page.getByRole('listbox').getByRole('option').first().waitFor();
  await page.waitForTimeout(1200);
  f.mark();
  await h.pause(300);
  await h.click(page.getByRole('listbox').getByRole('option').first(), {after: 1500});
  const presets = page.getByRole('region', {name: 'Presets'});
  // Rests just under the note, not over its text.
  await h.moveTo(presets.getByRole('alert'), {ox: 0.5, oy: 1.3});
  await h.pause(1300);
  await h.hover(presets.getByRole('combobox', {name: 'For'}), 1100);
  await h.hover(presets.getByRole('spinbutton', {name: 'frameIntervalMs'}), 1300);
  await h.hover(presets.getByRole('list', {name: 'Presets'}).getByText('glow', {exact: true}), 1200);
  await h.result(1800);
  await h.job('GUIs');
  await h.click(page.getByRole('tab', {name: /^Text animations/}), {after: 900});
  await h.click(page.getByRole('listbox').getByRole('option', {name: /town:sparkle/}), {after: 1500});
  const anim = page.getByRole('region', {name: 'Text animation'});
  const note = anim.getByRole('alert').filter({hasText: 'Uses preset glow'});
  await h.moveTo(note, {ox: 0.95});
  await h.pause(1600);
  await h.click(note.getByRole('button', {name: 'Copy fields in'}), {after: 1200});
  await h.hover(anim.getByRole('combobox', {name: 'Order'}), 900);
  await h.hover(anim.getByRole('spinbutton', {name: 'Each frame in milliseconds'}), 900);
  await h.moveTo(page.getByRole('region', {name: 'Preview'}), {ox: 0.75, oy: 0.4});
  await h.result(1800);
  return L.finish(f, {closing: 'town:sparkle now holds the glow preset fields itself'});
};
