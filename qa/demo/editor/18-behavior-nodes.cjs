// The behavior editor: the greeter reads as plain sentences in Simple, Edit in
// Advanced opens its Steps, steps fold to one plain line ("Say: ..."), and a
// new Say step is added to the end of main.
// Offline: no fake server needed. The example workspace is made before filming.
module.exports = async (L) => {
  const f = await L.session({id: '18-behavior-nodes'});
  const ws = await L.examplesWorkspace(f.setup);
  const page = await f.start(`${L.SITE}/${ws}/list/behavior`);
  const h = L.human(f);
  await page.getByRole('listbox', {name: 'Behaviors'}).waitFor();
  await L.clearToasts(page);
  await page.waitForTimeout(1500);
  const editor = page.getByRole('region', {name: 'Behavior editor'});
  const steps = editor.getByRole('group', {name: 'Steps of main'});
  // A step's header is a <summary> with no accessible name; it is found by its "Step n" text.
  // Folded steps hide their inner steps, whose headers also start with "Step n", so only visible ones count.
  const header = (n) => steps.locator('summary:visible').filter({hasText: new RegExp(`^Step ${n}(?!\\d)`)}).first();
  f.mark();
  await h.pause(300);
  await h.click(page.getByRole('listbox', {name: 'Behaviors'}).getByRole('option', {name: /^Greeter/}), {after: 1400});
  const plain = page.getByRole('region', {name: 'What this behavior does'});
  await h.moveTo(plain.getByRole('listitem').filter({hasText: 'Look at the player'}).first(), {ox: 0.6});
  await h.pause(1400);
  await h.click(plain.getByRole('button', {name: 'Edit in Advanced'}), {after: 1400});
  await h.click(header(1), {ox: 0.4, after: 700});
  await h.click(header(2), {ox: 0.4, after: 900});
  await h.result(1600);
  // With step 2 folded, the first Add step on the page is main's own.
  await h.click(editor.getByRole('button', {name: 'Add step'}).first(), {after: 600});
  await h.click(page.getByRole('menuitem', {name: 'Do', exact: true}), {after: 1000, noReveal: true});
  const text = steps.getByRole('combobox', {name: 'Text'}).last();
  await h.type(text, 'The ferry leaves at dusk.', {clear: true});
  await h.key('Escape', 300);
  await h.click(header(3), {ox: 0.4, after: 800});
  await h.moveTo(header(3), {ox: 0.75});
  await h.pause(600);
  await h.result(2200);
  return L.finish(f, {closing: 'main now ends with Say: The ferry leaves at dusk.'});
};
