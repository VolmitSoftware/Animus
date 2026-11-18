// The colour schemes and pages the layout checks open. Each check runs in
// dark and light unless ANIMUS_SCHEMES (comma separated) names fewer. The
// editor keeps the theme a person picked, so the page is told the scheme
// before it loads. Phones (390 px and narrower) are touch screens.
function schemes() {
  return (process.env.ANIMUS_SCHEMES || 'dark,light').split(',').map(scheme => scheme.trim()).filter(Boolean);
}

/** A browser context at [width] in [scheme], touch on phones. */
async function themedContext(browser, width, scheme) {
  const viewport = {width, height: width <= 390 ? 844 : 900};
  const context = await browser.newContext({viewport, colorScheme: scheme, hasTouch: width <= 390});
  await context.addInitScript(value => {
    try {
      window.localStorage.setItem('animus.theme', value);
    } catch (_) {
      // A page with no storage keeps the default theme.
    }
  }, scheme);
  return {context, viewport};
}

module.exports = {schemes, themedContext};
