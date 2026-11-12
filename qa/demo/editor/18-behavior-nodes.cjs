// The behavior node editor: the greeter's routines as a tree, steps closed
// to one plain line ("Say: ..."), and a new Say step added to a routine.
// Offline: no fake server needed. The example packs workspace is made before filming.
module.exports = async (L) => {
  const f = await L.session({id: '18-behavior-nodes'});
  await f.setup.goto(`${L.SITE}/#/`);
  await f.setup.getByRole('button', {name: 'Create with example packs'}).click();
  await f.setup.waitForFunction(() => location.hash.startsWith('#/workspace/'));
  const ws = f.setup.url().split('#')[1].split('/').slice(0, 3).join('/');
  const page = await f.start(`${L.SITE}/#${ws}/behavior/greeter%3Agreeter`);
  const h = L.human(f);
  await page.getByRole('tab', {name: 'Visual'}).click();
  await page.getByRole('region', {name: 'Behavior editor'}).waitFor();
  await page.waitForTimeout(1500);
  // A step's header is a <summary> with no accessible name; it is found by the plain-words line it shows.
  const header = (text) => page.locator('summary', {hasText: text}).first();
  const steps = page.getByRole('group', {name: 'Steps of main'});
  f.mark();
  await h.hover(page.getByRole('textbox', {name: 'Routine 1 name'}), 900);
  await h.click(header('Look at the player'), {ox: 0.3, after: 700});
  await h.click(header('Say: Welcome, &etraveller'), {ox: 0.3, after: 500});
  await h.click(header('Play sound minecraft:entity.villager.ambient'), {ox: 0.3, after: 500});
  await h.click(header('Set flag met (relationship) to true'), {ox: 0.3, after: 500});
  await h.click(header('Good to see you again'), {ox: 0.3, after: 700});
  await h.hover(header('Say: Welcome, &etraveller'), 900);
  await h.click(header('If flag met (relationship) is true'), {ox: 0.3, after: 700});
  await h.result(1800);
  const add = steps.getByRole('combobox', {name: 'Add step'}).last();
  await h.click(add, {after: 600});
  await h.click(page.getByRole('option', {name: 'action', exact: true}), {after: 1000});
  const text = steps.getByRole('combobox', {name: 'Text', exact: true}).last();
  await h.type(text, 'The ferry leaves at dusk.', {clear: true});
  await h.click(header('Say: The ferry leaves at dusk.'), {ox: 0.3, after: 800});
  await h.hover(header('Say: The ferry leaves at dusk.'), 600);
  await h.result(2200);
  return L.finish(f, {closing: 'main now ends with Say: The ferry leaves at dusk.'});
};
