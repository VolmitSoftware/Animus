// The start page with no server: the three ways in, then the examples opened
// as a workspace, and back on the start page, Continue's row menu renames,
// duplicates, deletes and restores a workspace.
// Offline: no fake server needed.
module.exports = async (L) => {
  const f = await L.session({id: '13-offline-home'});
  const page = await f.start(`${L.SITE}/#/`);
  const h = L.human(f);
  await page.getByRole('heading', {name: 'Animus web editor'}).waitFor();
  await page.waitForTimeout(1200);
  f.mark();
  await h.hover(page.getByRole('heading', {name: /^Pair a server/}), 900);
  await h.hover(page.getByRole('heading', {name: 'Try the examples'}), 900);
  await h.hover(page.getByRole('heading', {name: /^Open my files/}), 900);
  await h.click(page.getByRole('button', {name: 'Open examples'}), {after: 1200});
  await page.getByRole('heading', {name: 'Example packs', level: 1}).waitFor();
  await L.clearToasts(page);
  await h.result(1800);
  await h.click(page.getByRole('link', {name: 'Animus, all workspaces'}), {after: 1000, noReveal: true});
  const list = page.getByRole('list', {name: 'Continue'});
  await list.waitFor();
  const menuOf = (name) => list.getByRole('toolbar', {name, exact: true}).getByRole('button', {name: 'More actions'});
  await h.hover(list.getByText(/25 documents/).first(), 1200);
  await h.click(menuOf('Example packs'), {after: 500});
  await h.click(page.getByRole('menuitem', {name: 'Rename'}), {after: 700, noReveal: true});
  const rename = page.getByRole('dialog').filter({has: page.getByRole('textbox')}).first();
  await h.type(rename.getByRole('textbox').first(), 'Harbor examples', {clear: true});
  await h.click(rename.getByRole('button', {name: 'Rename', exact: true}), {after: 900, noReveal: true});
  await h.click(menuOf('Harbor examples'), {after: 500});
  await h.click(page.getByRole('menuitem', {name: 'Duplicate'}), {after: 900, noReveal: true});
  await list.getByRole('toolbar', {name: 'Harbor examples copy', exact: true}).waitFor();
  await h.result(1600);
  await h.click(menuOf('Harbor examples copy'), {after: 500});
  await h.click(page.getByRole('menuitem', {name: 'Delete workspace'}), {after: 700, noReveal: true});
  const confirm = page.getByRole('alertdialog');
  await h.moveTo(confirm.getByText(/Harbor examples copy/).first(), {ox: 1.06, noReveal: true});
  await h.pause(1200);
  await h.click(confirm.getByRole('button', {name: /^Delete/}), {after: 900, noReveal: true});
  await h.click(page.getByRole('button', {name: /^Restore/}).first(), {after: 900, noReveal: true});
  await list.getByRole('toolbar', {name: 'Harbor examples copy', exact: true}).waitFor();
  // Rest beside the restored row rather than on the row the list moved under the cursor.
  await h.moveTo(list.getByText('Harbor examples copy', {exact: true}), {ox: 1.4, noReveal: true});
  await h.result(1800);
  return L.finish(f, {closing: 'Three ways to start; workspaces rename, copy and restore'});
};
