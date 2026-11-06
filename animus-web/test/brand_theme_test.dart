import 'dart:io';
import 'dart:math' as math;

import 'package:animus_editor/config/animus_stylesheet.dart';
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
const List<String> oldBrand = <String>[
  '#3ec97a', '#8fbf6f', '#6fcf4f', '#5b7f3a', '#111310', '#181a16', '#2c2f29', '#9aa094', '#e8ebe3',
  '#f6f5f0', '#242822', '#3b4234', '#1d1f1b', '#2a2d27', '#e9f2ea',
];

void main() {
  for (final BrandTheme theme in <BrandTheme>[brandDark, brandLight]) {
    final String name = theme.isDark ? 'dark' : 'light';
    group('$name theme', () {
      test('should meet AA for body and muted text on every surface', () {
        for (final int surface in <int>[theme.background, theme.card, theme.cardHover, theme.muted, theme.accent]) {
          expect(contrast(theme.foreground, surface), greaterThanOrEqualTo(4.5));
          expect(contrast(theme.secondaryForeground, surface), greaterThanOrEqualTo(4.5));
          expect(contrast(theme.mutedForeground, surface), greaterThanOrEqualTo(4.5),
              reason: 'muted text on ${BrandTheme.hex(surface)}');
        }
      });

      test('should meet AA for pink text and for text on pink, resting and hovered', () {
        expect(contrast(theme.pink, theme.background), greaterThanOrEqualTo(4.5));
        expect(contrast(theme.pink, theme.card), greaterThanOrEqualTo(4.5));
        expect(contrast(theme.pinkForeground, theme.pink), greaterThanOrEqualTo(4.5));
        expect(contrast(theme.pinkForeground, theme.pinkDeep), greaterThanOrEqualTo(4.5));
      });

      test('should keep the focus ring and control borders visible at 3:1', () {
        expect(contrast(theme.ring, theme.background), greaterThanOrEqualTo(3));
        expect(contrast(theme.input, theme.background), greaterThanOrEqualTo(3));
        expect(contrast(theme.input, theme.card), greaterThanOrEqualTo(3));
      });

      test('should use a pink accent on a near-black or near-white neutral', () {
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

  test('should emit the brand tokens for both themes and the deeper pink hover', () {
    final String css = brandCss();
    expect(css, contains('--primary: #ff5fa8;'));
    expect(css, contains('--primary: #c2185b;'));
    expect(css, contains('--background: #0d0d0f;'));
    expect(css, contains('--animus-pink-deep: #e0408c;'));
    expect(css, contains('--shadcn-button-hover: var(--animus-pink-deep);'));
    expect(brandDark.themeColor, '#0d0d0f');
    expect(brandLight.themeColor, '#f5f3f4');
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
