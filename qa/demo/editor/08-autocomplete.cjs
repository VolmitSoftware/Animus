// Placeholder and colour tag completion in a new conversation: {player_name}
// and <red> in a line, then a Give item step's item picked by its name.
// Fake server data: --data=test/fixtures/npc_browser (from animus-web/).
module.exports = async (L) => {
  const {film: f, page, h} = await L.pairedSession('08-autocomplete', {path: '/list/behavior'});
  // Unfilmed: a new conversation to type in.
  await page.getByRole('button', {name: 'New behavior'}).click();
  const dialog = page.getByRole('dialog', {name: 'New behavior'});
  await dialog.getByRole('textbox', {name: 'Name'}).fill('Harbor welcome');
  await dialog.getByRole('button', {name: 'Create', exact: true}).click();
  await page.getByRole('heading', {name: 'Harbor welcome', level: 1}).waitFor();
  await L.clearToasts(page);
  await page.mouse.move(f.pos.x, f.pos.y);
  await page.waitForTimeout(1200);
  // Types into the focused field at the clip's typing speed.
  const say = async (text, hold = 500) => {
    await page.keyboard.type(text, {delay: L.slow(60)});
    await h.pause(hold);
  };
  f.mark();
  await h.pause(300);
  await h.click(page.getByRole('combobox', {name: 'Line 1 of Start'}), {after: 200});
  await h.key('ControlOrMeta+a', 200);
  await say('Hello ', 200);
  await say('{', 1500);
  await h.key('Enter', 700);
  await say(', ', 200);
  await say('<re', 1500);
  await h.key('Enter', 700);
  await say('welcome aboard', 900);
  await h.result(1500);
  await h.click(page.getByRole('toolbar', {name: 'Add to Start'}).getByRole('button', {name: 'Add step'}), {after: 500});
  await h.click(page.getByRole('menuitem', {name: /^Do/}), {after: 800, noReveal: true});
  const step = page.getByRole('toolbar', {name: 'Do 3'});
  await h.pick(step.getByRole('combobox', {name: 'Action'}), 'animus:give-item', {after: 600});
  // Simple's item field lists items by name; typed words narrow the list.
  const item = page.getByRole('combobox', {name: 'Item', exact: true});
  await h.click(item, {after: 100, rest: 200});
  await say('diamond sw', 1200);
  await h.click(page.getByRole('listbox', {name: 'Items for Item'}).getByRole('option', {name: 'Diamond sword', exact: true}), {after: 900, noReveal: true});
  await h.result(2000);
  return L.finish(f, {closing: 'Completed {player_name} and <red>, then picked Diamond sword by name'});
};
