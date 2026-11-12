// The command palette: open a document by name from Commands, then switch the theme with the shortcut.
// Fake server data: --data=test/fixtures/gui_editors
module.exports = async (L) => {
  const {film: f, page, h} = await L.pairedSession('42-command-palette');
  await page.waitForTimeout(500);
  await L.dismiss(page);
  await L.dismiss(page);
  await page.waitForTimeout(2500);
  f.mark();
  await h.click(page.getByRole('button', {name: 'Commands'}), {after: 900});
  const palette = page.getByRole('dialog', {name: 'Commands'});
  const search = palette.getByRole('combobox', {name: 'Search commands and documents'});
  await h.hover(palette.getByRole('listbox', {name: 'Commands'}), 1200);
  await search.pressSequentially('hub', {delay: L.slow(120)});
  await h.pause(L.TYPED_HOLD_MS);
  await h.hover(palette.getByRole('option').first(), 800);
  await h.key('Enter', 1200);
  await page.getByRole('img', {name: 'Menu town:hub'}).waitFor();
  await h.result(1800);
  await h.key('ControlOrMeta+k', 900);
  await search.pressSequentially('theme', {delay: L.slow(120)});
  await h.pause(L.TYPED_HOLD_MS);
  await h.hover(palette.getByRole('option').first(), 800);
  await h.key('Enter', 1000);
  await h.result(2000);
  return L.finish(f, {closing: 'Opened town:hub and switched to the light theme from the palette'});
};
