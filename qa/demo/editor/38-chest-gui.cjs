// The chest GUI editor: a slot in the window drawn as in game becomes a clock button with a message.
// Fake server data: --data=test/fixtures/gui_editors
module.exports = async (L) => {
  const {film: f, page} = await L.pairedSession('38-chest-gui');
  const h = L.panelHuman(f);
  await page.getByText('town:board', {exact: true}).first().click();
  await page.getByRole('group', {name: 'Chest window town:board'}).waitFor();
  await L.dismiss(page);
  await L.dismiss(page);
  await page.waitForTimeout(2500);
  await page.evaluate(() => window.scrollTo(0, 0));
  f.mark();
  const visual = page.getByRole('tabpanel', {name: 'Visual'});
  const window = page.getByRole('group', {name: 'Chest window town:board'});
  await h.hover(window, 900);
  await h.click(page.getByRole('button', {name: /^Slot 4,/}), {after: 900});
  await h.hover(visual.getByText(/^Drawn by mask key #/), 1100);
  await h.click(page.getByRole('button', {name: 'Make explicit slot'}), {after: 900});
  await h.click(visual.getByRole('combobox', {name: 'Type'}), {after: 600});
  await h.click(L.option(page, 'button'), {after: 900, noReveal: true});
  await h.type(visual.getByRole('combobox', {name: 'Item'}), 'clock', {clear: true, after: 600});
  await h.click(page.getByRole('listbox').getByText('minecraft:clock').first(), {after: 900, noReveal: true});
  await h.type(visual.getByRole('combobox', {name: 'Name'}), '&eDaily reward', {clear: true});
  await h.key('Escape', 400);
  const add = visual.getByRole('combobox', {name: 'Add an action'});
  await h.click(add, {after: 700});
  await h.click((await L.listboxOf(add)).getByRole('option', {name: 'Send a message'}), {after: 900, noReveal: true});
  await h.type(visual.getByRole('combobox', {name: 'Message'}), '&aCome back tomorrow.', {clear: true});
  await h.key('Escape', 400);
  await h.hover(page.getByRole('button', {name: /^Slot 4,/}), 600);
  await h.result(1900);
  return L.finish(f, {closing: 'Slot 4 is now a clock button that sends a message'});
};
