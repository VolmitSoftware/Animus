// The rig's animation names: opens the panel, reads what idle and walk do,
// adds the name salute for the wave animation and opens it from its row.
// Offline: the knight is imported unfilmed into a blank workspace (see rig.cjs).
const R = require('./rig.cjs');

module.exports = async (L) => {
  const {film: f, page, h} = await R.knightSession(L, '36-rig-animations');
  const names = page.getByLabel('Named animations', {exact: true});
  const row = (name) => names.getByRole('listitem').filter({has: page.getByLabel(`Name ${name}`, {exact: true})});
  f.mark();
  await h.pause(400);
  await R.scrollBy(f, h, 600);
  await h.click(page.getByRole('button', {name: 'Animation names'}), {after: 800, noReveal: true});
  // The role lines span the row; the cursor rests on their text at the left.
  await h.moveTo(row('idle').getByText('Plays while the NPC stands.'), {ox: 0.12});
  await h.pause(900);
  await h.moveTo(row('walk').getByText('Plays while the NPC walks.'), {ox: 0.12});
  await h.pause(500);
  await h.result(1800);
  await h.type(names.getByLabel('New animation name'), 'salute', {after: 300});
  const target = names.getByRole('combobox', {name: 'Animation for the new name'});
  await h.click(target, {after: 700});
  await h.click(L.option(page, 'animus:knight/wave'), {after: 700});
  await h.click(names.getByRole('button', {name: 'Add name'}), {after: 800});
  await h.hover(row('salute').getByLabel('Name salute'), 400);
  await h.result(1800);
  await h.click(row('salute').getByRole('button', {name: 'Open animus:knight/wave'}), {after: 900});
  await h.hover(page.getByLabel('Timeline', {exact: true}).getByRole('combobox', {name: 'Animation', exact: true}), 400);
  await h.result(2300);
  return L.finish(f, {closing: 'salute now plays the wave animation, and wave is open'});
};
