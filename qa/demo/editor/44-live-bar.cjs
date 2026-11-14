// The connection: online, reconnecting while the server's editor listener is down
// (the top bar, the save bar and, in Advanced, Home's Server section all say so),
// Retry now once it is back, and the resync notice.
// Needs a real server (ANIMUS_PAIR_LINK) on the town stage, and two shell commands
// that cut and restore the editor link mid-clip (the burner toggles [editor] listenerEnabled in
// plugins/Animus/config.toml, which Animus hotloads):
//   ANIMUS_LINK_DOWN="sed -i '' 's/^listenerEnabled = true/listenerEnabled = false/' <config.toml>"
//   ANIMUS_LINK_UP="sed -i '' 's/^listenerEnabled = false/listenerEnabled = true/' <config.toml>"
// While the link is down the browser logs refused connections; that is expected.
const {execSync} = require('node:child_process');

module.exports = async (L) => {
  const down = process.env.ANIMUS_LINK_DOWN, up = process.env.ANIMUS_LINK_UP;
  if (!down || !up) throw new Error('44-live-bar needs ANIMUS_LINK_DOWN and ANIMUS_LINK_UP');
  const {film: f, page, h} = await L.pairedSession('44-live-bar', {real: true, mode: 'advanced'});
  const server = page.getByRole('region', {name: /^Server/});
  await server.waitFor();
  await page.mouse.move(f.pos.x, f.pos.y);
  await page.waitForTimeout(1200);
  // The connection badge beside the switcher is found the way the editor's own checks find it.
  const badge = page.locator('[data-testid="context-state"]');
  const bar = page.getByRole('contentinfo', {name: 'Save'});
  f.mark();
  await h.pause(300);
  await rest(h, badge);
  await h.result(1600);
  await h.reveal(server.getByRole('heading', {name: /^Server/}));
  await h.moveTo(server.getByText('Direct path'), {ox: 1.3});
  await h.result(1800);
  execSync(down, {stdio: 'ignore'});
  await badge.filter({hasText: 'Reconnecting'}).waitFor();
  await rest(h, badge);
  await h.result(1800);
  await h.moveTo(server.getByText(/Next try at/), {ox: 0.4, oy: 2.2});
  await h.result(2000);
  await rest(h, bar.getByRole('status'));
  await h.result(1600);
  // The cursor rests on Retry now before the link comes back; the editor's own retry can win the race, so the press is skipped then.
  const retry = server.getByRole('button', {name: 'Retry now'});
  await h.hover(retry, 600);
  execSync(up, {stdio: 'ignore'});
  await page.waitForTimeout(900);
  if (await retry.isVisible().catch(() => false)) {
    await page.mouse.down(); await L.sleep(60); await page.mouse.up();
  }
  await badge.filter({hasText: 'Online'}).waitFor();
  const resynced = page.getByRole('region', {name: 'Notifications'}).getByText(/^Resynced with the server/);
  await resynced.waitFor();
  await h.moveTo(resynced, {ox: 0.5, oy: 2.2, noReveal: true});
  await h.result(2200);
  await rest(h, badge);
  await h.result(1500);
  return L.finish(f, {closing: 'Online, reconnecting while the link was down, then online again'});
};

/** Rests just right of [loc], so its words stay readable. */
async function rest(h, loc) {
  await h.moveTo(loc, {ox: 1.25, noReveal: true});
  await h.pause(400);
}
