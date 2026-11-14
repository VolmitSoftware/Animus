// Plays gate-walk on its top-down drawing: Play runs the dot along the
// recorded steps, the scrub moves it, and Events jumps it to the door.
// Fake server data: --data=../qa/demo/editor/fixtures/town-deep (from animus-web/).
module.exports = async (L) => {
  const {film: f, page, h} = await L.pairedSession('31-recording-playback', {path: '/list/route'});
  await page.getByRole('tab', {name: /^Recordings/}).click();
  await page.getByRole('listbox', {name: 'Recordings'}).waitFor();
  await page.waitForTimeout(1200);
  f.mark();
  await h.pause(300);
  await h.click(page.getByRole('listbox', {name: 'Recordings'}).getByRole('option', {name: /gate to the well/}), {after: 1400});
  const rec = page.getByRole('group', {name: 'Recording'});
  const playback = rec.getByRole('toolbar', {name: 'Playback'});
  await h.hover(rec.getByRole('img', {name: /^Recorded steps/}), 900);
  await h.click(playback.getByRole('button', {name: 'Play'}), {after: 300});
  // Rest on the drawing's empty corner, clear of the dot and the time.
  const drawing = rec.getByRole('img', {name: /^Recorded steps/});
  await h.moveTo(drawing, {ox: 0.9, oy: 0.9});
  await h.pause(4200);
  await h.click(playback.getByRole('button', {name: 'Pause'}), {after: 900});
  await h.result(1500);
  const scrub = playback.getByRole('slider', {name: 'Scrub'});
  const box = await scrub.boundingBox();
  await h.moveTo(scrub, {ox: 0.4, noReveal: true});
  await page.mouse.down();
  await h.glide(box.x + box.width * 0.15, box.y + box.height / 2);
  await h.glide(box.x + box.width * 0.6, box.y + box.height / 2);
  await page.mouse.up();
  await h.result(1500);
  // Events is a disclosure whose <summary> has no accessible name; it is found by its title.
  await h.click(rec.locator('details', {hasText: 'Events'}).first().locator('summary').first(), {ox: 0.97, after: 900});
  await h.click(rec.getByRole('list', {name: 'Recorded events'}).getByRole('button', {name: /^Go to 10\.0 s/}), {after: 900});
  await playback.getByText(/^10\.0 s of 12\.0 s$/).waitFor();
  await h.moveTo(drawing, {ox: 0.9, oy: 0.9});
  await h.result(2200);
  return L.finish(f, {closing: 'gate-walk played, scrubbed and jumped to its door event at 10 s'});
};
