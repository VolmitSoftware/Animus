// Pairs the editor with a server by pasting a link under Servers on the home page.
module.exports = async (L) => {
  await L.resetFake();
  const f = await L.session({id: '01-pair-server'});
  const page = await f.start(`${L.SITE}/#/`);
  const h = L.human(f);
  const servers = page.getByRole('region', {name: 'Servers'});
  await servers.waitFor();
  await page.waitForTimeout(1200);
  const {link, code} = await L.control('pair?paths=both', {});
  f.mark();
  await h.pause(400);
  await h.hover(servers.getByRole('heading', {name: 'Servers'}), 1000);
  const field = page.getByLabel('Pairing link or code');
  await h.click(field, {after: 400});
  await page.keyboard.insertText(link || `${L.SITE}/#/pair/${code}`); // a paste, not typing
  await h.result(1500);
  await h.click(page.getByRole('button', {name: 'Use this code'}), {after: 900});
  await page.getByTestId('pair-fingerprint').waitFor();
  await h.hover(page.getByTestId('pair-fingerprint'), 1800);
  await h.hover(page.getByRole('switch', {name: 'Remember this browser'}), 1200);
  await h.click(page.getByRole('button', {name: 'Pair', exact: true}), {after: 600});
  await page.waitForFunction(() => location.hash.startsWith('#/server/'));
  await h.result(1800);
  const openAll = page.getByRole('complementary', {name: 'Documents'}).getByRole('button', {name: /^Open all \d+/});
  await h.click(openAll, {after: 600});
  await page.getByText(/Opened \d+ documents/).first().waitFor();
  await h.hover(page.getByRole('navigation', {name: 'Documents by kind'}).getByRole('link', {name: /Baker/}), 600);
  await h.result(2200);
  return L.finish(f, {closing: 'Paired with the server and opened its 13 documents'});
};
