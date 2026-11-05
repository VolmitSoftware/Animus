/// Box-model entities the way the client builds them: parts with a pivot,
/// boxes in model pixels with the vanilla box unwrap (`u, v` at the top-left
/// of the unwrap: top and bottom above, then west, north, east, south in a
/// row). Y is DOWN in model space, like the client; the mesher flips it.
///
/// Adapted from Gloss Web's `mc_rig.dart`. Animus adds per-part pose offsets
/// and hidden parts (the client's crouch, armor stand flags), and pose
/// rotations replace a part's rest rotation the way `setupAnim` assigns
/// `xRot`/`yRot`/`zRot`, instead of adding to it.
library;

import '../scene/mc_math.dart';

final class McBox {
  const McBox({
    required this.origin,
    required this.size,
    required this.u,
    required this.v,
    this.mirror = false,
    this.inflate = 0,
  });

  /// Corner nearest the origin, relative to the part pivot, model pixels.
  final McVec3 origin;
  final McVec3 size;
  final double u;
  final double v;

  /// Swaps the U direction of every side face relative to the client's
  /// default (unmirrored) unwrap, for the client's mirrored limbs.
  final bool mirror;

  /// Pixels the box grows on every side (`CubeDeformation`).
  final double inflate;
}

final class McRigPart {
  const McRigPart({
    required this.name,
    required this.pivot,
    this.boxes = const <McBox>[],
    this.children = const <McRigPart>[],
    this.rotationDeg = McVec3.zero,
    this.scale = 1,
  });

  final String name;

  /// `PartPose` scale, applied after the rotation (`ModelPart.xScale`).
  final double scale;

  /// Model pixels, relative to the parent's pivot.
  final McVec3 pivot;
  final List<McBox> boxes;
  final List<McRigPart> children;

  /// The rest rotation (`PartPose` angles), degrees, applied Z then Y then X
  /// outermost first: `Rz * Ry * Rx`, as `ModelPart.translateAndRotate`.
  final McVec3 rotationDeg;
}

final class McRig {
  const McRig({
    required this.id,
    required this.textureWidth,
    required this.textureHeight,
    required this.parts,
    required this.heightBlocks,
    this.scale = 1,
  });

  final String id;
  final int textureWidth;
  final int textureHeight;
  final List<McRigPart> parts;

  /// The client's render scale about the feet (`0.9375` for the player,
  /// `1.1` for the horse).
  final double scale;

  /// Hitbox height, blocks: where names and text anchor.
  final double heightBlocks;

  Iterable<McRigPart> get allParts sync* {
    Iterable<McRigPart> walk(McRigPart part) sync* {
      yield part;
      for (final McRigPart child in part.children) {
        yield* walk(child);
      }
    }

    for (final McRigPart part in parts) {
      yield* walk(part);
    }
  }

  McRigPart? part(String name) {
    for (final McRigPart candidate in allParts) {
      if (candidate.name == name) return candidate;
    }
    return null;
  }

  McRig copyWith({String? id, List<McRigPart>? parts, double? scale, double? heightBlocks}) => McRig(
    id: id ?? this.id,
    textureWidth: textureWidth,
    textureHeight: textureHeight,
    parts: parts ?? this.parts,
    heightBlocks: heightBlocks ?? this.heightBlocks,
    scale: scale ?? this.scale,
  );
}

/// One frame's part state, keyed by part name. Parts not named keep their
/// rest pose.
final class McRigPose {
  const McRigPose({
    this.partRotationsDeg = const <String, McVec3>{},
    this.partOffsetsPx = const <String, McVec3>{},
    this.hidden = const <String>{},
  });

  static const McRigPose rest = McRigPose();

  /// Replaces the part's rest rotation, degrees.
  final Map<String, McVec3> partRotationsDeg;

  /// Added to the part's pivot, model pixels (the client's `part.y += 4.2`).
  final Map<String, McVec3> partOffsetsPx;

  /// Parts not drawn, with everything under them (`ModelPart.visible`).
  final Set<String> hidden;

  McVec3 rotationOf(McRigPart part) => partRotationsDeg[part.name] ?? part.rotationDeg;

  McVec3 pivotOf(McRigPart part) => part.pivot + (partOffsetsPx[part.name] ?? McVec3.zero);

  @override
  bool operator ==(Object other) =>
      other is McRigPose &&
      _sameMap(other.partRotationsDeg, partRotationsDeg) &&
      _sameMap(other.partOffsetsPx, partOffsetsPx) &&
      other.hidden.length == hidden.length &&
      other.hidden.containsAll(hidden);

  @override
  int get hashCode => Object.hash(
    Object.hashAllUnordered(<Object>[for (final MapEntry<String, McVec3> e in partRotationsDeg.entries) Object.hash(e.key, e.value)]),
    Object.hashAllUnordered(<Object>[for (final MapEntry<String, McVec3> e in partOffsetsPx.entries) Object.hash(e.key, e.value)]),
    Object.hashAllUnordered(hidden),
  );

  /// A stable text form for cache keys.
  String get signature {
    String vectors(Map<String, McVec3> map) {
      final List<String> keys = map.keys.toList()..sort();
      return <String>[for (final String key in keys) '$key=${map[key]!.x},${map[key]!.y},${map[key]!.z}'].join(';');
    }

    final List<String> hiddenSorted = hidden.toList()..sort();
    return 'r[${vectors(partRotationsDeg)}]o[${vectors(partOffsetsPx)}]h[${hiddenSorted.join(',')}]';
  }
}

bool _sameMap(Map<String, McVec3> a, Map<String, McVec3> b) {
  if (a.length != b.length) return false;
  for (final MapEntry<String, McVec3> entry in a.entries) {
    if (b[entry.key] != entry.value) return false;
  }
  return true;
}
