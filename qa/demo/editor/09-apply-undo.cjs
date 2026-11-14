// Renames an NPC, reviews and applies it with a change note (Advanced), then
// undoes the apply from the save bar.
// Fake server data: --data=test/fixtures/npc_browser (from animus-web/).
module.exports = async (L) => {
  const {film: f, page, h} = await L.pairedSession('09-apply-undo', {path: '/npcs', mode: 'advanced'});
  const list = page.getByRole('listbox', {name: 'NPCs'});
  await list.getByRole('option', {name: /^Baker/}).getByText('Baker', {exact: true}).click();
  await page.getByRole('heading', {name: 'Baker', level: 1}).waitFor();
  await page.mouse.move(f.pos.x, f.pos.y);
  await page.waitForTimeout(1500);
  f.mark();
  await h.pause(300);
  await h.type(page.getByRole('tabpanel', {name: 'Basics'}).getByRole('combobox', {name: 'Name', exact: true}), 'Master Baker', {clear: true, after: 300});
  const bar = page.getByRole('contentinfo', {name: 'Save'});
  await h.hover(bar.getByRole('status'), 900);
  await h.click(bar.getByRole('button', {name: 'Review'}), {after: 900, noReveal: true});
  const review = page.getByRole('dialog', {name: 'Review'});
  await h.hover(review.getByRole('region', {name: /^Changes/}).getByRole('listitem').first(), 1200);
  await h.type(review.getByRole('textbox', {name: 'Change note'}), 'Rename baker', {after: 300});
  await h.click(review.getByRole('button', {name: 'Apply 1'}), {after: 1200, noReveal: true});
  await review.getByRole('region', {name: 'Applied'}).waitFor();
  await h.result(1800);
  await h.click(review.getByRole('button', {name: 'Close sheet'}), {after: 800, noReveal: true});
  await h.hover(bar.getByRole('status'), 900);
  await h.click(bar.getByRole('button', {name: 'Undo'}), {after: 900, noReveal: true});
  const confirm = page.getByRole('alertdialog', {name: 'Undo the last apply?'});
  await h.hover(confirm.getByText(/^Puts 1 document on the server back/), 1200);
  await h.click(confirm.getByRole('button', {name: 'Undo on the server'}), {after: 1000, noReveal: true});
  // Rest just past the save bar's line, which now says the apply was undone.
  await h.moveTo(bar.getByRole('status'), {ox: 1.3, noReveal: true});
  await h.result(2200);
  return L.finish(f, {closing: 'Applied as Master Baker, then undone on the server'});
};
