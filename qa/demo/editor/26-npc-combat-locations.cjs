// The North Guard's combat module: adds players to its targets, then gives
// avoidance a range and a return point placed on the position grid.
// Fake server data: --data=../qa/demo/editor/fixtures/town-deep (from animus-web/).
module.exports = async (L) => {
  const {film: f, page, h} = await L.pairedSession('26-npc-combat-locations');
  const side = page.getByRole('complementary', {name: 'Documents'});
  await page.waitForTimeout(1500);
  f.mark();
  await h.pause(300);
  await h.click(side.getByText('North Guard', {exact: true}).first(), {after: 1200});
  const combat = page.getByRole('region', {name: 'Combat editor'});
  const cats = combat.getByRole('group', {name: 'targets.categories'});
  await h.reveal(cats);
  await h.hover(cats.getByRole('button', {name: 'monsters'}), 900);
  await h.click(cats.getByRole('button', {name: 'players'}), {after: 900});
  await h.result(1500);
  // Combat's parts are <details> whose summary is only a tooltip text; the part is found by that text.
  const avoid = combat.locator('details').filter({has: page.getByText('Avoidance', {exact: true})}).first();
  await h.click(avoid.getByText('Avoidance', {exact: true}), {after: 900});
  await h.type(avoid.getByLabel('avoidance.range', {exact: true}), '12', {after: 300});
  await h.click(avoid.getByRole('button', {name: 'Set return point'}), {after: 1000});
  const grid = avoid.getByRole('img', {name: /^Return point/});
  await h.reveal(grid);
  await h.result(1500);
  const box = await grid.boundingBox();
  await h.moveTo(grid.getByRole('button', {name: /^Return point at/}), {noReveal: true});
  await h.pause(400);
  await page.mouse.down();
  await h.glide(box.x + box.width * 0.22, box.y + box.height * 0.25);
  await page.mouse.up();
  await h.pause(700);
  await h.hover(avoid.getByRole('group', {name: 'Return point'}), 600);
  await h.result(2200);
  return L.finish(f, {closing: 'North Guard targets players and flees to a return point'});
};
