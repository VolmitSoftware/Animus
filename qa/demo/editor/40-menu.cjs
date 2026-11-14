// The holographic menu editor, opened from GUIs in Advanced: picks the market
// button on the stage, moves it, gives it a hover growth and a click sound.
// Fake server data: --data=test/fixtures/gui_editors (from animus-web/).
module.exports = async (L) => {
  const {film: f, page, h} = await L.pairedSession('40-menu', {mode: 'advanced', path: '/list/inventory'});
  await page.getByRole('tab', {name: /^Menus/}).click();
  await page.getByRole('listbox').getByRole('option', {name: /town:hub/}).waitFor();
  await page.waitForTimeout(1200);
  f.mark();
  await h.pause(300);
  await h.click(page.getByRole('listbox').getByRole('option', {name: /town:hub/}), {after: 1500});
  const menu = page.getByRole('region', {name: 'Menu'});
  const stage = page.getByRole('img', {name: 'Menu town:hub'});
  // Stage pick rings are pointer only and aria-hidden (the component list is the keyboard path); data-pick is the component id.
  const pick = (id) => page.locator(`[data-pick="${id}"]`);
  await h.moveTo(stage, {ox: 0.85, oy: 0.3});
  await h.pause(1200);
  await h.click(pick('market'), {after: 1000, noReveal: true});
  await h.hover(menu.getByRole('textbox', {name: 'Component name'}), 900);
  await h.type(menu.getByRole('spinbutton', {name: 'Place x'}), '-1.4', {clear: true, after: 600});
  await h.key('Enter', 300);
  await h.moveTo(stage, {ox: 0.85, oy: 0.3});
  await h.result(1800);
  // Hover and click box is a disclosure whose <summary> has no accessible name; it is found by its title.
  const box = menu.locator('details', {hasText: 'Hover and click box'}).last();
  await h.click(box.locator('summary').first(), {ox: 0.97, after: 800});
  await h.type(box.getByRole('spinbutton', {name: 'Highlight'}), '0.3', {clear: true});
  await h.type(box.getByRole('spinbutton', {name: 'Hover time in ticks'}), '8', {clear: true});
  const add = menu.getByRole('combobox', {name: 'Add action'}).first();
  await h.click(add, {after: 700});
  await page.keyboard.type('sound', {delay: L.slow(90)});
  await h.pause(600);
  await h.click(L.option(page, 'sound'), {after: 900, noReveal: true});
  await h.hover(menu.getByRole('combobox', {name: 'Action 2'}), 900);
  await h.result(1800);
  // Rests on the ring's corner: the stage shows the hover without the cursor over the icon.
  await h.moveTo(pick('market'), {ox: 0.9, oy: 0.9, noReveal: true});
  await h.pause(700);
  await h.result(1800);
  return L.finish(f, {closing: 'market moved left, grows on hover and plays a sound'});
};
