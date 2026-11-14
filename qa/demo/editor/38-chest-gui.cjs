// The chest GUI editor, opened from GUIs in Advanced: slot 4 of the window
// drawn as in game becomes its own slot, a clock button with a message.
// Fake server data: --data=test/fixtures/gui_editors (from animus-web/).
module.exports = async (L) => {
  const {film: f, page, h} = await L.pairedSession('38-chest-gui', {mode: 'advanced'});
  await page.waitForTimeout(1200);
  f.mark();
  await h.pause(300);
  await h.job('GUIs');
  await h.click(page.getByRole('listbox', {name: 'Chest GUIs'}).getByRole('option', {name: /^Town board/}), {after: 1500});
  const gui = page.getByRole('region', {name: 'Chest GUI'});
  const slot4 = page.getByRole('button', {name: /^Slot 4,/});
  await h.hover(page.getByRole('region', {name: 'Preview'}), 900);
  await h.click(slot4, {after: 900});
  await h.moveTo(gui.getByText(/^Key #: every # slot shows this/), {ox: 0.5});
  await h.pause(1100);
  await h.click(gui.getByRole('button', {name: 'Use own slot'}), {after: 900});
  await h.pick(gui.getByRole('combobox', {name: 'Type'}), 'button', {after: 900});
  const item = gui.getByRole('combobox', {name: 'Item'});
  await h.type(item, 'clock', {clear: true, after: 600});
  await h.click(page.getByRole('listbox', {name: 'Items for Item'}).getByRole('option').first(), {after: 900, noReveal: true});
  await h.type(gui.getByRole('combobox', {name: 'Name'}), '&eDaily reward', {clear: true});
  await h.key('Escape', 400);
  const add = gui.getByRole('combobox', {name: 'Add action'});
  await h.click(add, {after: 700});
  await page.keyboard.type('message', {delay: L.slow(90)});
  await h.pause(600);
  await h.click(L.option(page, 'message'), {after: 900, noReveal: true});
  await h.type(gui.getByRole('combobox', {name: 'Message'}), '&aCome back tomorrow.', {clear: true});
  await h.key('Escape', 400);
  // Resting on slot 4 shows its tooltip, the name players read.
  await h.moveTo(slot4, {ox: 0.85, oy: 0.85});
  await h.pause(600);
  await h.result(1900);
  return L.finish(f, {closing: 'Slot 4 is now a clock button that sends a message'});
};
