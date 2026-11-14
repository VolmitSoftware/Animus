// The gizmo on the rig stage: picks the right arm on the model, swings it
// with a Rotate handle, then raises the head with the Move handle; the
// inspector shows the new rest rotation and pivot. Offline: the knight is
// imported unfilmed into a blank workspace (see rig.cjs).
const R = require('./rig.cjs');

module.exports = async (L) => {
  const {film: f, page, h} = await R.knightSession(L, '33-rig-gizmo', {
    // A three-quarter view, so every handle has length on screen.
    prepare: async (p) => {
      await p.getByRole('img', {name: /^Rig animus:knight/}).focus();
      for (const k of ['ArrowLeft', 'ArrowLeft', 'ArrowLeft', 'ArrowUp']) { await p.keyboard.press(k); await p.waitForTimeout(150); }
      await R.bone(p, 'body').click();
    },
  });
  // Bone dots and gizmo handles are SVG circles on the stage overlay; dots are
  // aria-hidden (the tree is the keyboard route), so both are found by data-gizmo.
  const dot = (bone) => page.locator(`[data-gizmo="bone"][data-bone="${bone}"]`);
  const handle = (axis) => page.locator(`[data-gizmo="handle"][data-axis="${axis}"]`);
  const gizmo = page.getByRole('group', {name: 'Gizmo'});
  f.mark();
  await h.pause(400);
  await h.click(dot('arm_r'), {after: 700, noReveal: true});
  await h.click(gizmo.getByRole('button', {name: 'Rotate', exact: true}), {after: 700});
  // Rotate turns 0.75 degrees per pixel along the handle: about 60 degrees here.
  await R.drag(f, h, handle('x'), 80, 0);
  await h.moveTo(page.getByRole('textbox', {name: 'Rotation X'}), {ox: 1.2, oy: 1.8});
  await h.pause(300);
  await h.result(2000);
  await h.click(dot('head'), {after: 700, noReveal: true});
  await h.click(gizmo.getByRole('button', {name: 'Move', exact: true}), {after: 700});
  await R.drag(f, h, handle('y'), 0, -45);
  await h.moveTo(page.getByRole('textbox', {name: 'Pivot Y'}), {oy: 1.8});
  await h.pause(300);
  await h.result(2200);
  return L.finish(f, {closing: 'arm_r has a new rest rotation and the head sits higher'});
};
