// NPC and trait group documents show their form in tabs (Basics, Look,
// Movement, Talk and trade, Combat; Advanced adds Attributes, Modules and
// Integrations; a trait group starts with Members). The tab row is
// `.animus-npc-tabs`; each panel is `[data-npc-tab="<slug>"]`.

const {pickTab} = require('./shell.cjs');

/** The slug of a tab label: "Talk and trade" gives "talk-and-trade". */
function tabSlug(label) {
  return label.toLowerCase().replace(/[^a-z0-9]+/g, '-').replace(/^-|-$/g, '');
}

/**
 * Opens the NPC tab named [label] and waits for its panel. On a page
 * without NPC tabs it does nothing, so a check reads the same either way.
 */
async function npcTab(page, label) {
  const row = page.locator('.animus-npc-tabs');
  if (!(await row.count())) return null;
  const panel = page.locator(`[data-npc-tab="${tabSlug(label)}"]`);
  if (await panel.isVisible().catch(() => false)) return panel;
  await pickTab(page, row, label);
  await panel.waitFor({state: 'visible'});
  return panel;
}

module.exports = {npcTab, tabSlug};
