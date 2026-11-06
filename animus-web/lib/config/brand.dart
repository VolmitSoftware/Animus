/// Animus colours: pink on near-black and greys, the same pinks the server
/// uses in chat. Semantic colours (success, error, warning) are not brand
/// colours and stay with the stylesheet.
class BrandTheme {
  final bool isDark;
  final int background;
  final int card;
  final int cardHover;
  final int muted;
  final int accent;
  final int border;
  final int input;
  final int foreground;
  final int secondaryForeground;
  final int mutedForeground;

  /// The one accent: primary buttons, focus, selection tints and the caret.
  final int pink;
  final int pinkForeground;

  /// Hover and pressed state of [pink].
  final int pinkDeep;

  /// The focus ring.
  final int ring;

  const BrandTheme({
    required this.isDark,
    required this.background,
    required this.card,
    required this.cardHover,
    required this.muted,
    required this.accent,
    required this.border,
    required this.input,
    required this.foreground,
    required this.secondaryForeground,
    required this.mutedForeground,
    required this.pink,
    required this.pinkForeground,
    required this.pinkDeep,
    required this.ring,
  });

  /// The browser chrome colour for `theme-color`.
  String get themeColor => hex(background);

  /// CSS custom properties for this theme, as `--name: #rrggbb;` lines.
  Map<String, String> get properties => <String, String>{
    '--background': hex(background),
    '--foreground': hex(foreground),
    '--card': hex(card),
    '--card-foreground': hex(foreground),
    '--card-hover': hex(cardHover),
    '--popover': hex(card),
    '--popover-foreground': hex(foreground),
    '--primary': hex(pink),
    '--primary-foreground': hex(pinkForeground),
    '--secondary': hex(muted),
    '--secondary-foreground': hex(secondaryForeground),
    '--muted': hex(muted),
    '--muted-foreground': hex(mutedForeground),
    '--accent': hex(accent),
    '--accent-foreground': hex(foreground),
    '--border': hex(border),
    '--input': hex(input),
    '--ring': hex(ring),
    '--background-rgb': rgb(background),
    '--foreground-rgb': rgb(foreground),
    '--card-rgb': rgb(card),
    '--primary-rgb': rgb(pink),
    '--secondary-rgb': rgb(muted),
    '--accent-rgb': rgb(accent),
    '--muted-rgb': rgb(mutedForeground),
    '--border-rgb': rgb(border),
    // The editor's own surface aliases, set here so they resolve against these
    // tokens rather than the seed palette on :root.
    '--animus-panel': hex(card),
    '--animus-border': hex(border),
    '--animus-muted': hex(mutedForeground),
    '--animus-pink': hex(pink),
    '--animus-pink-deep': hex(pinkDeep),
  };

  static String hex(int argb) =>
      '#${(argb & 0xFFFFFF).toRadixString(16).padLeft(6, '0')}';

  static String rgb(int argb) =>
      '${argb >> 16 & 0xFF}, ${argb >> 8 & 0xFF}, ${argb & 0xFF}';
}

const BrandTheme brandDark = BrandTheme(
  isDark: true,
  background: 0xFF0D0D0F,
  card: 0xFF161518,
  cardHover: 0xFF222024,
  muted: 0xFF222024,
  accent: 0xFF2A272B,
  border: 0xFF343036,
  input: 0xFF7A747B,
  foreground: 0xFFF3F0F2,
  secondaryForeground: 0xFFE2DEE1,
  mutedForeground: 0xFFABA5AA,
  pink: 0xFFFF5FA8,
  pinkForeground: 0xFF0D0D0F,
  pinkDeep: 0xFFE0408C,
  ring: 0xFFFFB3D6,
);

const BrandTheme brandLight = BrandTheme(
  isDark: false,
  background: 0xFFF5F3F4,
  card: 0xFFFCFAFB,
  cardHover: 0xFFEFEBED,
  muted: 0xFFECE8EA,
  accent: 0xFFE5E0E3,
  border: 0xFFD6D0D3,
  input: 0xFF857D82,
  foreground: 0xFF141214,
  secondaryForeground: 0xFF3A353A,
  mutedForeground: 0xFF67606A,
  pink: 0xFFC2185B,
  pinkForeground: 0xFFFFFFFF,
  pinkDeep: 0xFFA0124A,
  ring: 0xFFC2185B,
);
