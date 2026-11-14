// Frame helpers for the browser checks. The nav lists jobs, not documents:
// documents open through Search (the command palette). At 760 px and below
// the nav is a drawer behind the top bar's Menu button, and Undo and Redo
// move into the overflow menu; these helpers open whatever is needed, so a
// check reads the same at every width.

/** True when the page shows the phone frame. */
async function isCompact(page) {
  return page.evaluate(() => window.matchMedia('(max-width: 760px)').matches);
}

/** The nav, with the drawer opened first on phones. */
async function sidebar(page) {
  if (await isCompact(page)) {
    if (!(await page.locator('#ui-nav-drawer .arcane-sidebar').isVisible().catch(() => false))) {
      await page.locator('#ui-nav-button').click();
      await page.locator('#ui-nav-drawer .arcane-sidebar').waitFor({state: 'visible'});
    }
    return page.locator('#ui-nav-drawer .arcane-sidebar');
  }
  return page.locator('.ui-nav .arcane-sidebar');
}

/** Closes the phone nav drawer if it is open. */
async function closeDrawer(page) {
  const drawer = page.locator('#ui-nav-drawer .arcane-sidebar');
  if (!(await drawer.isVisible().catch(() => false))) return;
  await page.keyboard.press('Escape');
  await drawer.waitFor({state: 'hidden'});
}

/** Opens a job from the nav by its label (NPCs, Shops, Movement). */
async function openJob(page, label) {
  await (await sidebar(page)).getByRole('link', {name: label, exact: false}).first().click();
}

/** The search box of Search (shadcn's command palette). */
function searchBox(page) {
  return page.locator('[data-arcane-command-input="ui-search"]');
}

/**
 * Opens a document through Search by its title or id: the result whose
 * label is exactly [text], else the first one.
 */
async function openFromSidebar(page, text) {
  await openSearch(page);
  await searchBox(page).fill(text);
  const results = page.locator('[data-arcane-command="ui-search"] [data-arcane-command-item]:not([hidden])');
  await results.first().waitFor();
  const exact = results.filter({has: page.locator(':scope > span', {hasText: new RegExp(`^${text.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}$`)})});
  // Search filters as it is typed in: give the exact match a moment to show.
  await exact.first().waitFor({timeout: 3000}).catch(() => {});
  await ((await exact.count()) ? exact.first() : results.first()).click();
  await searchBox(page).waitFor({state: 'hidden'});
}

/** Opens Search. */
async function openSearch(page) {
  await page.locator('.ui-topbar').getByRole('button', {name: 'Search'}).click();
  await searchBox(page).waitFor({state: 'visible'});
}

/** Sets the Mode (simple or advanced) and waits for the frame to follow. */
async function setMode(page, mode) {
  const label = mode === 'advanced' ? 'Advanced' : 'Simple';
  if (await isCompact(page)) {
    await sidebar(page);
    await page.locator('#ui-nav-drawer').getByRole('button', {name: label, exact: true}).click();
    await closeDrawer(page);
  } else {
    await page.locator('.ui-topbar .ui-mode-switch').getByRole('button', {name: label, exact: true}).click();
  }
  await page.waitForFunction(value => window.localStorage.getItem('animus.mode') === value, mode);
}

/** Old menu names, as the overflow menu says them now. */
const renamed = {
  'Commands': 'Search',
  'Export the data folder zip': 'Export zip',
  'Import a Blockbench model': 'Import Blockbench model',
  'Import a Gloss bundle': 'Import Gloss bundle',
  'Export a Gloss bundle': 'Export Gloss bundle',
  'Use light theme': 'Light theme',
  'Use dark theme': 'Dark theme',
};
const advancedOnly = new Set(['Import Blockbench model', 'Import Gloss bundle', 'Export Gloss bundle']);

/**
 * Runs a top bar control by name: Search, Undo and Redo, or an entry of
 * the overflow menu (switching to Advanced for its Advanced entries).
 */
async function topbarAction(page, name) {
  const wanted = renamed[name] || name;
  if (wanted === 'Search') return openSearch(page);
  const compact = await isCompact(page);
  if (!compact && (wanted === 'Undo' || wanted === 'Redo')) {
    await page.locator('.ui-topbar-history button').nth(wanted === 'Undo' ? 0 : 1).click();
    return;
  }
  if (advancedOnly.has(wanted) && await page.evaluate(() => window.localStorage.getItem('animus.mode')) !== 'advanced') await setMode(page, 'advanced');
  await page.locator('#ui-overflow-button').click();
  await page.getByRole('menuitem', {name: new RegExp(`^${wanted.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}`)}).first().click();
}

/** The save bar's line, and its button for [action] (apply, review, ...). */
function saveState(page) {
  return page.locator('.ui-save-bar .ui-save-message');
}

function saveAction(page, action) {
  return page.locator(`.ui-save-bar [data-save-action="${action}"]`);
}

/** Every kind's wire name, for walking a workspace's lists. */
const kinds = ['npc', 'group', 'behavior', 'pack', 'shop', 'quest', 'inventory', 'menu', 'hologram', 'rig', 'animation', 'route', 'recording', 'skin', 'text-animation', 'presets', 'server-behaviors'];

/**
 * Every document of the open workspace as its page's hash, read from the
 * kinds' list pages (the nav lists jobs, not documents), in Advanced, where
 * every kind has its list. Returns to the page and the mode it started on.
 */
async function documentHrefs(page) {
  const start = await page.evaluate(() => location.hash);
  const simple = await page.evaluate(() => window.localStorage.getItem('animus.mode')) !== 'advanced';
  if (simple) await setMode(page, 'advanced');
  const workspace = /#\/workspace\/([^/?]+)/.exec(start)?.[1];
  if (!workspace) throw new Error(`documentHrefs: not in a workspace (${start})`);
  const hrefs = [];
  for (const kind of kinds) {
    await page.evaluate(hash => { location.hash = hash; }, `#/workspace/${workspace}/list/${kind}`);
    await page.locator('.ui-library').waitFor();
    await page.waitForTimeout(60);
    const rows = page.locator('.ui-library-rows');
    if (!(await rows.count())) continue;
    const seen = new Set();
    for (let round = 0; round < 200; round++) {
      for (const key of await page.locator('.ui-library-row').evaluateAll(found => found.map(row => row.getAttribute('data-key')))) seen.add(key);
      const more = await rows.evaluate(el => {
        if (el.scrollTop + el.clientHeight >= el.scrollHeight - 1) return false;
        el.scrollTop += el.clientHeight;
        return true;
      });
      if (!more) break;
      await page.waitForTimeout(40);
    }
    for (const key of seen) {
      const colon = key.indexOf(':');
      hrefs.push(`#/workspace/${workspace}/${key.slice(0, colon)}/${encodeURIComponent(key.slice(colon + 1))}`);
    }
  }
  await page.evaluate(hash => { location.hash = hash; }, start);
  if (simple) await setMode(page, 'simple');
  return hrefs;
}


/**
 * Shows a document's view (visual, code, split, preview, conversation)
 * through the address, in Advanced mode, where the frame offers views.
 */
async function showView(page, view) {
  const wanted = view.toLowerCase();
  if (wanted !== 'visual' && (await page.evaluate(() => window.localStorage.getItem('animus.mode'))) !== 'advanced') await setMode(page, 'advanced');
  await page.evaluate((name) => {
    const [route] = location.hash.split('?');
    location.hash = name === 'visual' ? route : `${route}?view=${name}`;
  }, wanted);
  await page.locator('#animus-main').waitFor();
  await page.waitForTimeout(150);
}

/**
 * Picks [label] in the dropdown (shadcn's combobox) whose trigger is
 * [trigger]: `page.getByRole('combobox', {name: 'Pose'})` or `#<id>`.
 */
async function pickOption(page, trigger, label) {
  await trigger.click();
  const list = page.locator(`[id="${await trigger.getAttribute('aria-controls')}"]`);
  await list.getByRole('option', {name: label, exact: true}).click();
}

/**
 * Opens the tab named [label] inside [container]: a click on the tab, or,
 * where the row is too narrow for the strip (phones), a pick from the
 * dropdown the strip turned into. The strip measures itself after it draws
 * and folds a frame later, so the choice waits until it has stopped
 * changing; a tab found a moment before it folds would be gone when clicked.
 */
async function pickTab(page, container, label) {
  await container.first().evaluate((element) => new Promise((resolve) => {
    let last = performance.now();
    const observer = new MutationObserver(() => { last = performance.now(); });
    observer.observe(element, {subtree: true, childList: true, attributes: true});
    const check = () => {
      if (performance.now() - last >= 150) {
        observer.disconnect();
        resolve();
      } else {
        requestAnimationFrame(check);
      }
    };
    requestAnimationFrame(check);
  }));
  const tab = container.getByRole('tab', {name: label, exact: true});
  if (await tab.count()) return tab.first().click();
  const folded = container.locator('.ui-tabs.is-folded [role="combobox"]').first();
  await folded.waitFor();
  await pickOption(page, folded, label);
}

/**
 * Makes a blank workspace named [name] from the start page and lands on
 * its Home. Blank workspaces are an Advanced start, so the mode is
 * Advanced while the dialog runs and Simple again afterwards when it was.
 */
async function blankWorkspace(page, name) {
  const mode = await page.evaluate(() => window.localStorage.getItem('animus.mode'));
  if (mode !== 'advanced') {
    await page.evaluate(() => window.localStorage.setItem('animus.mode', 'advanced'));
    await page.reload();
  }
  await page.getByRole('heading', {name: 'Animus web editor'}).waitFor();
  const start = page.getByRole('button', {name: 'Blank workspace', exact: true});
  if (await start.isVisible()) await start.click();
  else {
    await page.locator('#start-more-button').click();
    await page.getByRole('menuitem', {name: 'Blank workspace'}).click();
  }
  const dialog = page.getByRole('dialog', {name: 'Blank workspace'});
  await dialog.waitFor();
  await dialog.locator('#start-workspace-name').fill(name);
  await dialog.getByRole('button', {name: 'Create', exact: true}).click();
  await page.waitForFunction(() => /^#\/workspace\/[^/?]+$/.test(location.hash));
  // Back through the Mode switch, so a workspace kept only in memory stays open.
  if (mode !== 'advanced') {
    await page.locator('.ui-save-bar').waitFor();
    await setMode(page, 'simple');
  }
}

/** Waits until a workspace opened from files shows its frame. */
async function workspaceOpen(page) {
  await page.waitForFunction(() => /#\/workspace\//.test(location.hash));
  await page.locator('.ui-save-bar').waitFor();
}

/** Waits until the open server workspace is online (the badge beside the switcher). */
async function waitOnline(page, timeout = 20000) {
  await page.locator('[data-testid="context-state"]', {hasText: 'Online'}).waitFor({timeout});
}

/**
 * Clicks the start page's [name] ("Open examples", "Open folder", "Open
 * zip"): the start row's own button, or its "..." menu entry where the row
 * is too narrow and folded it away. Waits for the page to draw first.
 */
async function startAction(page, name) {
  const button = page.getByRole('button', {name, exact: true});
  await button.or(page.locator('#start-more-button')).first().waitFor();
  // The row folds what does not fit a frame after it is drawn.
  await page.waitForTimeout(200);
  if (await button.isVisible()) return button.click();
  await page.locator('#start-more-button').click();
  await page.getByRole('menuitem', {name}).click();
}

/**
 * Shows a phone's preview: the page header's Preview (Test for a behavior)
 * toggle, pressed. Does nothing where the preview is already on the page.
 */
async function showPreview(page) {
  const toggle = page.locator('button[aria-controls="ui-doc-preview"][aria-pressed="false"]').first();
  if (await toggle.isVisible().catch(() => false)) await toggle.click();
}

/** Puts a phone's preview back behind its header toggle. */
async function hidePreview(page) {
  const toggle = page.locator('button[aria-controls="ui-doc-preview"][aria-pressed="true"]').first();
  if (await toggle.isVisible().catch(() => false)) await toggle.click();
}

module.exports = {startAction, showPreview, hidePreview, pickOption, pickTab, blankWorkspace, showView, waitOnline, workspaceOpen, searchBox, documentHrefs, isCompact, sidebar, closeDrawer, openJob, openFromSidebar, openSearch, setMode, topbarAction, saveState, saveAction};
