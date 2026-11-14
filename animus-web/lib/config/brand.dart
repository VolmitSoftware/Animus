/// Animus colours on shadcn Midnight. The pinks are the brand; the neutrals
/// are Midnight's own, listed here so the contrast tests check the pinks
/// against the surfaces the page really has and the browser chrome matches
/// the page. Semantic colours (success, error, warning) stay with the
/// stylesheet.
class BrandTheme {
  const BrandTheme({
    required this.isDark,
    required this.background,
    required this.card,
    required this.cardHover,
    required this.muted,
    required this.foreground,
    required this.mutedForeground,
    required this.pink,
    required this.pinkForeground,
    required this.ring,
  });

  final bool isDark;

  /// Midnight's surfaces and text, as the theme renders them.
  final int background;
  final int card;
  final int cardHover;
  final int muted;
  final int foreground;
  final int mutedForeground;

  /// The one accent: the primary action, selection and the caret.
  final int pink;
  final int pinkForeground;

  /// The focus ring token; the library draws it at half strength.
  final int ring;

  /// The browser chrome colour for `theme-color`.
  String get themeColor => hex(background);

  static String hex(int argb) => '#${(argb & 0xFFFFFF).toRadixString(16).padLeft(6, '0')}';
}

const BrandTheme brandDark = BrandTheme(
  isDark: true,
  background: 0xFF0A0A0A,
  card: 0xFF171717,
  cardHover: 0xFF1F1F1F,
  muted: 0xFF262626,
  foreground: 0xFFF7F4EC,
  mutedForeground: 0xFFA3A3A3,
  pink: 0xFFFF5FA8,
  pinkForeground: 0xFF000000,
  ring: 0xFFFFB3D6,
);

const BrandTheme brandLight = BrandTheme(
  isDark: false,
  background: 0xFFFFFFFF,
  card: 0xFFFFFFFF,
  cardHover: 0xFFF2F2F2,
  muted: 0xFFF5F5F5,
  foreground: 0xFF0A0A0A,
  mutedForeground: 0xFF6E6E6E,
  pink: 0xFFC2185B,
  pinkForeground: 0xFFFFFFFF,
  ring: 0xFF660A33,
);
