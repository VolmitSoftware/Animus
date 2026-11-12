// Converting a Citizens save in the browser: choose the file, convert, read the review and
// its one warning, and open the result as a workspace. Nothing is uploaded.
// The save is the converter fixture schema/fixtures/citizens/looks.yml (20 NPCs, one warning).
// Uses no server; the record.cjs --pair link is not needed.
const path = require('node:path');

module.exports = async (L) => {
  const save = path.join(L.REPO, 'schema', 'fixtures', 'citizens', 'looks.yml');
  const f = await L.session({id: '48-citizens-import'});
  const page = await f.start(`${L.SITE}/#/`);
  page.setDefaultTimeout(20000);
  await page.getByRole('heading', {name: 'From a data folder'}).waitFor();
  await page.waitForTimeout(1200);
  const h = L.human(f);
  f.mark();
  await h.pause(300);
  await h.click(page.getByRole('button', {name: 'Import Citizens and others'}), {after: 300});
  await page.getByRole('heading', {name: 'Import Citizens and others'}).waitFor();
  await h.pause(300);
  const chooser = page.waitForEvent('filechooser');
  await h.click(page.getByRole('button', {name: 'Choose saves.yml'}), {after: 100});
  await (await chooser).setFiles(save);
  await h.hover(page.getByText('looks.yml', {exact: true}), 600);
  await h.click(page.getByRole('button', {name: /^\W*Convert$/}), {after: 300});
  const review = page.getByRole('heading', {name: 'Review'});
  await review.waitFor();
  await h.hover(page.getByText(/^1 migration warning\./), 400);
  await h.result(1600);
  // Rest just past the end of the warning so the cursor does not cover it.
  await h.moveTo(page.getByText(/^NPC #8 \(Big Slime\)/), {ox: 1.04});
  await h.result(1800);
  await h.click(page.getByRole('button', {name: 'Open as workspace'}), {after: 300});
  await page.waitForFunction(() => location.hash.startsWith('#/workspace/'));
  await page.waitForTimeout(800);
  await h.moveTo(page.getByText('Every document passes the shared schemas'), {ox: 1.08});
  await h.pause(400);
  await h.result(2500);
  return L.finish(f, {closing: 'Converted in the browser: 20 Citizens NPCs, one warning'});
};
