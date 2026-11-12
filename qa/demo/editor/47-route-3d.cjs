// The 3D route view: the herald's own waypoints drawn over the town's real blocks,
// framing each point picked from the list, then an orbit around it.
// Needs a real server (ANIMUS_PAIR_LINK, default scopes, which include terrain) on the town stage,
// with waypoints of its own on town/herald, set from the console before recording:
//   animus edit town/herald {"modules":{"animus:navigation":{"enabled":true,"loop":true,"speed":1,"waitTicks":40,
//     "points":[{"x":5,"y":64,"z":5},{"x":12,"y":64,"z":0},{"x":16,"y":64,"z":-10},{"x":-16,"y":64,"z":-10},{"x":-12,"y":64,"z":6}]}}}
module.exports = async (L) => {
  const f = await L.session({id: '47-route-3d'});
  const page = await f.start();
  page.setDefaultTimeout(30000);
  await pairReal(L, page);
  await page.getByRole('checkbox', {name: 'town/herald', exact: true}).check();
  await page.getByRole('button', {name: /^Open 1/}).click();
  await page.getByRole('complementary', {name: 'Documents'}).getByText('Herald', {exact: true}).click();
  const grid = page.getByRole('img', {name: /^Route grid/});
  await grid.waitFor();
  await dismissToasts(page);
  // Start at the route section rather than scrolling past the whole NPC form on camera.
  await page.getByRole('heading', {name: 'Route'}).first().scrollIntoViewIfNeeded();
  await page.evaluate(() => window.scrollBy(0, 120));
  await page.waitForTimeout(1500);
  const h = L.human(f);
  f.mark();
  await h.pause(300);
  await h.hover(grid, 600);
  await h.result(1800);
  // The 3D toggle's name "3D" is not unique on the page once the view opens; it carries data-route-3d-toggle.
  await h.click(page.locator('[data-route-3d-toggle]'), {after: 300});
  const view = page.getByRole('img', {name: '3D route view'});
  await view.waitFor();
  await page.waitForTimeout(1500);
  await h.hover(view, 400);
  await h.result(2500);
  const points = page.getByRole('list', {name: 'Route points'});
  await h.click(points.getByRole('button', {name: /^2 point 16, 64, -10/}), {after: 300});
  await h.reveal(view);
  await page.waitForTimeout(800);
  await h.hover(view, 300);
  await h.result(2200);
  await drag(h, f, view, [0.6, 0.5], [0.45, 0.45]);
  await h.result(2500);
  return L.finish(f, {closing: 'The route in 3D over the town, framed on point 2'});
};

/** Pairs with the real server from ANIMUS_PAIR_LINK (unfilmed) and waits until it is online. */
async function pairReal(L, page) {
  const link = (process.env.ANIMUS_PAIR_LINK || '').trim();
  if (!link) throw new Error('47-route-3d needs a real server: pass --pair=<link>');
  await L.pair(page, {code: link.slice(link.indexOf('#/pair/') + 7), remember: false});
  // The connection pill has no accessible name of its own; it is found by class and phase.
  await page.locator('.animus-live .animus-pill[data-phase="online"]').waitFor();
  await page.getByText(/documents on the server/).waitFor();
}

/** Presses on [loc] at [from] (fractions of its box), glides to [to] and releases: an orbit. */
async function drag(h, f, loc, from, to) {
  const b = await loc.first().boundingBox();
  await h.glide(b.x + b.width * from[0], b.y + b.height * from[1]);
  await h.pause(300);
  await f.page.mouse.down();
  await h.glide(b.x + b.width * to[0], b.y + b.height * to[1]);
  await f.page.mouse.up();
}

/** Closes the pairing and open toasts (L.dismiss can hit a document's own Close button). */
async function dismissToasts(page) {
  const close = page.getByRole('region', {name: 'Notifications'}).getByRole('button', {name: 'Dismiss'});
  while (await close.count()) await close.first().click();
}
