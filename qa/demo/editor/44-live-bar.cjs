// The live bar's connection pill: online, reconnecting while the server's editor listener is
// down, Retry now, online again with the resync, and the live log that lists it.
// Needs a real server (ANIMUS_PAIR_LINK, default scopes) on the town stage, and two shell commands
// that cut and restore the editor link mid-clip (the burner toggles [editor] listenerEnabled in
// plugins/Animus/config.toml, which Animus hotloads):
//   ANIMUS_LINK_DOWN="sed -i '' 's/^listenerEnabled = true/listenerEnabled = false/' <config.toml>"
//   ANIMUS_LINK_UP="sed -i '' 's/^listenerEnabled = false/listenerEnabled = true/' <config.toml>"
const {execSync} = require('node:child_process');

module.exports = async (L) => {
  const down = process.env.ANIMUS_LINK_DOWN, up = process.env.ANIMUS_LINK_UP;
  if (!down || !up) throw new Error('44-live-bar needs ANIMUS_LINK_DOWN and ANIMUS_LINK_UP');
  const f = await L.session({id: '44-live-bar'});
  const page = await f.start();
  page.setDefaultTimeout(30000);
  await pairReal(L, page);
  await page.getByRole('checkbox', {name: 'town/baker', exact: true}).check();
  await page.getByRole('button', {name: /^Open 1/}).click();
  await page.getByRole('complementary', {name: 'Documents'}).getByText('Baker', {exact: true}).click();
  await page.getByRole('tab', {name: 'Visual', exact: true}).waitFor();
  await dismissToasts(page);
  await page.waitForTimeout(1200);
  const h = L.human(f);
  const bar = page.getByRole('region', {name: 'Live editing'});
  // The pill is a role=status span whose text is the state; data-phase says which state it is.
  const pill = (phase) => bar.locator(`.animus-pill[data-phase="${phase}"]`);
  f.mark();
  await h.pause(300);
  await rest(h, pill('online'));
  await h.result(2000);
  execSync(down, {stdio: 'ignore'});
  await pill('reconnecting').waitFor();
  await rest(h, pill('reconnecting'));
  await h.result(2000);
  // Below the cause line, so the cursor does not cover its words.
  await h.moveTo(bar.getByTestId('live-cause'), {ox: 0.45, oy: 2.4});
  await h.pause(400);
  await h.result(2200);
  execSync(up, {stdio: 'ignore'});
  await page.waitForTimeout(1500);
  await h.click(bar.getByRole('button', {name: 'Retry now'}), {after: 300});
  await pill('online').waitFor();
  await rest(h, pill('online'));
  await h.result(2000);
  await h.click(bar.getByTestId('live-last'), {after: 300});
  // The live log is an unnamed fold; its newest entries are found by class.
  await h.hover(bar.locator('.animus-live-log li').first(), 400);
  await h.result(2500);
  return L.finish(f, {closing: 'Online, reconnecting while the link was down, then online again'});
};

/** Rests just right of the pill, so its words stay readable. */
async function rest(h, loc) {
  await h.moveTo(loc, {ox: 1.25});
  await h.pause(400);
}

/** Pairs with the real server from ANIMUS_PAIR_LINK (unfilmed) and waits until it is online. */
async function pairReal(L, page) {
  const link = (process.env.ANIMUS_PAIR_LINK || '').trim();
  if (!link) throw new Error('44-live-bar needs a real server: pass --pair=<link>');
  await L.pair(page, {code: link.slice(link.indexOf('#/pair/') + 7), remember: false});
  await page.locator('.animus-live .animus-pill[data-phase="online"]').waitFor();
  await page.getByText(/documents on the server/).waitFor();
}

/** Closes the pairing and open toasts (L.dismiss can hit a document's own Close button). */
async function dismissToasts(page) {
  const close = page.getByRole('region', {name: 'Notifications'}).getByRole('button', {name: 'Dismiss'});
  while (await close.count()) await close.first().click();
}
