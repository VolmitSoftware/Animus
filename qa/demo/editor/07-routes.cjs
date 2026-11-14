// Makes a route from Movement: random order, a wait range and new picks each
// time under More settings, two points clicked onto the map, then one point's
// own wait and spread.
// Fake server data: --data=test/fixtures/npc_browser (from animus-web/).
module.exports = async (L) => {
  const {film: f, page, h} = await L.pairedSession('07-routes', {path: '/list/route'});
  await page.getByRole('button', {name: 'New route'}).waitFor();
  await page.waitForTimeout(800);
  f.mark();
  await h.pause(300);
  await h.hover(page.getByRole('tablist', {name: 'Movement'}).getByRole('tab', {name: 'Routes'}), 700);
  await h.click(page.getByRole('button', {name: 'New route'}), {after: 700});
  const dialog = page.getByRole('dialog', {name: 'New route'});
  await h.type(dialog.getByRole('textbox', {name: 'Name'}), 'Harbor walk', {after: 300});
  await h.click(dialog.getByRole('button', {name: 'Create', exact: true}), {after: 1200});
  await page.getByRole('heading', {name: 'Harbor walk', level: 1}).waitFor();
  await L.clearToasts(page);
  const editor = page.getByRole('group', {name: 'Route editor'});
  await h.pick(editor.getByRole('combobox', {name: 'Order'}), {label: 'Random'});
  // More settings is a native disclosure (details); its summary row has no name of its own.
  await h.click(editor.getByRole('group').filter({hasText: /^More settings/}).locator('summary').first(), {ox: 0.3, after: 700});
  await h.click(editor.getByRole('group', {name: 'Route wait kind'}).getByRole('button', {name: 'Range'}), {after: 500});
  await h.type(editor.getByRole('spinbutton', {name: 'Route shortest wait in seconds'}), '2', {clear: true, after: 300});
  await h.type(editor.getByRole('spinbutton', {name: 'Route longest wait in seconds'}), '5', {clear: true, after: 300});
  await h.pick(editor.getByRole('combobox', {name: 'Random picks'}), {label: 'New each time'});
  await h.result(1500);
  const map = editor.getByRole('img', {name: /^Route map/});
  await h.reveal(map);
  // Empty spots on the new route's map (x -5 to 11, z -5 to 11; the four points sit at 0 and 6).
  for (const [ox, oy] of [[0.86, 0.2], [0.2, 0.86]]) {
    await h.click(map, {ox, oy, after: 700, noReveal: true});
  }
  await h.result(1500);
  await h.click(map.getByRole('button', {name: /^Point 1 at/}), {after: 800, noReveal: true});
  const picked = editor.getByRole('group', {name: 'Selected Point 1'});
  await h.type(picked.getByRole('spinbutton', {name: 'Point 1 wait in seconds'}), '3', {after: 300});
  await h.click(picked.getByRole('group').filter({hasText: /^More/}).locator('summary').first(), {ox: 0.3, after: 700});
  await h.type(picked.getByRole('spinbutton', {name: /spread/i}), '1', {after: 300});
  await h.result(2000);
  return L.finish(f, {closing: 'Harbor walk: random order, waits 2 to 5 s; point 1 waits 3 s with spread 1'});
};
