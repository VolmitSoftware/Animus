import 'package:arcane_jaspr/theme/index.dart';
import 'package:arcane_jaspr_ascii/arcane_jaspr_ascii.dart';

import 'brand.dart';

/// The Ascii stylesheet in Animus colours: the brand tokens from [brandDark]
/// and [brandLight] replace Ascii's neutrals after its own CSS, and primary
/// buttons hover to the deeper pink.
class AnimusStylesheet extends AsciiStylesheet {
  const AnimusStylesheet();

  @override
  ThemeSeed get lightSeed => super.lightSeed.copyWith(
    primary: brandLight.pink,
    background: brandLight.background,
    secondary: brandLight.muted,
    accent: brandLight.accent,
    border: brandLight.border,
  );

  @override
  ThemeSeed get darkSeed => super.darkSeed.copyWith(
    primary: brandDark.pink,
    background: brandDark.background,
    secondary: brandDark.muted,
    accent: brandDark.accent,
    border: brandDark.border,
  );

  @override
  String get componentCss => '${super.componentCss}\n${brandCss()}';
}

/// The token overrides, on the same selectors Ascii uses so they win by order.
String brandCss() {
  String block(String selector, BrandTheme theme) {
    final StringBuffer out = StringBuffer('$selector {\n');
    theme.properties.forEach((String name, String value) => out.writeln('  $name: $value;'));
    out.writeln('}');
    return out.toString();
  }

  return '${block('#arcane-root.arcane-theme-ascii', brandLight)}'
      '${block('html.dark #arcane-root.arcane-theme-ascii,\n#arcane-root.dark.arcane-theme-ascii', brandDark)}'
      "#arcane-root.arcane-theme-ascii :is(.arcane-button, .arcane-cycle-button)[data-variant='primary'] {\n"
      '  --shadcn-button-hover: var(--animus-pink-deep);\n'
      '}\n'
      '$_controlCss';
}

/// Editor-wide control rules every lane's screens inherit:
/// - Controls read in Geist like the rest of the UI; Geist Mono stays for
///   code, ids and paths (`code`, `kbd`, `pre`, `textarea`).
/// - A disabled filled button turns neutral grey so it never reads as an
///   enabled pink one.
/// - A selected tab or segment (`aria-selected`, `aria-pressed` or
///   `aria-checked` inside `.animus-tabs` or `.animus-segmented`) is a raised
///   neutral fill with a pink underline; solid pink is for the one primary
///   action.
/// - Plain links take the pink accent.
/// - Focus rings sit inside scrolling containers so they are not clipped.
const String _controlCss = '''
#arcane-root.arcane-theme-ascii {
  --font-control: var(--font-sans);
}
#arcane-root.arcane-theme-ascii :is(button, input, select, .arcane-button, .arcane-cycle-button, .arcane-badge, .arcane-status-badge, .arcane-data-table th, .arcane-static-table th, .ascii-label) {
  font-family: var(--font-sans);
}
#arcane-root.arcane-theme-ascii :is(textarea, code, kbd, pre, samp) {
  font-family: var(--font-mono);
}
#arcane-root.arcane-theme-ascii :is(button, summary, label[for], select, [role='tab'], [role='menuitem'], [role='option'], [role='radio'], [role='switch']):not(:disabled):not([aria-disabled='true']) {
  cursor: pointer;
}
#arcane-root.arcane-theme-ascii :is(button, select, input):disabled,
#arcane-root.arcane-theme-ascii [aria-disabled='true'] {
  cursor: not-allowed;
}
#arcane-root.arcane-theme-ascii :is(.arcane-button, .arcane-cycle-button):is([data-variant='primary'], [data-variant='destructive']):is(:disabled, [aria-disabled='true']) {
  background: var(--muted);
  color: var(--muted-foreground);
  border-color: var(--border);
  opacity: 1;
  box-shadow: none;
}
#arcane-root.arcane-theme-ascii :is(.animus-tabs, .animus-segmented) :is([aria-selected='true'], [aria-pressed='true'], [aria-checked='true']) {
  background: var(--card-hover);
  color: var(--foreground);
  border-color: var(--border);
  box-shadow: inset 0 -2px 0 var(--primary);
}
#arcane-root.arcane-theme-ascii a:not([class]) {
  color: var(--primary);
  text-underline-offset: 0.2em;
}
#arcane-root.arcane-theme-ascii a:not([class]):hover {
  color: var(--animus-pink-deep);
}
#arcane-root.arcane-theme-ascii :is(.animus-sidebar, .animus-menu, .animus-palette, .animus-tabs, .animus-segmented, .animus-inset-focus) :is(button, a, input, textarea, select, summary, [tabindex]):focus-visible {
  outline-offset: -2px;
}
''';
