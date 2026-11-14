/// The scale of a game window in its frame (WindowFit). Windows are drawn at
/// the client's GUI scale 3: one game pixel is [mcGuiPixel] CSS pixels. A
/// window fits the frame's width and a height cap: the band above a form is
/// [bandFrameHeight] tall at most, a preview beside the form or on a phone
/// [sideFrameShare] of the window, the frame's header and status included.
library;

import 'dart:math' as math;

/// CSS pixels per game pixel at the natural size (GUI scale 3).
const int mcGuiPixel = 3;

/// CSS pixels of one slot (18 game pixels) at the natural size.
const int mcSlotPixels = 18 * mcGuiPixel;

/// The smallest slot a finger can still pick: a small button's height.
const double minSlotPixels = 32;

/// The tallest a preview frame in the band above a form may be.
const double bandFrameHeight = 240;

/// The share of the browser window's height a preview frame may take
/// beside the form or on a phone.
const double sideFrameShare = 0.6;

/// The least height a window keeps, whatever the frame around it takes.
const double minWindowRoom = 120;

/// The height a window has in its frame: the frame's cap ([band] or the
/// side share of [viewportHeight]) less the frame's own [chrome] (header,
/// status line, padding).
double windowRoomHeight({required bool band, required double viewportHeight, required double chrome}) =>
    math.max(minWindowRoom, (band ? bandFrameHeight : viewportHeight * sideFrameShare) - chrome);

/// The zoom that fits [natural] CSS px into [room] (and [naturalHeight]
/// into [roomHeight] when given) at [dpr] device pixels per CSS pixel: 1
/// when it fits, else the largest step of one device pixel per game pixel
/// that fits (never under one step). When that step leaves slots too small
/// to tap (a phone, the short band), the window fills the room instead,
/// each slot on whole device pixels, if that is larger.
double windowZoom({required double natural, required double room, required double dpr, double? naturalHeight, double? roomHeight}) {
  final double wide = _fit(natural, room, dpr);
  if (naturalHeight == null || roomHeight == null) return wide;
  return math.min(wide, _fit(naturalHeight, roomHeight, dpr));
}

double _fit(double natural, double room, double dpr) {
  if (!(natural > 0) || room >= natural) return 1;
  final double steps = mcGuiPixel * dpr;
  final int fit = (room / natural * steps + 1e-9).floor();
  final double crisp = (fit < 1 ? 1 : fit) / steps;
  if (crisp * mcSlotPixels >= minSlotPixels) return crisp;
  final double slotSteps = mcSlotPixels * dpr;
  final double filled = (room / natural * slotSteps + 1e-9).floor() / slotSteps;
  return filled > crisp ? filled : crisp;
}
