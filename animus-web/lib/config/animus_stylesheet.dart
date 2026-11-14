import 'package:arcane_jaspr/theme/index.dart';
import 'package:arcane_jaspr_shadcn/arcane_jaspr_shadcn.dart';

import 'brand.dart';
import 'theme_css.dart';

/// shadcn Midnight as shipped, with the Animus pink as primary and ring and
/// Geist as the font. `ShadcnTheme` is an enum, so the brand goes in through
/// the seeds and [themeCss] after the library's own CSS. Keep it const: the
/// base CSS is cached per instance.
class AnimusStylesheet extends ShadcnStylesheet {
  const AnimusStylesheet() : super(theme: ShadcnTheme.midnight);

  @override
  ThemeSeed get lightSeed => super.lightSeed.copyWith(
    primary: brandLight.pink,
    // Neutral seeds stop the palette tinting secondary, accent and border pink.
    secondary: 0xFFF5F5F5,
    accent: 0xFFF5F5F5,
    border: 0xFFE5E5E5,
  );

  @override
  ThemeSeed get darkSeed => super.darkSeed.copyWith(primary: brandDark.pink);

  @override
  FontConfig get fonts => const FontConfig(
    sans: "'Geist', ui-sans-serif, system-ui, -apple-system, 'Segoe UI', sans-serif",
    heading: "'Geist', ui-sans-serif, system-ui, -apple-system, 'Segoe UI', sans-serif",
    mono: "'Geist Mono', ui-monospace, SFMono-Regular, Menlo, monospace",
  );

  @override
  String get fontFaces => '''
@font-face { font-family: 'Geist'; src: url('/assets/fonts/geist/Geist-Regular.woff2') format('woff2'); font-weight: 400; font-display: swap; }
@font-face { font-family: 'Geist'; src: url('/assets/fonts/geist/Geist-SemiBold.woff2') format('woff2'); font-weight: 500 700; font-display: swap; }
@font-face { font-family: 'Geist Mono'; src: url('/assets/fonts/geist/GeistMono-Regular.woff2') format('woff2'); font-weight: 400; font-display: swap; }
''';

  @override
  String get componentCss => '${super.componentCss}\n${themeCss()}';
}
