// Shell helpers for the browser checks. At 760 px and below the sidebar is
// a drawer behind the top bar's Documents button, and the top bar's other
// controls sit in its More menu; these open them when needed, so a check
// reads the same at every width.

/** True when the page shows the phone shell. */
async function isCompact(page) {
  return page.evaluate(() => window.matchMedia('(max-width: 760px)').matches);
}

/** The sidebar, with the drawer opened first on phones. */
async function sidebar(page) {
  const aside = page.locator('#animus-sidebar');
  if (await isCompact(page) && !(await aside.evaluate(el => el.classList.contains('is-open')))) {
    await page.locator('#animus-documents-button').click();
    await page.locator('#animus-sidebar.is-open').waitFor();
    // The drawer moves focus inside itself once it is open.
    await page.waitForFunction(() => document.activeElement && document.activeElement.closest('#animus-sidebar'));
  }
  return aside;
}

/** Closes the phone drawer if it is open (Escape from inside it). */
async function closeDrawer(page) {
  if (!(await page.locator('#animus-sidebar.is-open').count())) return;
  await page.locator('#animus-sidebar.is-open').locator('a[href], button, input').first().focus();
  await page.keyboard.press('Escape');
  await page.locator('#animus-sidebar.is-open').waitFor({state: 'detached'});
}

/** Opens a sidebar entry by its visible text (the drawer closes itself). */
async function openFromSidebar(page, text, {exact = true} = {}) {
  await (await sidebar(page)).getByText(text, {exact}).first().click();
}

/**
 * Runs a top bar control by name: a direct button on wide screens, or the
 * item of the same name in More (or in the Open, Import and Export menus).
 */
async function topbarAction(page, name, {menu} = {}) {
  if (await isCompact(page)) {
    await page.locator('#animus-menu-more').click();
    await page.getByRole('menuitem', {name}).click();
    return;
  }
  if (menu) {
    await page.locator(`#animus-menu-${menu}`).click();
    await page.getByRole('menuitem', {name}).click();
    return;
  }
  await page.locator('.animus-topbar').getByRole('button', {name}).click();
}

module.exports = {isCompact, sidebar, closeDrawer, openFromSidebar, topbarAction};
