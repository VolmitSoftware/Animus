// A recording's Visual view: rewrites gate-walk's description, adds a tag,
// sets its default playback, then reads the recorded details.
// Fake server data: --data=../qa/demo/editor/fixtures/town-deep (from animus-web/).
module.exports = async (L) => {
  const {film: f, page, h} = await L.pairedSession('30-recording-details');
  const side = page.getByRole('complementary', {name: 'Documents'});
  await page.waitForTimeout(1500);
  f.mark();
  await h.pause(300);
  await h.click(side.getByText('gate-walk', {exact: true}).first(), {after: 1400});
  const rec = page.getByRole('region', {name: 'Recording'});
  await h.hover(rec.getByRole('textbox', {name: 'Id'}), 900);
  await h.type(rec.getByRole('combobox', {name: 'Description'}), 'The night watch walks from the gate to the well.', {clear: true, delay: 40, after: 300});
  await h.type(rec.getByRole('combobox', {name: 'New tag'}), 'night', {after: 300});
  await h.click(rec.getByRole('button', {name: 'Add tag'}), {after: 900});
  const playback = rec.getByRole('combobox', {name: 'Playback'});
  await h.click(playback, {after: 700});
  await h.click((await L.listboxOf(playback)).getByRole('option', {name: /^pingpong/}), {after: 900});
  await h.result(1500);
  // The details are a description list; each term is found by its text.
  for (const term of ['Length', 'Events', 'Origin', 'Recorded by']) {
    await h.hover(rec.getByRole('term').filter({hasText: term}).first(), 750);
  }
  await h.result(2000);
  return L.finish(f, {closing: 'gate-walk has a new description, the night tag and pingpong playback'});
};
