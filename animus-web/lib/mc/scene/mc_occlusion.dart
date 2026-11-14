/// Which nearby bodies a stage leaves out: in a crowded spot the NPCs that
/// stand in the way of the NPC being edited, or that would draw huge and
/// cut off at the view's edge, are not drawn, so the subject always shows.
library;

import 'dart:math' as math;

import 'mc_camera.dart';
import 'mc_math.dart';

/// A body standing near the subject, as a box on its feet.
final class McOccluder {
  const McOccluder(this.feet, {this.height = 2, this.halfWidth = 0.4});

  /// The middle of its feet, world blocks.
  final McVec3 feet;
  final double height;
  final double halfWidth;

  McBounds get box => McBounds(
        McVec3(feet.x - halfWidth, feet.y, feet.z - halfWidth),
        McVec3(feet.x + halfWidth, feet.y + height, feet.z + halfWidth),
      );
}

/// A body nearer the eye than this, blocks, draws huge and blurred.
const double mcCullNearBlocks = 2;

/// The [occluders] the stage leaves out for [camera] in a [widthPx] x
/// [heightPx] view: those whose box comes within [mcCullNearBlocks] of the
/// eye; those in front of [subject] that cover part of it on screen; those
/// in front of it cut by an edge of the view; those whose head leaves the
/// top of the view; and, at any depth, those that show on screen where the
/// subject's own name plates hang ([plates]). One wholly behind the eye
/// never counts.
Set<McOccluder> mcCulled(McCamera camera, McBounds subject, Iterable<McOccluder> occluders,
    {required double widthPx, required double heightPx, McBounds? plates}) {
  final Set<McOccluder> culled = <McOccluder>{};
  if (occluders.isEmpty) return culled;
  final _Rect? target = _screen(camera, subject, widthPx, heightPx);
  final _Rect? text = plates == null ? null : _screen(camera, plates, widthPx, heightPx);
  final double? depth = mcProjectToPixels(camera, subject.centre, widthPx: widthPx, heightPx: heightPx)?.z;
  final McVec3 eye = camera.eye;
  for (final McOccluder occluder in occluders) {
    final McBounds box = occluder.box;
    final _Rect? rect = _screen(camera, box, widthPx, heightPx);
    if (rect == null) continue;
    if (_distance(eye, box) < mcCullNearBlocks) {
      culled.add(occluder);
      continue;
    }
    final double? near = mcProjectToPixels(camera, box.centre, widthPx: widthPx, heightPx: heightPx)?.z;
    final bool inFront = depth != null && (near == null || near < depth);
    final bool inView = rect.right > 0 && rect.left < widthPx && rect.bottom > 0 && rect.top < heightPx;
    final bool cut = rect.left < 0 || rect.right > widthPx || rect.top < 0 || rect.bottom > heightPx;
    if (!inView) continue;
    if ((inFront && target != null && rect.overlaps(target)) || (inFront && cut) || (rect.top < 0 && rect.bottom > 0) || (text != null && rect.overlaps(text))) {
      culled.add(occluder);
    }
  }
  return culled;
}

/// How far [point] is from [box], blocks; 0 inside it.
double _distance(McVec3 point, McBounds box) {
  final double dx = math.max(math.max(box.min.x - point.x, 0), point.x - box.max.x);
  final double dy = math.max(math.max(box.min.y - point.y, 0), point.y - box.max.y);
  final double dz = math.max(math.max(box.min.z - point.z, 0), point.z - box.max.z);
  return math.sqrt(dx * dx + dy * dy + dz * dz);
}

/// What is in view of [box] on screen, or null when all of it is behind
/// the eye.
_Rect? _screen(McCamera camera, McBounds box, double widthPx, double heightPx) {
  double left = double.infinity, top = double.infinity, right = -double.infinity, bottom = -double.infinity;
  for (final McVec3 corner in box.corners) {
    final McVec3? at = mcProjectToPixels(camera, corner, widthPx: widthPx, heightPx: heightPx);
    if (at == null) continue;
    if (at.x < left) left = at.x;
    if (at.x > right) right = at.x;
    if (at.y < top) top = at.y;
    if (at.y > bottom) bottom = at.y;
  }
  return left > right ? null : _Rect(left, top, right, bottom);
}

final class _Rect {
  const _Rect(this.left, this.top, this.right, this.bottom);

  final double left;
  final double top;
  final double right;
  final double bottom;

  bool overlaps(_Rect other) => left < other.right && other.left < right && top < other.bottom && other.top < bottom;
}
