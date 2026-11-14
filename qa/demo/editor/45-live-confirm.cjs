// Sending a browser workspace to a paired server (Advanced): the server holds the mayor
// differently, so Use mine is picked, and Apply asks before it replaces the server's copy.
// Needs a real server (ANIMUS_PAIR_LINK) on the town stage, with town/mayor named "Mayor".
// Before filming it makes a blank workspace, pulls town/mayor from the server and renames it
// there, so the copies differ. Put the name back after a take before recording again:
//   animus edit town/mayor {"name":"Mayor"}
module.exports = async (L) => {
  const f = await L.session({id: '45-live-confirm'});
  await f.context.addInitScript(() => { try { localStorage.setItem('animus.mode', 'advanced'); } catch (e) {} });
  const setup = f.setup;
  setup.setDefaultTimeout(30000);
  await L.pair(setup, {code: L.pairCodeFromEnv()});
  const ws = await L.blankWorkspace(setup, 'Town edits');
  await setup.goto(`${L.SITE}/${ws}/sync`);
  await setup.getByRole('tablist', {name: 'Direction'}).getByRole('tab', {name: 'Pull'}).click();
  await setup.getByRole('listbox', {name: 'On the server'}).getByRole('option', {name: /^Mayor NPC/}).getByRole('checkbox').click();
  await setup.getByRole('main').getByRole('button', {name: /^Pull/}).first().click();
  await setup.getByText(/^Pulled 1 document/).waitFor();
  await setup.goto(`${L.SITE}/${ws}/npc/${encodeURIComponent('town/mayor')}`);
  await setup.getByRole('tabpanel', {name: 'Basics'}).getByRole('combobox', {name: 'Name', exact: true}).fill('&6Mayor Elena');
  await setup.waitForTimeout(1500);
  const page = await f.start(`${L.SITE}/${ws}/sync`);
  page.setDefaultTimeout(30000);
  const h = L.human(f);
  const summary = page.getByRole('status').filter({hasText: /\d+ new, \d+ changed, \d+ same/});
  await summary.waitFor();
  await L.clearToasts(page);
  await page.waitForTimeout(1200);
  f.mark();
  await h.pause(300);
  await h.moveTo(summary, {ox: 1.1, noReveal: true});
  await h.result(1800);
  await h.hover(page.getByRole('listbox', {name: 'Differences'}).getByRole('option').first(), 1200);
  await h.click(page.getByRole('group', {name: 'Changed documents'}).getByRole('button', {name: 'Use mine'}), {after: 800});
  await h.type(page.getByRole('textbox', {name: 'Change note'}), 'Rename the mayor', {after: 400});
  await h.click(page.getByRole('toolbar', {name: 'Page actions'}).getByRole('button', {name: /^Apply/}), {after: 600, noReveal: true});
  const confirm = page.getByRole('alertdialog');
  await confirm.waitFor();
  // Under the question, so the cursor does not cover what it replaces.
  await h.moveTo(confirm.getByRole('heading').or(confirm.getByText(/^Replace/)).first(), {ox: 0.3, oy: 2.6, noReveal: true});
  await h.result(2500);
  await h.click(confirm.getByRole('button').filter({hasText: /^Replace/}).first(), {after: 1200, noReveal: true});
  const done = page.getByRole('main').getByText(/^Applied 1 document as change/).first();
  await done.waitFor();
  // Under the result line, so the cursor does not cover the change id.
  await h.moveTo(done, {ox: 0.3, oy: 2.4, noReveal: true});
  await h.result(2500);
  return L.finish(f, {closing: 'Apply asked first, then replaced the mayor on the server'});
};
