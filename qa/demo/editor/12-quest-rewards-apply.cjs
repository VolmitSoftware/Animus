// Sets quest rewards, previews the offer and finish, validates and applies to the server.
// Needs a real server (ANIMUS_PAIR_LINK) with the town stage's town/baker and a live/qhealer
// (animus create qhealer type=minecraft:player world=world x=6.5 y=64 z=9.5 folder=live name=Healer).
// The token needs apply:commands (quest rewards with experience): scopes=read,validate,apply:npcs,apply:behaviors,apply:gui,apply:commands.
module.exports = async (L) => {
  const f = await L.session({id: '12-quest-rewards-apply'});
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
  await h.click(form.getByRole('tab', {name: /^Rewards/}), {after: 250});
  await h.type(form.getByLabel('Reward experience', {exact: true}), '120', {clear: true, delay: 55, after: 250});
  await h.click(form.getByRole('button', {name: '+ Add flag'}), {after: 250});
  await h.type(form.getByLabel('Reward flag 1 name', {exact: true}), 'baker_helped', {clear: true, delay: 55, after: 250});
  await h.click(preview.getByRole('tab', {name: 'Finished', exact: true}), {after: 250});
  await h.result(1600);
  await h.click(page.getByRole('button', {name: 'Validate'}), {after: 250});
  await h.result(1600);
  await h.click(page.getByRole('button', {name: 'Apply 1'}), {after: 250});
  const applied = page.getByTestId('live-last').filter({hasText: /Applied 1 document/});
  await applied.waitFor();
  // Rest just past the line so the cursor does not cover the change id.
  await h.moveTo(applied, {ox: 1.04});
  await h.pause(300);
  await h.result(2200);
  return L.finish(f, {closing: 'Applied to the server: 120 experience and the baker_helped flag'});
};
