// New document in a blank browser workspace: N, the Behavior kind and its
// greeter template, read in Simple's plain sentences, then Edit in Advanced
// and the behavior's Steps, Code and Split views.
// Offline: no fake server needed. The blank workspace is made before filming.
module.exports = async (L) => {
  const f = await L.session({id: '14-new-document'});
  const ws = await L.blankWorkspace(f.setup, 'Harbor town');
  const page = await f.start(`${L.SITE}/${ws}`);
  const h = L.human(f);
  await page.getByRole('heading', {name: 'Harbor town', level: 1}).waitFor();
  await page.waitForTimeout(1500);
  await page.evaluate(() => document.activeElement && document.activeElement.blur());
  f.mark();
  await h.hover(page.getByText('No documents yet.'), 900);
  await h.key('n', 1000);
  await page.getByRole('heading', {name: 'New document', level: 1}).waitFor();
  await h.pick(page.getByRole('combobox', {name: 'Kind'}), 'behavior', {after: 1000});
  await page.getByRole('heading', {name: 'New behavior', level: 1}).waitFor();
  await h.hover(page.getByRole('combobox', {name: 'Start from'}), 1000);
  await h.click(page.getByRole('main').getByRole('button', {name: 'Create', exact: true}), {after: 1500});
  await page.waitForFunction(() => /\/behavior\//.test(location.hash));
  await L.clearToasts(page);
  await h.hover(page.getByRole('region', {name: 'What this behavior does'}).getByRole('listitem').nth(3), 1200);
  await h.result(1600);
  await h.click(page.getByRole('button', {name: 'Edit in Advanced'}), {after: 1000});
  const views = page.getByRole('toolbar', {name: 'Page actions'}).getByRole('group', {name: 'View'});
  await h.click(views.getByRole('button', {name: 'Code'}), {noReveal: true, after: 700});
  await h.result(1800);
  await h.click(views.getByRole('button', {name: 'Split'}), {noReveal: true, after: 700});
  await h.result(1800);
  await h.click(views.getByRole('button', {name: 'Steps'}), {noReveal: true, after: 700});
  await h.result(1800);
  return L.finish(f, {closing: 'A new greeter behavior, in plain words, then its Steps, Code and Split views'});
};
