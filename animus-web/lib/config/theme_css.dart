/// The brand over shadcn Midnight, emitted after the library's component
/// CSS on Midnight's own selectors so it wins by order. Tokens only: no
/// component is restyled here.
library;

import 'brand.dart';

String _hex(int argb) => BrandTheme.hex(argb);

/// The focus ring token in the brand pink (Midnight re-declares `--ring` on
/// the root, after the seed); the library draws it at its own 3 px and 50%,
/// so the light ring is a deep pink that still reads at 3:1 on white. Light
/// `--card-hover` goes back to Midnight's grey; the palette would tint it
/// with the pink primary.
String themeCss() => '''
#arcane-root.arcane-theme-shadcn {
  --ring: ${_hex(brandLight.ring)};
  --card-hover: ${_hex(brandLight.cardHover)};
}
html.dark #arcane-root.arcane-theme-shadcn,
#arcane-root.dark.arcane-theme-shadcn {
  --ring: ${_hex(brandDark.ring)};
  --card-hover: ${_hex(brandDark.cardHover)};
}
''';
