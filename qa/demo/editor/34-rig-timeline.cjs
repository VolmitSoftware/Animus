// The rig timeline: picks the knight's walk, plays it, halves the speed,
// switches the loop mode to pingpong and lengthens it from 20 to 30 ticks.
// Offline: the knight is imported unfilmed into a blank workspace (see rig.cjs).
const R = require('./rig.cjs');

module.exports = async (L) => {
  const {film: f, page, h} = await R.knightSession(L, '34-rig-timeline');
  const timeline = page.getByLabel('Timeline', {exact: true});
  f.mark();
  await h.pause(400);
  // Brings the stage's lower half and the timeline toolbar on screen together.
  await R.scrollBy(f, h, 260);
  const pick = timeline.getByRole('combobox', {name: 'Animation', exact: true});
  await h.click(pick, {after: 800, noReveal: true});
  await h.hover(L.option(page, 'animus:knight/walk'), 400);
  await h.click(L.option(page, 'animus:knight/walk'), {after: 900});
  const playback = page.getByRole('group', {name: 'Playback'});
  const play = playback.getByRole('button', {name: 'Play'});
  const pause = playback.getByRole('button', {name: 'Pause'});
  await h.click(play, {after: 300, noReveal: true});
  await h.result(2500);
  // Paused while the settings change, then played again with them.
  await h.click(pause, {after: 500, noReveal: true});
  // Lists near the bottom edge scroll the page as they open and close again, so the settings are
  // changed in the middle of the screen, then the page goes back up to watch the stage.
  await R.scrollBy(f, h, 240);
  await R.choose(L, h, page, playback.getByRole('combobox', {name: 'Speed'}), '0.5');
  await R.choose(L, h, page, playback.getByRole('combobox', {name: 'Loop'}), 'pingpong');
  await h.type(playback.getByLabel('Duration in ticks'), '30', {clear: true, after: 300});
  await h.key('Enter', 600);
  await h.hover(page.getByText(/^tick [\d.]+ \/ 30$/), 300);
  await R.scrollBy(f, h, -240);
  await h.click(play, {after: 300, noReveal: true});
  await h.result(2500);
  await h.click(pause, {after: 600, noReveal: true});
  return L.finish(f, {closing: 'walk plays at half speed, pingpong, over 30 ticks'});
};
