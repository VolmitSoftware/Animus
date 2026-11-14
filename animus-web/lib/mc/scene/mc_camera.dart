/// The NPC stage's orbit camera, in blocks, and the projection the GL pass
/// and the text layer share.
///
/// [pivot] is the point on screen centre, [distance] how far the eye stands
/// back along the look direction, [yawDeg]/[pitchDeg] where that direction
/// points (Minecraft's convention: yaw 0 looks along +Z, increasing yaw turns
/// right, positive pitch looks down). Adapted from Gloss Web's shared stage
/// camera without the fly controls.
library;

import 'dart:math' as math;

import 'mc_math.dart';

const double mcCameraYawDegPerPx = 0.32;
const double mcCameraPitchDegPerPx = 0.24;
const double mcCameraDollyPerWheelPx = 0.0015;
const double mcCameraMinDistance = 0.75;
const double mcCameraMaxDistance = 64;
const double mcCameraPivotRange = 32;

/// Vertical field of view of every stage, degrees: the client's default 70
/// is too wide for a portrait of one body.
const double mcStageFovYDeg = 40;

const double _degToRad = math.pi / 180;

/// A `wheel` event's delta in pixels across browsers' three delta modes.
double mcWheelPixels(double delta, int deltaMode) => switch (deltaMode) {
  1 => delta * 16,
  2 => delta * 400,
  _ => delta,
};

final class McCamera {
  const McCamera({
    required this.pivot,
    required this.yawDeg,
    required this.pitchDeg,
    required this.distance,
    this.reach,
  });

  final McVec3 pivot;
  final double yawDeg;
  final double pitchDeg;
  final double distance;

  /// Where the pivot may go; [mcCameraPivotRange] around the origin when
  /// null. A stage with terrain along a long route widens it to the terrain.
  final McBounds? reach;

  /// Unit look direction, Minecraft yaw/pitch.
  McVec3 get forward {
    final double yaw = yawDeg * _degToRad, pitch = pitchDeg * _degToRad;
    return McVec3(-math.cos(pitch) * math.sin(yaw), -math.sin(pitch), math.cos(pitch) * math.cos(yaw));
  }

  /// True right: `forward x up`, matching `McMat4.lookAt`'s view basis.
  McVec3 get right {
    final McVec3 r = forward.cross(McVec3.up);
    return r.length <= 1e-9 ? const McVec3(1, 0, 0) : r.normalized;
  }

  McVec3 get up => right.cross(forward);

  McVec3 get eye => pivot - forward * distance;

  McCamera copyWith({McVec3? pivot, double? yawDeg, double? pitchDeg, double? distance}) => McCamera(
    pivot: pivot ?? this.pivot,
    yawDeg: yawDeg ?? this.yawDeg,
    pitchDeg: pitchDeg ?? this.pitchDeg,
    distance: distance ?? this.distance,
    reach: reach,
  );

  /// This camera with the pivot allowed within [reach], clamped into it.
  McCamera within(McBounds? reach) => McCamera(pivot: pivot, yawDeg: yawDeg, pitchDeg: pitchDeg, distance: distance, reach: reach).clamped();

  /// Drag right turns the eye right so the scene slides left under the
  /// pointer; drag down looks further down.
  McCamera orbitBy(double dxPx, double dyPx) => copyWith(
    yawDeg: _wrap(yawDeg + dxPx * mcCameraYawDegPerPx),
    pitchDeg: pitchDeg + dyPx * mcCameraPitchDegPerPx,
  ).clamped();

  McCamera dollyBy(double wheelPx) => copyWith(distance: distance * math.exp(wheelPx * mcCameraDollyPerWheelPx)).clamped();

  /// Blocks per pixel at the pivot's depth for a [viewportHeightPx] tall view.
  double blocksPerPixel(double viewportHeightPx) =>
      viewportHeightPx <= 0 ? 0 : distance * 2 * math.tan(mcStageFovYDeg * _degToRad / 2) / viewportHeightPx;

  /// Drag the world with the pointer: the pivot moves opposite the drag.
  McCamera panBy(double dxPx, double dyPx, double viewportHeightPx) {
    final double k = blocksPerPixel(viewportHeightPx);
    return copyWith(pivot: pivot - right * (dxPx * k) + up * (dyPx * k)).clamped();
  }

  /// Pitch within the poles, distance in a readable band, pivot near the
  /// subject: the subject can never be lost off screen.
  McCamera clamped() {
    final McVec3 low = reach?.min ?? const McVec3(-mcCameraPivotRange, -mcCameraPivotRange, -mcCameraPivotRange);
    final McVec3 high = reach?.max ?? const McVec3(mcCameraPivotRange, mcCameraPivotRange, mcCameraPivotRange);
    return McCamera(
      pivot: McVec3(pivot.x.clamp(low.x, high.x).toDouble(), pivot.y.clamp(low.y, high.y).toDouble(), pivot.z.clamp(low.z, high.z).toDouble()),
      yawDeg: yawDeg,
      pitchDeg: pitchDeg.clamp(-89, 89).toDouble(),
      distance: distance.clamp(mcCameraMinDistance, mcCameraMaxDistance).toDouble(),
      reach: reach,
    );
  }

  McMat4 view() => McMat4.lookAt(eye, pivot, McVec3.up);

  @override
  bool operator ==(Object other) =>
      other is McCamera &&
      other.pivot == pivot &&
      other.yawDeg == yawDeg &&
      other.pitchDeg == pitchDeg &&
      other.distance == distance &&
      other.reach?.min == reach?.min &&
      other.reach?.max == reach?.max;

  @override
  int get hashCode => Object.hash(pivot, yawDeg, pitchDeg, distance, reach?.min, reach?.max);

  static double _wrap(double degrees) {
    double d = degrees % 360;
    if (d > 180) d -= 360;
    if (d < -180) d += 360;
    return d;
  }
}

/// An axis-aligned box in world blocks.
final class McBounds {
  const McBounds(this.min, this.max);

  final McVec3 min;
  final McVec3 max;

  /// The box around [points]; a unit box at the origin when there are none.
  factory McBounds.of(Iterable<McVec3> points) {
    double minX = double.infinity, minY = double.infinity, minZ = double.infinity;
    double maxX = -double.infinity, maxY = -double.infinity, maxZ = -double.infinity;
    for (final McVec3 p in points) {
      minX = math.min(minX, p.x);
      minY = math.min(minY, p.y);
      minZ = math.min(minZ, p.z);
      maxX = math.max(maxX, p.x);
      maxY = math.max(maxY, p.y);
      maxZ = math.max(maxZ, p.z);
    }
    if (minX > maxX) return const McBounds(McVec3(-0.5, 0, -0.5), McVec3(0.5, 1, 0.5));
    return McBounds(McVec3(minX, minY, minZ), McVec3(maxX, maxY, maxZ));
  }

  McVec3 get centre => (min + max) * 0.5;

  /// The eight corners.
  List<McVec3> get corners => <McVec3>[
    for (final double x in <double>[min.x, max.x])
      for (final double y in <double>[min.y, max.y])
        for (final double z in <double>[min.z, max.z]) McVec3(x, y, z),
  ];
  McVec3 get size => max - min;

  McBounds union(McBounds other) => McBounds(
    McVec3(math.min(min.x, other.min.x), math.min(min.y, other.min.y), math.min(min.z, other.min.z)),
    McVec3(math.max(max.x, other.max.x), math.max(max.y, other.max.y), math.max(max.z, other.max.z)),
  );

  @override
  bool operator ==(Object other) => other is McBounds && other.min == min && other.max == max;

  @override
  int get hashCode => Object.hash(min, max);
}

/// How much of the view the framed box may cover, per axis.
const double mcFramingFill = 0.86;

/// The home framing for a subject: looking at the centre of [points] from
/// the front (yaw 180 looks back along -Z at a body facing +Z), a little
/// from above, as close as it can stand while every point stays inside
/// [mcFramingFill] of a view of width / height [aspect]. Points, not a box:
/// a dragon's tail far behind it does not push the camera back the way its
/// bounding box's empty near corners would. A player and a dragon therefore
/// fill the view alike.
McCamera mcCameraFraming(List<McVec3> points, {double yawDeg = 180, double pitchDeg = 12, double aspect = 4 / 3, McBounds? reach}) {
  final McBounds box = McBounds.of(points);
  final McCamera aim = McCamera(pivot: box.centre, yawDeg: yawDeg, pitchDeg: pitchDeg, distance: 1, reach: reach);
  final McVec3 f = aim.forward, r = aim.right, u = aim.up;
  final double tanY = math.tan(mcStageFovYDeg * _degToRad / 2) * mcFramingFill;
  final double tanX = tanY * (aspect > 0 ? aspect : 1);
  double distance = mcCameraMinDistance;
  for (final McVec3 point in points) {
    final McVec3 c = point - box.centre;
    // A point at depth d + c.f must satisfy |c.u| <= tanY (d + c.f).
    distance = math.max(distance, c.dot(u).abs() / tanY - c.dot(f));
    distance = math.max(distance, c.dot(r).abs() / tanX - c.dot(f));
  }
  return aim.copyWith(distance: distance).clamped();
}

/// OpenGL projection for a [widthPx] x [heightPx] viewport.
McMat4 mcGlProjection({required double widthPx, required double heightPx, double near = 0.05, double far = 256}) =>
    McMat4.perspective(mcStageFovYDeg * _degToRad, heightPx <= 0 ? 1 : widthPx / heightPx, near, far);

/// Viewport pixels (x right, y down) and the depth in blocks, or null at or
/// behind the eye.
McVec3? mcProjectToPixels(McCamera camera, McVec3 point, {required double widthPx, required double heightPx}) {
  final McVec3 view = camera.view().transformPoint(point);
  if (!(-view.z > 0)) return null;
  final McVec3 clip = mcGlProjection(widthPx: widthPx, heightPx: heightPx).transformPoint(view);
  return McVec3((clip.x + 1) / 2 * widthPx, (1 - clip.y) / 2 * heightPx, -view.z);
}

/// CSS pixels one block covers at [depthBlocks] from the eye.
double mcPixelsPerBlock(double heightPx, double depthBlocks) =>
    depthBlocks <= 0 ? 0 : heightPx / (2 * math.tan(mcStageFovYDeg * _degToRad / 2) * depthBlocks);

/// Smallest CSS font size the text layer draws at, so a name over a far or
/// large body stays readable.
const double mcTextMinFontPx = 13;

/// CSS font size for world text at [depthBlocks]: the client draws name tags
/// and text displays at 1/40 block per font pixel, 8 font pixels to the em,
/// floored at [mcTextMinFontPx]. Every text block uses this one rule.
double mcTextFontPx(double heightPx, double depthBlocks) =>
    math.max(mcTextMinFontPx, mcPixelsPerBlock(heightPx, depthBlocks) * 0.025 * 8);

/// [px] as the text layer draws it, so the pixel font stays crisp: whole
/// multiples of its 8 px grid up to 40 px (16 at least), whole pixels above.
double mcCrispFontPx(double px) => px <= 40 ? math.max(16, (px / 8).round() * 8).toDouble() : px.roundToDouble();

/// The gap the text layer keeps between a text block and the stage's edge.
const double mcTextInsetPx = 4;

/// Labels drawn only near the camera (route point numbers) show while their
/// depth is at most this many times the camera's distance to its pivot.
const double mcNearTextReach = 1.5;
