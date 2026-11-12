// The home screen with no server: the three ways to start a workspace, then
// renaming, duplicating, deleting and restoring one in the workspace list.
// Offline: no fake server needed.
module.exports = async (L) => {
  const f = await L.session({id: '13-offline-home'});
  const page = await f.start(`${L.SITE}/#/`);
  const h = L.human(f);
  await page.getByRole('heading', {name: 'Start a workspace'}).waitFor();
  await page.waitForTimeout(1200);
  f.mark();
  await h.hover(page.getByRole('textbox', {name: 'Workspace name'}), 900);
  await h.hover(page.getByRole('button', {name: 'Create with example packs'}), 900);
  await h.hover(page.getByRole('button', {name: 'Open zip'}), 900);
  await h.click(page.getByRole('button', {name: 'Create with example packs'}), {after: 1200});
  await page.getByRole('heading', {name: 'Example packs', level: 1}).waitFor();
  await h.result(1800);
  await h.click(page.getByRole('link', {name: 'Animus Editor'}), {after: 1000});
  const list = page.getByRole('region', {name: 'Workspaces in this browser'});
  await h.hover(list.getByText('25 documents, local'), 1200);
  await h.click(list.getByRole('button', {name: 'Rename Example packs'}), {after: 500});
  await h.type(list.getByRole('textbox', {name: 'New name for Example packs'}), 'Harbor examples', {clear: true});
  await h.click(list.getByRole('button', {name: 'Rename', exact: true}), {after: 900});
  await h.click(list.getByRole('button', {name: 'Duplicate Harbor examples'}), {after: 900});
  await list.getByRole('link', {name: 'Harbor examples copy'}).waitFor();
  await h.result(1600);
  await h.click(list.getByRole('button', {name: 'Delete Harbor examples copy'}), {after: 700});
  await h.hover(page.getByRole('alertdialog', {name: 'Delete Harbor examples copy?'}), 1200);
  await h.click(page.getByRole('button', {name: 'Delete workspace'}), {after: 900});
  await h.click(page.getByRole('button', {name: /^Restore/}).first(), {after: 900});
  await list.getByRole('link', {name: 'Harbor examples copy'}).waitFor();
  await h.result(1800);
  return L.finish(f, {closing: 'Three ways to start; workspaces rename, copy and restore'});
};
