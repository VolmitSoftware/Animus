// Health and death: makes the Town Crier killable, sets its health, armor
// and regeneration, and a respawn delay.
// Fake server data: --data=../qa/demo/editor/fixtures/town-deep (from animus-web/).
module.exports = async (L) => {
  const {film: f, page, h} = await L.pairedSession('25-npc-health');
  const side = page.getByRole('complementary', {name: 'Documents'});
  await page.waitForTimeout(1500);
  f.mark();
  await h.pause(300);
  await h.click(side.getByText('Town Crier', {exact: true}).first(), {after: 1200});
  const mods = L.section(page, 'modules');
  const off = mods.getByText('Off: cannot be hurt.');
  await h.reveal(off);
  await h.hover(off, 1200);
  await h.click(mods.getByRole('button', {name: 'Make killable'}), {after: 1000});
  const he = page.getByRole('region', {name: 'Health editor'});
  await h.type(he.getByLabel('health.maximumHealth', {exact: true}), '40', {after: 300});
  await h.type(he.getByLabel('health.armor', {exact: true}), '0.3', {after: 300});
  await h.click(he.getByRole('checkbox', {name: 'Regenerate'}), {after: 900});
  await h.result(1600);
  const after = he.getByRole('combobox', {name: 'health.respawn.afterDeath'});
  await h.hover(after, 1200);
  await h.type(he.getByLabel('health.respawn.delayTicks', {exact: true}), '200', {after: 300});
  await page.keyboard.press('Tab');
  await h.result(2200);
  return L.finish(f, {closing: 'Town Crier: 40 health, 0.3 armor, regenerates, respawns after 200 ticks'});
};
