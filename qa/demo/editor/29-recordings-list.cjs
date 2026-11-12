// Browse beside Recordings lists every recording with its length, frames,
// events, world and size; a click opens one.
// Fake server data: --data=../qa/demo/editor/fixtures/town-deep (from animus-web/).
module.exports = async (L) => {
  const {film: f, page, h} = await L.pairedSession('29-recordings-list');
  const side = page.getByRole('complementary', {name: 'Documents'});
  await page.waitForTimeout(1500);
  f.mark();
  await h.pause(300);
  const recordings = side.getByRole('button', {name: /^Recordings \d+ documents/});
  await h.reveal(recordings);
  await h.click(side.getByRole('link', {name: 'Browse recordings'}), {after: 600});
  const table = page.getByRole('table');
  await h.result(1500);
  // Each hover rests on the value the viewer reads: gate-walk's length, then dock-fishing's world.
  await h.hover(table.getByRole('row', {name: /^gate-walk/}).getByRole('cell').first(), 300);
  await h.result(1800);
  await h.hover(table.getByRole('row', {name: /^dock-fishing/}).getByRole('cell').nth(3), 300);
  await h.result(1600);
  await h.click(table.getByRole('link', {name: 'market-loop', exact: true}), {after: 1200});
  await page.getByRole('heading', {name: 'market-loop', level: 1}).waitFor();
  await h.result(2200);
  return L.finish(f, {closing: 'Recordings listed by length, frames, events and world; a click opens one'});
};
