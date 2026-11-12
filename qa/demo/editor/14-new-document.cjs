// New document in an offline workspace: pick a kind (a hologram), fill its
// first step, then look at the Visual, Preview, Code and Split views.
// Offline: no fake server needed. The blank workspace is made before filming.
module.exports = async (L) => {
  const f = await L.session({id: '14-new-document'});
  await f.setup.goto(`${L.SITE}/#/`);
  await f.setup.getByRole('textbox', {name: 'Workspace name'}).fill('Harbor town');
  await f.setup.getByRole('button', {name: 'Create blank workspace'}).click();
  await f.setup.waitForFunction(() => location.hash.startsWith('#/workspace/'));
  const ws = f.setup.url().split('#')[1].split('/').slice(0, 3).join('/');
  const page = await f.start(`${L.SITE}/#${ws}`);
  const h = L.human(f);
  await page.getByRole('link', {name: 'New document'}).waitFor();
  await page.waitForTimeout(1500);
  f.mark();
  await h.click(page.getByRole('link', {name: 'New document'}), {after: 900});
  const kinds = page.getByRole('navigation', {name: 'Document kinds'});
  await h.hover(kinds.getByRole('link', {name: /^Behavior/}), 600);
  await h.click(kinds.getByRole('link', {name: /^Hologram/}), {after: 900});
  await h.type(page.getByRole('textbox', {name: 'First line'}), '&6Welcome to the harbor');
  await h.type(page.getByRole('textbox', {name: 'Id'}), 'harbor:welcome', {clear: true});
  await h.click(page.getByRole('button', {name: 'Create hologram'}), {after: 1200});
  await page.getByRole('heading', {name: 'harbor:welcome', level: 1}).waitFor();
  await h.result(2000);
  const views = page.getByRole('tablist', {name: 'View'});
  await h.click(views.getByRole('tab', {name: 'Preview'}), {noReveal: true, after: 600});
  await h.result(1800);
  await h.click(views.getByRole('tab', {name: 'Code'}), {noReveal: true, after: 600});
  await h.result(1800);
  await h.click(views.getByRole('tab', {name: 'Split'}), {noReveal: true, after: 600});
  await h.result(2200);
  return L.finish(f, {closing: 'harbor:welcome is new, with Visual, Preview, Code and Split views'});
};
