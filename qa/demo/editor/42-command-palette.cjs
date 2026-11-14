// Search (the command palette): opens a document by name, then switches the
// theme from the keyboard with Mod+K.
// Fake server data: --data=test/fixtures/gui_editors (from animus-web/).
module.exports = async (L) => {
  const {film: f, page, h} = await L.pairedSession('42-command-palette');
  await page.waitForTimeout(1200);
  f.mark();
  await h.pause(300);
  await h.click(page.getByRole('banner').getByRole('button', {name: 'Search'}), {after: 900, noReveal: true});
  const palette = page.getByRole('dialog', {name: 'Command palette'});
  const search = palette.getByRole('textbox', {name: 'Search commands'});
  await h.moveTo(palette.getByRole('listbox'), {ox: 0.8, oy: 0.3});
  await h.pause(1400);
  await search.pressSequentially('welc', {delay: L.slow(120)});
  await h.pause(L.TYPED_HOLD_MS);
  await h.hover(palette.getByRole('option').first(), 800);
  await h.key('Enter', 1200);
  await page.getByRole('img', {name: 'Hologram town:welcome'}).waitFor();
  await h.moveTo(page.getByRole('img', {name: 'Hologram town:welcome'}), {ox: 0.85, oy: 0.25});
  await h.result(1800);
  await h.key('ControlOrMeta+k', 900);
  await search.pressSequentially('theme', {delay: L.slow(120)});
  await h.pause(L.TYPED_HOLD_MS);
  await h.hover(palette.getByRole('option').first(), 800);
  await h.key('Enter', 1000);
  await h.moveTo(page.getByRole('img', {name: 'Hologram town:welcome'}), {ox: 0.85, oy: 0.25});
  await h.result(2000);
  return L.finish(f, {closing: 'Opened Welcome to town and switched to the light theme from Search'});
};
