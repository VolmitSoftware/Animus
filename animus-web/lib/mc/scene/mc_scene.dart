/// What the NPC stage asks the renderer to draw: keyed nodes the renderer
/// meshes once per [McSceneNode.signature] and only re-places when just the
/// transform changed. Adapted from Gloss Web's scene nodes.
library;

import '../assets/mc_display_pose.dart';
import '../rigs/mc_rig.dart';
import 'mc_camera.dart';
import 'mc_math.dart';

sealed class McSceneNode {
  const McSceneNode({required this.key, required this.transform, this.glint = false});

  final String key;

  /// Mesh space to world blocks.
  final McMat4 transform;

  /// The enchantment glint over the whole node.
  final bool glint;

  /// Everything that decides the mesh; equal signatures share one mesh.
  String get signature;
}

/// A box-model body or layer. The mesh is in rig space (feet at the origin,
/// facing +Z); [transform] places it.
final class McRigNode extends McSceneNode {
  const McRigNode({
    required super.key,
    required this.rig,
    required this.textureId,
    required super.transform,
    this.pose = McRigPose.rest,
    this.tint = const <double>[1, 1, 1],
    super.glint,
  });

  final McRig rig;
  final McRigPose pose;

  /// A pack entity texture id, or a runtime skin id registered with the
  /// asset pack.
  final String textureId;
  final List<double> tint;

  @override
  String get signature => 'rig:${rig.id}:${pose.signature}:${tint.join(',')}';
}

/// An item model at a display pose, mesh space as the client's item renderer
/// leaves it: the model's display transform applied, centred on the origin.
final class McItemNode extends McSceneNode {
  const McItemNode({
    required super.key,
    required this.itemId,
    required this.pose,
    required super.transform,
    this.leftHand = false,
    super.glint,
  });

  /// Bare item key (`diamond_sword`), the `items/<key>.json` name.
  final String itemId;
  final McDisplayPose pose;

  /// `ItemTransform.apply`'s left-hand fix: mirrors translation X and
  /// rotation Y and Z.
  final bool leftHand;

  @override
  String get signature => 'item:$itemId:${pose.name}:$leftHand';
}

final class McScene {
  McScene(this.nodes, {this.bounds}) : byKey = <String, McSceneNode>{} {
    for (final McSceneNode node in nodes) {
      if (byKey.containsKey(node.key)) {
        throw ArgumentError.value(node.key, 'key', 'duplicate scene node key');
      }
      byKey[node.key] = node;
    }
  }

  static final McScene empty = McScene(const <McSceneNode>[]);

  final List<McSceneNode> nodes;
  final Map<String, McSceneNode> byKey;

  /// What the home camera frames, world blocks.
  final McBounds? bounds;
}
