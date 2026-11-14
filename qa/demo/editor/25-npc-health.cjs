// Health and death on the Combat tab: makes the Town Crier killable with 40
// health, then in Advanced gives it armor, regeneration and a respawn delay.
// Fake server data: --data=../qa/demo/editor/fixtures/town-deep (from animus-web/).
module.exports = async (L) => {
  const {film: f, page, h} = await L.pairedSession('25-npc-health', {path: '/npcs'});
  const list = page.getByRole('listbox', {name: 'NPCs'});
  await list.waitFor();
  await page.waitForTimeout(1200);
  f.mark();
  await h.pause(300);
  await h.click(list.getByRole('option', {name: /^Town Crier/}), {after: 1400});
  await h.click(page.getByRole('tablist', {name: 'NPC sections'}).getByRole('tab', {name: 'Combat'}), {after: 900});
  const panel = page.getByRole('tabpanel', {name: 'Combat'});
  await h.click(panel.getByRole('switch', {name: 'Can be hurt'}), {after: 900});
  await h.type(panel.getByRole('spinbutton', {name: 'Max health'}), '40', {clear: true, after: 300});
  await h.moveTo(panel.getByRole('paragraph').filter({hasText: 'hearts'}), {ox: 1.2});
  await h.result(1600);
  await h.mode('advanced');
  // Damage and Respawn are disclosures whose <summary> has no accessible name; each is found by its title.
  const part = (title) => panel.locator('details', {hasText: title}).last();
  await h.click(part('Damage').locator('summary').first(), {ox: 0.97, after: 900});
  await h.type(panel.getByRole('spinbutton', {name: 'Armor'}), '0.3', {after: 300});
  await h.click(panel.getByRole('switch', {name: 'Regenerates'}), {after: 900});
  await h.result(1500);
  await h.click(part('Respawn').locator('summary').first(), {ox: 0.97, after: 900});
  await h.hover(panel.getByRole('combobox', {name: 'After death'}), 1200);
  await h.type(panel.getByRole('spinbutton', {name: 'Delay'}), '200', {after: 300});
  await page.keyboard.press('Enter');
  await h.moveTo(panel.getByRole('spinbutton', {name: 'Delay'}), {ox: 1.6});
  await h.result(2200);
  return L.finish(f, {closing: 'Town Crier: 40 health, 0.3 armor, regenerates, respawns after 200 ticks'});
};
