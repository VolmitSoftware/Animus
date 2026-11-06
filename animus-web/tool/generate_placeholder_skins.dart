/// Draws the built-in placeholder skins (original art, 64x64 modern layout)
/// into web/assets/skins/placeholder/ with their index.json. Output is
/// deterministic: rerunning writes the same bytes.
///
///   dart run tool/generate_placeholder_skins.dart
library;

import 'dart:io';

import 'package:animus_editor/logic/npc/placeholder_skins.dart';
import 'package:image/image.dart' as img;

final class Rgb {
  const Rgb(this.r, this.g, this.b, [this.a = 255]);

  final int r, g, b, a;

  Rgb shade(double f) => Rgb(_c(r * f), _c(g * f), _c(b * f), a);

  Rgb mix(Rgb o, double t) => Rgb(_c(r + (o.r - r) * t), _c(g + (o.g - g) * t), _c(b + (o.b - b) * t), a);

  static int _c(num v) => v.round().clamp(0, 255);
}

enum Face { top, bottom, right, front, left, back }

bool _side(Face f) => f == Face.right || f == Face.left;

bool _vertical(Face f) => f != Face.top && f != Face.bottom;

/// A pixel of one face: [x], [y] within the face, [w], [h] its size, and
/// [toFront] the distance to the front edge on side faces (0 elsewhere).
typedef Painter = Rgb? Function(Face face, int x, int y, int w, int h, int toFront);

/// Paints a cuboid of [w] x [h] x [d] whose texture starts at [u], [v].
void box(img.Image image, int u, int v, int w, int h, int d, int seed, Painter paint) {
  void face(Face f, int fx, int fy, int fw, int fh) {
    for (int y = 0; y < fh; y++) {
      for (int x = 0; x < fw; x++) {
        final int toFront = f == Face.right ? fw - 1 - x : (f == Face.left ? x : 0);
        final Rgb? c = paint(f, x, y, fw, fh, toFront);
        if (c == null) continue;
        final Rgb lit = c.a == 0 ? c : c.shade(_light(f) * _grain(fx + x, fy + y, seed));
        image.setPixelRgba(fx + x, fy + y, lit.r, lit.g, lit.b, lit.a);
      }
    }
  }

  face(Face.top, u + d, v, w, d);
  face(Face.bottom, u + d + w, v, w, d);
  face(Face.right, u, v + d, d, h);
  face(Face.front, u + d, v + d, w, h);
  face(Face.left, u + d + w, v + d, d, h);
  face(Face.back, u + d + w + d, v + d, w, h);
}

double _light(Face f) => switch (f) {
  Face.top => 1.08,
  Face.front => 1.0,
  Face.right || Face.left => 0.88,
  Face.back => 0.82,
  Face.bottom => 0.7,
};

/// A small fixed per-pixel variation so flat colours read as cloth.
double _grain(int x, int y, int seed) {
  int h = (x * 374761393 + y * 668265263 + seed * 2147483647) & 0x7fffffff;
  h = ((h ^ (h >> 13)) * 1274126177) & 0x7fffffff;
  return 0.95 + (h % 1000) / 1000 * 0.08;
}

enum Hair { short, long, bald }

/// One character. Overrides return null to fall back to the base layer.
final class Outfit {
  Outfit({
    required this.skin,
    required this.hair,
    required this.hairColor,
    required this.eyes,
    required this.shirt,
    required this.sleeve,
    required this.pants,
    required this.boots,
    this.sleeveRows = 12,
    this.hands,
    this.bootRows = 3,
    this.belt,
    this.buckle,
    this.beltRow = 8,
    this.beard,
    this.mustache = false,
    this.body,
    this.arm,
    this.leg,
    this.face,
    this.hat,
    this.jacket,
    this.armLayer,
    this.legLayer,
  });

  final Rgb skin;
  final Hair hair;
  final Rgb hairColor;
  final Rgb eyes;
  final Rgb shirt;
  final Rgb sleeve;
  final Rgb pants;
  final Rgb boots;

  /// Rows of the arm under the sleeve; the rest is [hands] or bare skin.
  final int sleeveRows;
  final Rgb? hands;
  final int bootRows;
  final Rgb? belt;
  final Rgb? buckle;
  final int beltRow;
  final Rgb? beard;
  final bool mustache;
  final Painter? body, arm, leg, face, hat, jacket, armLayer, legLayer;
}

Rgb? _head(Outfit o, Face f, int x, int y, int toFront) {
  final Rgb? custom = o.face?.call(f, x, y, 8, 8, toFront);
  if (custom != null) return custom;
  final Rgb hair = o.hairColor;
  final bool bald = o.hair == Hair.bald;
  final bool long = o.hair == Hair.long;
  final Rgb? beard = o.beard;
  switch (f) {
    case Face.top:
      return bald ? o.skin.shade(1.02) : hair;
    case Face.bottom:
      return beard ?? o.skin.shade(0.85);
    case Face.back:
      if (!bald && (long || y < 6)) return hair.shade(y.isOdd ? 0.96 : 1.0);
      if (bald && y < 1) return o.skin;
      return beard != null && y >= 6 ? beard : o.skin.shade(0.94);
    case Face.right:
    case Face.left:
      if (beard != null && y >= 5 && toFront <= 4) return beard;
      if (!bald && (y < 2 || (y < 3 && toFront > 0) || (long && toFront >= 2) || (y < 6 && toFront >= 5))) return hair;
      if (y >= 3 && y <= 4 && toFront >= 3 && toFront <= 4) return o.skin.shade(0.86);
      return o.skin;
    case Face.front:
      if (!bald && (y < 2 || (y == 2 && (x == 0 || x == 7 || x == 3)))) return hair;
      if (long && (x == 0 || x == 7) && y < 6) return hair;
      if (y == 3 && (x == 1 || x == 2 || x == 5 || x == 6)) return (bald ? o.skin : hair).shade(0.8);
      if (y == 4) {
        if (x == 1 || x == 6) return const Rgb(240, 240, 236);
        if (x == 2 || x == 5) return o.eyes;
      }
      if (y == 5 && (x == 3 || x == 4)) return o.skin.shade(0.88);
      if (beard != null) {
        if (y >= 6 || (y == 5 && (x <= 1 || x >= 6))) return (y == 6 && (x == 3 || x == 4)) ? beard.shade(0.6) : beard;
      }
      if (o.mustache && y == 6 && x >= 2 && x <= 5) return hair.shade(0.9);
      if (y == 6 && (x == 3 || x == 4)) return o.skin.mix(const Rgb(120, 40, 40), 0.45);
      if (y == 7 && x == 3) return o.skin.shade(0.94);
      return o.skin;
  }
}

Rgb? _body(Outfit o, Face f, int x, int y, int toFront) {
  final Rgb? custom = o.body?.call(f, x, y, _vertical(f) ? (_side(f) ? 4 : 8) : 8, _vertical(f) ? 12 : 4, toFront);
  if (custom != null) return custom;
  if (f == Face.top && y >= 1 && y <= 2 && x >= 3 && x <= 4) return o.skin.shade(0.9);
  if (_vertical(f)) {
    final Rgb? belt = o.belt;
    if (belt != null && (y == o.beltRow || y == o.beltRow + 1)) {
      if (f == Face.front && (x == 3 || x == 4) && o.buckle != null) return o.buckle;
      return belt.shade(y == o.beltRow ? 1.0 : 0.88);
    }
    if (f == Face.front && y == 0 && (x == 3 || x == 4)) return o.skin.shade(0.92);
    if (y == 11) return o.shirt.shade(0.88);
  }
  return o.shirt;
}

Rgb? _arm(Outfit o, Face f, int x, int y, int w, int toFront) {
  final Rgb? custom = o.arm?.call(f, x, y, w, 12, toFront);
  if (custom != null) return custom;
  final Rgb hand = o.hands ?? o.skin;
  if (f == Face.top) return o.sleeveRows > 0 ? o.sleeve : o.skin;
  if (f == Face.bottom) return hand.shade(0.9);
  if (y >= 10 && o.hands != null) return hand.shade(y == 10 ? 1.0 : 0.9);
  if (y < o.sleeveRows) return o.sleeve.shade(y == o.sleeveRows - 1 ? 0.84 : 1.0);
  return y >= 10 ? hand : o.skin;
}

Rgb? _leg(Outfit o, Face f, int x, int y, int toFront) {
  final Rgb? custom = o.leg?.call(f, x, y, 4, 12, toFront);
  if (custom != null) return custom;
  if (f == Face.top) return o.pants;
  if (f == Face.bottom) return o.boots.shade(0.6);
  if (y >= 12 - o.bootRows) return o.boots.shade(y == 12 - o.bootRows ? 1.1 : 1.0);
  return o.pants.shade(y == 0 ? 0.92 : 1.0);
}

img.Image draw(Outfit o, {required bool slim}) {
  final img.Image image = img.Image(width: 64, height: 64, numChannels: 4);
  final int aw = slim ? 3 : 4;
  box(image, 0, 0, 8, 8, 8, 1, (Face f, int x, int y, int w, int h, int t) => _head(o, f, x, y, t));
  box(image, 16, 16, 8, 12, 4, 2, (Face f, int x, int y, int w, int h, int t) => _body(o, f, x, y, t));
  box(image, 40, 16, aw, 12, 4, 3, (Face f, int x, int y, int w, int h, int t) => _arm(o, f, x, y, aw, t));
  box(image, 32, 48, aw, 12, 4, 4, (Face f, int x, int y, int w, int h, int t) => _arm(o, f, x, y, aw, t));
  box(image, 0, 16, 4, 12, 4, 5, (Face f, int x, int y, int w, int h, int t) => _leg(o, f, x, y, t));
  box(image, 16, 48, 4, 12, 4, 6, (Face f, int x, int y, int w, int h, int t) => _leg(o, f, x, y, t));
  final Painter? hat = o.hat, jacket = o.jacket, armLayer = o.armLayer, legLayer = o.legLayer;
  if (hat != null) box(image, 32, 0, 8, 8, 8, 7, hat);
  if (jacket != null) box(image, 16, 32, 8, 12, 4, 8, jacket);
  if (armLayer != null) {
    box(image, 40, 32, aw, 12, 4, 9, armLayer);
    box(image, 48, 48, aw, 12, 4, 10, armLayer);
  }
  if (legLayer != null) {
    box(image, 0, 32, 4, 12, 4, 11, legLayer);
    box(image, 0, 48, 4, 12, 4, 12, legLayer);
  }
  return image;
}

// Palette ---------------------------------------------------------------------

const Rgb steel = Rgb(168, 174, 184);
const Rgb gold = Rgb(222, 178, 64);
const Rgb leather = Rgb(112, 74, 44);
const Rgb darkLeather = Rgb(64, 42, 28);
const Rgb black = Rgb(36, 34, 40);

bool _chain(int x, int y) => (x + y).isEven;

final Map<String, Outfit> outfits = <String, Outfit>{
  'guard': Outfit(
    skin: const Rgb(198, 146, 108),
    hair: Hair.short,
    hairColor: const Rgb(58, 40, 30),
    eyes: const Rgb(60, 90, 140),
    shirt: steel,
    sleeve: steel,
    sleeveRows: 10,
    hands: leather,
    pants: const Rgb(70, 72, 82),
    boots: black,
    bootRows: 4,
    belt: black,
    buckle: const Rgb(210, 212, 218),
    beltRow: 7,
    body: (Face f, int x, int y, int w, int h, int t) {
      final bool tabard = (f == Face.front || f == Face.back) && x >= 2 && x <= 5 && y < 7;
      final bool tail = f == Face.front && x >= 2 && x <= 5 && y >= 9;
      if (tabard || tail) {
        if (f == Face.front && y >= 2 && y <= 4 && x >= 3 && x <= 4) return gold;
        if (f == Face.front && y == 3 && (x == 2 || x == 5)) return gold.shade(0.85);
        if (x == 2 || x == 5) return const Rgb(140, 28, 32);
        return const Rgb(176, 36, 40);
      }
      if (_vertical(f) && (y < 7 || y > 8)) return _chain(x, y) ? steel : steel.shade(0.72);
      return null;
    },
    arm: (Face f, int x, int y, int w, int h, int t) => _vertical(f) && y < 10 ? (_chain(x, y) ? steel : steel.shade(0.72)) : null,
    hat: (Face f, int x, int y, int w, int h, int t) {
      const Rgb iron = Rgb(150, 156, 166);
      if (f == Face.top) return (x == 3 || x == 4) ? iron.shade(1.15) : iron;
      if (f == Face.bottom) return null;
      if (f == Face.front) {
        if (y == 0) return iron.shade(1.1);
        if (y == 1) return (x == 1 || x == 6) ? iron.shade(1.3) : iron.shade(0.85);
        if ((x == 3 || x == 4) && y <= 5) return iron.shade(0.95);
        return null;
      }
      if (f == Face.back) return y <= 5 ? (y == 1 ? iron.shade(0.85) : iron) : null;
      if (y <= 1) return y == 1 ? iron.shade(0.85) : iron;
      if (t <= 1 && y <= 6) return iron.shade(0.9);
      return y <= 4 && t >= 4 ? iron : null;
    },
  ),
  'merchant': Outfit(
    skin: const Rgb(222, 178, 130),
    hair: Hair.short,
    hairColor: const Rgb(110, 64, 34),
    eyes: const Rgb(78, 54, 34),
    mustache: true,
    shirt: const Rgb(232, 222, 196),
    sleeve: const Rgb(232, 222, 196),
    sleeveRows: 9,
    pants: const Rgb(96, 64, 42),
    boots: darkLeather,
    belt: leather,
    buckle: gold,
    body: (Face f, int x, int y, int w, int h, int t) {
      const Rgb vest = Rgb(46, 112, 70);
      if (f == Face.right && y >= 8 && y <= 10 && x >= 1 && x <= 2) return y == 8 ? leather : gold.shade(0.8);
      if (f == Face.front && y < 8 && (x <= 2 || x >= 5)) {
        if ((x == 2 || x == 5) && (y == 3 || y == 6)) return gold;
        return (x == 2 || x == 5) ? vest.shade(0.85) : vest;
      }
      if ((f == Face.back || _side(f)) && y < 8) return vest;
      return null;
    },
    hat: (Face f, int x, int y, int w, int h, int t) {
      const Rgb felt = Rgb(120, 34, 40);
      if (f == Face.top) return felt;
      if (f == Face.bottom) return null;
      if (y == 0) return felt;
      if (y == 1) return f == Face.front ? gold.shade(0.9) : felt.shade(0.8);
      return null;
    },
  ),
  'villager': Outfit(
    skin: const Rgb(242, 204, 170),
    hair: Hair.short,
    hairColor: const Rgb(150, 98, 52),
    eyes: const Rgb(70, 120, 60),
    shirt: const Rgb(110, 140, 64),
    sleeve: const Rgb(110, 140, 64),
    sleeveRows: 6,
    pants: const Rgb(196, 178, 140),
    boots: leather,
    belt: const Rgb(200, 168, 104),
    beltRow: 7,
    face: (Face f, int x, int y, int w, int h, int t) =>
        f == Face.front && y == 5 && (x == 1 || x == 6) ? const Rgb(222, 160, 128) : null,
    body: (Face f, int x, int y, int w, int h, int t) {
      if (f == Face.front && y >= 1 && y <= 3 && (x == 3 || x == 4)) return y == 1 ? const Rgb(232, 194, 160) : const Rgb(90, 116, 52);
      return null;
    },
    leg: (Face f, int x, int y, int w, int h, int t) => _vertical(f) && y == 8 ? const Rgb(176, 158, 120) : null,
    hat: (Face f, int x, int y, int w, int h, int t) {
      const Rgb straw = Rgb(226, 196, 112);
      if (f == Face.top) return (x + y).isEven ? straw : straw.shade(0.9);
      if (f == Face.bottom) return null;
      if (y == 0) return straw.shade(0.94);
      if (y == 1) return const Rgb(170, 52, 44);
      if (y == 2) return straw.shade(x.isEven ? 0.86 : 0.8);
      return null;
    },
  ),
  'noble': Outfit(
    skin: const Rgb(150, 98, 66),
    hair: Hair.long,
    hairColor: const Rgb(28, 22, 22),
    eyes: const Rgb(110, 70, 30),
    shirt: const Rgb(94, 48, 132),
    sleeve: const Rgb(94, 48, 132),
    sleeveRows: 10,
    hands: const Rgb(236, 232, 224),
    pants: const Rgb(40, 32, 56),
    boots: black,
    bootRows: 5,
    belt: gold,
    buckle: const Rgb(190, 40, 60),
    body: (Face f, int x, int y, int w, int h, int t) {
      if (f == Face.front) {
        if (y == 0) return (x >= 2 && x <= 5) ? const Rgb(240, 236, 228) : gold;
        if ((x == 3 || x == 4) && y < 8) return y.isOdd ? gold : const Rgb(94, 48, 132).shade(0.8);
        if (x == 0 || x == 7) return gold.shade(0.85);
      }
      if (_vertical(f) && y == 11) return gold;
      return null;
    },
    arm: (Face f, int x, int y, int w, int h, int t) => _vertical(f) && y == 9 ? gold : null,
    leg: (Face f, int x, int y, int w, int h, int t) => _vertical(f) && y == 7 ? gold.shade(0.9) : null,
    hat: (Face f, int x, int y, int w, int h, int t) {
      if (f == Face.top || f == Face.bottom) return null;
      if (y == 1) return f == Face.front && (x == 3 || x == 4) ? const Rgb(60, 160, 210) : gold;
      if (y == 0 && f == Face.front && (x == 0 || x == 3 || x == 4 || x == 7)) return gold.shade(1.1);
      return null;
    },
    jacket: (Face f, int x, int y, int w, int h, int t) {
      const Rgb cape = Rgb(150, 28, 44);
      if (f == Face.back) return y == 0 ? gold : cape.shade(y.isEven ? 1.0 : 0.92);
      if (_side(f) && t >= 3) return cape.shade(0.9);
      if (f == Face.top) return y == 3 ? cape : null;
      return null;
    },
  ),
  'miner': Outfit(
    skin: const Rgb(232, 180, 148),
    hair: Hair.short,
    hairColor: const Rgb(196, 110, 52),
    eyes: const Rgb(64, 96, 120),
    beard: const Rgb(178, 98, 46),
    shirt: const Rgb(120, 122, 128),
    sleeve: const Rgb(120, 122, 128),
    sleeveRows: 8,
    hands: darkLeather,
    pants: const Rgb(52, 78, 132),
    boots: const Rgb(84, 58, 38),
    face: (Face f, int x, int y, int w, int h, int t) =>
        f == Face.front && ((x == 6 && y == 5) || (x == 0 && y == 3)) ? const Rgb(150, 120, 100) : null,
    body: (Face f, int x, int y, int w, int h, int t) {
      const Rgb denim = Rgb(52, 78, 132);
      if (f == Face.front) {
        if (y >= 5) return y == 5 ? denim.shade(0.85) : (x >= 2 && x <= 5 && y >= 6 && y <= 8 ? denim.shade(0.9) : denim);
        if (x == 1 || x == 6) return y == 4 ? const Rgb(210, 210, 214) : denim;
      }
      if (f == Face.back && (y >= 5 || x == 2 || x == 5)) return denim;
      if (_side(f) && y >= 5) return denim;
      return null;
    },
    hat: (Face f, int x, int y, int w, int h, int t) {
      const Rgb helmet = Rgb(232, 182, 40);
      if (f == Face.top) return (x == 3 || x == 4) ? helmet.shade(0.85) : helmet;
      if (f == Face.bottom) return null;
      if (f == Face.front) {
        if (y == 0 && (x == 3 || x == 4)) return const Rgb(90, 90, 96);
        if (y == 1 && (x == 3 || x == 4)) return const Rgb(255, 246, 170);
        if (y == 1 && (x == 2 || x == 5)) return const Rgb(90, 90, 96);
        if (y <= 1) return helmet;
        return y == 2 ? helmet.shade(0.75) : null;
      }
      if (y <= 1) return helmet;
      return y == 2 ? helmet.shade(0.75) : null;
    },
  ),
  'traveler': Outfit(
    skin: const Rgb(104, 68, 48),
    hair: Hair.short,
    hairColor: const Rgb(20, 16, 16),
    eyes: const Rgb(54, 38, 26),
    shirt: const Rgb(140, 104, 70),
    sleeve: const Rgb(140, 104, 70),
    sleeveRows: 10,
    hands: darkLeather,
    pants: const Rgb(84, 76, 64),
    boots: const Rgb(72, 50, 34),
    bootRows: 4,
    belt: darkLeather,
    buckle: const Rgb(170, 150, 120),
    body: (Face f, int x, int y, int w, int h, int t) {
      if (f == Face.front && x == y - 1 && y >= 1 && y <= 8) return leather;
      if (f == Face.front && y == 0) return const Rgb(186, 70, 40);
      if (f == Face.left && y >= 6 && y <= 9 && t >= 1 && t <= 2) return y == 6 ? darkLeather : leather;
      return null;
    },
    hat: (Face f, int x, int y, int w, int h, int t) {
      const Rgb hood = Rgb(58, 82, 52);
      if (f == Face.top) return hood;
      if (f == Face.bottom) return null;
      if (f == Face.back) return hood.shade(y.isEven ? 1.0 : 0.94);
      if (f == Face.front) {
        if (y == 0) return hood;
        if (y == 1) return hood.shade(0.8);
        if (x == 0 || x == 7) return hood.shade(0.86);
        return null;
      }
      return t == 0 ? hood.shade(0.86) : hood;
    },
    jacket: (Face f, int x, int y, int w, int h, int t) {
      const Rgb cloak = Rgb(58, 82, 52);
      if (f == Face.back) return cloak.shade(y.isEven ? 1.0 : 0.94);
      if (_side(f)) return t >= 2 ? cloak : null;
      if (f == Face.front && y <= 9 && (x == 0 || x == 7)) return cloak.shade(0.9);
      if (f == Face.front && y == 0 && (x == 1 || x == 6)) return gold;
      if (f == Face.top) return cloak;
      return null;
    },
  ),
  'smith': Outfit(
    skin: const Rgb(122, 80, 55),
    hair: Hair.bald,
    hairColor: const Rgb(30, 24, 22),
    eyes: const Rgb(40, 30, 26),
    beard: const Rgb(36, 28, 24),
    shirt: const Rgb(150, 70, 52),
    sleeve: const Rgb(150, 70, 52),
    sleeveRows: 3,
    hands: black,
    pants: const Rgb(62, 58, 56),
    boots: black,
    bootRows: 4,
    body: (Face f, int x, int y, int w, int h, int t) {
      const Rgb apron = Rgb(96, 62, 38);
      if (f == Face.front) {
        if (y == 0 && (x == 1 || x == 6)) return darkLeather;
        if (y >= 1 && x >= 1 && x <= 6) return y == 7 ? darkLeather : (x == 1 || x == 6 ? apron.shade(0.86) : apron);
      }
      if (f == Face.back && (x == y || x == 7 - y) && y < 8) return darkLeather;
      if (_vertical(f) && y == 7) return darkLeather;
      return null;
    },
    leg: (Face f, int x, int y, int w, int h, int t) => f == Face.front && y < 5 ? (y == 4 ? const Rgb(96, 62, 38).shade(0.8) : const Rgb(96, 62, 38)) : null,
  ),
  'scholar': Outfit(
    skin: const Rgb(236, 196, 160),
    hair: Hair.short,
    hairColor: const Rgb(180, 180, 186),
    eyes: const Rgb(80, 110, 150),
    shirt: const Rgb(42, 62, 128),
    sleeve: const Rgb(42, 62, 128),
    sleeveRows: 11,
    pants: const Rgb(42, 62, 128),
    boots: const Rgb(60, 44, 34),
    bootRows: 2,
    belt: const Rgb(160, 40, 44),
    buckle: gold,
    face: (Face f, int x, int y, int w, int h, int t) {
      if (f != Face.front) return null;
      const Rgb frame = Rgb(150, 116, 50);
      if (y == 4 && (x == 0 || x == 3 || x == 4 || x == 7)) return frame;
      if (y == 3 && (x == 1 || x == 2 || x == 5 || x == 6)) return frame;
      return null;
    },
    body: (Face f, int x, int y, int w, int h, int t) {
      if (f == Face.front && (x == 3 || x == 4) && y < 8 && y > 0) return y.isEven ? gold : const Rgb(42, 62, 128).shade(0.8);
      if (f == Face.front && y == 0) return const Rgb(232, 228, 216);
      if (f == Face.right && y >= 8 && y <= 11 && t == 1) return const Rgb(232, 220, 180);
      return null;
    },
    arm: (Face f, int x, int y, int w, int h, int t) => _vertical(f) && y >= 9 && y <= 10 ? (y == 10 ? gold.shade(0.85) : const Rgb(42, 62, 128).shade(1.15)) : null,
    leg: (Face f, int x, int y, int w, int h, int t) => _vertical(f) && y == 9 ? gold.shade(0.85) : null,
  ),
};

Future<void> main(List<String> args) async {
  final Directory out = Directory('web/$placeholderSkinBase');
  await out.create(recursive: true);
  for (final PlaceholderSkin skin in placeholderSkins) {
    final Outfit? outfit = outfits[skin.look];
    if (outfit == null) throw StateError('no outfit drawn for ${skin.look}');
    await File('${out.path}/${skin.file}').writeAsBytes(img.encodePng(draw(outfit, slim: skin.slim), level: 9));
  }
  await File('${out.path}/index.json').writeAsString(placeholderIndexJson(placeholderSkins));
  stdout.writeln('wrote ${placeholderSkins.length} placeholder skins to ${out.path}');
}
