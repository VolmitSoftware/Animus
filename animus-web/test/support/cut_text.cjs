// Text cut off inside a popup: an element whose text is clipped (ellipsis
// or overflow) or a short label broken over two lines.
const assert = require('node:assert/strict');

/**
 * Fails when any element matching [selector] inside [scope] clips its text
 * or wraps it onto a second line.
 */
async function assertNothingCut(scope, selector, where) {
  const cut = await scope.evaluate((root, query) => [...root.querySelectorAll(query)].flatMap((element) => {
    const text = element.textContent.trim();
    if (!text) return [];
    const range = document.createRange();
    range.selectNodeContents(element);
    const lines = new Set([...range.getClientRects()].map((rect) => Math.round(rect.top))).size;
    return element.scrollWidth > element.clientWidth + 1 || lines > 1 ? [text] : [];
  }), selector);
  assert.deepEqual(cut, [], `${where}: labels cut off`);
}

module.exports = {assertNothingCut};
