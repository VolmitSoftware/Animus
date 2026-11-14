// A step that takes a value: a new repeat step's Times switches from a typed
// number to an expression through Fill from, and the expression checks itself
// as it is typed.
// Offline: no fake server needed. The example workspace is made before filming.
module.exports = async (L) => {
  const f = await L.session({id: '19-typed-values'});
  await f.context.addInitScript(() => { try { localStorage.setItem('animus.mode', 'advanced'); } catch (e) {} });
  const ws = await L.examplesWorkspace(f.setup);
  const page = await f.start(`${L.SITE}/${ws}/behavior/greeter%3Agreeter`);
  const h = L.human(f);
  const editor = page.getByRole('region', {name: 'Behavior editor'});
  await editor.waitFor();
  await L.clearToasts(page);
  // A part's header is a <summary> with no accessible name. Folding main before
  // filming brings the wave part up the page.
  await editor.locator('summary:visible').filter({hasText: /^Main/}).first().click();
  await page.waitForTimeout(1500);
  const steps = editor.getByRole('group', {name: 'Steps of wave'});
  f.mark();
  await h.pause(300);
  await h.click(editor.getByRole('button', {name: 'Add step'}).last(), {after: 600});
  await h.click(page.getByRole('menuitem', {name: 'Repeat', exact: true}), {after: 1000, noReveal: true});
  const times = steps.getByRole('spinbutton', {name: 'Times step 3 repeats'});
  await h.hover(times, 900);
  await h.click(steps.getByRole('button', {name: 'Fill from'}).last(), {after: 700});
  await h.hover(page.getByRole('menuitem', {name: 'From a variable'}), 700);
  await h.click(page.getByRole('menuitem', {name: 'From an expression'}), {after: 900, noReveal: true});
  const expr = steps.getByRole('combobox', {name: 'Times of step 3 expression'});
  await h.type(expr, 'min(3, player.level', {clear: true, after: 400});
  await page.keyboard.press('Escape');
  const problem = steps.getByText(/paren/i).first();
  await problem.waitFor();
  await h.moveTo(problem, {ox: 1.08});
  await h.pause(600);
  await h.result(2000);
  await h.click(expr, {ox: 0.95, after: 300});
  await page.keyboard.press('End');
  await page.keyboard.type(')', {delay: L.slow(50)});
  await page.keyboard.press('Escape');
  const header = steps.locator('summary:visible').filter({hasText: 'Repeat min(3, player.level) times'}).first();
  await header.waitFor();
  await h.moveTo(header, {ox: 0.8});
  await h.pause(600);
  await h.result(2200);
  return L.finish(f, {closing: 'Times is now min(3, player.level), checked as typed'});
};
