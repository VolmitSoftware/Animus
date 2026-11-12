// A pack manifest's Visual view: bumps market-stalls to 1.1.0, rewrites its
// description and adds the economy module as optional.
// Fake server data: --data=../qa/demo/editor/fixtures/town-deep (from animus-web/).
module.exports = async (L) => {
  const {film: f, page, h} = await L.pairedSession('28-npc-packs');
  await page.waitForTimeout(1500);
  f.mark();
  await h.pause(300);
  const side = page.getByRole('complementary', {name: 'Documents'});
  await h.click(side.getByText('Market stalls', {exact: true}).first(), {after: 1200});
  const pack = page.getByRole('region', {name: 'Pack'});
  await h.hover(pack.getByRole('textbox', {name: 'Name'}), 800);
  await h.type(pack.getByRole('textbox', {name: 'Version'}), '1.1.0', {clear: true, after: 300});
  await h.type(pack.getByRole('textbox', {name: 'Description'}), 'Stall keepers who buy and sell at the town market.', {clear: true, delay: 40, after: 300});
  await h.result(1500);
  await h.type(pack.getByRole('textbox', {name: 'New module name'}), 'economy', {after: 300});
  await h.click(pack.getByRole('button', {name: 'Add module'}), {after: 1000});
  const required = pack.getByRole('checkbox', {name: 'Required'});
  await h.hover(required, 700);
  await h.click(required, {after: 600});
  await h.moveTo(pack.getByRole('textbox', {name: 'Module economy name'}), {noReveal: true});
  await h.result(2200);
  return L.finish(f, {closing: 'market-stalls 1.1.0 uses the economy module, now optional'});
};
