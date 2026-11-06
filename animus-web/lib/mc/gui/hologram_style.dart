/// A hologram's Gloss display style as its one text display draws it, with
/// Animus's hologram defaults (facing the viewer, seen through blocks, no
/// background), and what of it the preview cannot draw.
library;

import 'dart:math' as math;

import '../../logic/gui/gui_json.dart';
import '../npc/npc_scene.dart';

final class HologramTextStyle {
  const HologramTextStyle({
    required this.billboard,
    required this.seeThrough,
    required this.background,
    required this.shadow,
    required this.alignment,
    required this.opacity,
    required this.lineWidth,
    required this.scaleX,
    required this.scaleY,
    required this.brightness,
    required this.notes,
  });

  factory HologramTextStyle.of(JsonMap style) {
    final String billboard = asString(style['billboard']) ?? 'center';
    final bool seeThrough = style['seeThrough'] is bool ? style['seeThrough']! as bool : true;
    final int? block = asInt(style['blockLight']), sky = asInt(style['skyLight']);
    final List<String> unshown = <String>[
      if (style['glowColor'] is String) 'glowColor',
      if ((asDouble(style['shadowRadius']) ?? 0) > 0) 'shadowRadius',
    ];
    return HologramTextStyle(
      billboard: billboard,
      seeThrough: seeThrough,
      background: asString(style['backgroundArgb']) ?? '#00000000',
      shadow: style['shadow'] == true,
      alignment: McTextAlign.values.asNameMap()[asString(style['textAlignment'])] ?? McTextAlign.center,
      opacity: hologramTextOpacity(asInt(style['textOpacity']) ?? 255),
      lineWidth: asInt(style['lineWidth']) ?? 16384,
      scaleX: asDouble(style['scaleX']) ?? 1,
      scaleY: asDouble(style['scaleY']) ?? 1,
      brightness: block == null || sky == null ? null : mcLightBrightness(math.max(block, sky)),
      notes: <String>[
        if (billboard != 'center') 'Style billboard $billboard turns the text with yaw and pitch in game; the preview draws it facing the camera.',
        if (!seeThrough) 'Style seeThrough is off, so blocks in front hide the text in game; the preview draws it over them.',
        if (unshown.isNotEmpty) 'Style ${unshown.join(', ')} is saved and used by the server; the preview does not draw it.',
      ],
    );
  }

  final String billboard;
  final bool seeThrough;

  /// `#AARRGGBB`; the server sends a clear background unless one is set.
  final String background;
  final bool shadow;
  final McTextAlign alignment;
  final double opacity;
  final int lineWidth;
  final double scaleX;
  final double scaleY;
  final double? brightness;

  /// What the preview draws differently from the game.
  final List<String> notes;

  /// Billboards that keep the display's pitch, so the text block tilts.
  bool get tilts => billboard == 'fixed' || billboard == 'vertical';
}

/// Glyph opacity for a text display's `textOpacity`: the client draws 0 to 3
/// fully opaque, and its text shader drops anything under a tenth.
double hologramTextOpacity(int value) {
  if (value <= 3) return 1;
  final double alpha = value.clamp(0, 255) / 255;
  return alpha < 0.1 ? 0 : alpha;
}

/// How bright the client's light map draws light [level] (0 to 15) at the
/// default brightness setting, 0 to 1.
double mcLightBrightness(int level) {
  final double light = level.clamp(0, 15) / 15;
  final double curved = light / (4 - 3 * light);
  final double lifted = 1 - math.pow(1 - curved, 4).toDouble();
  return ((curved + lifted) / 2) * 0.96 + 0.75 * 0.04;
}
