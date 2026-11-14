// Builds a quest (Advanced): New quest with the baker as giver, a kill objective
// added to stage 1, and a second stage whose objective becomes "bring 3 bread to
// Baker", with the journal preview on stage 2.
// Needs a real server (ANIMUS_PAIR_LINK) with the town stage's town/baker.
module.exports = async (L) => {
  const {film: f, page, h} = await L.questSession('11-quest-editor');
  const quest = page.getByRole('region', {name: 'Quest'});
  const preview = page.getByRole('region', {name: 'Preview'});
  await h.hover(preview.getByRole('heading', {name: /^Journal/}), 900);
  await h.click(quest.getByRole('tablist', {name: 'Quest'}).getByRole('tab', {name: /^Stages/}), {after: 600});
  const stage1 = quest.getByRole('list', {name: 'Stages'}).getByRole('listitem').first();
  await h.click(stage1.getByRole('button', {name: 'Add objective'}), {after: 500});
  await h.click(page.getByRole('menuitem', {name: /^Kill mobs or an NPC/}), {after: 900, noReveal: true});
  await h.result(1500);
  await h.click(quest.getByRole('toolbar', {name: 'Stages'}).getByRole('button', {name: 'Add stage'}), {after: 900});
  const objective = quest.getByRole('list', {name: 'Stage 2 objectives'}).getByRole('listitem').first();
  // An objective row is a native disclosure (details); its summary row has no name of its own.
  await h.click(objective.locator('summary').first(), {ox: 0.3, after: 700});
  await h.pick(objective.getByRole('combobox', {name: 'Type'}), 'bring', {after: 600});
  await h.type(objective.getByRole('combobox', {name: 'Stage 2 objective 1 item'}), 'minecraft:bread', {clear: true, delay: 35, after: 300});
  await h.key('Escape', 200);
  await h.type(objective.getByRole('spinbutton', {name: 'Stage 2 objective 1 amount'}), '3', {clear: true, delay: 55, after: 300});
  await h.pick(preview.getByRole('combobox', {name: 'Journal shows'}), {label: 'Stage 2'}, {after: 800});
  // Rest under the journal line so the cursor does not cover it.
  await h.moveTo(preview.getByText(/Bring 3 bread to Baker/).last(), {ox: 0.4, oy: 2.6});
  await h.pause(300);
  await h.result(2200);
  return L.finish(f, {closing: 'Two stages: wheat and a zombie, then 3 bread for the baker'});
};
