import 'dart:convert';
import 'dart:io';

import 'package:animus_editor/mc/assets/mc_display_pose.dart';
import 'package:animus_editor/mc/npc/mc_text.dart';
import 'package:animus_editor/mc/npc/npc_look.dart';
import 'package:animus_editor/mc/npc/npc_scene.dart';
import 'package:animus_editor/mc/npc/npc_skin.dart';
import 'package:animus_editor/mc/rigs/mc_player_rig.dart';
import 'package:animus_editor/mc/rigs/mc_rigs.dart';
import 'package:animus_editor/mc/scene/mc_camera.dart';
import 'package:animus_editor/mc/scene/mc_math.dart';
import 'package:animus_editor/mc/scene/mc_scene.dart';
import 'package:test/test.dart';

/// The browser check's sample data folder.
Map<String, Object?> sample(String path) =>
    jsonDecode(File('test/fixtures/npc_preview/$path').readAsStringSync()) as Map<String, Object?>;

NpcScene sceneOf(String path, {NpcSkinChoice skin = NpcSkinChoice.steve}) => npcScene(NpcLook.fromDefinition(sample(path)), skin: skin);

Map<String, McSceneNode> byKey(NpcScene scene) => scene.scene.byKey;

void main() {
  group('definition', () {
    test('reads equipment, text, pose and skin the way the server does', () {
      final NpcLook look = NpcLook.fromDefinition(sample('npcs/players/hero.json'));
      expect(look.isPlayer, isTrue);
      expect(look.equipment.keys, NpcSlot.values);
      expect(look.equipment[NpcSlot.mainHand]!.glint, isTrue);
      expect(look.equipment[NpcSlot.chest]!.glint, isTrue);
      expect(look.equipment[NpcSlot.legs]!.dyedColor, 3361970);
      expect(look.skin, hasLength(64));
      expect(look.yawDeg, 30);
      expect(look.nativeName, isTrue);
      expect(look.textLines, <String>['§6§lQuest Giver', '§7Right click to talk']);
    });

    test('refused values fall back: bad items, poses on other bodies, long lines', () {
      final NpcLook look = NpcLook.fromDefinition(<String, Object?>{
        'type': 'minecraft:horse',
        'name': 'Horse',
        'pose': 'sitting',
        'equipment': <String, Object?>{
          'main_hand': <String, Object?>{'id': 'not an id'},
          'tail': <String, Object?>{'id': 'minecraft:stick'},
          'saddle': <String, Object?>{'id': 'minecraft:saddle', 'components': 'no brackets'},
        },
        'hologram': <String, Object?>{'lines': <Object?>['ok', 'x' * 257, 'bad\u0007', 7]},
      });
      expect(look.pose, NpcPose.standing);
      expect(look.equipment.keys, <NpcSlot>[NpcSlot.saddle]);
      expect(look.equipment[NpcSlot.saddle]!.components, isNull);
      expect(look.hologram, <String>['ok']);
    });

    test('names that are long or formatted ride the text display, not the nameplate', () {
      final NpcLook long = NpcLook.fromDefinition(sample('npcs/players/merchant.json'));
      expect(long.nativeName, isFalse);
      expect(long.textLines, <String>['§6The Very Long Named Merchant']);
      final NpcLook hidden = NpcLook.fromDefinition(<String, Object?>{'type': 'minecraft:player', 'name': 'Quiet', 'nameplate': <String, Object?>{'visible': false}});
      expect(hidden.nativeName, isFalse);
      expect(hidden.textLines, isEmpty);
      final NpcLook horse = NpcLook.fromDefinition(<String, Object?>{'type': 'minecraft:horse', 'name': 'Steed'});
      expect(horse.nativeName, isFalse);
      expect(horse.textLines, <String>['Steed']);
    });

    test('components: item_model, glint override, nested values', () {
      const NpcItem item = NpcItem(
        id: 'minecraft:stick',
        components: '[custom_name="a, b",item_model="minecraft:blaze_rod",enchantments={sharpness:1},enchantment_glint_override=false]',
      );
      expect(item.modelKey, 'blaze_rod');
      expect(item.glint, isFalse);
      expect(item.component('custom_name'), '"a, b"');
      expect(const NpcItem(id: 'minecraft:leather_boots', components: '[dyed_color={rgb:255}]').dyedColor, 255);
      expect(const NpcItem(id: 'minecraft:stick').glint, isFalse);
    });

    test('armor stand flags and angles', () {
      final NpcLook look = NpcLook.fromDefinition(sample('npcs/stands/posed.json'));
      final NpcArmorStand stand = look.armorStand!;
      expect(stand.arms, isTrue);
      expect(stand.angles['right_arm'], <double>[-90, 0, 10]);
      expect(stand.angles['left_leg'], <double>[20, 0, -5]);
      expect(NpcArmorStand.parse(<String, Object?>{'head': <Object?>[1, 2, 999]}).angles, isEmpty);
    });

    test('a skin document yields its arm model and texture address', () {
      final Directory skins = Directory('test/fixtures/npc_preview/skins');
      final File file = skins.listSync().whereType<File>().single;
      final NpcSkinInfo info = NpcSkinInfo.fromDocument(jsonDecode(file.readAsStringSync()))!;
      expect(info.slim, isTrue);
      expect(info.textureUrl, startsWith('http://textures.minecraft.net/texture/'));
      expect(info.signed, isFalse);
      expect(NpcSkinInfo.fromDocument(<String, Object?>{'textureRaw': '!!'}), isNull);
    });
  });

  group('scene', () {
    test('the hero wears every humanoid slot and says what it cannot draw', () {
      final NpcScene scene = sceneOf('npcs/players/hero.json', skin: NpcSkinChoice.alex);
      final Map<String, McSceneNode> nodes = byKey(scene);
      expect(nodes.keys, containsAll(<String>[
        'body',
        'equipment:main_hand',
        'equipment:off_hand',
        'equipment:head',
        'equipment:chest',
        'equipment:legs',
        'equipment:legs:overlay',
        'equipment:feet',
      ]));
      final McRigNode body = nodes['body']! as McRigNode;
      expect(body.textureId, mcAlexTexture);
      expect(body.rig.id, 'player_slim');
      expect((nodes['equipment:head']! as McRigNode).textureId, 'entity/equipment/humanoid/iron');
      expect((nodes['equipment:legs']! as McRigNode).textureId, 'entity/equipment/humanoid_leggings/leather');
      expect((nodes['equipment:legs']! as McRigNode).tint[0], closeTo(0x33 / 255, 1e-9));
      expect((nodes['equipment:feet']! as McRigNode).textureId, 'entity/equipment/humanoid/gold');
      expect(nodes['equipment:chest']!.glint, isTrue);
      final McItemNode main = nodes['equipment:main_hand']! as McItemNode;
      final McItemNode off = nodes['equipment:off_hand']! as McItemNode;
      expect(main.pose, McDisplayPose.thirdPersonRightHand);
      expect(off.leftHand, isTrue);
      expect(scene.notes, hasLength(2));
      expect(scene.notes.join(' '), allOf(contains('body: minecraft:white_carpet'), contains('saddle: minecraft:saddle')));
    });

    test('a held item sits at the hand, in front of the body, on its right', () {
      final NpcScene scene = npcScene(NpcLook.fromDefinition(<String, Object?>{
        'type': 'minecraft:player',
        'name': 'Smith',
        'equipment': <String, Object?>{'main_hand': <String, Object?>{'id': 'minecraft:iron_sword'}},
      }));
      final McVec3 hand = (byKey(scene)['equipment:main_hand']! as McItemNode).transform.transformPoint(McVec3.zero);
      // Facing +Z at yaw 0, the body's right is -X; the hand hangs at hip height.
      expect(hand.x, lessThan(-0.25));
      expect(hand.y, inInclusiveRange(0.6, 1.0));
      expect(hand.z, greaterThan(0));
    });

    test('text: the hologram rides above the native nameplate', () {
      final NpcScene scene = sceneOf('npcs/players/hero.json');
      expect(scene.texts, hasLength(2));
      final NpcText nameplate = scene.texts.firstWhere((NpcText text) => text.nameplate);
      final NpcText display = scene.texts.firstWhere((NpcText text) => !text.nameplate);
      expect(nameplate.lines, <String>['Hero']);
      expect(nameplate.anchor.y, closeTo(1.8 + 0.25, 1e-9));
      expect(display.anchor.y, closeTo(1.8 + 0.55, 1e-9));
      final NpcScene sitter = sceneOf('npcs/players/sitter.json');
      expect(sitter.texts.single.anchor.y, closeTo(-0.6 + 1.8 + 0.25, 1e-9));
      final NpcScene horse = sceneOf('npcs/animals/horse.json');
      expect(horse.texts.single.anchor.y, closeTo(1.6 + 0.3, 1e-9));
    });

    test('the horse wears its saddle and armour layers', () {
      final Map<String, McSceneNode> nodes = byKey(sceneOf('npcs/animals/horse.json'));
      expect((nodes['equipment:saddle']! as McRigNode).textureId, 'entity/equipment/horse_saddle/saddle');
      expect((nodes['equipment:saddle']! as McRigNode).pose.hidden, contains('left_saddle_line'));
      expect((nodes['equipment:body']! as McRigNode).textureId, 'entity/equipment/horse_body/diamond');
    });

    test('the llama wears its carpet; the armadillo shows nothing it cannot', () {
      expect((byKey(sceneOf('npcs/animals/llama.json'))['equipment:body']! as McRigNode).textureId, 'entity/equipment/llama_body/red');
      final NpcScene armadillo = npcScene(NpcLook.fromDefinition(<String, Object?>{
        'type': 'minecraft:armadillo',
        'name': 'Roller',
        'equipment': <String, Object?>{'head': <String, Object?>{'id': 'minecraft:iron_helmet'}},
      }));
      expect(byKey(armadillo).keys, <String>['body']);
      expect(armadillo.notes.single, contains('minecraft:armadillo'));
    });

    test('armor stands: armour on the stand pivots, invisible hides only the body', () {
      final Map<String, McSceneNode> posed = byKey(sceneOf('npcs/stands/posed.json'));
      expect((posed['equipment:head']! as McRigNode).rig.part('head')!.pivot, const McVec3(0, 1, 0));
      expect(posed.keys, contains('equipment:main_hand'));
      final Map<String, McSceneNode> invisible = byKey(npcScene(NpcLook.fromDefinition(<String, Object?>{
        'type': 'minecraft:armor_stand',
        'name': 'Ghost',
        'armorStand': <String, Object?>{'invisible': true},
        'equipment': <String, Object?>{'head': <String, Object?>{'id': 'minecraft:diamond_helmet'}},
      })));
      expect(invisible.keys, <String>['equipment:head']);
      final Map<String, McSceneNode> tiny = byKey(sceneOf('npcs/stands/tiny.json'));
      expect((tiny['body']! as McRigNode).rig.id, 'armor_stand_small');
      expect((tiny['equipment:head']! as McRigNode).rig.id, 'armor_head_small');
    });

    test('armour in the wrong slot is not drawn; a non-armour head item is', () {
      final NpcScene scene = npcScene(NpcLook.fromDefinition(<String, Object?>{
        'type': 'minecraft:player',
        'name': 'Odd',
        'equipment': <String, Object?>{
          'chest': <String, Object?>{'id': 'minecraft:iron_helmet'},
          'head': <String, Object?>{'id': 'minecraft:carved_pumpkin'},
        },
      }));
      expect(byKey(scene)['equipment:head'], isA<McItemNode>());
      expect(byKey(scene)['equipment:chest'], isNull);
      expect(scene.notes.single, contains('chest: minecraft:iron_helmet'));
    });

    test('every sample renders; an unknown type is not presentable', () {
      for (final File file in Directory('test/fixtures/npc_preview/npcs').listSync(recursive: true).whereType<File>()) {
        final NpcScene scene = npcScene(NpcLook.fromDefinition(jsonDecode(file.readAsStringSync()) as Map<String, Object?>));
        expect(scene.presentable, isTrue, reason: file.path);
        expect(scene.scene.nodes, isNotEmpty, reason: file.path);
        expect(scene.scene.frame, isNotEmpty, reason: file.path);
      }
      final NpcScene zombie = npcScene(NpcLook.fromDefinition(<String, Object?>{'type': 'minecraft:zombie', 'name': 'Z'}));
      expect(zombie.presentable, isFalse);
      expect(zombie.scene.nodes, isEmpty);
    });

    test('signatures change with what the mesh depends on', () {
      final McRigNode standing = byKey(npcScene(NpcLook.fromDefinition(<String, Object?>{'type': 'minecraft:player', 'name': 'A'})))['body']! as McRigNode;
      final McRigNode sneaking = byKey(npcScene(NpcLook.fromDefinition(<String, Object?>{'type': 'minecraft:player', 'name': 'A', 'pose': 'sneaking'})))['body']! as McRigNode;
      final McRigNode turned = byKey(npcScene(NpcLook.fromDefinition(<String, Object?>{
        'type': 'minecraft:player',
        'name': 'A',
        'position': <String, Object?>{'world': 'w', 'x': 0, 'y': 0, 'z': 0, 'yaw': 90, 'pitch': 0},
      })))['body']! as McRigNode;
      expect(sneaking.signature, isNot(standing.signature));
      expect(turned.signature, standing.signature);
      expect(turned.transform.m, isNot(standing.transform.m));
    });
  });

  group('armour slots', () {
    const Map<String, String> materials = <String, String>{
      'leather': 'leather',
      'chainmail': 'chainmail',
      'iron': 'iron',
      'golden': 'gold',
      'diamond': 'diamond',
      'netherite': 'netherite',
      'copper': 'copper',
    };
    const Map<String, (NpcSlot, McArmorLayer, Set<String>)> pieces = <String, (NpcSlot, McArmorLayer, Set<String>)>{
      'helmet': (NpcSlot.head, McArmorLayer.head, <String>{'head'}),
      'chestplate': (NpcSlot.chest, McArmorLayer.chest, <String>{'body', 'right_arm', 'left_arm'}),
      'leggings': (NpcSlot.legs, McArmorLayer.legs, <String>{'body', 'right_leg', 'left_leg'}),
      'boots': (NpcSlot.feet, McArmorLayer.feet, <String>{'right_leg', 'left_leg'}),
    };

    for (final String type in <String>['minecraft:player', 'minecraft:armor_stand']) {
      test('every material and slot on $type draws as its armour layer', () {
        for (final MapEntry<String, String> material in materials.entries) {
          for (final MapEntry<String, (NpcSlot, McArmorLayer, Set<String>)> piece in pieces.entries) {
            final (NpcSlot slot, McArmorLayer layer, Set<String> parts) = piece.value;
            final String id = 'minecraft:${material.key}_${piece.key}';
            final NpcScene scene = npcScene(NpcLook.fromDefinition(<String, Object?>{
              'type': type,
              'name': 'Wearer',
              'equipment': <String, Object?>{slot.key: <String, Object?>{'id': id, 'components': '[dyed_color=16711680]'}},
            }));
            final McSceneNode? node = byKey(scene)['equipment:${slot.key}'];
            expect(node, isA<McRigNode>(), reason: '$type $id');
            final McRigNode rig = node! as McRigNode;
            expect(rig.rig.id, 'armor_${layer.name}', reason: '$type $id');
            expect(rig.rig.allParts.where((part) => part.boxes.isNotEmpty).map((part) => part.name).toSet(), parts, reason: '$type $id');
            final String folder = slot == NpcSlot.legs ? 'humanoid_leggings' : 'humanoid';
            expect(rig.textureId, 'entity/equipment/$folder/${material.value}', reason: '$type $id');
            expect(mcEntityTextureIds, contains(rig.textureId));
            final bool leather = material.key == 'leather';
            expect(rig.tint, leather ? <double>[1, 0, 0] : <double>[1, 1, 1], reason: '$type $id');
            expect(byKey(scene).containsKey('equipment:${slot.key}:overlay'), leather, reason: '$type $id');
            if (leather) expect(mcEntityTextureIds, contains('${rig.textureId}_overlay'));
            expect(scene.scene.nodes.whereType<McItemNode>(), isEmpty, reason: '$type $id');
            expect(scene.notes, isEmpty, reason: '$type $id');
          }
        }
      });
    }

    test('the turtle helmet is head armour; boots in another slot are not drawn', () {
      final NpcScene turtle = npcScene(NpcLook.fromDefinition(<String, Object?>{
        'type': 'minecraft:player',
        'name': 'Diver',
        'equipment': <String, Object?>{
          'head': <String, Object?>{'id': 'minecraft:turtle_helmet'},
          'legs': <String, Object?>{'id': 'minecraft:iron_boots'},
        },
      }));
      expect((byKey(turtle)['equipment:head']! as McRigNode).textureId, 'entity/equipment/humanoid/turtle_scute');
      expect(byKey(turtle).containsKey('equipment:legs'), isFalse);
      expect(turtle.notes.single, contains('legs: minecraft:iron_boots'));
    });

    test('layers ride the body: the player layer scale and pivots, the stand its own', () {
      final McRigNode player = byKey(sceneOf('npcs/armour/leather.json'))['equipment:feet']! as McRigNode;
      final McRigNode stand = byKey(sceneOf('npcs/armour/leather-stand.json'))['equipment:feet']! as McRigNode;
      expect(player.rig.scale, 0.9375);
      expect(player.rig.part('right_leg')!.pivot.y, 12);
      expect(stand.rig.scale, 1);
      expect(stand.rig.part('right_leg')!.pivot.y, 11);
      final McRigNode small = byKey(sceneOf('npcs/armour/diamond-stand.json'))['equipment:feet']! as McRigNode;
      expect(small.rig.id, 'armor_feet_small');
    });
  });

  group('framing', () {
    /// The share of a 4:3 view the framed points span, on the tighter axis.
    double fill(NpcScene scene) {
      const double w = 800, h = 600;
      final McCamera camera = mcCameraFraming(scene.scene.frame, yawDeg: 180, aspect: w / h);
      double minX = double.infinity, maxX = -double.infinity, minY = double.infinity, maxY = -double.infinity;
      for (final McVec3 point in scene.scene.frame) {
        final McVec3 at = mcProjectToPixels(camera, point, widthPx: w, heightPx: h)!;
        expect(at.x, inInclusiveRange(-1, w + 1));
        expect(at.y, inInclusiveRange(-1, h + 1));
        minX = at.x < minX ? at.x : minX;
        maxX = at.x > maxX ? at.x : maxX;
        minY = at.y < minY ? at.y : minY;
        maxY = at.y > maxY ? at.y : maxY;
      }
      final double x = (maxX - minX) / w, y = (maxY - minY) / h;
      return x > y ? x : y;
    }

    test('text on the body is framed; text floating well above it is not', () {
      final NpcScene hero = sceneOf('npcs/players/hero.json');
      final double heroTop = hero.scene.frame.map((McVec3 p) => p.y).reduce((double a, double b) => a > b ? a : b);
      expect(heroTop, closeTo(1.8 + 0.55 + 2 * 0.25, 1e-9));
      final NpcScene dragon = sceneOf('npcs/animals/dragon.json');
      final double dragonTop = dragon.scene.frame.map((McVec3 p) => p.y).reduce((double a, double b) => a > b ? a : b);
      expect(dragon.texts.single.anchor.y, closeTo(8.3, 1e-9));
      expect(dragonTop, lessThan(8.3 - mcFramedTextGap));
    });

    test('every body fills the view about as much as the player does', () {
      final double player = fill(sceneOf('npcs/players/hero.json'));
      expect(player, greaterThan(0.6));
      for (final String path in <String>['npcs/animals/dragon.json', 'npcs/animals/horse.json', 'npcs/animals/turtle.json', 'npcs/stands/tiny.json']) {
        expect(fill(sceneOf(path)), closeTo(player, 0.15), reason: path);
      }
    });
  });

  group('legacy text', () {
    test('colour codes reset decorations; reset clears all', () {
      expect(mcLegacyRuns('§6§lQuest §7Giver§r!'), <McTextRun>[
        const McTextRun('Quest ', color: 0xFFAA00, bold: true),
        const McTextRun('Giver', color: 0xAAAAAA),
        const McTextRun('!'),
      ]);
      expect(mcLegacyRuns('plain'), <McTextRun>[const McTextRun('plain')]);
      expect(mcLegacyRuns('§x§'), <McTextRun>[const McTextRun('§')]);
      expect(const McTextRun('a', color: 0x55FF55).cssColor, '#55ff55');
    });
  });
}
