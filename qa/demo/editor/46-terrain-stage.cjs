// The NPC preview drawing the server's real terrain around the guard and its patrol:
// terrain off and on, a wider radius from the preview's menu, then an orbit along the route.
// Needs a real server (ANIMUS_PAIR_LINK; a console pairing has every scope, terrain included)
// on the town stage, with town/guard walking the patrol route.
module.exports = async (L) => {
  const {film: f, page, h} = await L.pairedSession('46-terrain-stage', {path: '/npcs', real: true});
  await page.getByRole('listbox', {name: 'NPCs'}).getByRole('option', {name: /^Guard/}).first().click();
  await page.getByRole('heading', {name: 'Guard', level: 1}).waitFor();
  // The stage reports how many terrain meshes it holds only as a data attribute.
  const terrainDrawn = () => page.waitForFunction(() => {
    const host = document.querySelector('[data-terrain-meshes]');
    return host && host.getAttribute('data-terrain-meshes') !== '0';
  }, null, {timeout: 30000});
  await terrainDrawn();
  await L.clearToasts(page);
  await page.mouse.move(f.pos.x, f.pos.y);
  await page.waitForTimeout(2000);
  const preview = page.getByRole('region', {name: 'Preview'});
  const stage = preview.getByRole('img', {name: 'Preview of Guard'});
  const show = preview.getByRole('group', {name: 'Show'});
  f.mark();
  await h.pause(300);
  await h.hover(stage, 400);
  await h.result(2500);
  await h.click(show.getByRole('button', {name: 'Terrain', exact: true}), {after: 300, noReveal: true});
  await h.result(2000);
  await h.click(show.getByRole('button', {name: 'Terrain', exact: true}), {after: 300, noReveal: true});
  await terrainDrawn();
  await h.result(1500);
  await h.click(preview.getByRole('button', {name: 'More actions'}), {after: 500, noReveal: true});
  await h.click(page.getByRole('menuitem', {name: /^24 block radius/}), {after: 300, noReveal: true});
  await page.waitForTimeout(1500);
  await h.result(2000);
  await drag(h, f, stage, [0.6, 0.45], [0.38, 0.52]);
  await h.result(2500);
  return L.finish(f, {closing: 'The stage draws the server blocks around the guard and its patrol'});
};

/** Presses on [loc] at [from] (fractions of its box), glides to [to] and releases: an orbit. */
async function drag(h, f, loc, from, to) {
  const b = await loc.first().boundingBox();
  await h.glide(b.x + b.width * from[0], b.y + b.height * from[1]);
  await h.pause(300);
  await f.page.mouse.down();
  await h.glide(b.x + b.width * to[0], b.y + b.height * to[1]);
  await f.page.mouse.up();
}
