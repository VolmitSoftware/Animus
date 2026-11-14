// The rig timeline: picks the knight's walk, plays it, halves the speed,
// switches the loop mode to pingpong and lengthens it from 20 to 30 ticks.
// Offline: the knight is imported unfilmed into a blank workspace (see rig.cjs).
const R = require('./rig.cjs');

module.exports = async (L) => {
  const {film: f, page, h} = await R.knightSession(L, '34-rig-timeline');
  const playback = page.getByRole('toolbar', {name: 'Playback'});
  const clip = page.getByRole('toolbar', {name: 'Clip'});
  f.mark();
  await h.pause(400);
  // Brings the knight and the timeline's toolbars on screen together.
  await R.scrollBy(f, h, 300);
  await h.pick(page.getByRole('toolbar', {name: 'Animation'}).getByRole('combobox', {name: 'Animation'}), 'animus:knight/walk', {after: 900});
  await h.click(playback.getByRole('button', {name: 'Play'}), {after: 300, noReveal: true});
  await h.result(2500);
  // Paused while the settings change, then played again with them.
  await h.click(playback.getByRole('button', {name: 'Pause'}), {after: 500, noReveal: true});
  await R.choose(L, h, page, playback.getByRole('combobox', {name: 'Speed'}), '0.5');
  await R.choose(L, h, page, clip.getByRole('combobox', {name: 'Loop'}), 'pingpong');
  await h.type(clip.getByRole('textbox', {name: 'Length in ticks'}), '30', {clear: true, after: 300});
  await h.key('Enter', 600);
  await h.hover(playback.getByText(/\/ 30 ticks/), 300);
  await h.click(playback.getByRole('button', {name: 'Play'}), {after: 300, noReveal: true});
  await h.result(2500);
  await h.click(playback.getByRole('button', {name: 'Pause'}), {after: 300, noReveal: true});
  await h.moveTo(page.getByRole('img', {name: /^Rig animus:knight/}), {ox: 0.85, oy: 0.8, noReveal: true});
  await h.pause(400);
  return L.finish(f, {closing: 'walk plays at half speed, ping-pong, over 30 ticks'});
};
