// A hologram's rows on the stage, then a text row that plays the town:sparkle text animation.
// Fake server data: --data=test/fixtures/gui_editors
module.exports = async (L) => {
  const {film: f, page} = await L.pairedSession('39-hologram-animation');
  const h = L.panelHuman(f);
  await page.getByText('town:welcome', {exact: true}).first().click();
  await page.getByRole('img', {name: 'Hologram town:welcome'}).waitFor();
  await L.dismiss(page);
  await L.dismiss(page);
  await page.waitForTimeout(2500);
  await page.evaluate(() => window.scrollTo(0, 0));
  f.mark();
  const visual = page.getByRole('tabpanel', {name: 'Visual'});
  const stage = page.getByRole('img', {name: 'Hologram town:welcome'});
  await h.hover(stage, 1300);
  await h.hover(visual.getByRole('combobox', {name: 'Row 1 text'}), 900);
  await h.hover(visual.getByRole('combobox', {name: 'Row 3 kind'}), 900);
  await h.click(visual.getByRole('button', {name: 'Add text'}), {after: 900});
  const row = visual.getByRole('combobox', {name: 'Row 6 text'});
  await h.type(row, '|animation.sp', {clear: true, after: 600});
  await h.click(page.getByRole('listbox').getByText('sparkle', {exact: true}).first(), {after: 900, noReveal: true});
  await row.first().press('End');
  await row.first().pressSequentially(' &aMarket open |animation.sparkle|', {delay: L.slow(60)});
  await h.pause(L.TYPED_HOLD_MS);
  await h.key('Escape', 300);
  await h.hover(stage, 600);
  await h.result(2500);
  const toggle = page.getByRole('button', {name: 'Pause animations'});
  await h.click(toggle, {after: 600});
  await h.result(1800);
  await h.click(page.getByRole('button', {name: 'Play animations'}), {after: 600});
  await h.hover(stage, 600);
  await h.result(2000);
  return L.finish(f, {closing: 'The new row plays town:sparkle on both sides of Market open'});
};
