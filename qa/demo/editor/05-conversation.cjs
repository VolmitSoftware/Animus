// Writes a conversation from the keyboard, plays it in the preview and attaches it to an NPC.
module.exports = async (L) => {
  const {film: f, page, h} = await L.pairedSession('05-conversation');
  await page.getByRole('link', {name: 'New conversation'}).click();
  await page.getByRole('button', {name: 'Create behavior'}).click();
  const conv = page.getByRole('region', {name: 'Conversation', exact: true});
  await conv.waitFor();
  await conv.evaluate((e) => e.scrollIntoView({block: 'start'}));
  await page.mouse.move(f.pos.x, f.pos.y);
  await page.waitForTimeout(1200);
  // Types into the focused conversation field at the clip's typing speed.
  const say = async (text, hold = 600) => {
    await page.keyboard.type(text, {delay: L.slow(55)});
    await h.pause(hold);
  };
  f.mark();
  await h.pause(300);
  // The template's own question is the main-block step that holds the just-looking block.
  // The step menu is clipped by the block (editor bug), so Delete, its last item, is picked with End and Enter.
  const old = conv.getByRole('region', {name: 'Block main'}).getByRole('listitem')
    .filter({has: page.getByRole('region', {name: 'Block just-looking'})}).first();
  await h.click(old.getByRole('button', {name: 'Step options'}).first(), {after: 500});
  await h.hover(page.getByRole('menuitem', {name: /^Move up/}), 500);
  await h.key('End', 500);
  await h.key('Enter', 900);
  await h.click(conv.getByRole('combobox', {name: 'NPC line 1 in main'}), {after: 200});
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
  const preview = conv.getByRole('region', {name: 'Preview', exact: true});
  await h.click(preview.getByRole('button', {name: 'Ferry', exact: true}), {after: 700});
  await h.result(2000);
  const npcs = conv.getByRole('region', {name: 'NPCs', exact: true});
  await h.click(npcs.getByRole('checkbox', {name: 'Baker (town/baker)'}), {after: 500});
  await h.click(npcs.getByRole('button', {name: /^Attach/}), {after: 800});
  await h.result(2000);
  return L.finish(f, {closing: 'A question with two answers, attached to the baker'});
};
