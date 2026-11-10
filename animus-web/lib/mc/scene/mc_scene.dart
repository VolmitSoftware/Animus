/// What the NPC stage asks the renderer to draw: keyed nodes the renderer
/// meshes once per [McSceneNode.signature] and only re-places when just the
/// transform changed. Adapted from Gloss Web's scene nodes.
library;

import '../assets/mc_display_pose.dart';
import '../assets/mc_model_resolver.dart';
import '../mesh/mc_mesh.dart';
import '../rigs/mc_rig.dart';
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
    this.opacity = 1,
    super.glint,
  });

  final McRig rig;

  /// How opaque the layer draws, 0 to 1: below 1 for the energy swirl of a
  /// charged creeper or an armoured wither.
  final double opacity;
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

/// A block model the way a block display draws it: the default blockstate
/// variant unless [state] names properties, the block's corner at the mesh
/// origin, one block across.
final class McBlockNode extends McSceneNode {
  const McBlockNode({required super.key, required this.blockKey, required super.transform, this.state, this.modelId, super.glint});

  /// Bare block key (`carved_pumpkin`), block state properties stripped.
  final String blockKey;

  /// The block state with its properties (`oak_stairs[facing=east]`), drawn
  /// from the pack's full blockstates; null or bare for the default variant.
  final String? state;

  /// A block model drawn as it is, for a model no blockstate names
  /// (`block/item_frame`).
  final String? modelId;

  @override
  String get signature => 'block:$blockKey:${state ?? ''}:${modelId ?? ''}';
}

/// Elements that are not in the asset pack (an item model from the
/// workspace's resource pack sources) drawn with one runtime texture that
/// spans the whole image. Mesh space is the model's, in blocks.
final class McModelNode extends McSceneNode {
  const McModelNode({
    required super.key,
    required this.model,
    required this.textureId,
    required this.modelSignature,
    required super.transform,
    super.glint,
  });

  final McResolvedModel model;

  /// A runtime image id registered with the asset loader.
  final String textureId;

  /// Decides the mesh: the model source and the texture variable drawn.
  final String modelSignature;

  @override
  String get signature => 'model:$modelSignature';
}

/// The outline a glowing entity gets: [keys] are the nodes of its body and
/// what it wears, drawn as one silhouette edge in [color] (`0xRRGGBB`).
final class McGlow {
  const McGlow(this.color, this.keys);

  final int color;
  final Set<String> keys;
}

/// Geometry built outside the renderer (terrain around the NPC), drawn
/// with the block atlas. [translucent] meshes draw last, blended, without
/// writing depth.
final class McMeshNode extends McSceneNode {
  McMeshNode({required super.key, required this.mesh, required this.meshSignature, this.translucent = false}) : super(transform: McMat4.identity());

  final McMesh mesh;

  /// Decides the mesh: equal signatures are the same geometry.
  final String meshSignature;
  final bool translucent;

  @override
  String get signature => 'mesh:$meshSignature';
}

/// [node] placed [by] further: its transform with [by] applied after it.
McSceneNode mcMovedNode(McSceneNode node, McMat4 by) => switch (node) {
      McRigNode() => McRigNode(key: node.key, rig: node.rig, textureId: node.textureId, transform: by.multiply(node.transform), pose: node.pose, tint: node.tint, glint: node.glint),
      McItemNode() => McItemNode(key: node.key, itemId: node.itemId, pose: node.pose, transform: by.multiply(node.transform), leftHand: node.leftHand, glint: node.glint),
      McBlockNode() => McBlockNode(key: node.key, blockKey: node.blockKey, transform: by.multiply(node.transform), glint: node.glint),
      McModelNode() => McModelNode(key: node.key, model: node.model, textureId: node.textureId, modelSignature: node.modelSignature, transform: by.multiply(node.transform), glint: node.glint),
      // Meshes are built in place (terrain) and never moved.
      McMeshNode() => node,
    };

final class McScene {
  McScene(this.nodes, {this.frame = const <McVec3>[], this.glow, this.ground = true}) : byKey = <String, McSceneNode>{} {
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

  /// Points the home camera keeps in view, world blocks: the body's mesh
  /// and the text above it.
  final List<McVec3> frame;

  /// The glowing outline, or null when nothing glows.
  final McGlow? glow;
  /// The grass patch under the body; terrain replaces it.
  final bool ground;
}
