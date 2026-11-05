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
  });

  final McVec3 pivot;
  final double yawDeg;
  final double pitchDeg;
  final double distance;

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
  );

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
  McCamera clamped() => McCamera(
    pivot: McVec3(
      pivot.x.clamp(-mcCameraPivotRange, mcCameraPivotRange).toDouble(),
      pivot.y.clamp(-mcCameraPivotRange, mcCameraPivotRange).toDouble(),
      pivot.z.clamp(-mcCameraPivotRange, mcCameraPivotRange).toDouble(),
    ),
    yawDeg: yawDeg,
    pitchDeg: pitchDeg.clamp(-89, 89).toDouble(),
    distance: distance.clamp(mcCameraMinDistance, mcCameraMaxDistance).toDouble(),
  );

  McMat4 view() => McMat4.lookAt(eye, pivot, McVec3.up);

  @override
  bool operator ==(Object other) =>
      other is McCamera && other.pivot == pivot && other.yawDeg == yawDeg && other.pitchDeg == pitchDeg && other.distance == distance;

  @override
  int get hashCode => Object.hash(pivot, yawDeg, pitchDeg, distance);

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

  McVec3 get centre => (min + max) * 0.5;
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

/// The home framing for a subject: looking at its centre from the front
/// (yaw 180 looks back along -Z at a body facing +Z), a little from above,
/// far enough that the bounding sphere fits the vertical field of view.
McCamera mcCameraFraming(McBounds bounds, {double yawDeg = 180, double pitchDeg = 12}) {
  final double radius = math.max(0.5, bounds.size.length / 2);
  final double distance = radius / math.sin(mcStageFovYDeg * _degToRad / 2) * 1.1;
  return McCamera(pivot: bounds.centre, yawDeg: yawDeg, pitchDeg: pitchDeg, distance: distance).clamped();
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
