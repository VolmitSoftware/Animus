// Pairs the editor with a server by pasting a link into Pair a server on the start page,
// then opens the server's NPCs from its Home.
// Fake server data: --data=test/fixtures/npc_browser (from animus-web/).
module.exports = async (L) => {
  await L.resetFake();
  const f = await L.session({id: '01-pair-server'});
  const page = await f.start(`${L.SITE}/#/`);
  const h = L.human(f);
  const field = page.getByRole('textbox', {name: 'Pairing link'});
  await field.waitFor();
  await page.waitForTimeout(1200);
  const {link, code} = await L.control('pair?paths=both', {});
  f.mark();
  await h.pause(400);
  await h.hover(page.getByRole('heading', {name: /^Pair a server/}), 1000);
  await h.click(field, {after: 400});
  await page.keyboard.insertText(link || `${L.SITE}/#/pair/${code}`); // a paste, not typing
  await h.result(1500);
  await h.click(page.getByRole('button', {name: 'Open link'}), {after: 900});
  const card = page.getByRole('main');
  await card.getByRole('switch', {name: 'Stay paired'}).waitFor();
  // Rest just past each line so the cursor does not cover it.
  await h.moveTo(card.getByText(/Link works until/), {ox: 1.08});
  await h.pause(1200);
  await h.moveTo(card.getByText(/Edit NPCs, behaviors, shops and GUIs/), {ox: 1.06});
  await h.pause(1600);
  await h.hover(card.getByRole('switch', {name: 'Stay paired'}), 1200);
  await h.click(card.getByRole('button', {name: 'Pair', exact: true}), {after: 600});
  await L.shell.waitOnline(page, 20000);
  await page.getByRole('navigation', {name: 'Library'}).waitFor();
  await h.result(2000);
  await h.job('NPCs');
  await page.getByRole('listbox', {name: 'NPCs'}).waitFor();
  await h.hover(page.getByRole('listbox', {name: 'NPCs'}).getByRole('option').nth(3), 600);
  await h.result(2200);
  return L.finish(f, {closing: 'Paired with the server; every NPC is listed by name'});
};
