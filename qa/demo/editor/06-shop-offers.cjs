// Builds a shop with a trade and a buy offer, previews both windows (Advanced)
// and attaches it to an NPC from Settings' Opened by.
// The fishmonger is used because the baker already opens a shop (attaching would ask to replace it).
// Fake server data: --data=test/fixtures/npc_browser (from animus-web/).
module.exports = async (L) => {
  const {film: f, page, h} = await L.pairedSession('06-shop-offers', {path: '/list/shop'});
  await page.getByRole('button', {name: 'New shop'}).waitFor();
  await page.waitForTimeout(800);
  f.mark();
  await h.pause(300);
  await h.click(page.getByRole('button', {name: 'New shop'}), {after: 700});
  const dialog = page.getByRole('dialog', {name: 'New shop'});
  await h.type(dialog.getByRole('textbox', {name: 'Title'}), '&3Harbor Goods', {after: 300});
  await h.hover(dialog.getByRole('combobox', {name: 'Start from'}), 700);
  await h.click(dialog.getByRole('button', {name: 'Create', exact: true}), {after: 1000});
  await page.getByRole('heading', {name: 'Harbor Goods', level: 1}).waitFor();
  await L.clearToasts(page);
  const offers = page.getByRole('toolbar', {name: 'Offers'});
  await h.click(offers.getByRole('button', {name: 'More actions'}), {after: 500});
  await h.click(page.getByRole('menuitem', {name: 'Add trade'}), {after: 900, noReveal: true});
  const trades = page.getByRole('region', {name: 'Trades'});
  // The item suggestions are the editor's own list; each option is named by the item.
  await h.type(trades.getByRole('combobox', {name: 'Gives 1 item'}), 'enchanted_bo', {clear: true, after: 500});
  await h.click(page.getByRole('option', {name: 'Enchanted book', exact: true}), {after: 800, noReveal: true});
  await h.click(trades.getByRole('button', {name: 'More: Takes 1 amount'}), {after: 900});
  await h.result(1500);
  await h.click(offers.getByRole('button', {name: 'Add offer'}), {after: 900});
  const buying = page.getByRole('region', {name: 'Buying'});
  await h.type(buying.getByRole('spinbutton', {name: 'Price'}), '25', {clear: true, after: 300});
  await h.result(1500);
  await h.mode('advanced');
  const preview = page.getByRole('region', {name: 'Preview'});
  await h.click(preview.getByRole('button', {name: 'More actions'}), {after: 500, noReveal: true});
  await h.click(page.getByRole('menuitem', {name: 'Trading screen'}), {after: 900, noReveal: true});
  await h.result(2000);
  await h.click(preview.getByRole('button', {name: 'More actions'}), {after: 500, noReveal: true});
  await h.click(page.getByRole('menuitem', {name: 'Chest window'}), {after: 900, noReveal: true});
  await h.result(1600);
  await h.click(page.getByRole('tablist', {name: 'Shop'}).getByRole('tab', {name: 'Settings'}), {after: 800});
  await h.pick(page.getByRole('combobox', {name: 'NPC that opens this shop'}), {label: 'Fishmonger'}, {after: 1000});
  await h.result(2000);
  return L.finish(f, {closing: 'Harbor Goods: a trade and a buy offer, opened by the fishmonger'});
};
