// Stage helpers for the browser checks: every 3D view sits in the kit's
// stage frame (`.ui-stage`): a header with its toggles and buttons, the
// canvas (`.animus-mc-stage`), and one status line (`.ui-stage-status`)
// whose (i) tip holds the details.

/** The first stage on the page, or the one inside [scope]. */
function stage(page, scope) {
  return (scope || page).locator('.ui-stage').first();
}

/** Waits until the stage's canvas has drawn more than [after] frames with nothing left to build. */
async function stageDrawn(page, after = 0) {
  await page.waitForFunction((n) => {
    const host = document.querySelector('.animus-mc-gl-host');
    return host && Number(host.getAttribute('data-frames')) > n && host.getAttribute('data-unbuilt') === '0';
  }, after);
}

/** The frames the first stage has drawn so far. */
async function stageFrames(page) {
  return Number(await page.locator('.animus-mc-gl-host').first().getAttribute('data-frames'));
}

/** The status line under the first stage. */
async function stageLine(page) {
  return ((await page.locator('.ui-stage-status').first().textContent()) || '').trim();
}

/** The tip beside the status line, as the (i) button's description gives it. */
async function stageTip(page) {
  return page.evaluate(() => {
    const button = document.querySelector('.ui-stage-status [data-animus-info]');
    const id = button && button.getAttribute('aria-describedby');
    const text = id && document.getElementById(id);
    return text ? text.textContent.trim() : '';
  });
}

/** Waits until the stage's tip matches [pattern]. */
async function waitForStageTip(page, pattern, timeout = 15000) {
  await page.waitForFunction((source) => {
    const button = document.querySelector('.ui-stage-status [data-animus-info]');
    const id = button && button.getAttribute('aria-describedby');
    const text = id && document.getElementById(id);
    return Boolean(text) && new RegExp(source).test(text.textContent);
  }, pattern.source, {timeout});
}

/** Shows the preview on phones, where it waits behind the page header's Preview button (or its "..." menu). */
async function showPreview(page) {
  const narrow = await page.evaluate(() => window.matchMedia('(max-width: 760px)').matches);
  if (!narrow) return;
  await page.locator('#animus-main-heading').waitFor();
  if (await page.locator('.ui-stage').count()) return;
  const button = page.getByRole('button', {name: 'Preview', exact: true});
  if (await button.first().isVisible().catch(() => false)) {
    await button.first().click();
  } else {
    await page.locator('main').getByRole('button', {name: 'More actions', exact: true}).first().click();
    await page.getByRole('menuitem', {name: 'Preview', exact: true}).filter({visible: true}).click();
  }
  await page.locator('.ui-stage').first().waitFor();
}

/** Opens the "..." menu in the first stage's header. */
async function openStageMenu(page) {
  await page.locator('.ui-stage-head').first().getByRole('button', {name: 'More actions', exact: true}).click();
  await page.getByRole('menuitem').filter({visible: true}).first().waitFor();
}

/**
 * Turns a layer (Terrain, Route, Nearby) of the first stage on or off: its
 * toggle beside the title while the toggles fit, else its row in the
 * header's "..." menu, which says "On" while the layer is on.
 */
async function setLayer(page, label, on) {
  const head = page.locator('.ui-stage-head').first();
  const toggle = head.getByRole('button', {name: label, exact: true});
  if (await toggle.isVisible().catch(() => false)) {
    if ((await toggle.getAttribute('aria-pressed')) !== String(on)) await toggle.click();
    return;
  }
  await openStageMenu(page);
  const item = page.getByRole('menuitem', {name: new RegExp(label)}).filter({visible: true});
  const checked = /On$/.test((await item.textContent()).trim());
  if (checked !== on) await item.click();
  else await page.keyboard.press('Escape');
}

/** Picks the stage's radius from the header's "..." menu. */
async function pickRadius(page, blocks) {
  await openStageMenu(page);
  await page.getByRole('menuitem', {name: new RegExp(`\\b${blocks} block radius`)}).filter({visible: true}).click();
}

/** The first stage's subject on its canvas, from `data-subject`: {left, top, right, bottom, width, height} in canvas pixels. */
async function subjectBox(page) {
  return page.evaluate(() => {
    const host = document.querySelector('.animus-mc-gl-host');
    const stage = document.querySelector('.animus-mc-stage').getBoundingClientRect();
    const [left, top, right, bottom] = (host.getAttribute('data-subject') || '').split(' ').map(Number);
    return {left, top, right, bottom, width: stage.width, height: stage.height};
  });
}

/** The WCAG contrast of two `[r, g, b]` colours (0 to 255). */
function contrast(a, b) {
  const lum = ([r, g, bl]) => [r, g, bl].map((c) => {
    const v = c / 255;
    return v <= 0.03928 ? v / 12.92 : ((v + 0.055) / 1.055) ** 2.4;
  }).reduce((sum, v, i) => sum + v * [0.2126, 0.7152, 0.0722][i], 0);
  const [hi, lo] = [lum(a), lum(b)].sort((x, y) => y - x);
  return (hi + 0.05) / (lo + 0.05);
}

/**
 * Every visible text on the first stage, as drawn over what is behind it:
 * the layer is hidden for one screenshot of the canvas, and each light run
 * of each text (at least as light as `&7` grey) is measured against its
 * plate laid over the lightest pixel behind the text. [{text, ratio}].
 */
async function textContrast(page) {
  const stage = page.locator('.animus-mc-stage').first();
  const texts = await page.evaluate(() => {
    const frame = document.querySelector('.animus-mc-stage').getBoundingClientRect();
    const rgba = (value) => (value.match(/[\d.]+/g) || []).map(Number);
    return [...document.querySelectorAll('.animus-mc-stage .animus-mc-text')]
      .filter((text) => getComputedStyle(text).visibility !== 'hidden' && text.textContent.trim())
      .map((text) => {
        const box = text.getBoundingClientRect();
        return {
          text: text.textContent.trim(),
          box: [box.left - frame.left, box.top - frame.top, box.width, box.height],
          plate: rgba(getComputedStyle(text).backgroundColor),
          runs: [...text.querySelectorAll('span')].filter((span) => span.textContent.trim()).map((span) => rgba(getComputedStyle(span).color)),
        };
      });
  });
  await page.evaluate(() => { document.querySelector('.animus-mc-text-layer').style.visibility = 'hidden'; });
  const picture = (await stage.screenshot()).toString('base64');
  await page.evaluate(() => { document.querySelector('.animus-mc-text-layer').style.visibility = ''; });
  const behind = await page.evaluate(async ({picture, boxes}) => {
    const image = new Image();
    image.src = `data:image/png;base64,${picture}`;
    await image.decode();
    const canvas = document.createElement('canvas');
    canvas.width = image.width;
    canvas.height = image.height;
    const context = canvas.getContext('2d');
    context.drawImage(image, 0, 0);
    const scale = image.width / document.querySelector('.animus-mc-stage').getBoundingClientRect().width;
    return boxes.map(([x, y, w, h]) => {
      const data = context.getImageData(Math.max(0, Math.floor(x * scale)), Math.max(0, Math.floor(y * scale)), Math.max(1, Math.ceil(w * scale)), Math.max(1, Math.ceil(h * scale))).data;
      let best = [0, 0, 0], light = -1;
      for (let i = 0; i < data.length; i += 4) {
        const sum = data[i] * 0.2126 + data[i + 1] * 0.7152 + data[i + 2] * 0.0722;
        if (sum > light) { light = sum; best = [data[i], data[i + 1], data[i + 2]]; }
      }
      return best;
    });
  }, {picture, boxes: texts.map((text) => text.box)});
  const grey = contrast([0xaa, 0xaa, 0xaa], [0, 0, 0]);
  const out = [];
  texts.forEach((text, index) => {
    const [r, g, b, a = 1] = text.plate.length ? text.plate : [0, 0, 0, 0];
    const back = behind[index].map((c, i) => c * (1 - a) + [r, g, b][i] * a);
    for (const run of text.runs) {
      if (contrast(run.slice(0, 3), [0, 0, 0]) < grey) continue;
      out.push({text: text.text, ratio: contrast(run.slice(0, 3), back)});
    }
  });
  return out;
}

module.exports = {stage, stageDrawn, stageFrames, stageLine, stageTip, waitForStageTip, showPreview, openStageMenu, setLayer, pickRadius, subjectBox, textContrast, contrast};
