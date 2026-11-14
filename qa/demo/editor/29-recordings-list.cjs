// Movement's Recordings tab lists every recording with its length and world;
// a click opens one.
// Fake server data: --data=../qa/demo/editor/fixtures/town-deep (from animus-web/).
module.exports = async (L) => {
  const {film: f, page, h} = await L.pairedSession('29-recordings-list');
  await page.waitForTimeout(1200);
  f.mark();
  await h.pause(300);
  await h.job('Movement');
  await h.hover(page.getByRole('listbox', {name: 'Routes'}), 900);
  await h.click(page.getByRole('tab', {name: /^Recordings/}), {after: 900});
  const list = page.getByRole('listbox', {name: 'Recordings'});
  await h.hover(page.getByRole('main').getByText('Replays what you did.'), 1200);
  // Each hover rests on the line the viewer reads: a recording's length and world.
  for (const name of [/gate to the well/, /paces the pier/]) {
    await h.moveTo(list.getByRole('option', {name}), {ox: 0.5});
    await h.pause(1400);
  }
  await h.click(list.getByRole('option', {name: /^Market loop/}), {after: 1200});
  await page.getByRole('heading', {level: 1, name: 'Market loop'}).waitFor();
  await h.moveTo(page.getByRole('img', {name: /^Recorded steps/}), {ox: 0.85, oy: 0.2});
  await h.result(2200);
  return L.finish(f, {closing: 'Recordings listed with their length and world; a click opens one'});
};
