// Writes a conversation from the keyboard, deletes the template's question,
// tests it in the browser and attaches it to an NPC.
// Fake server data: --data=test/fixtures/npc_browser (from animus-web/).
module.exports = async (L) => {
  const {film: f, page, h} = await L.pairedSession('05-conversation', {path: '/list/behavior'});
  await page.getByRole('button', {name: 'New behavior'}).waitFor();
  await page.waitForTimeout(800);
  // Types into the focused conversation field at the clip's typing speed.
  const say = async (text, hold = 600) => {
    await page.keyboard.type(text, {delay: L.slow(55)});
    await h.pause(hold);
  };
  f.mark();
  await h.pause(300);
  await h.click(page.getByRole('button', {name: 'New behavior'}), {after: 800});
  const dialog = page.getByRole('dialog', {name: 'New behavior'});
  await h.hover(dialog.getByRole('radio', {name: /^Conversation/}), 900);
  await h.type(dialog.getByRole('textbox', {name: 'Name'}), 'Harbor welcome', {after: 300});
  await h.click(dialog.getByRole('button', {name: 'Create', exact: true}), {after: 1200});
  await page.getByRole('heading', {name: 'Harbor welcome', level: 1}).waitFor();
  await L.clearToasts(page);
  await h.click(page.getByRole('combobox', {name: 'Line 1 of Start'}), {after: 200});
  await h.key('ControlOrMeta+a', 200);
  await say('Welcome to the harbor, ', 200);
  await say('{', 1400);
  await h.key('Enter', 900);
  await h.key('End', 200);
  await h.key('Enter', 500);
  await say('Where to? [Ferry] [Market]', 1000);
  await h.key('Enter', 600);
  await h.result(1600);
  await h.key('ControlOrMeta+Enter', 500);
  await say('The ferry leaves at dusk.', 1000);
  // The template's own question is now step 3; deleting it leaves its two answer parts, which go too.
  await h.click(page.getByRole('toolbar', {name: 'Ask 3'}).getByRole('button', {name: 'More actions'}), {after: 500});
  await h.click(page.getByRole('menuitem', {name: 'Delete'}), {after: 600, noReveal: true});
  await h.click(page.getByRole('alertdialog', {name: 'Delete this ask step?'}).getByRole('button', {name: 'Delete'}), {after: 900, noReveal: true});
  for (const part of ['Just looking', 'Goodbye']) {
    await h.click(page.getByRole('toolbar', {name: `Part ${part}`}).getByRole('button', {name: 'More actions'}), {after: 500});
    await h.click(page.getByRole('menuitem', {name: 'Delete part'}), {after: 500, noReveal: true});
    await h.click(page.getByRole('alertdialog', {name: 'Delete this part?'}).getByRole('button', {name: 'Delete part'}), {after: 800, noReveal: true});
  }
  await h.result(1500);
  const test = page.getByRole('region', {name: 'Test'});
  await h.hover(test.getByRole('combobox', {name: 'Starts'}), 700);
  await h.click(test.getByRole('button', {name: 'Run'}), {after: 1200});
  await h.click(test.getByRole('group', {name: 'Answers'}).getByRole('button', {name: 'Ferry', exact: true}), {after: 900});
  await h.result(2000);
  await h.pick(page.getByRole('combobox', {name: /^NPCs/}), {label: 'Baker'});
  await h.result(2000);
  return L.finish(f, {closing: 'A question with two answers, attached to the baker'});
};
