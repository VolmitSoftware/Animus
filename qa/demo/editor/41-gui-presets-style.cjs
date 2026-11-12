// The presets file's defaults and named presets, and inlining a preset into a text animation that names one.
// Fake server data: --data=test/fixtures/gui_editors
module.exports = async (L) => {
  const {film: f, page} = await L.pairedSession('41-gui-presets-style');
  const h = L.panelHuman(f);
  await page.getByText('presets', {exact: true}).first().click();
  await page.getByRole('region', {name: 'Presets'}).waitFor();
  await L.dismiss(page);
  await L.dismiss(page);
  await page.waitForTimeout(2500);
  await page.evaluate(() => window.scrollTo(0, 0));
  f.mark();
  const presets = page.getByRole('region', {name: 'Presets'});
  await h.hover(presets.getByRole('combobox', {name: 'Kind'}), 1100);
  await h.hover(presets.getByRole('spinbutton', {name: 'frameIntervalMs'}), 1300);
  await h.click(presets.getByText('glow', {exact: true}), {after: 1000});
  await h.result(2000);
  await h.click(page.getByText('town:sparkle', {exact: true}).first(), {after: 1500});
  const visual = page.getByRole('tabpanel', {name: 'Visual'});
  const fix = visual.getByRole('alert').filter({hasText: '$.preset'});
  await h.hover(fix, 1600);
  await h.click(fix.getByRole('button', {name: 'Inline preset'}), {after: 1200});
  await h.hover(visual.getByRole('combobox', {name: 'Mode *'}), 900);
  await h.hover(visual.getByRole('spinbutton', {name: 'Frame interval in milliseconds'}), 900);
  await h.result(1800);
  return L.finish(f, {closing: 'town:sparkle now holds the glow preset fields itself'});
};
