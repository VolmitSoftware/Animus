// The Code and Split views of an NPC (Advanced): the name is edited in the JSON,
// Use this code takes it in, and the form's name field and, back in Form, the
// preview's name tag follow.
// Offline: no fake server needed. The examples workspace is made before filming.
module.exports = async (L) => {
  const f = await L.session({id: '21-json-view'});
  await f.context.addInitScript(() => { try { localStorage.setItem('animus.mode', 'advanced'); } catch (e) {} });
  const ws = await L.examplesWorkspace(f.setup);
  const page = await f.start(`${L.SITE}/${ws}/npc/examples%2Finnkeeper`);
  const h = L.human(f);
  await page.getByRole('heading', {name: 'Innkeeper', level: 1}).waitFor();
  await L.clearToasts(page);
  await page.waitForTimeout(2000);
  const views = page.getByRole('toolbar', {name: 'Page actions'}).getByRole('group', {name: 'View'});
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
  await h.hover(page.getByRole('img', {name: /^Preview of/}), 900);
  await h.click(views.getByRole('button', {name: 'Code'}), {noReveal: true, after: 900});
  await h.result(1500);
  await h.click(views.getByRole('button', {name: 'Split'}), {noReveal: true, after: 1200});
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
  await h.click(page.getByRole('button', {name: 'Use this code'}), {after: 1200});
  await page.getByRole('heading', {name: 'Landlady', level: 1}).waitFor();
  await h.hover(page.getByRole('tabpanel', {name: 'Basics'}).getByRole('combobox', {name: 'Name', exact: true}), 1500);
  await h.click(views.getByRole('button', {name: 'Form'}), {noReveal: true, after: 1200});
  await h.moveTo(page.getByRole('img', {name: /^Preview of/}), {ox: 0.15, oy: 0.85, noReveal: true});
  await h.result(2200);
  return L.finish(f, {closing: 'Renamed Landlady in the JSON; the form and the preview follow'});
};
