import 'dart:io';
import 'dart:math' as math;

import 'package:animus_editor/config/theme_css.dart';
import 'package:animus_editor/config/brand.dart';
import 'package:test/test.dart';

/// WCAG 2.x contrast ratio between two opaque colours.
double contrast(int a, int b) {
  double luminance(int argb) {
    double channel(int value) {
      final double c = value / 255;
      return c <= 0.03928 ? c / 12.92 : math.pow((c + 0.055) / 1.055, 2.4).toDouble();
    }

    return 0.2126 * channel(argb >> 16 & 0xFF) + 0.7152 * channel(argb >> 8 & 0xFF) + 0.0722 * channel(argb & 0xFF);
  }

  final double la = luminance(a), lb = luminance(b);
  return (math.max(la, lb) + 0.05) / (math.min(la, lb) + 0.05);
}

/// The green accent and olive neutrals the editor used before the pink palette.
/// [colour] at half strength over [surface], as the library's ring draws it.
int over(int colour, int surface) {
  int channel(int shift) => (((colour >> shift & 0xFF) + (surface >> shift & 0xFF)) / 2).round();
  return 0xFF000000 | channel(16) << 16 | channel(8) << 8 | channel(0);
}

const List<String> oldBrand = <String>[
  '#3ec97a', '#8fbf6f', '#6fcf4f', '#5b7f3a', '#111310', '#181a16', '#2c2f29', '#9aa094', '#e8ebe3',
  '#f6f5f0', '#242822', '#3b4234', '#1d1f1b', '#2a2d27', '#e9f2ea',
];

void main() {
  for (final BrandTheme theme in <BrandTheme>[brandDark, brandLight]) {
    final String name = theme.isDark ? 'dark' : 'light';
    group('$name theme', () {
      test('should meet AA for body and muted text on every Midnight surface', () {
        for (final int surface in <int>[theme.background, theme.card, theme.cardHover, theme.muted]) {
          expect(contrast(theme.foreground, surface), greaterThanOrEqualTo(4.5));
          expect(contrast(theme.mutedForeground, surface), greaterThanOrEqualTo(4.5), reason: 'muted text on ${BrandTheme.hex(surface)}');
        }
      });

      test('should meet AA for pink text and for text on pink', () {
        expect(contrast(theme.pink, theme.background), greaterThanOrEqualTo(4.5));
        expect(contrast(theme.pink, theme.card), greaterThanOrEqualTo(4.5));
        expect(contrast(theme.pinkForeground, theme.pink), greaterThanOrEqualTo(4.5));
      });

      test('should keep the stock half-strength focus ring visible at 3:1 on every surface', () {
        for (final int surface in <int>[theme.background, theme.card, theme.muted]) {
          expect(contrast(over(theme.ring, surface), surface), greaterThanOrEqualTo(3), reason: 'ring on ${BrandTheme.hex(surface)}');
        }
      });

      test('should use a pink accent on a near-black or white neutral', () {
        final int red = theme.pink >> 16 & 0xFF, green = theme.pink >> 8 & 0xFF, blue = theme.pink & 0xFF;
        expect(red, greaterThan(green + 80), reason: 'pink is red-led');
        expect(blue, greaterThan(green), reason: 'pink leans blue, not orange');
        final int bg = theme.background;
        final int spread = <int>[bg >> 16 & 0xFF, bg >> 8 & 0xFF, bg & 0xFF].reduce(math.max) -
            <int>[bg >> 16 & 0xFF, bg >> 8 & 0xFF, bg & 0xFF].reduce(math.min);
        expect(spread, lessThanOrEqualTo(4), reason: 'the background is a grey, not a tint');
      });
    });
  }

  test('should set only tokens after Midnight: the pink ring and the grey card hover', () {
    final String css = themeCss();
    expect(css, contains('--ring: #660a33;'));
    expect(css, contains('--ring: #ffb3d6;'));
    expect(css, contains('--card-hover: #f2f2f2;'));
    expect(css, isNot(contains('--shadcn-focus-ring')), reason: 'the library draws its own ring geometry');
    expect(RegExp(r'\.arcane-(?!theme-)').hasMatch(css), isFalse, reason: 'no component is restyled by the theme');
    expect(css, isNot(contains('!important')));
    expect(brandDark.themeColor, '#0a0a0a');
    expect(brandLight.themeColor, '#ffffff');
  });

  test('should keep the browser chrome colour in step with the theme', () {
    expect(File('web/index.html').readAsStringSync(), contains('content="${brandDark.themeColor}"'));
    final String bootstrap = File('web/bootstrap.js').readAsStringSync();
    expect(bootstrap, contains("'${brandLight.themeColor}'"));
    expect(bootstrap, contains("'${brandDark.themeColor}'"));
  });

  test('should load the arcane runtime as a deferred file the CSP allows', () {
    final String index = File('web/index.html').readAsStringSync();
    expect(index, contains('<script defer data-arcane-runtime src="/arcane-runtime.js"></script>'));
    expect(index, isNot(contains('<script>')));
    expect(File('web/arcane-runtime.js').existsSync(), isTrue, reason: 'dart run tool/write_arcane_runtime.dart');
  });

  test('should leave no old green brand colour in the site files', () {
    final List<File> files = <File>[
      ...Directory('web/styles').listSync().whereType<File>().where((File file) => file.path.endsWith('.css')),
      File('web/favicon.svg'),
      File('web/index.html'),
      File('web/bootstrap.js'),
      File('lib/services/browser.dart'),
    ];
    for (final File file in files) {
      final String text = file.readAsStringSync().toLowerCase();
      for (final String old in oldBrand) {
        expect(text.contains(old), isFalse, reason: '$old left in ${file.path}');
      }
    }
    expect(File('web/favicon.svg').readAsStringSync(), contains('#ff5fa8'));
  });
}
