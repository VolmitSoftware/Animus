// Edits a random route: a wait range, a random seed, new points on the grid and one point's own wait and jitter.
module.exports = async (L) => {
  const {film: f, page, h} = await L.pairedSession('07-routes');
  await page.getByRole('complementary', {name: 'Documents'}).getByRole('link', {name: 'town-patrol'}).click();
  const editor = page.getByRole('group', {name: 'Route editor'});
  await editor.waitFor();
  await page.waitForTimeout(1200);
  f.mark();
  await h.pause(300);
  await h.hover(editor.getByRole('combobox', {name: 'Order'}), 1000);
  await h.click(editor.getByRole('button', {name: 'route wait range'}), {after: 500});
  await h.type(editor.getByLabel('route waitTicks min', {exact: true}), '30', {clear: true, after: 300});
  await h.type(editor.getByLabel('route waitTicks max', {exact: true}), '90', {clear: true, after: 300});
  await h.click(editor.getByRole('combobox', {name: 'Seed'}), {after: 500});
  await h.click(L.option(page, 'random'), {after: 700, noReveal: true});
  const grid = editor.getByRole('img', {name: /^Route grid/});
  await h.reveal(grid);
  // Empty spots on the fixture's grid (x -3 to 13, z 7 to 23).
  for (const [ox, oy] of [[0.375, 0.82], [0.12, 0.5]]) {
    await h.click(grid, {ox, oy, after: 600, noReveal: true});
  }
  await h.result(1500);
  await h.click(grid.getByRole('button', {name: /^Point 1 at/}), {after: 700, noReveal: true});
  const picked = editor.getByRole('group', {name: 'Selected Point 1'});
  await h.type(picked.getByLabel('Point 1 waitTicks', {exact: true}), '60', {after: 300});
  await h.type(picked.getByLabel('Point 1 jitter', {exact: true}), '1', {after: 300});
  await h.result(2000);
  return L.finish(f, {closing: 'town-patrol waits 30 to 90 ticks; point 1 waits 60 with jitter 1'});
};
