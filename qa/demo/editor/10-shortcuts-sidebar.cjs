// The shortcut sheet, then the single keys: N for New document, B for the NPC
// list and / for Search, which opens an NPC by name.
// Fake server data: --data=test/fixtures/npc_browser (from animus-web/).
module.exports = async (L) => {
  const {film: f, page, h} = await L.pairedSession('10-shortcuts-sidebar');
  await page.waitForTimeout(1200);
  // Single-key shortcuts only reach the page when no text field has focus.
  const blur = () => page.evaluate(() => document.activeElement && document.activeElement.blur());
  await blur();
  f.mark();
  await h.pause(300);
  await h.hover(page.getByRole('banner').getByRole('button', {name: 'Search'}), 900);
  await h.key('?', 900);
  const sheet = page.getByRole('dialog', {name: 'Keyboard shortcuts'});
  await sheet.waitFor();
  await h.moveTo(sheet, {noReveal: true});
  await h.result(2000);
  for (let i = 0; i < 3; i++) { await page.mouse.wheel(0, 220); await h.pause(800); }
  await h.result(1500);
  await h.key('Escape', 800);
  await blur();
  await h.key('n', 900);
  await page.getByRole('heading', {name: 'New document', level: 1}).waitFor();
  await h.result(1600);
  await blur();
  await h.key('b', 900);
  await page.getByRole('listbox', {name: 'NPCs'}).waitFor();
  await h.result(1600);
  await blur();
  await h.key('/', 700);
  const box = page.getByRole('dialog', {name: 'Command palette'}).getByRole('textbox');
  await box.waitFor();
  await page.keyboard.type('ferr', {delay: L.slow(90)});
  await h.pause(900);
  await h.key('Enter', 1200);
  await page.getByRole('heading', {name: 'Ferryman', level: 1}).waitFor();
  await h.result(2000);
  return L.finish(f, {closing: '? lists the keys; N, B and / jump straight there'});
};
