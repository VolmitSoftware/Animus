// Creates a trait group with a golden helmet, adds three NPCs to it from the
// NPC list's picked rows, then reads what one member takes from it.
// Trait groups and picked rows are Advanced, so the clip switches Mode first.
// Fake server data: --data=test/fixtures/npc_browser (from animus-web/).
module.exports = async (L) => {
  const {film: f, page, h} = await L.pairedSession('04-trait-groups');
  await page.waitForTimeout(800);
  f.mark();
  await h.pause(300);
  await h.mode('advanced');
  await h.job('Trait groups');
  await h.click(page.getByRole('button', {name: 'New trait group'}), {after: 700});
  const dialog = page.getByRole('dialog', {name: 'New trait group'});
  await h.type(dialog.getByRole('textbox', {name: 'Id'}), 'harbor-crew', {clear: true, after: 300});
  await h.click(dialog.getByRole('button', {name: 'Create', exact: true}), {after: 1000});
  await page.getByRole('heading', {name: 'Harbor crew', level: 1}).waitFor();
  await L.clearToasts(page);
  await h.click(page.getByRole('tablist', {name: 'Trait group sections'}).getByRole('tab', {name: 'Look'}), {after: 700});
  const look = page.getByRole('tabpanel', {name: 'Look'});
  await h.click(look.getByRole('group', {name: 'Equipment slots'}).getByRole('button', {name: /^Head/}), {after: 500});
  await h.type(look.getByRole('combobox', {name: 'equipment.head'}), 'golden_h', {after: 500});
  await h.click(page.getByRole('option', {name: 'Golden helmet', exact: true}), {after: 900, noReveal: true});
  await h.job('NPCs');
  const list = page.getByRole('listbox', {name: 'NPCs'});
  await list.waitFor();
  for (const name of ['Baker', 'Ferryman', 'Fishmonger']) {
    await h.click(list.getByRole('option', {name: new RegExp(`^${name}`)}).getByRole('checkbox'), {after: 350});
  }
  await h.click(page.getByRole('toolbar', {name: 'Picked rows'}).getByRole('button', {name: 'Trait group', exact: true}), {after: 700, noReveal: true});
  const assign = page.getByRole('dialog', {name: 'Trait group for 3 NPCs'});
  await h.pick(assign.getByRole('combobox', {name: 'Trait group'}), 'harbor-crew');
  await h.click(assign.getByRole('button', {name: 'Add to group'}), {after: 900});
  await h.result(1600);
  await L.clearToasts(page);
  await h.click(list.getByRole('option', {name: /^Ferryman/}).getByText('Ferryman', {exact: true}), {after: 1200});
  await page.getByRole('heading', {name: 'Ferryman', level: 1}).waitFor();
  const groups = page.getByRole('tabpanel', {name: 'Basics'}).getByRole('group').filter({hasText: /^Trait groups/}).first();
  await h.hover(groups.getByRole('button', {name: 'Open group harbor-crew'}), 900);
  // What it takes is a native disclosure (details); its summary row has no name of its own.
  const takes = groups.getByRole('group').filter({hasText: /^What it takes/}).locator('summary').first();
  await h.click(takes, {ox: 0.7, after: 900});
  // Scroll the opened list up from under the closing caption's corner.
  for (let i = 0; i < 2; i++) { await page.mouse.wheel(0, 200); await L.sleep(250); }
  // The equipment row names harbor-crew as its source; rest just past it.
  await h.moveTo(groups.getByText('harbor-crew', {exact: true}).last(), {ox: 1.25});
  await h.result(2400);
  return L.finish(f, {closing: 'harbor-crew gives three NPCs a golden helmet'});
};
