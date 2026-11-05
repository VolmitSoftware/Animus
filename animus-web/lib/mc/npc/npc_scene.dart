/// An NPC definition as a scene: the body rig in its pose, its equipment as
/// armour layers and held items, and the text the client draws above it.
/// The body stands at the origin turned to its definition yaw.
library;

import '../assets/mc_display_pose.dart';
import '../mesh/mc_mesh.dart';
import '../rigs/mc_entity_rigs.dart';
import '../rigs/mc_player_rig.dart';
import '../rigs/mc_rig.dart';
import '../rigs/mc_rig_mesher.dart';
import '../rigs/mc_rigs.dart';
import '../scene/mc_camera.dart';
import '../scene/mc_math.dart';
import '../scene/mc_scene.dart';
import 'npc_look.dart';
import 'npc_pose.dart';

/// Leather's undyed colour (`DyedItemColor.LEATHER_COLOR`).
const int mcLeatherColor = 0xA06540;

/// Text more than this far above the body's mesh is left out of the home
/// framing, blocks.
const double mcFramedTextGap = 1.0;

/// Text the client draws in world space, as the server lays it out.
final class NpcText {
  const NpcText({required this.anchor, required this.lines, required this.nameplate});

  /// Bottom centre, world blocks: text grows upward from here.
  final McVec3 anchor;

  /// Section-sign formatted lines, top first.
  final List<String> lines;

  /// The client's own player nameplate rather than a text display.
  final bool nameplate;
}

final class NpcScene {
  const NpcScene({required this.scene, required this.texts, required this.notes, required this.presentable});

  final McScene scene;
  final List<NpcText> texts;

  /// What the definition declares that the preview does not draw, and why.
  final List<String> notes;

  /// False for a type no transport presents.
  final bool presentable;
}

/// The skin a player body wears: a pack texture id or a registered runtime
/// image id, and the arm model.
final class NpcSkinChoice {
  const NpcSkinChoice({required this.textureId, required this.slim});

  static const NpcSkinChoice steve = NpcSkinChoice(textureId: mcSteveTexture, slim: false);
  static const NpcSkinChoice alex = NpcSkinChoice(textureId: mcAlexTexture, slim: true);

  final String textureId;
  final bool slim;
}

/// An armour material and its layer textures (`assets/minecraft/equipment`).
final class McArmorPiece {
  const McArmorPiece({required this.slot, required this.material});

  final NpcSlot slot;

  /// `iron`, `gold`, `leather`, `turtle_scute`.
  final String material;

  McArmorLayer get layer => switch (slot) {
    NpcSlot.head => McArmorLayer.head,
    NpcSlot.legs => McArmorLayer.legs,
    NpcSlot.feet => McArmorLayer.feet,
    _ => McArmorLayer.chest,
  };

  String get texture => 'entity/equipment/${slot == NpcSlot.legs ? 'humanoid_leggings' : 'humanoid'}/$material';
  bool get dyeable => material == 'leather';
  String get overlayTexture => '${texture}_overlay';
}

final RegExp _armorId = RegExp(r'^(leather|chainmail|iron|golden|diamond|netherite|copper)_(helmet|chestplate|leggings|boots)$');

/// The humanoid armour piece [item] is, or null. Only the slot the item
/// equips into draws it, as `HumanoidArmorLayer` does.
McArmorPiece? mcArmorPiece(NpcItem item) {
  if (item.key == 'turtle_helmet') return const McArmorPiece(slot: NpcSlot.head, material: 'turtle_scute');
  final RegExpMatch? match = _armorId.firstMatch(item.key);
  if (match == null) return null;
  final NpcSlot slot = switch (match.group(2)) {
    'helmet' => NpcSlot.head,
    'chestplate' => NpcSlot.chest,
    'leggings' => NpcSlot.legs,
    _ => NpcSlot.feet,
  };
  return McArmorPiece(slot: slot, material: mcArmorMaterials[match.group(1)]!);
}

/// `minecraft:iron_horse_armor` to its `horse_body` material.
String? mcHorseArmorMaterial(NpcItem item) {
  final RegExpMatch? match = RegExp(r'^(leather|iron|golden|diamond|netherite|copper)_horse_armor$').firstMatch(item.key);
  return match == null ? null : mcArmorMaterials[match.group(1)];
}

/// `minecraft:red_carpet` to its `llama_body` colour.
String? mcCarpetColor(NpcItem item) {
  if (!item.key.endsWith('_carpet')) return null;
  final String color = item.key.substring(0, item.key.length - 7);
  return mcDyeColors.contains(color) ? color : null;
}

List<double> _rgb(int color) => <double>[((color >> 16) & 0xFF) / 255, ((color >> 8) & 0xFF) / 255, (color & 0xFF) / 255];

NpcScene npcScene(NpcLook look, {NpcSkinChoice skin = NpcSkinChoice.steve}) {
  final List<McSceneNode> nodes = <McSceneNode>[];
  final List<String> notes = <String>[];
  final bool small = look.armorStand?.small ?? false;
  final McRig? rig = mcBodyRig(look.type, slim: skin.slim, small: small);
  if (rig == null) {
    return NpcScene(
      scene: McScene.empty,
      texts: const <NpcText>[],
      notes: <String>['${look.type.isEmpty ? 'This definition' : look.type} is not a body type Animus presents.'],
      presentable: false,
    );
  }

  final McBodyPose body = switch (look.type) {
    'minecraft:player' => mcPlayerPose(
      look.pose,
      yawDeg: look.yawDeg,
      rightHeld: look.equipment.containsKey(NpcSlot.mainHand),
      leftHeld: look.equipment.containsKey(NpcSlot.offHand),
    ),
    'minecraft:armor_stand' => McBodyPose(
      rig: mcArmorStandPose(look.armorStand ?? const NpcArmorStand()),
      frame: McMat4.identity(),
      heightBlocks: rig.heightBlocks,
    ),
    // `EnderDragonRenderer` moves the model one block back along its facing.
    'minecraft:ender_dragon' => McBodyPose(rig: McRigPose.rest, frame: McMat4.translation(0, 0, -1), heightBlocks: rig.heightBlocks),
    _ => McBodyPose(rig: McRigPose.rest, frame: McMat4.identity(), heightBlocks: rig.heightBlocks),
  };
  final McMat4 root = McMat4.rotationY(-look.yawDeg).multiply(body.frame);
  final McMat4 model = root.multiply(mcRigModelToWorld(rig));
  // Every part, visible or not: an armless armor stand still holds items,
  // because the client shows the arm for the transform
  // (`ArmorStandModel.translateToHand`).
  final Map<String, McMat4> parts = mcRigPartMatrices(
    rig,
    McRigPose(partRotationsDeg: body.rig.partRotationsDeg, partOffsetsPx: body.rig.partOffsetsPx),
  );

  final bool invisible = look.armorStand?.invisible ?? false;
  if (!invisible) {
    nodes.add(McRigNode(
      key: 'body',
      rig: rig,
      pose: body.rig,
      textureId: look.isPlayer ? skin.textureId : mcBodyTexture(look.type)!,
      transform: root,
    ));
  }

  final bool humanoid = look.isPlayer || look.type == 'minecraft:armor_stand';
  for (final MapEntry<NpcSlot, NpcItem> entry in look.equipment.entries) {
    final NpcSlot slot = entry.key;
    final NpcItem item = entry.value;
    final String where = '${slot.key}: ${item.id}';
    if (humanoid) {
      if (slot == NpcSlot.mainHand || slot == NpcSlot.offHand) {
        nodes.add(_heldItem(look, parts, model, slot, item, small: small, slim: skin.slim));
        continue;
      }
      final McArmorPiece? armor = mcArmorPiece(item);
      if (armor != null && armor.slot == slot) {
        nodes.addAll(_armorLayers(look, armor, item, body.rig, root, small: small));
        continue;
      }
      if (slot == NpcSlot.head) {
        nodes.add(_headItem(parts, model, item));
        continue;
      }
      notes.add('$where is not drawn: the client shows only ${armor == null ? 'armour' : '${armor.slot.key} armour'} in this slot.');
      continue;
    }
    if (look.type == 'minecraft:horse' && slot == NpcSlot.body && mcHorseArmorMaterial(item) != null) {
      final String material = mcHorseArmorMaterial(item)!;
      final McRig armor = mcHorseRig(g: 0.1);
      final bool leather = material == 'leather';
      nodes.add(McRigNode(
        key: 'equipment:body',
        rig: armor,
        textureId: 'entity/equipment/horse_body/$material',
        transform: root,
        tint: leather ? _rgb(item.dyedColor ?? mcLeatherColor) : const <double>[1, 1, 1],
        glint: item.glint,
      ));
      if (leather) {
        nodes.add(McRigNode(key: 'equipment:body:overlay', rig: armor, textureId: 'entity/equipment/horse_body/leather_overlay', transform: root, glint: item.glint));
      }
      continue;
    }
    if (look.type == 'minecraft:horse' && slot == NpcSlot.saddle && item.key == 'saddle') {
      nodes.add(McRigNode(
        key: 'equipment:saddle',
        rig: mcHorseRig(saddle: true),
        pose: const McRigPose(hidden: <String>{'left_saddle_line', 'right_saddle_line'}),
        textureId: 'entity/equipment/horse_saddle/saddle',
        transform: root,
        glint: item.glint,
      ));
      continue;
    }
    if (look.type == 'minecraft:llama' && slot == NpcSlot.body && mcCarpetColor(item) != null) {
      nodes.add(McRigNode(
        key: 'equipment:body',
        rig: mcLlamaRig(g: 0.5),
        textureId: 'entity/equipment/llama_body/${mcCarpetColor(item)}',
        transform: root,
        glint: item.glint,
      ));
      continue;
    }
    notes.add('$where is not drawn on ${look.type}.');
  }

  final double feet = look.isPlayer && look.pose == NpcPose.sitting ? -mcPlayerVehicleAttachment : 0;
  final double top = feet + body.heightBlocks;
  final List<NpcText> texts = <NpcText>[
    if (look.nativeName) NpcText(anchor: McVec3(0, top + 0.25, 0), lines: <String>[look.name], nameplate: true),
    if (look.textLines.isNotEmpty)
      NpcText(anchor: McVec3(0, top + (look.nativeName ? 0.55 : 0.3), 0), lines: look.textLines, nameplate: false),
  ];

  final List<McVec3> frame = _framePoints(mcMeshRig(rig, pose: body.rig), root);
  if (frame.isEmpty) frame.addAll(McBounds(McVec3(-0.5, feet, -0.5), McVec3(0.5, top, 0.5)).corners);
  // Text that sits on the body is framed with it. Text the client floats
  // well clear of the body (a dragon's sits at its 8-block hitbox, 4 blocks
  // over the model) is not, so the body still fills the view; orbit or zoom
  // out to see it.
  final double bodyTop = McBounds.of(frame).max.y;
  for (final NpcText text in texts) {
    if (text.anchor.y > bodyTop + mcFramedTextGap) continue;
    frame.add(text.anchor + McVec3(0, text.lines.length * 0.25, 0));
  }
  return NpcScene(scene: McScene(nodes, frame: frame), texts: texts, notes: notes, presentable: true);
}

List<McSceneNode> _armorLayers(NpcLook look, McArmorPiece armor, NpcItem item, McRigPose pose, McMat4 root, {required bool small}) {
  final bool stand = look.type == 'minecraft:armor_stand';
  McRig layer = mcHumanoidArmorRig(
    armor.layer,
    scale: stand ? 1 : 0.9375,
    headPivotY: stand ? 1 : 0,
    legPivotY: stand ? 11 : 12,
  );
  if (small) layer = mcSmallTransform(layer);
  final String key = 'equipment:${armor.slot.key}';
  return <McSceneNode>[
    McRigNode(
      key: key,
      rig: layer,
      pose: pose,
      textureId: armor.texture,
      transform: root,
      tint: armor.dyeable ? _rgb(item.dyedColor ?? mcLeatherColor) : const <double>[1, 1, 1],
      glint: item.glint,
    ),
    if (armor.dyeable) McRigNode(key: '$key:overlay', rig: layer, pose: pose, textureId: armor.overlayTexture, transform: root, glint: item.glint),
  ];
}

/// `ItemInHandLayer`: the arm's transform, `Rx(-90) * Ry(180)`, then one
/// pixel out, two down and ten forward (`useBabyOffset`: 0, 1, 4.5).
McItemNode _heldItem(
  NpcLook look,
  Map<String, McMat4> parts,
  McMat4 model,
  NpcSlot slot,
  NpcItem item, {
  required bool small,
  required bool slim,
}) {
  final bool left = slot == NpcSlot.offHand;
  McMat4 arm = parts[left ? 'left_arm' : 'right_arm'] ?? McMat4.identity();
  // `PlayerModel.translateToHand`: a slim arm's hand sits half a pixel out.
  if (look.isPlayer && slim) arm = McMat4.translation((left ? -0.5 : 0.5) / 16, 0, 0).multiply(arm);
  final double x = small ? 0 : 1, y = small ? 1 : 2, z = small ? -4.5 : -10;
  final McMat4 hand = arm
      .multiply(McMat4.rotationX(-90))
      .multiply(McMat4.rotationY(180))
      .multiply(McMat4.translation((left ? -x : x) / 16, y / 16, z / 16));
  return McItemNode(
    key: 'equipment:${slot.key}',
    itemId: item.modelKey,
    pose: left ? McDisplayPose.thirdPersonLeftHand : McDisplayPose.thirdPersonRightHand,
    leftHand: left,
    transform: model.multiply(hand),
    glint: item.glint,
  );
}

/// `CustomHeadLayer`: the head's transform, a quarter block up, turned to
/// face forward and scaled 0.625, at the `head` display pose.
McItemNode _headItem(Map<String, McMat4> parts, McMat4 model, NpcItem item) {
  final McMat4 head = (parts['head'] ?? McMat4.identity())
      .multiply(McMat4.translation(0, -0.25, 0))
      .multiply(McMat4.rotationY(180))
      .multiply(McMat4.scale(0.625, -0.625, -0.625));
  return McItemNode(key: 'equipment:head', itemId: item.modelKey, pose: McDisplayPose.head, transform: model.multiply(head), glint: item.glint);
}

/// The mesh's distinct corners in world space, the points the camera frames.
List<McVec3> _framePoints(McMesh mesh, McMat4 transform) {
  final Set<McVec3> points = <McVec3>{};
  for (int v = 0; v < mesh.vertexCount; v++) {
    points.add(transform.transformPoint(McVec3(mesh.positions[v * 3], mesh.positions[v * 3 + 1], mesh.positions[v * 3 + 2])));
  }
  return points.toList();
}
