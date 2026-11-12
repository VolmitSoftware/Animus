// A step that takes a value: a new repeat step's count switches from a typed
// value to an expression, which checks itself as it is typed.
// Offline: no fake server needed. The example packs workspace is made before filming.
module.exports = async (L) => {
  const f = await L.session({id: '19-typed-values'});
  await f.setup.goto(`${L.SITE}/#/`);
  await f.setup.getByRole('button', {name: 'Create with example packs'}).click();
  await f.setup.waitForFunction(() => location.hash.startsWith('#/workspace/'));
  const ws = f.setup.url().split('#')[1].split('/').slice(0, 3).join('/');
  const page = await f.start(`${L.SITE}/#${ws}/behavior/greeter%3Agreeter`);
  const h = L.human(f);
  await page.getByRole('tab', {name: 'Visual'}).click();
  await page.getByRole('region', {name: 'Behavior editor'}).waitFor();
  // A routine's header is a <summary> with no accessible name. Closing main
  // before filming brings the wave routine up the page.
  await page.locator('summary', {hasText: '2 steps in order'}).first().click();
  await page.waitForTimeout(1500);
  const steps = page.getByRole('group', {name: 'Steps of wave'});
  f.mark();
  await h.click(steps.getByRole('combobox', {name: 'Add step'}).last(), {after: 600});
  await h.click(page.getByRole('option', {name: 'repeat', exact: true}), {after: 1000});
  const count = steps.getByRole('spinbutton', {name: 'Count of step 3'});
  await h.hover(count, 900);
  await h.click(steps.getByRole('combobox', {name: 'Count of step 3 source'}), {after: 700});
  await h.hover(page.getByRole('option', {name: 'From a variable'}), 700);
  await h.click(page.getByRole('option', {name: 'From an expression'}), {after: 900});
  const expr = steps.getByRole('combobox', {name: 'Count of step 3 expression'});
  await h.type(expr, 'min(3, player.level', {clear: true, after: 400});
  await page.keyboard.press('Escape');
  await page.getByText('Unclosed paren at column 20.').waitFor();
  await h.hover(page.getByText('Unclosed paren at column 20.'), 600);
  await h.result(2000);
  await h.click(expr, {ox: 0.95, after: 300});
  await page.keyboard.press('End');
  await page.keyboard.type(')', {delay: L.slow(50)});
  await page.locator('summary', {hasText: 'Repeat min(3, player.level) times'}).waitFor();
  await h.hover(page.locator('summary', {hasText: 'Repeat min(3, player.level) times'}), 600);
  await h.result(2200);
  return L.finish(f, {closing: 'The count is now min(3, player.level), checked as typed'});
};
