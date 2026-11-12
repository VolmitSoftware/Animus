// Builds a quest: kill and bring objectives over two stages, previewing the journal.
// Needs a real server (ANIMUS_PAIR_LINK) with the town stage's town/baker and a live/qhealer
// (animus create qhealer type=minecraft:player world=world x=6.5 y=64 z=9.5 folder=live name=Healer).
module.exports = async (L) => {
  const f = await L.session({id: '11-quest-editor'});
  const page = await f.start();
  page.setDefaultTimeout(20000);
  await L.openQuestDraft(f, page);
  const h = L.human(f);
  f.mark();
  // The quest form and preview panes have no accessible name.
  const form = page.locator('.animus-quest-form');
  const preview = page.locator('.animus-quest-preview');
  await h.type(page.getByLabel('Title', {exact: true}), '&6Bread for the baker', {clear: true, delay: 40, after: 250});
  await h.click(page.getByRole('combobox', {name: 'Giver', exact: true}), {after: 250});
  await h.click(L.option(page, 'baker'), {after: 250});
  await h.click(page.getByRole('button', {name: /^Create /}), {after: 250});
  await page.waitForFunction(() => location.hash.includes('/quest/'));
  await h.click(form.getByRole('tab', {name: /^Stages/}), {after: 250});
  const stage1 = form.locator('[data-stage="0"]');
  await h.click(stage1.getByRole('button', {name: '+ Add objective'}), {after: 250});
  await h.click(page.getByRole('menuitem', {name: 'Kill mobs or an NPC'}), {after: 250});
  await h.click(form.getByRole('button', {name: '+ Add stage'}), {after: 250});
  const stage2 = form.locator('[data-stage="1"]');
  await stage2.waitFor();
  // Objective dropdowns use path ids from guiPathId(); their visible labels repeat in every stage.
  await h.click(stage2.locator('[id="animus-at--stages-1-objectives-0-type"]'), {after: 250});
  await h.click(L.option(page, 'bring'), {after: 250});
  await h.click(stage2.locator('[id="animus-at--stages-1-objectives-0-npc"]'), {after: 250});
  await h.click(L.option(page, 'baker'), {after: 250});
  await h.type(stage2.getByLabel('Stage 2 objective 1 item', {exact: true}), 'minecraft:bread', {clear: true, delay: 35, after: 300});
  await h.type(stage2.getByLabel('Stage 2 objective 1 amount', {exact: true}), '3', {clear: true, delay: 55, after: 300});
  await h.click(preview.getByRole('tab', {name: 'Stage 2', exact: true}), {after: 250});
  // Rest just past the journal line so the cursor does not cover it.
  await h.moveTo(preview.getByText(/Bring 3 bread to Baker/), {ox: 1.15});
  await h.pause(300);
  await h.result(2200);
  return L.finish(f, {closing: 'Two stages: wheat and a zombie, then 3 bread for the baker'});
};
