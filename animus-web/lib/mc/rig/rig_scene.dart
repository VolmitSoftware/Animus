/// A sampled rig to stage nodes, drawn the way the client draws the display
/// entities the server sends (dev/editor-design.md, "Server playback through
/// packets"):
///
/// - item bones: an item display, which turns its model 180 degrees about Y
///   and centres it (`ItemDisplayRenderer`), with the bone's visual matrix;
///   an `item_model` from the workspace's resource pack sources draws with
///   its own texture, a vanilla one through the asset pack;
/// - block bones: a block display, the default blockstate model with its
///   corner at the visual origin;
/// - text bones: a text display, drawn by the stage's text layer.
///
/// Rig space is world space: blocks, Y up, feet at the origin, facing +Z.
library;

import 'dart:convert';
import 'dart:typed_data';

import '../../logic/data_folder_export.dart';
import '../../logic/rig_edit.dart';
import '../../logic/rig_sampler.dart';
import '../assets/mc_display_pose.dart';
import '../assets/mc_ids.dart';
import '../assets/mc_model_json.dart';
import '../assets/mc_model_resolver.dart';
import '../npc/npc_look.dart';
import '../npc/npc_scene.dart';
import '../scene/mc_camera.dart';
import '../scene/mc_math.dart';
import '../scene/mc_scene.dart';

/// A runtime image id for a texture resource; the caller registers the
/// image under it.
typedef RigTextureId = String Function(String resourcePath);

final class RigScene {
  const RigScene({required this.nodes, required this.texts, required this.bounds, required this.notes});

  final List<McSceneNode> nodes;
  final List<NpcText> texts;
  final McBounds bounds;

  /// Bones the stage shows differently from the client, and why.
  final List<String> notes;
}

/// The sampler's row-major 3x4 transform as a column-major [McMat4].
McMat4 mcMatOfAffine(Affine affine) {
  final List<double> a = affine.m;
  return McMat4(Float64List.fromList(<double>[
    a[0], a[4], a[8], 0, //
    a[1], a[5], a[9], 0, //
    a[2], a[6], a[10], 0, //
    a[3], a[7], a[11], 1, //
  ]));
}

McVec3 originOf(Affine affine) => McVec3(affine.m[3], affine.m[7], affine.m[11]);

final McMat4 _itemDisplayTurn = McMat4.rotationY(180);

const Map<String, McDisplayPose> _contexts = <String, McDisplayPose>{
  'none': McDisplayPose.none,
  'fixed': McDisplayPose.fixed,
  'gui': McDisplayPose.gui,
  'ground': McDisplayPose.ground,
  'head': McDisplayPose.head,
  'thirdperson_righthand': McDisplayPose.thirdPersonRightHand,
  'thirdperson_lefthand': McDisplayPose.thirdPersonLeftHand,
  'firstperson_righthand': McDisplayPose.firstPersonRightHand,
  'firstperson_lefthand': McDisplayPose.firstPersonLeftHand,
};

RigScene rigScene(
  JsonMap rig,
  Map<String, BonePose> poses, {
  Map<String, Uint8List> resources = const <String, Uint8List>{},
  RigTextureId? textureId,
}) {
  final List<McSceneNode> nodes = <McSceneNode>[];
  final List<NpcText> texts = <NpcText>[];
  final List<String> notes = <String>[];
  double minX = -0.5, minY = 0, minZ = -0.5, maxX = 0.5, maxY = 1, maxZ = 0.5;
  void include(McVec3 point, double pad) {
    if (point.x - pad < minX) minX = point.x - pad;
    if (point.y - pad < minY) minY = point.y - pad;
    if (point.z - pad < minZ) minZ = point.z - pad;
    if (point.x + pad > maxX) maxX = point.x + pad;
    if (point.y + pad > maxY) maxY = point.y + pad;
    if (point.z + pad > maxZ) maxZ = point.z + pad;
  }

  for (final JsonMap bone in bonesOf(rig)) {
    final String id = '${bone['id']}';
    final BonePose? pose = poses[id];
    if (pose == null) continue;
    include(originOf(pose.model), 0.1);
    final Object? visual = bone['visual'];
    final Affine? at = pose.visual;
    if (!pose.visible || visual is! JsonMap || at == null) continue;
    final McMat4 matrix = mcMatOfAffine(at);
    include(originOf(at), 0.5);
    switch (visual['type']) {
      case 'item':
        nodes.addAll(_item(id, visual, matrix, resources, textureId, notes));
      case 'block':
        final String block = visual['block'] is String ? visual['block']! as String : 'minecraft:stone';
        final int state = block.indexOf('[');
        nodes.add(McBlockNode(key: 'bone:$id', blockKey: mcBlockId(state < 0 ? block : block.substring(0, state)), transform: matrix));
      case 'text':
        final String text = visual['text'] is String ? visual['text']! as String : '';
        texts.add(NpcText(anchor: originOf(at), lines: NpcLook.legacy(text).split('\n'), nameplate: false));
    }
  }
  return RigScene(
    nodes: nodes,
    texts: texts,
    bounds: McBounds(McVec3(minX, minY, minZ), McVec3(maxX, maxY, maxZ)),
    notes: notes,
  );
}

List<McSceneNode> _item(String bone, JsonMap visual, McMat4 matrix, Map<String, Uint8List> resources, RigTextureId? textureId, List<String> notes) {
  final Object? item = visual['item'];
  final String itemId = item is JsonMap && item['id'] is String ? item['id']! as String : 'minecraft:stick';
  final String? itemModel = itemModelOf(item);
  final McDisplayPose context = _contexts[visual['itemDisplay']] ?? McDisplayPose.none;
  final McMat4 placed = matrix.multiply(_itemDisplayTurn);
  if (itemModel != null && !itemModel.startsWith('minecraft:')) {
    final ResolvedItemModel? resolved = resolveWorkspaceItemModel(resources, itemModel);
    if (resolved != null && textureId != null) {
      final List<McSceneNode> parts = _workspaceModel(bone, itemModel, resolved, resources, placed, textureId, context);
      if (parts.isNotEmpty) return parts;
    }
    notes.add('$bone: $itemModel is not in this workspace, so the stage shows $itemId.');
  }
  return <McSceneNode>[
    McItemNode(
      key: 'bone:$bone',
      itemId: mcStrip(itemModel != null && itemModel.startsWith('minecraft:') ? itemModel : itemId),
      pose: context,
      transform: placed,
    ),
  ];
}

List<McSceneNode> _workspaceModel(
  String bone,
  String itemModel,
  ResolvedItemModel resolved,
  Map<String, Uint8List> resources,
  McMat4 placed,
  RigTextureId textureId,
  McDisplayPose context,
) {
  final Object? rawElements = resolved.model['elements'];
  if (rawElements is! List<Object?>) return const <McSceneNode>[];
  final Map<String, List<McResolvedElement>> byTexture = <String, List<McResolvedElement>>{};
  for (final Object? raw in rawElements) {
    if (raw is! JsonMap) continue;
    final McElementJson element = McElementJson.fromJson(raw);
    final Map<String, Map<String, McResolvedFace>> faces = <String, Map<String, McResolvedFace>>{};
    for (final MapEntry<String, McFaceJson> face in element.faces.entries) {
      final String variable = face.value.texture.startsWith('#') ? face.value.texture.substring(1) : face.value.texture;
      if (!resolved.textures.containsKey(variable)) continue;
      faces.putIfAbsent(variable, () => <String, McResolvedFace>{})[face.key] = McResolvedFace(
        texture: variable,
        uv: face.value.uv ?? _defaultUv(face.key, element.from, element.to),
        rotation: face.value.rotation,
        tintIndex: face.value.tintIndex,
      );
    }
    for (final MapEntry<String, Map<String, McResolvedFace>> group in faces.entries) {
      byTexture.putIfAbsent(group.key, () => <McResolvedElement>[]).add(McResolvedElement(
        from: element.from,
        to: element.to,
        faces: group.value,
        shade: element.shade,
        rotation: element.rotation,
      ));
    }
  }
  final Object? display = resolved.model['display'];
  final Object? transform = display is JsonMap ? display[context.key] : null;
  final McMat4 centred = McMat4.translation(-0.5, -0.5, -0.5)
      .multiply(mcDisplayPoseMatrix(transform is JsonMap && context != McDisplayPose.none ? McDisplayTransformJson.fromJson(transform) : null));
  final String source = utf8.decode(resources[resolved.paths[1]] ?? Uint8List(0), allowMalformed: true);
  return <McSceneNode>[
    for (final MapEntry<String, List<McResolvedElement>> group in byTexture.entries)
      McModelNode(
        key: 'bone:$bone:${group.key}',
        model: McResolvedModel(
          id: itemModel,
          elements: group.value,
          textures: const <String, String>{},
          display: const <String, McDisplayTransformJson>{},
          generated: false,
          entityBuiltin: false,
          layers: const <String>[],
        ),
        textureId: textureId(resolved.textures[group.key]!),
        modelSignature: '$itemModel#${group.key}#${source.hashCode}',
        transform: placed.multiply(centred),
      ),
  ];
}

/// The client's default face UV: the element's bounds projected on the face.
List<double> _defaultUv(String face, List<double> from, List<double> to) => switch (face) {
  'up' || 'down' => <double>[from[0], from[2], to[0], to[2]],
  'north' || 'south' => <double>[from[0], 16 - to[1], to[0], 16 - from[1]],
  _ => <double>[from[2], 16 - to[1], to[2], 16 - from[1]],
};
