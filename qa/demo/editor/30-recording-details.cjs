// A recording's page: rewrites gate-walk's description, adds a tag, sets its
// default playback, then reads the recorded facts in Advanced.
// Fake server data: --data=../qa/demo/editor/fixtures/town-deep (from animus-web/).
module.exports = async (L) => {
  const {film: f, page, h} = await L.pairedSession('30-recording-details', {path: '/list/route'});
  await page.getByRole('tab', {name: /^Recordings/}).click();
  await page.getByRole('listbox', {name: 'Recordings'}).waitFor();
  await page.waitForTimeout(1200);
  f.mark();
  await h.pause(300);
  await h.click(page.getByRole('listbox', {name: 'Recordings'}).getByRole('option', {name: /gate to the well/}), {after: 1400});
  const rec = page.getByRole('group', {name: 'Recording'});
  await h.type(rec.getByRole('textbox', {name: 'Description'}), 'The night watch walks from the gate to the well.', {clear: true, delay: 40, after: 300});
  const tags = rec.getByRole('textbox', {name: 'Tags'});
  await h.click(tags, {ox: 0.95, after: 200});
  await page.keyboard.press('End');
  await tags.pressSequentially(', night', {delay: L.slow(60)});
  await h.pause(L.TYPED_HOLD_MS);
  await h.click(rec.getByRole('group', {name: 'Playback'}).getByRole('button', {name: 'Back and forth'}), {after: 900});
  await h.result(1500);
  await h.mode('advanced');
  // Facts is a disclosure whose <summary> has no accessible name; it is found by its title.
  await h.click(rec.locator('details', {hasText: 'Facts'}).last().locator('summary').first(), {ox: 0.97, after: 900});
  const facts = rec.locator('details', {hasText: 'Facts'}).last();
  await h.reveal(facts);
  // Rest in the empty right half of the facts, off their values.
  await h.moveTo(facts, {ox: 0.75, oy: 0.4});
  await h.pause(1200);
  await h.moveTo(facts, {ox: 0.75, oy: 0.75});
  await h.result(2000);
  return L.finish(f, {closing: 'gate-walk has a new description, the night tag and back-and-forth playback'});
};
