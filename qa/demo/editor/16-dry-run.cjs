// A behavior's Preview dry run: start the innkeeper's dialogue from its
// click trigger, step through what it says and asks, answer a prompt.
// Offline: no fake server needed. The example packs workspace is made before filming.
module.exports = async (L) => {
  const f = await L.session({id: '16-dry-run'});
  await f.setup.goto(`${L.SITE}/#/`);
  await f.setup.getByRole('button', {name: 'Create with example packs'}).click();
  await f.setup.waitForFunction(() => location.hash.startsWith('#/workspace/'));
  const ws = f.setup.url().split('#')[1].split('/').slice(0, 3).join('/');
  const page = await f.start(`${L.SITE}/#${ws}`);
  const h = L.human(f);
  await page.getByRole('heading', {name: 'Example packs', level: 1}).waitFor();
  await page.waitForTimeout(1500);
  f.mark();
  await h.click(page.getByRole('link', {name: 'Dry-run dialogue:innkeeper'}), {after: 1200});
  const run = page.getByRole('region', {name: 'Dry run'});
  await run.getByRole('group', {name: 'Start the dry run'}).waitFor();
  await h.hover(run.getByRole('group', {name: 'Start the dry run'}), 1000);
  await h.click(run.getByRole('button', {name: /^click: /}), {after: 1000});
  const step = run.getByRole('toolbar', {name: 'Dry run controls'}).getByRole('button', {name: 'Step', exact: true});
  await h.click(step, {after: 1000});
  await h.click(step, {after: 1000});
  await run.getByRole('group', {name: 'Answer topic'}).waitFor();
  await h.result(2000);
  await h.click(run.getByRole('button', {name: 'Heard any rumours?'}), {after: 1200});
  await h.click(step, {after: 1000});
  await run.getByRole('group', {name: 'Answer mine'}).waitFor();
  await h.result(1800);
  await h.click(run.getByRole('button', {name: 'Tell me more'}), {after: 1000});
  for (let i = 0; i < 4 && await step.isEnabled().catch(() => false); i++) await h.click(step, {after: 900});
  await h.hover(run.getByRole('list', {name: 'Steps'}).getByRole('listitem').last(), 400);
  await h.result(2200);
  return L.finish(f, {closing: 'The innkeeper ran in the browser, answers and all'});
};
