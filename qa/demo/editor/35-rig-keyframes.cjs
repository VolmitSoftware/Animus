// Keyframes on the wave animation's arm_l rotation track: selects one, edits
// its value and easing, drags it along the lane, then keys the arm again at
// the playhead. Offline: the knight is imported unfilmed into a blank
// workspace (see rig.cjs); the wave is opened with arm_l selected before
// filming, so the page does not jump when the bone's inspector opens.
const R = require('./rig.cjs');

module.exports = async (L) => {
  const {film: f, page, h} = await R.knightSession(L, '35-rig-keyframes', {
    prepare: async (p) => {
      await p.locator('.animus-rig-tree [data-bone="arm_l"]').click();
      const pick = p.getByLabel('Timeline', {exact: true}).getByRole('combobox', {name: 'Animation', exact: true});
      await pick.click();
      await L.option(p, 'animus:knight/wave').click();
      await p.getByRole('button', {name: 'arm_l rotation keyframe at tick 6'}).waitFor();
    },
  });
  const key = (tick) => page.getByRole('button', {name: `arm_l rotation keyframe at tick ${tick}`, exact: true});
  const keyframe = page.getByLabel('Keyframe', {exact: true});
  f.mark();
  await h.pause(400);
  // Brings the lanes and the keyframe panel on screen together.
  await R.scrollBy(f, h, 1080);
  await h.click(key(6), {after: 700, noReveal: true});
  await h.hover(keyframe, 300);
  await h.result(1800);
  await h.type(keyframe.getByLabel('Keyframe value Z'), '-170', {clear: true, after: 300});
  await h.key('Enter', 700);
  await h.click(keyframe.getByRole('combobox', {name: 'Easing'}), {after: 700, noReveal: true});
  await h.click(L.option(page, 'ease_out'), {after: 700});
  await h.result(1800);
  // About 26 px per tick on this lane: two ticks to the right.
  await R.drag(f, h, key(6), 52, 0);
  await h.hover(keyframe, 300);
  await h.result(2000);
  const range = page.getByLabel('Playhead tick');
  const box = await range.boundingBox();
  await h.click(range, {ox: (8 + 0.5 * (box.width - 16)) / box.width, after: 500, noReveal: true});
  for (let i = 0; i < 8; i++) {
    const v = Number(await range.inputValue());
    if (v === 16) break;
    await h.key(v < 16 ? 'ArrowRight' : 'ArrowLeft', 200);
  }
  await h.click(page.getByRole('button', {name: 'Add keyframe'}), {after: 600, noReveal: true});
  await h.hover(key(16), 300);
  await h.result(2300);
  return L.finish(f, {closing: 'arm_l has an eased key at tick 8 and a new one at tick 16'});
};
