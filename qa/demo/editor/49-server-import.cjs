// Importing a Citizens save on a paired server with the Import wizard (Advanced): Already on my
// server as the source, the save uploaded instead of the server's own, the server's review, then
// Install, which asks first and installs the import with its NPCs spawned.
// Needs a real server (ANIMUS_PAIR_LINK) whose pairing may import (a console pairing has every scope).
// The save is the converter fixture schema/fixtures/citizens/looks.yml (20 NPCs in world "world").
// Roll the import back after a take before recording again: /animus import citizens rollback.
const path = require('node:path');

module.exports = async (L) => {
  const save = path.join(L.REPO, 'schema', 'fixtures', 'citizens', 'looks.yml');
  const {film: f, page, h} = await L.pairedSession('49-server-import', {real: true, mode: 'advanced', path: '/import'});
  await page.getByRole('heading', {name: 'Import', level: 1}).waitFor();
  await page.mouse.move(f.pos.x, f.pos.y);
  await page.waitForTimeout(1200);
  f.mark();
  await h.pause(300);
  const source = page.getByRole('radiogroup', {name: 'Import from'});
  await h.click(source.getByRole('radio', {name: /^Already on my server/}), {after: 700});
  await h.click(page.getByRole('button', {name: 'Next'}), {after: 900});
  const files = page.getByRole('region', {name: 'Files'});
  await h.hover(files.getByText(/^Animus tutorial stage/).first(), 900);
  // Upload instead is a native disclosure (details); its summary row has no name of its own.
  const upload = files.getByRole('group').filter({hasText: /^Upload instead/}).first();
  await h.click(upload.locator('summary').first(), {ox: 0.2, after: 700});
  const chooser = page.waitForEvent('filechooser');
  await h.click(upload.getByRole('button', {name: 'Choose saves.yml'}), {after: 100});
  await (await chooser).setFiles(save);
  await h.hover(upload.getByText('looks.yml', {exact: false}).first(), 700);
  await h.click(page.getByRole('button', {name: 'Convert'}), {after: 300});
  const review = page.getByRole('region', {name: 'Review'});
  await review.getByText('Ready to install').waitFor();
  await h.moveTo(review.getByText(/^\d+ NPCs$/).first(), {ox: 1.4});
  await h.result(1600);
  await h.hover(review.getByRole('listitem').filter({hasText: /^Citizens save/}), 600);
  await h.result(2000);
  await h.click(page.getByRole('button', {name: 'Install'}), {after: 300});
  const confirm = page.getByRole('alertdialog', {name: 'Install this import on the server?'});
  await confirm.waitFor();
  // Under the question, so the cursor does not cover what the install changes.
  await h.moveTo(confirm.getByText(/NPCs spawn as they were/), {ox: 0.3, oy: 2.2, noReveal: true});
  await h.result(2500);
  await h.click(confirm.getByRole('button', {name: 'Install import'}), {after: 300, noReveal: true});
  const result = page.getByRole('region', {name: 'Result'});
  await result.getByText(/NPCs installed/).waitFor();
  await h.moveTo(result.getByText(/NPCs installed/), {ox: 1.3});
  await h.result(2000);
  // Under the two-line report entry, so the cursor does not cover it.
  await h.moveTo(result.getByRole('listitem').filter({hasText: /^Installed import/}), {ox: 0.3, oy: 1.7});
  await h.pause(600);
  await h.result(2000);
  return L.finish(f, {closing: 'Staged, reviewed and installed on the server: 20 NPCs'});
};
