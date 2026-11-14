// Converting a Citizens save in the browser with the Import wizard (an Advanced
// start): Citizens as the source, choose the file, convert, read the review and
// its one warning, and open the result as a workspace. Nothing is uploaded.
// The save is the converter fixture schema/fixtures/citizens/looks.yml (20 NPCs, one warning).
// Uses no server; the record.cjs --pair link is not needed.
const path = require('node:path');

module.exports = async (L) => {
  const save = path.join(L.REPO, 'schema', 'fixtures', 'citizens', 'looks.yml');
  const f = await L.session({id: '48-citizens-import'});
  const page = await f.start(`${L.SITE}/#/`);
  page.setDefaultTimeout(20000);
  await page.getByRole('heading', {name: 'Animus web editor'}).waitFor();
  await page.waitForTimeout(1200);
  const h = L.human(f);
  f.mark();
  await h.pause(300);
  await h.mode('advanced');
  await h.click(page.getByRole('toolbar', {name: 'Other ways to start'}).getByRole('button', {name: 'Import'}), {after: 900});
  await page.getByRole('heading', {name: 'Import', level: 1}).waitFor();
  const source = page.getByRole('radiogroup', {name: 'Import from'});
  await h.hover(source.getByRole('radio', {name: /^Citizens/}), 900);
  await h.click(page.getByRole('button', {name: 'Next'}), {after: 800});
  const chooser = page.waitForEvent('filechooser');
  await h.click(page.getByRole('button', {name: 'Choose saves.yml'}), {after: 100});
  await (await chooser).setFiles(save);
  await h.hover(page.getByText('looks.yml', {exact: false}).first(), 700);
  await h.click(page.getByRole('button', {name: 'Convert'}), {after: 300});
  const review = page.getByRole('region', {name: 'Review'});
  await review.waitFor();
  await h.moveTo(review.getByText(/^\d+ NPCs and \d+ behaviors/), {ox: 1.1});
  await h.result(1600);
  // Rest just past the end of the warning so the cursor does not cover it.
  await h.moveTo(review.getByText(/^NPC #8 \(Big Slime\)/), {ox: 1.04});
  await h.result(1800);
  await h.click(page.getByRole('button', {name: 'Next'}), {after: 900});
  await h.hover(page.getByRole('region', {name: 'Result'}).getByRole('listitem').first(), 1000);
  await h.click(page.getByRole('button', {name: 'Open as workspace'}), {after: 300});
  await page.waitForFunction(() => location.hash.startsWith('#/workspace/'));
  await page.waitForTimeout(800);
  await h.hover(page.getByRole('main').getByRole('navigation', {name: 'Library'}).getByRole('link', {name: /^NPCs/}), 600);
  await h.result(2500);
  return L.finish(f, {closing: 'Converted in the browser: 20 Citizens NPCs, one warning'});
};
