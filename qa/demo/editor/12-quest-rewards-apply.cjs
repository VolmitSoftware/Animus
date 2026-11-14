// Sets quest rewards (Advanced), previews the finish in the journal, then validates and
// applies the new quest to the server from Review.
// Needs a real server (ANIMUS_PAIR_LINK) with the town stage's town/baker. Quest rewards with
// experience need apply:commands, which a console pairing has. The applied quest stays on the
// server, so reset the stage (or delete quests/town/bread-for-the-baker.json) before another take.
module.exports = async (L) => {
  const {film: f, page, h} = await L.questSession('12-quest-rewards-apply');
  const quest = page.getByRole('region', {name: 'Quest'});
  const preview = page.getByRole('region', {name: 'Preview'});
  await h.click(quest.getByRole('tablist', {name: 'Quest'}).getByRole('tab', {name: /^Rewards/}), {after: 600});
  await h.type(quest.getByRole('spinbutton', {name: 'Reward experience'}), '120', {clear: true, delay: 55, after: 250});
  await h.click(quest.getByRole('toolbar', {name: 'Reward flags'}).getByRole('button', {name: 'Add flag'}), {after: 600});
  const flag = quest.getByRole('textbox', {name: /flag 1 name/i}).or(quest.getByRole('combobox', {name: /flag 1 name/i})).first();
  await h.type(flag, 'baker_helped', {clear: true, delay: 55, after: 250});
  await h.pick(preview.getByRole('combobox', {name: 'Journal shows'}), {label: 'Finished'}, {after: 800});
  await h.result(1600);
  const bar = page.getByRole('contentinfo', {name: 'Save'});
  await h.click(bar.getByRole('button', {name: 'Review'}), {after: 900, noReveal: true});
  const review = page.getByRole('dialog', {name: 'Review'});
  await h.click(review.getByRole('button', {name: 'Validate'}), {after: 1200, noReveal: true});
  await h.result(1600);
  await h.click(review.getByRole('button', {name: /^Apply 1/}), {after: 1200, noReveal: true});
  const applied = review.getByRole('region', {name: 'Applied'});
  await applied.waitFor();
  // Rest just past the applied line so the cursor does not cover it.
  await h.moveTo(applied.getByRole('listitem').first(), {ox: 0.6, oy: 1.8, noReveal: true});
  await h.result(2200);
  return L.finish(f, {closing: 'Applied to the server: 120 experience and the baker_helped flag'});
};
