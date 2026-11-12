// The holographic menu editor: pick a component on the stage, move it, give it a hover and a click sound.
// Fake server data: --data=test/fixtures/gui_editors
module.exports = async (L) => {
  const {film: f, page} = await L.pairedSession('40-menu');
  const h = L.panelHuman(f);
  await page.getByText('town:hub', {exact: true}).first().click();
  await page.getByRole('img', {name: 'Menu town:hub'}).waitFor();
  await L.dismiss(page);
  await L.dismiss(page);
  await page.waitForTimeout(2500);
  await page.evaluate(() => window.scrollTo(0, 0));
  f.mark();
  const visual = page.getByRole('tabpanel', {name: 'Visual'});
  const stage = page.getByRole('img', {name: 'Menu town:hub'});
  // Stage pick rings are pointer only and aria-hidden (the component list is the keyboard path); data-pick is the component id.
  const pick = (id) => page.locator(`[data-pick="${id}"]`);
  await h.hover(stage, 1200);
  await h.click(pick('market'), {after: 1000, noReveal: true});
  await h.hover(visual.getByRole('button', {name: 'market (button)'}), 900);
  await h.type(visual.getByRole('spinbutton', {name: 'Offset * x'}), '-1.4', {clear: true, after: 600});
  await h.key('Tab', 300);
  await h.hover(stage, 600);
  await h.result(1800);
  await h.click(visual.getByText('Hover and click box', {exact: true}), {after: 800});
  await h.type(visual.getByRole('spinbutton', {name: 'Highlight'}), '0.3', {clear: true});
  await h.type(visual.getByRole('spinbutton', {name: 'Hover time in ticks'}), '8', {clear: true});
  const add = visual.getByRole('combobox', {name: 'Add an action'});
  await h.click(add, {after: 700});
  await h.click((await L.listboxOf(add)).getByRole('option', {name: 'Play a sound'}), {after: 900, noReveal: true});
  await h.hover(visual.getByRole('combobox', {name: 'Action 2'}), 900);
  await h.result(1800);
  await h.hover(pick('market'), 700);
  await h.result(1800);
  return L.finish(f, {closing: 'market moved left, grows on hover and plays a sound'});
};
