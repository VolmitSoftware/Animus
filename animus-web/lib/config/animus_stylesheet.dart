import 'package:arcane_jaspr/theme/index.dart';
import 'package:arcane_jaspr_ascii/arcane_jaspr_ascii.dart';

import 'brand.dart';
import 'control_css.dart';

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
      '$controlCss';
}
