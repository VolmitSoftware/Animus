// Shared setup for the rig clips (32-37). Not a clip: record.cjs only picks
// up files named NN-*.cjs. The rig clips run offline, in a blank workspace,
// with no fake server or pairing: the knight comes from a Blockbench import.
const path = require('node:path');

const KNIGHT = path.join(__dirname, '..', 'stage', 'assets', 'knight.bbmodel');

/** True once the rig stage has drawn a frame with every model built. */
async function drawn(page) {
  // The GL host exposes its frame count and build state only as data attributes.
  await page.waitForFunction(() => {
    const host = document.querySelector('.animus-mc-gl-host');
    return host && Number(host.getAttribute('data-frames')) > 0 && host.getAttribute('data-unbuilt') === '0';
  }, null, {timeout: 30000});
}

/** Closes every toast that is showing. */
async function clearToasts(page) {
  for (let i = 0; i < 8; i++) {
    const close = page.getByRole('button', {name: 'Close'}).or(page.getByRole('button', {name: '×'}));
    if (!(await close.count())) return;
    await close.first().click({timeout: 2000}).catch(() => {});
    await page.waitForTimeout(250);
  }
}

/** Creates a blank workspace named [name] on [page] (unfilmed). */
async function blankWorkspace(L, page, name = 'Town') {
  await page.goto(`${L.SITE}/`);
  await page.getByLabel('Workspace name').fill(name);
  await page.getByRole('button', {name: 'Create blank workspace'}).click();
  await page.waitForFunction(() => location.hash.startsWith('#/workspace/'));
  await page.getByText(`Created ${name}.`).waitFor();
  // The workspace is stored after the toast; the film page reopens it from storage.
  await page.waitForTimeout(1500);
}

/** Imports knight.bbmodel through the top bar's Import menu (unfilmed). */
async function importKnight(page) {
  const chooser = page.waitForEvent('filechooser');
  // The top bar menus are found by id, as in animus-web/test/support/shell.cjs.
  await page.locator('#animus-menu-import').click();
  await page.getByRole('menuitem', {name: 'Import a Blockbench model'}).click();
  await (await chooser).setFiles(KNIGHT);
  await page.locator('.animus-rig-editor[data-rig="animus:knight"]').waitFor();
}

/**
 * A film page on the knight's rig editor, with nothing filmed yet. [prepare]
 * runs on the film page after the stage draws, for unfilmed edits.
 * The knight's hitbox bone is a large box around the model, so it is deleted
 * unless [keepHitbox], letting the stage show the knight itself.
 */
async function knightSession(L, id, {prepare, keepHitbox = false} = {}) {
  const film = await L.session({id});
  await blankWorkspace(L, film.setup);
  await importKnight(film.setup);
  const url = film.setup.url();
  const page = await film.start(url);
  await page.locator('.animus-rig-editor[data-rig="animus:knight"]').waitFor();
  await drawn(page);
  if (!keepHitbox) {
    await page.locator('.animus-rig-tree [data-bone="hitbox"]').click();
    await page.getByRole('button', {name: 'Delete bone'}).click();
    await page.locator('.animus-rig-tree [data-bone="hitbox"]').waitFor({state: 'detached'});
    await page.locator('.animus-rig-tree [data-bone="body"]').click();
  }
  if (prepare) await prepare(page);
  await clearToasts(page);
  await page.evaluate(() => window.scrollTo(0, 0));
  await page.waitForTimeout(1200);
  return {film, page, h: L.human(film)};
}

/** Drags with the mouse button held, the cursor gliding from [loc]'s middle by (dx, dy). */
async function drag(film, h, loc, dx, dy, {rest = 400, hold = 250} = {}) {
  await h.moveTo(loc);
  await h.pause(rest);
  const page = film.page;
  await page.mouse.down();
  await h.pause(hold);
  const {x, y} = film.pos;
  const n = 18;
  for (let i = 1; i <= n; i++) {
    const t = i / n, e = t < 0.5 ? 2 * t * t : 1 - Math.pow(-2 * t + 2, 2) / 2;
    await page.mouse.move(x + dx * e, y + dy * e);
    await h.pause(28);
  }
  film.pos = {x: x + dx, y: y + dy};
  await h.pause(hold);
  await page.mouse.up();
  await h.pause(500);
}

/** Waits until [loc] stops moving: a closing list shrinks the page and it scrolls back after a moment. */
async function settled(loc) {
  let last = null;
  for (let i = 0; i < 30; i++) {
    const b = await loc.first().boundingBox();
    if (b && last && Math.abs(b.y - last.y) < 0.5 && Math.abs(b.x - last.x) < 0.5) return;
    last = b;
    await new Promise((r) => setTimeout(r, 150));
  }
}

/**
 * Opens dropdown [combo] and picks [value], once [combo] has stopped moving;
 * the list gets a second click if the first did not open it.
 */
async function choose(L, h, page, combo, value) {
  await settled(combo);
  await h.click(combo, {after: 700, noReveal: true});
  const opt = L.option(page, value);
  if (!(await opt.first().waitFor({timeout: 2500}).then(() => true, () => false))) await h.click(combo, {after: 700, noReveal: true});
  await h.click(opt, {after: 700});
}

/** Scrolls the page with the wheel by about [dy] pixels, in small steps, with the cursor away from the sidebar. */
async function scrollBy(film, h, dy) {
  if (film.pos.x < 400) await h.glide(860, film.pos.y);
  for (let left = Math.abs(dy); left > 0; left -= 120) {
    await film.page.mouse.wheel(0, Math.sign(dy) * Math.min(120, left));
    await h.pause(90);
  }
  await h.pause(300);
}

module.exports = {KNIGHT, drawn, clearToasts, blankWorkspace, importKnight, knightSession, drag, choose, scrollBy};
