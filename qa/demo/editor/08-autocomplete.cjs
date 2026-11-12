// Placeholder, colour tag and item id completion in a conversation.
module.exports = async (L) => {
  const {film: f, page, h} = await L.pairedSession('08-autocomplete');
  await page.getByRole('link', {name: 'New conversation'}).click();
  await page.getByRole('button', {name: 'Create behavior'}).click();
  const conv = page.getByRole('region', {name: 'Conversation', exact: true});
  await conv.waitFor();
  await conv.evaluate((e) => e.scrollIntoView({block: 'start'}));
  await page.mouse.move(f.pos.x, f.pos.y);
  await page.waitForTimeout(1200);
  // Types into the focused field at the clip's typing speed.
  const say = async (text, hold = 500) => {
    await page.keyboard.type(text, {delay: L.slow(60)});
    await h.pause(hold);
  };
  f.mark();
  await h.pause(300);
  await h.click(conv.getByRole('combobox', {name: 'NPC line 1 in main'}), {after: 200});
  await h.key('ControlOrMeta+a', 200);
  await say('Hello ', 200);
  await say('{', 1500);
  await h.key('Enter', 700);
  await say(', ', 200);
  await say('<re', 1500);
  await h.key('Enter', 700);
  await say('welcome aboard', 900);
  await h.result(1500);
  // Item id in a Give item action.
  await h.click(conv.getByRole('button', {name: 'Add a step to main'}), {after: 500});
  // The step menu can open below the page fold, so Action (third item) is picked from the keyboard;
  // the cursor rests on it so the viewer sees which item is taken.
  // Arrow keys scroll the menu into view, so the cursor goes to Action after them.
  await h.key('ArrowDown', 350);
  await h.key('ArrowDown', 450);
  await h.moveTo(page.getByRole('menuitem', {name: 'Action'}), {noReveal: true});
  await h.pause(300);
  await h.key('Enter', 800);
  await h.click(conv.getByRole('combobox', {name: 'Action'}), {after: 500});
  await h.click(L.option(page, 'animus:give-item'), {after: 250, noReveal: true});
  await h.click(conv.getByRole('combobox', {name: 'Item', exact: true}), {after: 100, rest: 200});
  await say('dsw', 1600);
  await h.key('Enter', 700);
  await h.result(2000);
  return L.finish(f, {closing: 'Completed {player_name}, <red>, and diamond_sword from dsw'});
};
