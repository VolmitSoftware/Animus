// The Code (JSON) and Split views of an NPC: the name is edited in the JSON,
// Apply to draft, and the preview's name tag and the Visual name field follow.
// Offline: no fake server needed. The example packs workspace is made before filming.
module.exports = async (L) => {
  const f = await L.session({id: '21-json-view'});
  await f.setup.goto(`${L.SITE}/#/`);
  await f.setup.getByRole('button', {name: 'Create with example packs'}).click();
  await f.setup.waitForFunction(() => location.hash.startsWith('#/workspace/'));
  const ws = f.setup.url().split('#')[1].split('/').slice(0, 3).join('/');
  const page = await f.start(`${L.SITE}/#${ws}/npc/examples%2Finnkeeper`);
  const h = L.human(f);
  await page.getByRole('heading', {name: 'Innkeeper', level: 1}).waitFor();
  await page.waitForTimeout(2000);
  const views = page.getByRole('tablist', {name: 'View'});
  const code = page.getByRole('combobox', {name: 'Document JSON'});
  /** Viewport point of [needle]'s end in the JSON text area (a plain monospace textarea). */
  const endOf = (needle) => code.evaluate((area, n) => {
    const at = area.value.indexOf(n) + n.length;
    const before = area.value.slice(0, at).split('\n');
    const s = getComputedStyle(area);
    const ctx = document.createElement('canvas').getContext('2d');
    ctx.font = s.font;
    const r = area.getBoundingClientRect();
    const lh = parseFloat(s.lineHeight) || parseFloat(s.fontSize) * 1.5;
    return {
      x: r.left + parseFloat(s.paddingLeft) + ctx.measureText(before[before.length - 1]).width - area.scrollLeft,
      y: r.top + parseFloat(s.paddingTop) + (before.length - 0.5) * lh - area.scrollTop,
    };
  }, needle);
  f.mark();
  await h.click(views.getByRole('tab', {name: 'Code'}), {noReveal: true, after: 900});
  await h.result(1500);
  await h.click(views.getByRole('tab', {name: 'Split'}), {noReveal: true, after: 1200});
  const p = await endOf('"&6Innkeeper');
  // A caret position inside the textarea has no locator, so this is h.click's glide, rest and press by hand.
  await h.glide(p.x, p.y);
  await h.pause(L.CLICK_REST_MS);
  await page.mouse.down(); await page.mouse.up();
  await h.pause(500);
  for (let i = 0; i < 'Innkeeper'.length; i++) { await page.keyboard.press('Backspace'); await h.pause(L.slow(40)); }
  await page.keyboard.type('Landlady', {delay: L.slow(70)});
  await page.keyboard.press('Escape');
  await h.pause(L.TYPED_HOLD_MS);
  await h.click(page.getByRole('button', {name: 'Apply to draft'}), {after: 1200});
  await page.getByRole('heading', {name: 'Landlady', level: 1}).waitFor();
  await h.result(2200);
  await h.click(views.getByRole('tab', {name: 'Visual'}), {noReveal: true, after: 900});
  await h.result(2000);
  return L.finish(f, {closing: 'Renamed Landlady in the JSON; the preview and Visual follow'});
};
