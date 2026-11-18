/// Text contrast for the game windows the editor draws: a coloured title on
/// the window's grey keeps its colour and gains an outline that reads.
library;

import 'dart:math' as math;

double _channel(int value) {
  final double c = value / 255;
  return c <= 0.03928 ? c / 12.92 : math.pow((c + 0.055) / 1.055, 2.4).toDouble();
}

double _luminance(int rgb) =>
    0.2126 * _channel((rgb >> 16) & 0xFF) + 0.7152 * _channel((rgb >> 8) & 0xFF) + 0.0722 * _channel(rgb & 0xFF);

/// The WCAG contrast ratio of two 0xRRGGBB colours, 1 to 21.
double contrastRatio(int a, int b) {
  final double x = _luminance(a);
  final double y = _luminance(b);
  return (math.max(x, y) + 0.05) / (math.min(x, y) + 0.05);
}

/// The game's drop shadow shade of [rgb]: each channel a quarter.
int shadowShade(int rgb) => (rgb & 0xFCFCFC) >> 2;

/// The outline that makes [color] read at 4.5:1 on anything behind it (a
/// 3D scene): the game's shadow shade when that reads, else white.
int sceneOutline(int color) {
  final int shade = shadowShade(color);
  return contrastRatio(color, shade) >= 4.5 ? shade : 0xFFFFFF;
}

/// The outline that makes [color] read at 4.5:1 where it sits on [surface],
/// or null when it reads already.
int? readableOutline(int color, int surface) => contrastRatio(color, surface) >= 4.5 ? null : sceneOutline(color);

/// A text-shadow drawing [rgb] [width] out on every side.
String outlineShadow(int rgb, String width) {
  final String colour = '#${rgb.toRadixString(16).padLeft(6, '0')}';
  return '$width 0 $colour, calc(-1 * $width) 0 $colour, 0 $width $colour, 0 calc(-1 * $width) $colour';
}
