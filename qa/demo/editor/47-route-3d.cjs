// The route's 3D view: the herald's own points drawn over the town's real blocks,
// framing a point picked from the list, then an orbit around it.
// Needs a real server (ANIMUS_PAIR_LINK; a console pairing has every scope, terrain included)
// on the town stage, with points of its own on town/herald, set from the console before recording:
//   animus edit town/herald {"modules":{"animus:navigation":{"enabled":true,"loop":true,"speed":1,"waitTicks":40,
//     "points":[{"x":5,"y":64,"z":5},{"x":12,"y":64,"z":0},{"x":16,"y":64,"z":-10},{"x":-16,"y":64,"z":-10},{"x":-12,"y":64,"z":6}]}}}
module.exports = async (L) => {
  const {film: f, page, h} = await L.pairedSession('47-route-3d', {path: '/npcs', real: true});
  await page.getByRole('listbox', {name: 'NPCs'}).getByRole('option', {name: /^Herald/}).first().click();
  await page.getByRole('heading', {name: 'Herald', level: 1}).waitFor();
  await L.clearToasts(page);
  await page.mouse.move(f.pos.x, f.pos.y);
  await page.waitForTimeout(1500);
  const editor = page.getByRole('group', {name: 'Route editor'});
  f.mark();
  await h.pause(300);
  await h.click(page.getByRole('tablist', {name: 'NPC sections'}).getByRole('tab', {name: 'Movement'}), {after: 900});
  const map = editor.getByRole('img', {name: /^Route map/});
  await map.waitFor();
  await h.hover(map, 600);
  await h.result(1800);
  await h.click(editor.getByRole('group', {name: 'Route tool'}).getByRole('button', {name: '3D'}), {after: 300});
  const view = editor.getByRole('img', {name: '3D route view'});
  await view.waitFor();
  // The 3D view reports its terrain meshes only as a data attribute.
  await page.waitForFunction(() => {
    const host = document.querySelector('[data-route-3d][data-terrain-meshes]');
    return host && host.getAttribute('data-terrain-meshes') !== '0';
  }, null, {timeout: 30000});
  await page.waitForTimeout(1000);
  await h.hover(view, 400);
  await h.result(2500);
  await h.click(editor.getByRole('listbox', {name: 'Route points'}).getByRole('option', {name: /^Point 3 /}), {after: 300});
  await h.reveal(view);
  await page.waitForTimeout(800);
  await h.hover(view, 300);
  await h.result(2200);
  await drag(h, f, view, [0.6, 0.5], [0.45, 0.45]);
  await h.result(2500);
  return L.finish(f, {closing: 'The route in 3D over the town, framed on point 3'});
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
