import 'package:animus_editor/mc/assets/mc_display_pose.dart';
import 'package:animus_editor/mc/mesh/mc_mesh.dart';
import 'package:animus_editor/mc/npc/npc_look.dart';
import 'package:animus_editor/mc/npc/npc_scene.dart';
import 'package:animus_editor/mc/rigs/mc_mob_bodies.dart';
import 'package:animus_editor/mc/rigs/mc_rig_mesher.dart';
import 'package:animus_editor/mc/rigs/mc_rigs.dart';
import 'package:animus_editor/mc/rigs/mc_vanilla_layers.g.dart';
import 'package:animus_editor/mc/scene/mc_camera.dart';
import 'package:animus_editor/mc/scene/mc_math.dart';
import 'package:animus_editor/mc/scene/mc_scene.dart';
import 'package:test/test.dart';

/// Settings a body needs before it shows anything.
const Map<String, Map<String, Object?>> _needs = <String, Map<String, Object?>>{
  'minecraft:block_display': <String, Object?>{'blockDisplay': <String, Object?>{'block': 'minecraft:oak_stairs[facing=east]'}},
  'minecraft:item_display': <String, Object?>{'itemDisplay': <String, Object?>{'item': <String, Object?>{'id': 'minecraft:diamond_sword'}}},
  'minecraft:text_display': <String, Object?>{'textDisplay': <String, Object?>{'text': '&aHello'}},
  'minecraft:item': <String, Object?>{'droppedItem': <String, Object?>{'item': <String, Object?>{'id': 'minecraft:apple'}}},
};

NpcScene scene(String type, [Map<String, Object?> extra = const <String, Object?>{}]) => npcScene(NpcLook.fromDefinition(<String, Object?>{
      'type': type,
      'name': 'Body',
      'nameplate': <String, Object?>{'visible': false},
      ...?_needs[type],
      ...extra,
    }));

Map<String, McSceneNode> nodes(NpcScene scene) => scene.scene.byKey;

McRigNode rigNode(NpcScene scene, [String key = 'body']) => nodes(scene)[key]! as McRigNode;

(McVec3, McVec3) worldBounds(McRigNode node) {
  final McMesh mesh = mcMeshRig(node.rig, pose: node.pose);
  double lx = 1e9, ly = 1e9, lz = 1e9, hx = -1e9, hy = -1e9, hz = -1e9;
  for (int v = 0; v < mesh.vertexCount; v++) {
    final McVec3 p = node.transform.transformPoint(McVec3(mesh.positions[v * 3], mesh.positions[v * 3 + 1], mesh.positions[v * 3 + 2]));
    if (p.x < lx) lx = p.x;
    if (p.y < ly) ly = p.y;
    if (p.z < lz) lz = p.z;
    if (p.x > hx) hx = p.x;
    if (p.y > hy) hy = p.y;
    if (p.z > hz) hz = p.z;
  }
  return (McVec3(lx, ly, lz), McVec3(hx, hy, hz));
}

void main() {
  group('every body a definition can name', () {
    test('draws a model, never a placeholder note', () {
      expect(mcPresentedBodyTypes, isNotEmpty);
      for (final String type in mcPresentedBodyTypes) {
        final NpcScene drawn = scene(type);
        expect(drawn.presentable, isTrue, reason: type);
        expect(drawn.scene.nodes.isNotEmpty || drawn.texts.isNotEmpty, isTrue, reason: type);
        expect(drawn.notes.where((String note) => note.contains('no model')), isEmpty, reason: type);
        expect(drawn.scene.frame, isNotEmpty, reason: type);
      }
    });

    test('every rig node meshes and binds a texture the pack carries', () {
      for (final String type in mcPresentedBodyTypes) {
        for (final McRigNode node in scene(type).scene.nodes.whereType<McRigNode>()) {
          expect(mcMeshRig(node.rig, pose: node.pose).triangleCount, greaterThan(0), reason: '$type ${node.key}');
          expect(mcEntityTextureIds, contains(node.textureId), reason: '$type ${node.key}');
        }
        for (final McModelNode node in scene(type).scene.nodes.whereType<McModelNode>()) {
          expect(mcEntityTextureIds, contains(node.textureId), reason: '$type ${node.key}');
        }
      }
    });

    test('walking mobs stand on the ground and reach about their hitbox height', () {
      const Set<String> floating = <String>{
        'minecraft:bat', 'minecraft:bee', 'minecraft:allay', 'minecraft:vex', 'minecraft:ghast', 'minecraft:happy_ghast', 'minecraft:phantom', //
        'minecraft:squid', 'minecraft:glow_squid', 'minecraft:blaze', 'minecraft:breeze', 'minecraft:pufferfish', 'minecraft:guardian', //
        'minecraft:elder_guardian', 'minecraft:end_crystal', 'minecraft:ender_dragon', 'minecraft:wither', 'minecraft:tadpole', //
        'minecraft:cod', 'minecraft:salmon', 'minecraft:tropical_fish', 'minecraft:dolphin', 'minecraft:nautilus', 'minecraft:zombie_nautilus',
      };
      for (final String type in mcMobBodyTypes) {
        if (floating.contains(type)) continue;
        final (McVec3 lo, McVec3 hi) = worldBounds(rigNode(scene(type)));
        expect(lo.y, closeTo(0, 0.13), reason: type);
        expect(hi.y, greaterThan(0.2), reason: type);
        expect(hi.y, lessThan(mcMobBody(type, const <String, Object?>{})!.heightBlocks * 1.6 + 0.2), reason: type);
      }
    });

    test('every layer and second pose a body names is generated', () {
      final Map<String, Map<String, Object?>> settings = <String, Map<String, Object?>>{
        'minecraft:cat': <String, Object?>{'sitting': true, 'lying': true, 'collar': 'red'},
        'minecraft:wolf': <String, Object?>{'sitting': true, 'interested': true, 'angry': true, 'collar': 'blue'},
        'minecraft:fox': <String, Object?>{'sitting': true, 'crouching': true, 'interested': true, 'pouncing': true, 'sleeping': true, 'faceplanted': true},
        'minecraft:panda': <String, Object?>{'sitting': true, 'rolling': true, 'sneezing': true, 'eating': true},
        'minecraft:parrot': <String, Object?>{'sitting': true},
        'minecraft:axolotl': <String, Object?>{'playingDead': true},
        'minecraft:goat': <String, Object?>{'leftHorn': 'hidden', 'rightHorn': 'hidden'},
        'minecraft:polar_bear': <String, Object?>{'standing': true},
        'minecraft:bee': <String, Object?>{'stung': true},
        'minecraft:vex': <String, Object?>{'charging': true},
        'minecraft:evoker': <String, Object?>{'spell': 'fangs'},
        'minecraft:bat': <String, Object?>{'resting': true},
        'minecraft:enderman': <String, Object?>{'angry': true},
        'minecraft:piglin': <String, Object?>{'dancing': true},
        'minecraft:allay': <String, Object?>{'dancing': true},
        'minecraft:camel': <String, Object?>{'sitting': true, 'dashing': true},
        'minecraft:donkey': <String, Object?>{'chest': true},
        'minecraft:mule': <String, Object?>{'chest': true},
      };
      for (final String type in mcMobBodyTypes) {
        final McMobBody body = mcMobBody(type, settings[type] ?? const <String, Object?>{})!;
        for (final McMobLayer layer in body.layers) {
          expect(mcVanillaLayers, contains(layer.layer), reason: type);
          for (final String pose in layer.poses) {
            if (pose == 'angry' && layer.layer.startsWith('wolf')) continue;
            expect(mcVanillaLayerPoses, contains('${layer.layer}@$pose'), reason: type);
          }
        }
      }
      for (final (String, String) saddle in mcMobSaddles.values) {
        expect(mcVanillaLayers, contains(saddle.$1));
      }
    });
  });

  group('body settings', () {
    test('a sheep wears its wool colour, and sheared shows none', () {
      final NpcScene red = scene('minecraft:sheep', <String, Object?>{'sheep': <String, Object?>{'color': 'red'}});
      expect(rigNode(red, 'body:2').textureId, 'entity/sheep/sheep_wool');
      expect(rigNode(red, 'body:2').tint, mcWoolTint('red'));
      expect(rigNode(red, 'body:1').textureId, 'entity/sheep/sheep_wool_undercoat');
      final NpcScene shorn = scene('minecraft:sheep', <String, Object?>{'sheep': <String, Object?>{'sheared': true}});
      expect(shorn.scene.nodes.whereType<McRigNode>().map((McRigNode node) => node.textureId), isNot(contains('entity/sheep/sheep_wool')));
      expect(mcWoolTint('white'), mcRgb(0xE6E6E6));
    });

    test('slime and phantom sizes scale the body and its name', () {
      final (McVec3 _, McVec3 small) = worldBounds(rigNode(scene('minecraft:slime', <String, Object?>{'slime': <String, Object?>{'size': 1}})));
      final (McVec3 _, McVec3 big) = worldBounds(rigNode(scene('minecraft:slime', <String, Object?>{'slime': <String, Object?>{'size': 4}})));
      expect(big.y, closeTo(small.y * 4, 0.05));
      final NpcScene phantom = scene('minecraft:phantom', <String, Object?>{'phantom': <String, Object?>{'size': 10}});
      expect(rigNode(phantom).rig.id, contains('*2.5'));
    });

    test('a tropical fish picks its shape, pattern and two colours', () {
      final NpcScene fish = scene('minecraft:tropical_fish', <String, Object?>{
        'tropicalFish': <String, Object?>{'pattern': 'glitter', 'baseColor': 'blue', 'patternColor': 'yellow'},
      });
      expect(rigNode(fish).rig.id, startsWith('tropical_fish_large#main'));
      expect(rigNode(fish).tint, mcDyeTint('blue'));
      expect(rigNode(fish, 'body:1').textureId, 'entity/fish/tropical_b_pattern_3');
      expect(rigNode(fish, 'body:1').tint, mcDyeTint('yellow'));
    });

    test('a villager wears its biome, job and badge; a full hat hides the biome hat', () {
      final NpcScene farmer = scene('minecraft:villager', <String, Object?>{
        'villager': <String, Object?>{'type': 'desert', 'profession': 'farmer', 'level': 'master'},
      });
      expect(rigNode(farmer, 'body:1').textureId, 'entity/villager/type/desert');
      expect(rigNode(farmer, 'body:1').rig.id, startsWith('villager_no_hat'));
      expect(rigNode(farmer, 'body:2').textureId, 'entity/villager/profession/farmer');
      expect(rigNode(farmer, 'body:3').textureId, 'entity/villager/profession_level/diamond');
      final NpcScene plain = scene('minecraft:villager');
      expect(rigNode(plain, 'body:1').textureId, 'entity/villager/type/plains');
      expect(nodes(plain).containsKey('body:2'), isFalse);
    });

    test('flags pose the model: a sitting cat differs from a standing one', () {
      final McRigNode standing = rigNode(scene('minecraft:cat'));
      final McRigNode sitting = rigNode(scene('minecraft:cat', <String, Object?>{'cat': <String, Object?>{'sitting': true}}));
      expect(sitting.signature, isNot(standing.signature));
      final McRigNode chested = rigNode(scene('minecraft:donkey', <String, Object?>{'donkey': <String, Object?>{'chest': true}}));
      expect(chested.pose.hidden, isNot(contains('left_chest')));
      expect(rigNode(scene('minecraft:donkey')).pose.hidden, contains('left_chest'));
    });

    test('colours and coats pick their textures', () {
      expect(rigNode(scene('minecraft:shulker', <String, Object?>{'shulker': <String, Object?>{'color': 'lime'}})).textureId, 'entity/shulker/shulker_lime');
      expect(rigNode(scene('minecraft:wolf', <String, Object?>{'wolf': <String, Object?>{'variant': 'ashen', 'collar': 'red'}})).textureId, 'entity/wolf/wolf_ashen_tame');
      expect(rigNode(scene('minecraft:rabbit', <String, Object?>{'rabbit': <String, Object?>{'type': 'evil'}})).textureId, 'entity/rabbit/rabbit_caerbannog');
      expect(rigNode(scene('minecraft:parrot', <String, Object?>{'parrot': <String, Object?>{'variant': 'gray'}})).textureId, 'entity/parrot/parrot_grey');
      expect(rigNode(scene('minecraft:panda', <String, Object?>{'panda': <String, Object?>{'mainGene': 'brown', 'hiddenGene': 'brown'}})).textureId, 'entity/panda/panda_brown');
      expect(rigNode(scene('minecraft:panda', <String, Object?>{'panda': <String, Object?>{'mainGene': 'brown'}})).textureId, 'entity/panda/panda');
      expect(rigNode(scene('minecraft:cow', <String, Object?>{'cow': <String, Object?>{'variant': 'cold'}})).rig.id, startsWith('cold_cow#main'));
      expect(rigNode(scene('minecraft:creeper', <String, Object?>{'creeper': <String, Object?>{'powered': true}}), 'body:1').textureId, 'entity/creeper/creeper_armor');
    });

    test('a snow golem wears its pumpkin and a mooshroom grows mushrooms', () {
      expect((nodes(scene('minecraft:snow_golem'))['body:block:0']! as McBlockNode).blockKey, 'carved_pumpkin');
      expect(nodes(scene('minecraft:snow_golem', <String, Object?>{'snowGolem': <String, Object?>{'pumpkin': 'hidden'}})).containsKey('body:block:0'), isFalse);
      final NpcScene brown = scene('minecraft:mooshroom', <String, Object?>{'mooshroom': <String, Object?>{'variant': 'brown'}});
      expect(brown.scene.nodes.whereType<McBlockNode>().map((McBlockNode node) => node.blockKey).toSet(), <String>{'brown_mushroom'});
    });

    test('humanoid mobs hold items and wear armour on their own layers', () {
      final NpcScene zombie = scene('minecraft:zombie', <String, Object?>{
        'equipment': <String, Object?>{
          'main_hand': <String, Object?>{'id': 'minecraft:iron_sword'},
          'head': <String, Object?>{'id': 'minecraft:golden_helmet'},
        },
      });
      expect(nodes(zombie)['equipment:main_hand'], isA<McItemNode>());
      expect(rigNode(zombie, 'equipment:head').rig.id, startsWith('zombie#helmet'));
      expect(rigNode(zombie, 'equipment:head').textureId, 'entity/equipment/humanoid/gold');
      expect(zombie.notes, isEmpty);
      final NpcScene pig = scene('minecraft:pig', <String, Object?>{
        'equipment': <String, Object?>{'saddle': <String, Object?>{'id': 'minecraft:saddle'}},
      });
      expect(rigNode(pig, 'equipment:saddle').textureId, 'entity/equipment/pig_saddle/saddle');
    });
  });

  group('objects', () {
    test('a painting is as big as its art and faces its side', () {
      final NpcScene painting = scene('minecraft:painting', <String, Object?>{'painting': <String, Object?>{'variant': 'minecraft:pointer', 'facing': 'east'}});
      final McModelNode art = nodes(painting)['body']! as McModelNode;
      expect(art.textureId, 'painting/pointer');
      // The art is the model's north face, so the side it shows is -Z turned.
      final McVec3 front = art.transform.transformPoint(const McVec3(0, 0, -1)) - art.transform.transformPoint(McVec3.zero);
      expect(front.x, closeTo(1, 1e-9));
      final McBounds box = McBounds.of(painting.scene.frame);
      expect(box.max.y - box.min.y, closeTo(4, 1e-6));
      expect(box.max.z - box.min.z, closeTo(4, 1e-6));
      expect(box.max.x - box.min.x, closeTo(1 / 16, 1e-6));
      expect(scene('minecraft:painting', <String, Object?>{'painting': <String, Object?>{'variant': 'mod:art'}}).notes.single, contains('Art (mod)'));
    });

    test('an item frame holds its item turned by eighths', () {
      final NpcScene frame = scene('minecraft:glow_item_frame', <String, Object?>{
        'glowItemFrame': <String, Object?>{'item': <String, Object?>{'id': 'minecraft:compass'}, 'rotation': 2},
      });
      expect((nodes(frame)['body']! as McBlockNode).modelId, 'block/glow_item_frame');
      final McItemNode item = nodes(frame)['body:item']! as McItemNode;
      expect(item.itemId, 'compass');
      expect(item.pose, McDisplayPose.fixed);
    });

    test('blocks keep their state; items their display transform; text its text', () {
      final McBlockNode falling = nodes(scene('minecraft:falling_block', <String, Object?>{
        'fallingBlock': <String, Object?>{'block': 'minecraft:oak_stairs[facing=west]'},
      }))['body']! as McBlockNode;
      expect(falling.blockKey, 'oak_stairs');
      expect(falling.state, 'oak_stairs[facing=west]');
      expect((nodes(scene('minecraft:falling_block'))['body']! as McBlockNode).blockKey, 'sand');
      final McItemNode held = nodes(scene('minecraft:item_display', <String, Object?>{
        'itemDisplay': <String, Object?>{'item': <String, Object?>{'id': 'minecraft:bow'}, 'transform': 'head'},
      }))['body']! as McItemNode;
      expect(held.pose, McDisplayPose.head);
      final NpcScene text = scene('minecraft:text_display', <String, Object?>{
        'textDisplay': <String, Object?>{'text': 'One\nTwo', 'alignment': 'left', 'billboard': 'center', 'shadowed': true},
      });
      expect(text.texts.single.lines, <String>['One', 'Two']);
      expect(text.texts.single.alignment, McTextAlign.left);
      expect(text.texts.single.shadow, isTrue);
      expect(text.notes, isEmpty);
      expect((nodes(scene('minecraft:item'))['body']! as McItemNode).pose, McDisplayPose.ground);
    });

    test('an end crystal floats over its base, which can be hidden', () {
      final McRigNode crystal = rigNode(scene('minecraft:end_crystal'));
      expect(crystal.pose.hidden, isEmpty);
      expect(rigNode(scene('minecraft:end_crystal', <String, Object?>{'endCrystal': <String, Object?>{'base': 'hidden'}})).pose.hidden, <String>{'base'});
      final (McVec3 lo, McVec3 hi) = worldBounds(crystal);
      expect(lo.y, closeTo(-1, 0.01));
      expect(hi.y, greaterThan(1.5));
    });
  });

  test('a baby body is drawn grown and says so', () {
    expect(scene('minecraft:cow', <String, Object?>{'baby': true}).notes.single, contains('baby'));
    expect(scene('minecraft:cow').notes, isEmpty);
  });

  group('disguise', () {
    test('a disguise that names a vanilla type draws that body', () {
      final NpcLook look = NpcLook.fromDefinition(<String, Object?>{
        'type': 'minecraft:player',
        'name': 'Hidden',
        'disguise': 'zombie setBaby true',
        'sheep': <String, Object?>{'color': 'red'},
      });
      expect(look.type, 'minecraft:zombie');
      expect(look.disguise, 'zombie setBaby true');
      expect(NpcLook.fromDefinition(<String, Object?>{'type': 'minecraft:cow', 'name': 'M', 'disguise': 'MUSHROOM_COW'}).type, 'minecraft:mooshroom');
      final NpcLook sheep = NpcLook.fromDefinition(<String, Object?>{
        'type': 'minecraft:cow',
        'name': 'S',
        'disguise': 'sheep',
        'sheep': <String, Object?>{'color': 'red'},
      });
      expect(sheep.settings['color'], 'red');
      expect(npcScene(sheep).notes.single, contains('disguise'));
    });

    test('a custom or unknown disguise keeps the body and says so', () {
      final NpcLook look = NpcLook.fromDefinition(<String, Object?>{'type': 'minecraft:cow', 'name': 'C', 'disguise': 'my_custom_thing'});
      expect(look.type, 'minecraft:cow');
      expect(npcScene(look).notes.single, contains('my_custom_thing'));
    });
  });
}
