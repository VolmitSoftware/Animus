// The North Guard's combat: Who it fights becomes Monsters and players, then
// in Advanced its Avoidance gets a range and a return point dragged on the
// position grid.
// Fake server data: --data=../qa/demo/editor/fixtures/town-deep (from animus-web/).
module.exports = async (L) => {
  const {film: f, page, h} = await L.pairedSession('26-npc-combat-locations', {path: '/npcs'});
  const list = page.getByRole('listbox', {name: 'NPCs'});
  await list.waitFor();
  await page.waitForTimeout(1200);
  f.mark();
  await h.pause(300);
  await h.click(list.getByRole('option', {name: /^North Guard/}), {after: 1400});
  await h.click(page.getByRole('tablist', {name: 'NPC sections'}).getByRole('tab', {name: 'Combat'}), {after: 900});
  const panel = page.getByRole('tabpanel', {name: 'Combat'});
  await h.pick(panel.getByRole('combobox', {name: 'Who it fights'}), 'monstersAndPlayers', {after: 900});
  await h.result(1500);
  await h.mode('advanced');
  // Avoidance is a disclosure whose <summary> has no accessible name; it is found by its title.
  const avoid = panel.locator('details', {hasText: 'Avoidance'}).last();
  await h.click(avoid.locator('summary').first(), {ox: 0.97, after: 900});
  await h.type(avoid.getByRole('spinbutton', {name: 'Range'}), '12', {clear: true, after: 300});
  await h.click(avoid.getByRole('button', {name: 'Set return point'}), {after: 1000});
  const grid = avoid.getByRole('img', {name: /^Return point/});
  const point = grid.getByRole('button', {name: /^Return point at/});
  await h.reveal(point);
  await h.result(1500);
  const box = await grid.boundingBox();
  await h.moveTo(point, {noReveal: true});
  await h.pause(400);
  await page.mouse.down();
  await h.glide(box.x + box.width * 0.25, box.y + box.height * 0.3);
  await page.mouse.up();
  await h.pause(700);
  // Rest past the point's Z field so the new coordinates stay readable; the page
  // first scrolls them up off the closing caption's line.
  await h.moveTo(avoid.getByRole('spinbutton', {name: 'Return point Z'}), {ox: 1.8});
  await page.mouse.wheel(0, 160);
  await h.pause(400);
  await h.moveTo(avoid.getByRole('spinbutton', {name: 'Return point Z'}), {ox: 1.8, noReveal: true});
  await h.pause(600);
  await h.result(2200);
  return L.finish(f, {closing: 'North Guard fights monsters and players, and flees to a return point'});
};
