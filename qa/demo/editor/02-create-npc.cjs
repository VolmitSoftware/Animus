// Creates an NPC from New document, then turns its 3D preview.
// The new NPC is not on the fake server yet, so the stage's terrain lookup
// (/animus/v1/terrain/position) answers 404 once or twice; that is expected.
module.exports = async (L) => {
  const {film: f, page, h} = await L.pairedSession('02-create-npc');
  await page.waitForTimeout(800);
  f.mark();
  await h.pause(300);
  await h.click(page.getByRole('link', {name: 'New document'}), {after: 900});
  await h.click(page.getByRole('navigation', {name: 'Document kinds'}).getByRole('link', {name: /^NPC/}), {after: 700});
  const form = page.getByRole('region', {name: 'New NPC'});
  await h.type(form.getByLabel('Name', {exact: true}), 'Harbor Lookout');
  await h.type(form.getByLabel('Folder', {exact: true}), 'harbor', {clear: true});
  await h.hover(form.getByLabel('File id'), 1200);
  await h.click(form.getByRole('radio', {name: /^Workspace skin/}), {after: 900});
  await h.type(form.getByRole('group', {name: 'Position'}).getByLabel('X', {exact: true}), '12', {clear: true, after: 300});
  await h.click(form.getByRole('button', {name: 'Create NPC'}), {after: 1200});
  await page.getByRole('heading', {name: 'Harbor Lookout', level: 1}).waitFor();
  await h.result(1800);
  await h.click(page.getByRole('tablist', {name: 'View'}).getByRole('tab', {name: 'Preview'}), {after: 900});
  // The fixture's terrain buries the model in grass; turning it off shows the body clearly.
  await h.click(page.getByRole('button', {name: 'Terrain', exact: true}), {after: 1000});
  const stage = page.getByRole('img', {name: 'Preview of Harbor Lookout'});
  await h.moveTo(stage, {ox: 0.5, oy: 0.55});
  const {x, y} = f.pos;
  await page.mouse.down();
  await h.glide(x + 170, y + 10);
  await h.glide(x - 170, y - 10);
  await h.glide(x + 20, y);
  await page.mouse.up();
  await h.result(2000);
  return L.finish(f, {closing: 'Harbor Lookout created in harbor/ and shown in the preview'});
};
