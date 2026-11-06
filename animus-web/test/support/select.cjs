// Helpers for the editor's dropdowns (lib/components/select). A dropdown is
// a button with role="combobox" whose current value sits in data-value; its
// options render only while it is open, each a [role="option"] with the
// value in data-value.

/** Every dropdown trigger inside [scope]. */
function dropdowns(scope) {
  return scope.locator('button[role="combobox"]');
}

/** The dropdown trigger inside [scope] showing [text] (its value or placeholder). */
function dropdownShowing(scope, text) {
  return dropdowns(scope).filter({has: scope.page().locator('.animus-select-value', {hasText: text})}).first();
}

/**
 * Narrows [locator] to the trigger button: a label locator such as
 * getByLabel also matches the open listbox, which carries the same name.
 */
function triggerOf(locator) {
  return locator.and(locator.page().locator('button[role="combobox"]'));
}

/** The open listbox of [trigger]. */
async function listboxOf(trigger) {
  trigger = triggerOf(trigger);
  const id = await trigger.getAttribute('aria-controls');
  return trigger.page().locator(`[id="${id}"]`);
}

/** Opens [trigger] (if closed) and returns its listbox. */
async function openDropdown(trigger) {
  trigger = triggerOf(trigger);
  if ((await trigger.getAttribute('aria-expanded')) !== 'true') await trigger.click();
  const listbox = await listboxOf(trigger);
  await listbox.waitFor();
  return listbox;
}

/** Picks the option with [value] in the dropdown [trigger] by pointer. */
async function choose(trigger, value) {
  const listbox = await openDropdown(trigger);
  const option = listbox.locator(`[role="option"][data-value="${value.replace(/"/g, '\\"')}"]`);
  await option.scrollIntoViewIfNeeded();
  await option.click();
  await listbox.waitFor({state: 'detached'});
}

/** Picks the option labelled [text] (exact) in the dropdown [trigger]. */
async function chooseText(trigger, text) {
  const listbox = await openDropdown(trigger);
  const option = listbox.locator('[role="option"]', {has: trigger.page().locator('.animus-select-label', {hasText: new RegExp(`^${text.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}$`)})}).first();
  await option.scrollIntoViewIfNeeded();
  await option.click();
  await listbox.waitFor({state: 'detached'});
}

/** The chosen value of the dropdown [trigger]. */
async function valueOf(trigger) {
  trigger = triggerOf(trigger);
  return (await trigger.getAttribute('data-value')) ?? '';
}

/** The option values of [trigger], opening and closing it. */
async function optionValues(trigger) {
  const listbox = await openDropdown(trigger);
  const values = await listbox.locator('[role="option"]:not([data-action])').evaluateAll(rows => rows.map(row => row.getAttribute('data-value')));
  await trigger.page().keyboard.press('Escape');
  await listbox.waitFor({state: 'detached'});
  return values;
}

/** The option labels of [trigger], opening and closing it. */
async function optionLabels(trigger) {
  const listbox = await openDropdown(trigger);
  const labels = await listbox.locator('[role="option"]:not([data-action]) .animus-select-label').allTextContents();
  await trigger.page().keyboard.press('Escape');
  await listbox.waitFor({state: 'detached'});
  return labels;
}

/** Waits until the dropdown [trigger] holds [value]. */
async function waitForValue(trigger, value) {
  const deadline = Date.now() + 10000;
  let seen = await valueOf(trigger);
  while (seen !== value) {
    if (Date.now() > deadline) throw new Error(`the dropdown holds "${seen}", not "${value}"`);
    await trigger.page().waitForTimeout(25);
    seen = await valueOf(trigger);
  }
}

/** Picks [value] in [trigger] and waits until the trigger shows it. */
async function chooseAndWait(trigger, value) {
  await choose(trigger, value);
  await waitForValue(trigger, value);
}

/**
 * The first dropdown trigger inside [scope] with an option whose label
 * matches [text] (a string is a substring match, as hasText was).
 */
async function dropdownWithOption(scope, text) {
  const triggers = dropdowns(scope);
  await triggers.first().waitFor();
  const count = await triggers.count();
  const seen = [];
  for (let index = 0; index < count; index++) {
    const trigger = triggers.nth(index);
    if (!(await trigger.isVisible()) || (await trigger.isDisabled())) continue;
    const labels = await optionLabels(trigger);
    seen.push(labels.join(' | '));
    if (!labels.some(label => typeof text === 'string' ? label.includes(text) : text.test(label))) continue;
    const id = await trigger.getAttribute('id');
    return id ? scope.page().locator(`[id="${id}"]`) : trigger;
  }
  throw new Error(`no dropdown has an option ${text}; saw: ${seen.join(' / ')}`);
}

module.exports = {triggerOf, dropdowns, dropdownShowing, dropdownWithOption, listboxOf, openDropdown, choose, chooseAndWait, chooseText, valueOf, waitForValue, optionValues, optionLabels};
