/// An NPC definition as a scene: the body rig in its pose, its equipment as
/// armour layers and held items, and the text the client draws above it.
/// The body stands at the origin turned to its definition yaw.
library;

import 'dart:math' as math;

import '../../logic/gui/gui_text.dart';
import '../../logic/item_ids.dart' show itemName;
import '../../logic/npc/npc_appearance.dart' show bodyLabel;
import '../assets/mc_display_pose.dart';
import '../mesh/mc_mesh.dart';
import '../rigs/mc_entity_rigs.dart';
import '../rigs/mc_mob_bodies.dart';
import '../rigs/mc_player_rig.dart';
import '../rigs/mc_rig.dart';
import '../rigs/mc_rig_mesher.dart';
import '../rigs/mc_rigs.dart';
import '../scene/mc_camera.dart';
import '../scene/mc_math.dart';
import '../scene/mc_occlusion.dart';
import '../scene/mc_scene.dart';
import 'mc_text.dart';
import 'npc_look.dart';
import 'npc_objects.dart';
import 'npc_pose.dart';

/// Leather's undyed colour (`DyedItemColor.LEATHER_COLOR`).
const int mcLeatherColor = 0xA06540;

/// Text more than this far above the body's mesh is left out of the home
/// framing, blocks.
const double mcFramedTextGap = 1.0;

/// How a text display lines up its lines against the widest one.
enum McTextAlign { center, left, right }

/// Text the client draws in world space, as the server lays it out.
final class NpcText {
  const NpcText({
    required this.anchor,
    required this.lines,
    required this.nameplate,
    this.styled,
    this.dim = false,
    this.lineWidth = 200,
    this.background,
    this.shadow = false,
    this.alignment = McTextAlign.center,
    this.opacity = 1,
    this.scaleX = 1,
    this.scaleY = 1,
    this.brightness,
    this.keepInside = false,
    this.yields = false,
    this.nearOnly = false,
    this.occluder,
    this.placed = false,
  });

  final McTextAlign alignment;

  /// How opaque the glyphs are, 0 to 1; the background keeps its own alpha.
  final double opacity;

  /// A text display's scale across and up.
  final double scaleX;
  final double scaleY;

  /// How bright the text is drawn, 0 to 1, from a display's fixed block and
  /// sky light; null for full light.
  final double? brightness;

  /// The stage shifts this text, and wraps it to the stage's width, so it
  /// never leaves the stage: a speech bubble must stay readable at any pan.
  final bool keepInside;

  /// An editor label (another NPC's name, a route point's number): drawn
  /// after the subject's text, never moved, and left out where it would
  /// cover a text already placed or the subject.
  final bool yields;

  /// Drawn only while its anchor is near the camera ([mcNearTextReach]).
  final bool nearOnly;

  /// The neighbour this name belongs to: hidden with its body when the
  /// stage leaves that body out ([mcCulled]).
  final McOccluder? occluder;

  /// Placed by its scene on purpose (a menu's components, a hologram's
  /// lines): the stage draws it exactly where it projects, never clamps or
  /// moves it, and other texts keep clear of it.
  final bool placed;

  /// A sneaking player's nameplate: the client draws it faded and hides it
  /// behind blocks.
  final bool dim;

  /// The text display's wrap width in font pixels.
  final int lineWidth;

  /// A text display's `#AARRGGBB` background, or null for the default.
  final String? background;

  /// A text display's drop shadow.
  final bool shadow;

  /// Bottom centre, world blocks: text grows upward from here.
  final McVec3 anchor;

  /// Section-sign formatted lines of one text display, top first: a style
  /// carries on down them as the client draws it.
  final List<String> lines;

  /// Drawn as a name tag (the client's own player nameplate, or an editor
  /// label) rather than a text display.
  final bool nameplate;

  /// Rows already styled (GUI text with sample values, or lines that are
  /// displays of their own); when set, the stage draws these instead of
  /// reading [lines].
  final List<List<McTextRun>>? styled;

  /// The same text with its anchor moved [by].
  NpcText moved(McVec3 by) => NpcText(
        anchor: anchor + by,
        lines: lines,
        nameplate: nameplate,
        styled: styled,
        dim: dim,
        lineWidth: lineWidth,
        background: background,
        shadow: shadow,
        alignment: alignment,
        opacity: opacity,
        scaleX: scaleX,
        scaleY: scaleY,
        brightness: brightness,
        keepInside: keepInside,
        yields: yields,
        nearOnly: nearOnly,
        occluder: occluder,
        placed: placed,
      );
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

/// Mob heads and skulls, which the client draws with `SkullBlockRenderer`.
bool mcSkullItem(NpcItem item) => item.key.endsWith('_head') || item.key.endsWith('_skull');

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
  final NpcScene drawn = _bodyScene(look, skin);
  final String? disguise = look.disguise;
  final String? named = disguise == null ? null : NpcLook.disguiseType(disguise);
  final List<String> notes = <String>[
    if (disguise != null && named == null) 'The disguise $disguise names no vanilla body; the preview draws the NPC\'s own body and LibsDisguises shows the disguise in game.',
    if (named != null) 'The preview draws the ${bodyLabel(named).toLowerCase()} body the disguise names; LibsDisguises options show in game only.',
    if (look.baby) 'The preview draws the grown body; the server shows the baby.',
  ];
  if (notes.isEmpty) return drawn;
  return NpcScene(scene: drawn.scene, texts: drawn.texts, notes: <String>[...notes, ...drawn.notes], presentable: drawn.presentable);
}

NpcScene _bodyScene(NpcLook look, NpcSkinChoice skin) {
  final List<McSceneNode> nodes = <McSceneNode>[];
  final List<String> notes = <String>[];
  final bool small = look.armorStand?.small ?? false;
  final McRig? rig = mcBodyRig(look.type, slim: skin.slim, small: small);
  if (rig == null) {
    final NpcObject? object = npcObject(look);
    if (object != null) return _objectScene(look, object);
    final McMobBody? mob = mcMobBody(look.type, look.settings);
    if (mob != null) return _mobScene(look, mob);
  }
  if (rig == null) {
    return NpcScene(
      scene: McScene.empty,
      texts: const <NpcText>[],
      notes: <String>[
        '${look.type.isEmpty ? 'This NPC names no body' : '${bodyLabel(look.type)} is not a body Animus shows'}. Pick a body from the list.',
      ],
      presentable: false,
    );
  }

  final bool avatar = look.isPlayer || look.type == mcMannequin;
  final McBodyPose body = switch (look.type) {
    'minecraft:player' || mcMannequin => mcPlayerPose(
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
  final double scale = look.scale;
  final McMat4 root = McMat4.rotationY(-look.yawDeg).multiply(McMat4.scale(scale, scale, scale)).multiply(body.frame);
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
      textureId: look.isPlayer ? skin.textureId : mcSettingsTexture(look.type, look.settings)!,
      transform: root,
    ));
    final String? markings = look.type == 'minecraft:horse' ? mcHorseMarkingsTexture(look.settings) : null;
    if (markings != null) nodes.add(McRigNode(key: 'markings', rig: rig, pose: body.rig, textureId: markings, transform: root));
  }
  if (look.type == 'minecraft:llama' && look.settings['chest'] == true) notes.add('The preview does not draw the llama\'s chest.');
  if (look.type == 'minecraft:armadillo' && look.settings['state'] is String && look.settings['state'] != 'idle') {
    notes.add('The armadillo shows ${'${look.settings['state']}'.replaceAll('_', ' ')} in game; the preview draws it idle.');
  }
  if (look.type == 'minecraft:ender_dragon' && look.settings['phase'] is String) {
    notes.add('The dragon\'s ${'${look.settings['phase']}'.replaceAll('_', ' ')} phase shows in game while it rests; the preview does not animate it.');
  }

  final bool humanoid = avatar || look.type == 'minecraft:armor_stand';
  for (final MapEntry<NpcSlot, NpcItem> entry in look.equipment.entries) {
    final NpcSlot slot = entry.key;
    final NpcItem item = entry.value;
    final String where = itemName(item.id);
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
      if (slot == NpcSlot.head && mcSkullItem(item)) {
        notes.add('$where is worn on the head; the preview does not draw heads.');
        continue;
      }
      if (slot == NpcSlot.head) {
        nodes.add(_headItem(parts, model, item));
        continue;
      }
      if (slot == NpcSlot.chest && item.key == 'elytra') {
        notes.add('$where is worn on the back in game; the preview does not draw elytra.');
        continue;
      }
      if (slot == NpcSlot.body) {
        notes.add('$where is not drawn: ${avatar ? 'players' : 'armor stands'} do not show the body slot.');
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
    notes.add('$where is not drawn on ${bodyLabel(look.type).toLowerCase()} bodies.');
  }

  final double feet = (look.isPlayer && look.pose == NpcPose.sitting ? -mcPlayerVehicleAttachment : 0) * scale;
  return _finish(look, nodes, notes, _drawnPoints(_framePoints(mcMeshRig(rig, pose: body.rig), root), nodes), feet: feet, top: feet + body.heightBlocks * scale);
}

/// The name and hologram over [top], the framing and the glow.
NpcScene _finish(NpcLook look, List<McSceneNode> nodes, List<String> notes, List<McVec3> frame, {required double feet, required double top}) {
  final List<NpcText> texts = <NpcText>[
    if (look.nativeName)
      NpcText(
        anchor: McVec3(0, top + 0.25, 0),
        lines: <String>[look.name],
        nameplate: true,
        dim: look.isPlayer && look.pose == NpcPose.sneaking,
      ),
    if (look.textLines.isNotEmpty) _lookText(look, McVec3(0, top + (look.nativeName ? 0.55 : 0.3), 0)),
  ];
  return _framed(look, nodes, texts, notes, frame, feet: feet, top: top);
}

/// The hologram lines and name over a body, drawn as [NpcLook.textRows].
NpcText _lookText(NpcLook look, McVec3 anchor) => NpcText(anchor: anchor, lines: look.textLines, nameplate: false, styled: look.textRows);

NpcScene _framed(NpcLook look, List<McSceneNode> nodes, List<NpcText> texts, List<String> notes, List<McVec3> frame, {required double feet, required double top}) {
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
  final int? glowColor = look.glowColor;
  final McGlow? glow = glowColor == null ? null : McGlow(glowColor, <String>{for (final McSceneNode node in nodes) node.key});
  return NpcScene(scene: McScene(nodes, frame: frame, glow: glow), texts: texts, notes: notes, presentable: true);
}

/// A mob built from the client's layers: each layer over the same pose, the
/// blocks it carries, then what it wears.
NpcScene _mobScene(NpcLook look, McMobBody mob) {
  final double scale = look.scale;
  McMat4 root = McMat4.rotationY(-look.yawDeg).multiply(McMat4.scale(scale, scale, scale));
  if (mob.frame != null) root = root.multiply(mob.frame!);
  McRig rigOf(String layer) => mcMobRig(layer, scale: mob.scale, offsetPx: mob.offsetPx, heightBlocks: mob.heightBlocks);
  final List<McSceneNode> nodes = <McSceneNode>[];
  final List<String> notes = <String>[...mob.notes];
  final McMobLayer first = mob.layers.first;
  final McRig rig = rigOf(first.layer);
  final McRigPose pose = mcMobPose(first.layer, first.poses);
  for (int i = 0; i < mob.layers.length; i++) {
    final McMobLayer layer = mob.layers[i];
    nodes.add(McRigNode(
      key: i == 0 ? 'body' : 'body:$i',
      rig: i == 0 ? rig : rigOf(layer.layer),
      pose: i == 0 ? pose : mcMobPose(layer.layer, layer.poses),
      textureId: layer.texture,
      transform: root,
      tint: layer.tint,
      opacity: layer.opacity,
    ));
  }
  final McMat4 model = root.multiply(mcRigModelToWorld(rig));
  final Map<String, McMat4> parts = mcRigPartMatrices(rig, pose);
  for (int i = 0; i < mob.blocks.length; i++) {
    final McMobBlock block = mob.blocks[i];
    final McMat4 at = block.part == null ? McMat4.identity() : (parts[block.part] ?? McMat4.identity());
    nodes.add(McBlockNode(key: 'body:block:$i', blockKey: block.blockKey, transform: model.multiply(at).multiply(block.transform)));
  }

  for (final MapEntry<NpcSlot, NpcItem> entry in look.equipment.entries) {
    final NpcSlot slot = entry.key;
    final NpcItem item = entry.value;
    final String where = itemName(item.id);
    final String? armor = mob.armor;
    if (armor != null) {
      if (slot == NpcSlot.mainHand || slot == NpcSlot.offHand) {
        nodes.add(_heldItem(look, parts, model, slot, item, small: false, slim: false));
        continue;
      }
      final McArmorPiece? piece = mcArmorPiece(item);
      if (piece != null && piece.slot == slot) {
        final String layer = '$armor#${const <NpcSlot, String>{NpcSlot.head: 'helmet', NpcSlot.chest: 'chestplate', NpcSlot.legs: 'leggings', NpcSlot.feet: 'boots'}[slot]}';
        final McRig worn = rigOf(layer);
        final McRigPose wornPose = mcMobPose(layer, first.poses);
        final String key = 'equipment:${slot.key}';
        nodes.add(McRigNode(
          key: key,
          rig: worn,
          pose: wornPose,
          textureId: piece.texture,
          transform: root,
          tint: piece.dyeable ? _rgb(item.dyedColor ?? mcLeatherColor) : const <double>[1, 1, 1],
          glint: item.glint,
        ));
        if (piece.dyeable) nodes.add(McRigNode(key: '$key:overlay', rig: worn, pose: wornPose, textureId: piece.overlayTexture, transform: root, glint: item.glint));
        continue;
      }
      if (slot == NpcSlot.head && mcSkullItem(item)) {
        notes.add('$where is worn on the head; the preview does not draw heads.');
        continue;
      }
      if (slot == NpcSlot.head) {
        nodes.add(_headItem(parts, model, item));
        continue;
      }
    }
    final (String, String)? saddle = mcMobSaddles[look.type];
    if (slot == NpcSlot.saddle && saddle != null && item.key == 'saddle') {
      nodes.add(McRigNode(
        key: 'equipment:saddle',
        rig: rigOf(saddle.$1),
        pose: mcMobPose(saddle.$1, first.poses),
        textureId: saddle.$2,
        transform: root,
        glint: item.glint,
      ));
      continue;
    }
    final (String, String, List<double>)? worn = slot == NpcSlot.body ? mcMobBodyArmor(look.type, item.key, item.dyedColor) : null;
    if (worn != null) {
      nodes.add(McRigNode(key: 'equipment:body', rig: rigOf(worn.$1), pose: mcMobPose(worn.$1, first.poses), textureId: worn.$2, transform: root, tint: worn.$3, glint: item.glint));
      continue;
    }
    notes.add('$where is not drawn on ${bodyLabel(look.type).toLowerCase()} bodies.');
  }
  return _finish(look, nodes, notes, _drawnPoints(_framePoints(mcMeshRig(rig, pose: pose), root), nodes), feet: 0, top: mob.heightBlocks * scale);
}

/// An object body: its own nodes and text, and the name over its top.
NpcScene _objectScene(NpcLook look, NpcObject object) {
  final List<McVec3> frame = <McVec3>[...object.frame];
  final double top = frame.isEmpty ? 0.5 : McBounds.of(frame).max.y;
  final List<NpcText> texts = <NpcText>[
    ...object.texts,
    if (look.textLines.isNotEmpty) _lookText(look, McVec3(0, top + 0.3, 0)),
  ];
  return _framed(look, object.nodes, texts, <String>[...object.notes], frame, feet: 0, top: top);
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

/// How far round its attachment point a held or worn item is framed,
/// blocks: about half a hand-held sword or block as the client draws it.
const double _itemReach = 0.45;

/// [body] (the body's own points) and those of everything else it draws:
/// armour layers and saddles by their meshes, held and worn items as a
/// cube round their attachment point. The camera frames what is drawn, so
/// a helmet, a base plate or a sword never leaves the view.
List<McVec3> _drawnPoints(List<McVec3> body, List<McSceneNode> nodes) {
  final Set<McVec3> points = <McVec3>{...body};
  for (final McSceneNode node in nodes) {
    if (node.key == 'body') continue;
    if (node is McRigNode) points.addAll(_framePoints(mcMeshRig(node.rig, pose: node.pose), node.transform));
    if (node is McItemNode) {
      for (final double x in <double>[-_itemReach, _itemReach]) {
        for (final double y in <double>[-_itemReach, _itemReach]) {
          for (final double z in <double>[-_itemReach, _itemReach]) {
            points.add(node.transform.transformPoint(McVec3(x, y, z)));
          }
        }
      }
    }
  }
  return points.toList();
}

/// The mesh's distinct corners in world space, the points the camera frames.
List<McVec3> _framePoints(McMesh mesh, McMat4 transform) {
  final Set<McVec3> points = <McVec3>{};
  for (int v = 0; v < mesh.vertexCount; v++) {
    points.add(transform.transformPoint(McVec3(mesh.positions[v * 3], mesh.positions[v * 3 + 1], mesh.positions[v * 3 + 2])));
  }
  return points.toList();
}

/// How wide the text layer draws [line] (legacy codes allowed) at
/// [fontPx]: the client's glyphs advance 6 font pixels (7 bold) of 8 to the
/// em. An estimate; thin glyphs make it a little wide.
double mcTextLineWidthPx(String line, double fontPx) {
  double units = 0;
  for (final McTextRun run in guiSectionRuns(line)) {
    units += run.text.length * (run.bold ? 7 : 6);
  }
  return units / 8 * fontPx;
}

/// [home] moved back until every framed text block (its top is one of the
/// [frame] points) clears the top of a [widthPx] x [heightPx] view by the
/// framing margin and its widest line fits the view without wrapping, at
/// the size the stage draws it ([mcCrispFontPx]). The font never goes below
/// its floor, so over a large body a name is taller than the quarter block a
/// line takes in the world, and a long name is wider than a phone stage: the
/// points alone let it run off the top or wrap over the head.
McCamera mcFrameTexts(McCamera home, List<NpcText> texts, List<McVec3> frame, {required double widthPx, required double heightPx}) {
  if (widthPx <= 0 || heightPx <= 0) return home;
  // One pixel of slack: a name at its world size sits on the margin already.
  final double margin = heightPx * (1 - mcFramingFill) / 2 - 1;
  final double room = widthPx - 2 * mcTextInsetPx;
  final double floor = mcCrispFontPx(mcTextMinFontPx);
  final List<NpcText> framed = <NpcText>[
    for (final NpcText text in texts)
      if (frame.contains(text.anchor + McVec3(0, text.lines.length * 0.25, 0))) text,
  ];
  bool clear(McCamera camera) {
    for (final NpcText text in framed) {
      final McVec3? at = mcProjectToPixels(camera, text.anchor, widthPx: widthPx, heightPx: heightPx);
      if (at == null) continue;
      final double font = mcCrispFontPx(mcTextFontPx(heightPx, at.z) * text.scaleY);
      final double wrap = math.min(font * text.lineWidth / 8, room);
      int lines = 0;
      double widest = 0;
      for (final String line in text.lines) {
        final double width = mcTextLineWidthPx(line, font) * text.scaleX / text.scaleY;
        widest = math.max(widest, width);
        lines += math.max(1, (width / wrap).ceil());
      }
      if (widest > room && font > floor) return false;
      if (at.y - lines * 1.25 * font < margin) return false;
    }
    return true;
  }

  McCamera camera = home;
  for (int step = 0; step < 32 && !clear(camera); step++) {
    camera = camera.copyWith(distance: camera.distance * 1.08).clamped();
  }
  return camera;
}

/// The stage's home view of [frame] in a [widthPx] x [heightPx] stage
/// (4:3 until it is measured): from the front of a body turned [yawDeg], a
/// little from above, every framed point inside the view, then moved back
/// until the framed [texts] fit ([mcFrameTexts]).
McCamera mcHomeCamera(List<McVec3> frame, List<NpcText> texts, {required double yawDeg, required double widthPx, required double heightPx, McBounds? reach}) =>
    mcFrameTexts(
      mcCameraFraming(frame, yawDeg: yawDeg + 180, aspect: heightPx > 0 ? widthPx / heightPx : 4 / 3, reach: reach),
      texts,
      frame,
      widthPx: widthPx,
      heightPx: heightPx,
    );

/// Roughly where the subject's own texts (nameplate, hologram, speech) hang:
/// each grows up from its anchor, a line ten font pixels tall and a glyph
/// about six wide, forty font pixels to a block. Null without any.
McBounds? npcPlates(List<NpcText> texts) {
  double minX = double.infinity, minY = double.infinity, minZ = double.infinity;
  double maxX = -double.infinity, maxY = -double.infinity, maxZ = -double.infinity;
  for (final NpcText text in texts) {
    if (text.yields || text.occluder != null) continue;
    final List<String> rows = text.styled == null
        ? <String>[for (final String line in text.lines) guiPlainText(line)]
        : <String>[for (final List<McTextRun> row in text.styled!) row.map((McTextRun run) => run.text).join()];
    if (rows.isEmpty) continue;
    final int widest = rows.map((String row) => row.length).reduce(math.max);
    final double half = widest * 3 / 40 * text.scaleX, high = rows.length * 10 / 40 * text.scaleY;
    minX = math.min(minX, text.anchor.x - half);
    maxX = math.max(maxX, text.anchor.x + half);
    minZ = math.min(minZ, text.anchor.z - half);
    maxZ = math.max(maxZ, text.anchor.z + half);
    minY = math.min(minY, text.anchor.y);
    maxY = math.max(maxY, text.anchor.y + high);
  }
  return minX > maxX ? null : McBounds(McVec3(minX, minY, minZ), McVec3(maxX, maxY, maxZ));
}
