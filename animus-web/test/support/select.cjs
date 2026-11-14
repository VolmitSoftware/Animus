// Helpers for the editor's dropdowns: arcane_jaspr's shadcn combobox behind
// AnimusSelect. The trigger is a button with role="combobox"
// whose aria-controls names its list; the list is in the page while closed
// (hidden) and shows while open; each option is a [role="option"] with its
// value in data-arcane-value and its text in data-label; the chosen one has
// aria-selected="true".

/** Every dropdown trigger inside [scope]. */
function dropdowns(scope) {
  return scope.locator('button[role="combobox"]');
}

/** The dropdown trigger inside [scope] showing [text] (its value or placeholder). */
function dropdownShowing(scope, text) {
  return dropdowns(scope).filter({hasText: text}).first();
}

/**
 * Narrows [locator] to the trigger button: a label locator such as
 * getByLabel also matches the list, which carries the same name.
 */
function triggerOf(locator) {
  return locator.and(locator.page().locator('button[role="combobox"]'));
}

/** The list of [trigger], open or not. */
async function listboxOf(trigger) {
  trigger = triggerOf(trigger);
  const id = await trigger.getAttribute('aria-controls');
  return trigger.page().locator(`[id="${id}"]`);
}

/** Opens [trigger] (if closed) and returns its list. */
async function openDropdown(trigger) {
  trigger = triggerOf(trigger);
  const listbox = await listboxOf(trigger);
  if (!(await listbox.isVisible().catch(() => false))) await trigger.click();
  await listbox.waitFor({state: 'visible'});
  return listbox;
}

const quoted = value => value.replace(/"/g, '\\"');

/** Picks the option with [value] in the dropdown [trigger] by pointer. */
async function choose(trigger, value) {
  const listbox = await openDropdown(trigger);
  const option = listbox.locator(`[role="option"][data-arcane-value="${quoted(value)}"]`);
  await option.scrollIntoViewIfNeeded();
  await option.click();
  await listbox.waitFor({state: 'hidden'});
}

/** Picks the option labelled [text] (exact) in the dropdown [trigger]. */
async function chooseText(trigger, text) {
  const listbox = await openDropdown(trigger);
  const option = listbox.locator(`[role="option"][data-label="${quoted(text)}"]`).first();
  await option.scrollIntoViewIfNeeded();
  await option.click();
  await listbox.waitFor({state: 'hidden'});
}

/** The chosen value of the dropdown [trigger] ('' when none is). */
async function valueOf(trigger) {
  const listbox = await listboxOf(trigger);
  const chosen = listbox.locator('[role="option"][aria-selected="true"]');
  return (await chosen.count()) ? ((await chosen.first().getAttribute('data-arcane-value')) ?? '') : '';
}

/** The option values of [trigger], opening and closing it. */
async function optionValues(trigger) {
  const listbox = await openDropdown(trigger);
  const values = await listbox.locator('[role="option"]').evaluateAll(rows => rows.map(row => row.getAttribute('data-arcane-value')));
  await trigger.page().keyboard.press('Escape');
  await listbox.waitFor({state: 'hidden'});
  return values;
}

/** The option labels of [trigger], opening and closing it. */
async function optionLabels(trigger) {
  const listbox = await openDropdown(trigger);
  const labels = await listbox.locator('[role="option"]').evaluateAll(rows => rows.map(row => row.getAttribute('data-label')));
  await trigger.page().keyboard.press('Escape');
  await listbox.waitFor({state: 'hidden'});
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
