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

/** The open listbox of [trigger]. */
async function listboxOf(trigger) {
  const id = await trigger.getAttribute('aria-controls');
  return trigger.page().locator(`[id="${id}"]`);
}

/** Opens [trigger] (if closed) and returns its listbox. */
async function openDropdown(trigger) {
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

module.exports = {dropdowns, dropdownShowing, listboxOf, openDropdown, choose, chooseText, valueOf, optionValues};
