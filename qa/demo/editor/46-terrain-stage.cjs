// The NPC preview stage drawing the server's real terrain around the guard and its patrol:
// terrain off and on, a wider radius, then an orbit along the route.
// Needs a real server (ANIMUS_PAIR_LINK, default scopes, which include terrain) on the town stage,
// with town/guard walking the patrol route.
module.exports = async (L) => {
  const f = await L.session({id: '46-terrain-stage'});
  const page = await f.start();
  page.setDefaultTimeout(30000);
  await pairReal(L, page);
  // The patrol route joins the workspace too, so the stage draws it and the ground along it.
  await page.getByRole('checkbox', {name: 'town/guard', exact: true}).check();
  await page.getByRole('checkbox', {name: 'patrol', exact: true}).check();
  await page.getByRole('button', {name: /^Open 2/}).click();
  await page.getByRole('complementary', {name: 'Documents'}).getByText('Guard', {exact: true}).click();
  await page.getByRole('tab', {name: 'Visual', exact: true}).waitFor();
  await dismissToasts(page);
  await page.waitForTimeout(1200);
  const h = L.human(f);
  f.mark();
  await h.pause(300);
  await h.click(page.getByRole('tab', {name: 'Preview', exact: true}), {after: 300});
  // The preview panel has no accessible name; it reports its terrain meshes as a data attribute.
  const panel = page.locator('.animus-npc-preview');
  await panel.and(page.locator(':not([data-terrain-meshes="0"])')).waitFor();
  const stage = page.getByRole('img', {name: 'Preview of Guard'});
  await h.hover(stage, 400);
  await h.result(2500);
  const terrain = page.getByRole('group', {name: 'Terrain'}).getByRole('button', {name: 'Terrain', exact: true});
  await h.click(terrain, {after: 300});
  await h.result(2000);
  await h.click(terrain, {after: 300});
  await panel.and(page.locator(':not([data-terrain-meshes="0"])')).waitFor();
  await h.result(1500);
  await h.click(page.getByRole('combobox', {name: 'Terrain radius'}), {after: 400});
  await h.click(L.option(page, '24'), {after: 300});
  await page.waitForTimeout(1500);
  await h.result(2000);
  await drag(h, f, stage, [0.6, 0.45], [0.38, 0.52]);
  await h.result(2500);
  return L.finish(f, {closing: 'The stage draws the server blocks around the guard and its patrol'});
};

/** Pairs with the real server from ANIMUS_PAIR_LINK (unfilmed) and waits until it is online. */
async function pairReal(L, page) {
  const link = (process.env.ANIMUS_PAIR_LINK || '').trim();
  if (!link) throw new Error('46-terrain-stage needs a real server: pass --pair=<link>');
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
