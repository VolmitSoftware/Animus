// Builds a shop with a trade and a buy offer, previews both windows and attaches it to an NPC.
// The fishmonger is used because the baker already opens a shop (attaching would ask to replace it).
module.exports = async (L) => {
  const {film: f, page, h} = await L.pairedSession('06-shop-offers');
  await page.waitForTimeout(800);
  f.mark();
  await h.pause(300);
  await h.click(page.getByRole('link', {name: 'New document'}), {after: 700});
  await h.click(page.getByRole('navigation', {name: 'Document kinds'}).getByRole('link', {name: /^Shop/}), {after: 700});
  const form = page.getByRole('region', {name: 'New shop'});
  await h.type(form.getByLabel('Title', {exact: true}), '&3Harbor Goods', {after: 300});
  await h.click(form.getByRole('button', {name: 'Create shop'}), {after: 1000});
  const board = page.getByRole('region', {name: 'Offers', exact: true});
  const add = board.getByRole('button', {name: '+ Add offer'});
  await h.click(add, {after: 400});
  await h.click(page.getByRole('menuitem', {name: /^Trade/}), {after: 700, noReveal: true});
  await h.type(board.getByLabel('Gives 1 item', {exact: true}), 'enchanted_bo', {clear: true, after: 500});
  await h.click(page.getByRole('option', {name: /^minecraft:enchanted_book/}).first(), {after: 1000, noReveal: true});
  await h.click(add, {after: 500});
  await h.click(page.getByRole('menuitem', {name: /^Buy/}), {after: 800, noReveal: true});
  await h.type(board.getByLabel('Price', {exact: true}).first(), '25', {clear: true, after: 300});
  const windows = page.getByRole('region', {name: 'Shop windows', exact: true});
  await h.click(windows.getByRole('tab', {name: /^Trading screen/}), {after: 600});
  await h.result(2000);
  const settings = page.getByRole('region', {name: 'Shop settings', exact: true});
  await h.click(settings.getByRole('tab', {name: 'NPCs'}), {after: 600});
  await h.click(settings.getByRole('combobox', {name: 'NPC to attach this shop to'}), {after: 600});
  await h.click(page.getByRole('option', {name: /Fishmonger/}).first(), {after: 600, noReveal: true});
  await h.click(settings.getByRole('button', {name: 'Attach', exact: true}), {after: 800});
  await h.result(2000);
  return L.finish(f, {closing: 'Harbor Goods: a trade and a buy offer, opened by the fishmonger'});
};
