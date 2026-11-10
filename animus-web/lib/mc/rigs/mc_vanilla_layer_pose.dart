/// A second pose of a generated layer (`cat#main@sitting`): what the
/// model's `setupAnim` changes from the rest pose when a render state field
/// is set.
library;

import '../scene/mc_math.dart';
import 'mc_rig.dart';

final class McVanillaLayerPose {
  const McVanillaLayerPose({
    this.rotationsDeg = const <String, McVec3>{},
    this.offsetsPx = const <String, McVec3>{},
    this.hides = const <String>{},
    this.shows = const <String>{},
  });

  /// Part rotations that replace the rest rotation, degrees.
  final Map<String, McVec3> rotationsDeg;

  /// Moves from the rest pivot, model pixels.
  final Map<String, McVec3> offsetsPx;

  /// Parts this pose hides, and parts hidden at rest that it shows.
  final Set<String> hides;
  final Set<String> shows;
}

/// [poses] applied in order over a layer that hides [restHidden], later
/// poses winning where two touch the same part.
McRigPose mcLayerPose(Set<String> restHidden, Iterable<McVanillaLayerPose> poses) {
  final Map<String, McVec3> rotations = <String, McVec3>{};
  final Map<String, McVec3> offsets = <String, McVec3>{};
  final Set<String> hidden = <String>{...restHidden};
  for (final McVanillaLayerPose pose in poses) {
    rotations.addAll(pose.rotationsDeg);
    offsets.addAll(pose.offsetsPx);
    hidden
      ..removeAll(pose.shows)
      ..addAll(pose.hides);
  }
  return McRigPose(partRotationsDeg: rotations, partOffsetsPx: offsets, hidden: hidden);
}
