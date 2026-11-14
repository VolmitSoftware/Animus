// Helpers for arcane_jaspr's slider: a root carrying data-arcane-slider (its
// id) with min, max and step in data-arcane-slider-*, and one thumb with
// role="slider" and aria-valuenow. The runtime moves it by pointer and by
// keyboard (arrows step, Page Up/Down step ten, Home and End).

/** The thumb of the slider whose id is [id]. */
function sliderThumb(page, id) {
  return page.locator(`[data-arcane-slider="${id}"] [role="slider"]`);
}

/**
 * Moves [thumb] to [value] from the keyboard: Home, then Page Up and the
 * arrows by its step, and waits until it reports the value.
 */
async function setSlider(thumb, value) {
  const root = thumb.locator('xpath=ancestor::*[@data-arcane-slider][1]');
  const min = Number(await root.getAttribute('data-arcane-slider-min'));
  const step = Number(await root.getAttribute('data-arcane-slider-step') || '1');
  const steps = Math.round((value - min) / step);
  await thumb.focus();
  await thumb.press('Home');
  for (let i = 0; i < Math.floor(steps / 10); i++) await thumb.press('PageUp');
  for (let i = 0; i < steps % 10; i++) await thumb.press('ArrowRight');
  await thumb.page().waitForFunction(([el, want]) => Number(el.getAttribute('aria-valuenow')) === want, [await thumb.elementHandle(), value]);
}

/**
 * Presses on [thumb]'s track at [from] (0 to 1 of its width) and moves to
 * [to] without letting go; returns a function that lets go.
 */
async function dragSlider(thumb, from, to) {
  const track = await thumb.locator('xpath=ancestor::*[@data-arcane-slider-track][1]').boundingBox();
  const y = track.y + track.height / 2;
  const mouse = thumb.page().mouse;
  await mouse.move(track.x + track.width * from, y);
  await mouse.down();
  await mouse.move(track.x + track.width * to, y, {steps: 4});
  return () => mouse.up();
}

module.exports = {sliderThumb, setSlider, dragSlider};
