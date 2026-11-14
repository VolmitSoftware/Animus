// The rig's animation names: opens the panel, reads what idle and walk do,
// adds the name salute for the wave animation and opens it from its row.
// Offline: the knight is imported unfilmed into a blank workspace (see rig.cjs).
const R = require('./rig.cjs');

module.exports = async (L) => {
  const {film: f, page, h} = await R.knightSession(L, '36-rig-animations');
  // Animation names is a disclosure whose <summary> has no accessible name; it is found by its title.
  const names = page.getByRole('main').locator('details', {hasText: 'Animation names'}).last();
  f.mark();
  await h.pause(400);
  await R.scrollBy(f, h, 500);
  await h.click(names.locator('summary').first(), {ox: 0.5, after: 900});
  // The role lines sit under their rows; the cursor rests on their text at the left.
  await h.moveTo(names.getByText('Plays while the NPC stands.'), {ox: 0.15});
  await h.pause(900);
  await h.moveTo(names.getByText('Plays while the NPC walks.'), {ox: 0.15});
  await h.pause(500);
  await h.result(1800);
  await h.type(names.getByRole('textbox', {name: 'New name'}), 'salute', {after: 300});
  await R.choose(L, h, page, names.getByRole('combobox', {name: 'Animation for the new name'}), 'animus:knight/wave');
  await h.click(names.getByRole('button', {name: 'Add name'}), {after: 800});
  await h.hover(names.getByRole('textbox', {name: 'Name salute'}), 400);
  await h.result(1800);
  const open = names.getByRole('button', {name: 'Open animus:knight/wave'}).last();
  await h.click(open, {after: 1000});
  const pick = page.getByRole('toolbar', {name: 'Animation'}).getByRole('combobox', {name: 'Animation'});
  await h.reveal(pick);
  await h.moveTo(pick, {ox: 1.3});
  await h.result(2300);
  return L.finish(f, {closing: 'salute now plays the wave animation, and wave is open'});
};
