// Plays gate-walk on its top-down path: Play runs the marker along the
// recorded positions, the scrub and the events list jump it to a tick.
// Fake server data: --data=../qa/demo/editor/fixtures/town-deep (from animus-web/).
module.exports = async (L) => {
  const {film: f, page, h} = await L.pairedSession('31-recording-playback');
  const side = page.getByRole('complementary', {name: 'Documents'});
  await page.waitForTimeout(1500);
  f.mark();
  await h.pause(300);
  await h.click(side.getByText('gate-walk', {exact: true}).first(), {after: 1400});
  const rec = page.getByRole('region', {name: 'Recording'});
  const play = rec.getByRole('button', {name: 'Play'});
  await h.reveal(play);
  await h.hover(rec.getByRole('img', {name: /^Recorded path/}), 900);
  await h.click(play, {after: 300});
  await h.moveTo(rec.getByText(/^tick \d+ \/ 240/));
  await h.pause(4200);
  await h.click(rec.getByRole('button', {name: 'Pause'}), {after: 900});
  await h.result(1500);
  const scrub = rec.getByRole('slider', {name: 'Scrub tick'});
  const box = await scrub.boundingBox();
  await h.moveTo(scrub, {ox: 0.75, noReveal: true});
  await page.mouse.down();
  await h.glide(box.x + box.width * 0.2, box.y + box.height / 2);
  await h.glide(box.x + box.width * 0.45, box.y + box.height / 2);
  await page.mouse.up();
  await h.result(1500);
  await h.click(rec.getByRole('button', {name: /^Scrub to tick 200:/}), {after: 900});
  await h.moveTo(rec.getByText(/^tick 200 \/ 240/), {noReveal: true});
  await h.result(2200);
  return L.finish(f, {closing: 'gate-walk played, scrubbed and jumped to its door event at tick 200'});
};
