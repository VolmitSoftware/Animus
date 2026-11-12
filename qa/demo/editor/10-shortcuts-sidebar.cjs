// The shortcut sheet, hiding and showing the sidebar, and single-key navigation.
module.exports = async (L) => {
  const {film: f, page, h} = await L.pairedSession('10-shortcuts-sidebar');
  await page.getByRole('complementary', {name: 'Documents'}).getByRole('link', {name: 'Browse'}).click();
  await page.getByRole('region', {name: 'NPC browser'}).waitFor();
  await page.waitForTimeout(1200);
  // Single-key shortcuts only reach the page when no text field has focus.
  await page.evaluate(() => document.activeElement && document.activeElement.blur());
  f.mark();
  await h.pause(300);
  await h.hover(page.getByRole('button', {name: 'Commands'}), 900);
  await h.key('?', 900);
  const sheet = page.getByRole('dialog', {name: 'Keyboard shortcuts'});
  await sheet.waitFor();
  await h.moveTo(sheet, {noReveal: true});
  await h.result(1800);
  for (let i = 0; i < 3; i++) { await page.mouse.wheel(0, 220); await h.pause(700); }
  await h.result(1500);
  await h.key('Escape', 800);
  await h.key('ControlOrMeta+b', 900);
  await h.result(1500);
  await h.click(page.getByRole('button', {name: 'Show sidebar'}), {after: 900, noReveal: true});
  await page.evaluate(() => document.activeElement && document.activeElement.blur());
  await h.key('n', 900);
  await page.getByRole('navigation', {name: 'Document kinds'}).waitFor();
  await h.result(1500);
  await h.key('b', 900);
  await page.getByRole('region', {name: 'NPC browser'}).waitFor();
  await h.result(1800);
  return L.finish(f, {closing: '? lists the keys; Cmd+B hides the sidebar; N and B jump'});
};
