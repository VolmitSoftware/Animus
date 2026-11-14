// Keyframes on the wave animation's arm_l rotation track: selects one, edits
// its value and easing, drags it along the lane, then keys the arm again at
// the playhead. Offline: the knight is imported unfilmed into a blank
// workspace (see rig.cjs); the wave is opened with arm_l selected before
// filming, so the page does not jump when the bone's inspector opens.
const R = require('./rig.cjs');

module.exports = async (L) => {
  const {film: f, page, h} = await R.knightSession(L, '35-rig-keyframes', {
    prepare: async (p) => {
      await R.bone(p, 'arm_l').click();
      await p.getByRole('toolbar', {name: 'Animation'}).getByRole('combobox', {name: 'Animation'}).click();
      await L.option(p, 'animus:knight/wave').click();
      await p.getByRole('button', {name: 'arm_l rotation keyframe at tick 6'}).waitFor();
    },
  });
  const key = (tick) => page.getByRole('button', {name: `arm_l rotation keyframe at tick ${tick}`, exact: true});
  const keyframe = page.getByRole('region', {name: 'Keyframe'});
  f.mark();
  await h.pause(400);
  // Brings the lanes and the keyframe panel on screen together.
  await R.scrollBy(f, h, 900);
  await h.click(key(6), {after: 700, noReveal: true});
  await h.moveTo(keyframe.getByRole('heading'), {ox: 1.2}); // beside the heading, which names the key's tick
  await h.pause(300);
  await h.result(1800);
  await h.type(keyframe.getByRole('textbox', {name: 'Keyframe value Z'}), '-170', {clear: true, after: 300});
  await h.key('Enter', 700);
  await R.choose(L, h, page, keyframe.getByRole('combobox', {name: 'Easing'}), 'ease_out');
  await h.result(1800);
  // About 44 px per tick on this lane: two ticks to the right.
  await R.drag(f, h, key(6), 89, 0);
  await h.moveTo(keyframe.getByRole('heading'), {ox: 1.2}); // beside the heading, which names the key's tick
  await h.pause(300);
  await h.result(2000);
  // The playhead is a slider: a click on its track jumps there, then the
  // arrow keys step it a quarter tick at a time to exactly 16.
  const playhead = page.getByRole('slider', {name: 'Playhead tick'});
  // The track is the thumb's parent, which has no role of its own.
  const track = playhead.locator('xpath=..');
  const max = Number(await playhead.getAttribute('aria-valuemax'));
  await h.click(track, {ox: 16 / max, after: 400, noReveal: true});
  for (let i = 0; i < 16; i++) {
    const v = Number(await playhead.getAttribute('aria-valuenow'));
    if (v === 16) break;
    await h.key(v < 16 ? 'ArrowRight' : 'ArrowLeft', 150);
  }
  await h.click(page.getByRole('toolbar', {name: 'Keys'}).getByRole('button', {name: 'Add keyframe'}), {after: 600, noReveal: true});
  await h.moveTo(key(16), {ox: 2.6, noReveal: true}); // beside the new key, off it and the playhead
  await h.pause(300);
  await h.result(2300);
  return L.finish(f, {closing: 'arm_l has an eased key at tick 8 and a new one at tick 16'});
};
