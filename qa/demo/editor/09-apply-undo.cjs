// Renames an NPC, applies it with a change note, then undoes it on the server.
module.exports = async (L) => {
  const {film: f, page, h} = await L.pairedSession('09-apply-undo');
  await page.getByRole('navigation', {name: 'Documents by kind'}).getByRole('link', {name: /^Baker/}).click();
  await page.getByRole('heading', {name: 'Baker', level: 1}).waitFor();
  await page.waitForTimeout(1500);
  f.mark();
  await h.pause(300);
  await h.type(L.section(page, 'identity').getByRole('combobox', {name: 'Name', exact: true}), 'Master Baker', {clear: true, after: 300});
  // The live bar sits at the top of the page, where h.reveal cannot scroll it any higher; skip the reveal.
  const live = page.getByRole('region', {name: 'Live editing'});
  await h.click(live.getByRole('textbox', {name: 'Change note'}), {after: 150, noReveal: true});
  await page.keyboard.type('Rename baker', {delay: L.slow(50)});
  await h.pause(L.TYPED_HOLD_MS);
  await h.click(live.getByRole('button', {name: /^Apply/}), {after: 900, noReveal: true});
  await h.result(2000);
  await h.click(live.getByRole('button', {name: 'Undo last apply'}), {after: 800, noReveal: true});
  await h.click(live.getByRole('button', {name: 'Undo on the server'}), {after: 900, noReveal: true});
  await h.result(2200);
  return L.finish(f, {closing: 'Applied as Master Baker, then undone on the server'});
};
