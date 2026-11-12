// Creates a trait group, assigns NPCs to it and shows the effective values.
module.exports = async (L) => {
  const {film: f, page, h} = await L.pairedSession('04-trait-groups');
  await page.waitForTimeout(800);
  f.mark();
  await h.pause(300);
  await h.click(page.getByRole('link', {name: 'New document'}), {after: 700});
  await h.click(page.getByRole('navigation', {name: 'Document kinds'}).getByRole('link', {name: /^Group/}), {after: 700});
  await h.type(page.getByRole('region', {name: 'New group'}).getByLabel('Id', {exact: true}), 'harbor-crew', {clear: true, after: 300});
  await h.click(page.getByRole('button', {name: 'Create group'}), {after: 1000});
  const ap = L.section(page, 'appearance');
  await h.click(ap.getByRole('button', {name: /^Head/}), {after: 500});
  await h.type(ap.getByLabel('equipment.head', {exact: true}), 'golden_h', {after: 500});
  await h.click(page.getByText('minecraft:golden_helmet', {exact: true}).first(), {after: 900, noReveal: true});
  // Assign from the NPC browser.
  await h.click(page.getByRole('complementary', {name: 'Documents'}).getByRole('link', {name: 'Browse'}), {after: 1000});
  for (const k of ['harbor/ferryman', 'town/baker', 'town/market/fishmonger']) {
    await h.click(page.getByRole('checkbox', {name: `Select ${k}`, exact: true}), {after: 350});
  }
  const groups = page.getByRole('region', {name: 'Trait groups'});
  await h.click(groups.getByRole('combobox', {name: 'Group', exact: true}), {after: 600});
  await h.click(L.option(page, 'harbor-crew'), {after: 600, noReveal: true});
  await h.click(groups.getByRole('button', {name: 'Assign', exact: true}), {after: 600});
  await h.result(1500);
  // Effective view on one member.
  await h.click(page.getByRole('complementary', {name: 'Documents'}).getByRole('link', {name: /^Ferryman/}), {after: 900});
  const eff = L.section(page, 'effective');
  if (!(await eff.evaluate((e) => e.open))) await h.click(eff.locator('summary').first(), {after: 800});
  // Effective rows carry their source group only as data-source.
  await h.hover(eff.locator('tr[data-source="harbor-crew"]').first(), 600);
  await h.result(2200);
  return L.finish(f, {closing: 'harbor-crew gives three NPCs a golden helmet'});
};
