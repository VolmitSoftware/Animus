import 'package:animus_editor/logic/rig_edit.dart';
import 'package:animus_editor/logic/rig_gizmo_edit.dart';
import 'package:animus_editor/logic/rig_sampler.dart';
import 'package:animus_editor/mc/npc/mc_text.dart';
import 'package:animus_editor/mc/npc/npc_look.dart';
import 'package:animus_editor/mc/npc/npc_playground.dart';
import 'package:animus_editor/mc/npc/npc_scene.dart';
import 'package:animus_editor/mc/npc/npc_skin_failure.dart';
import 'package:animus_editor/mc/rig/rig_scene.dart';
import 'package:animus_editor/mc/scene/mc_scene.dart';
import 'package:test/test.dart';

import 'fixtures.dart';

JsonMap golem() => cloneJson(readFixture('animation/rigs/golem.json')! as JsonMap);

JsonMap walk() => cloneJson(readFixture('animation/clips/golem-walk.json')! as JsonMap);

Map<String, BonePose> posesAt(JsonMap clip, double tick) => sampleRig(Rig.fromJson(golem()), <Layer>[Layer(Clip.fromJson(clip))], 0, tick);

void main() {
  group('gizmo edits', () {
    test('should nudge by a 16th of a block, 5 degrees or 5 percent, a quarter with Shift', () {
      expect(GizmoMode.move.nudge(1), 1 / 16);
      expect(GizmoMode.rotate.nudge(-1), -5);
      expect(GizmoMode.scale.nudge(1, fine: true), closeTo(1.0125, 1e-9));
    });

    test('should move a bone and its subtree in the rest pose', () {
      final JsonMap rig = restGizmoEdit(golem(), 'torso', GizmoMode.move, 1, GizmoMode.move.nudge(1));
      expect(boneById(rig, 'torso')!['pivot'], <Object?>[0, 1.1625, 0]);
      expect(boneById(rig, 'head')!['pivot'], <Object?>[0, 1.6625, 0]);
    });

    test('should key the selected add track with its own value plus the nudge', () {
      final JsonMap clip = walk();
      final double own = (trackValueAt(tracksOf(clip)[3], 10)! as List<double>)[2];
      final JsonMap keyed = keyGizmoEdit(clip, 'arm', GizmoMode.rotate, 2, 5, 10, track: 3, pose: posesAt(clip, 10)['arm']);
      final List<JsonMap> keys = keyframesOf(tracksOf(keyed)[3]);
      expect(keys.map((JsonMap key) => key['tick']), <Object?>[0, 10, 20]);
      expect((keys[1]['value']! as List<Object?>)[2], closeTo(own + 5, 1e-6));
      expect(keyframesOf(tracksOf(keyed)[2]), keyframesOf(tracksOf(clip)[2]));
    });

    test('should start a new track from the value that leaves the pose unchanged', () {
      final JsonMap clip = walk();
      final BonePose before = posesAt(clip, 4)['head']!;
      final JsonMap keyed = keyGizmoEdit(clip, 'head', GizmoMode.scale, 0, 1, 4, blend: Blend.multiply, pose: before);
      expect(tracksOf(keyed).last['blend'], 'multiply');
      expect(keyframesOf(tracksOf(keyed).last).single['value'], <Object?>[1, 1, 1]);
      expect(ownTrackValue(clip, null, TrackTarget.rotation, Blend.add, 4, before), <double>[0, 0, 0]);
    });
  });

  group('rig scene', () {
    test('should hang socket equipment on its bone and say the server does not yet', () {
      final RigScene scene = rigScene(golem(), sampleRig(Rig.fromJson(golem()), const <Layer>[], 0, 0), equipment: socketSampleItems);
      final McSceneNode? sword = scene.nodes.where((McSceneNode node) => node.key == 'socket:main_hand').firstOrNull;
      expect(sword, isA<McItemNode>());
      expect((sword! as McItemNode).itemId, 'iron_sword');
      expect(scene.notes.join(' '), contains('main_hand on arm'));
    });

    test('should carry text display line width, background and shadow', () {
      final JsonMap rig = updateBone(golem(), 'label', (JsonMap bone) {
        final JsonMap visual = bone['visual']! as JsonMap;
        visual['lineWidth'] = 80;
        visual['background'] = '#80ff0000';
        visual['shadow'] = true;
      });
      final NpcText text = rigScene(rig, sampleRig(Rig.fromJson(rig), const <Layer>[], 0, 0)).texts.single;
      expect(text.lineWidth, 80);
      expect(text.background, '#80ff0000');
      expect(text.shadow, isTrue);
    });

    test('should draw an NPC equipment item at the socket when the body is hidden', () {
      final NpcScene scene = npcPlaygroundScene(
        NpcLook.fromDefinition(<String, Object?>{
          'type': 'minecraft:player',
          'equipment': <String, Object?>{'main_hand': <String, Object?>{'id': 'minecraft:diamond_axe'}},
        }),
        rig: golem(),
      );
      final McItemNode axe = scene.scene.byKey['socket:main_hand']! as McItemNode;
      expect(axe.itemId, 'diamond_axe');
    });
  });

  group('NPC preview notes', () {
    NpcScene player(Map<String, Object?> equipment, {String pose = 'standing'}) => npcScene(NpcLook.fromDefinition(<String, Object?>{
      'type': 'minecraft:player',
      'name': 'Kim',
      'pose': pose,
      'equipment': equipment,
    }));

    test('should explain elytra, heads and the body slot instead of dropping them silently', () {
      final NpcScene scene = player(<String, Object?>{
        'chest': <String, Object?>{'id': 'minecraft:elytra'},
        'head': <String, Object?>{'id': 'minecraft:player_head'},
        'body': <String, Object?>{'id': 'minecraft:white_carpet'},
      });
      final String notes = scene.notes.join('\n');
      expect(notes, contains('chest: minecraft:elytra is worn on the back'));
      expect(notes, contains('head: minecraft:player_head is worn on the head'));
      expect(notes, contains('body: minecraft:white_carpet is not drawn: players do not show the body slot'));
      expect(scene.scene.byKey['equipment:head'], isNull);
    });

    test('should dim a sneaking player nameplate', () {
      expect(player(const <String, Object?>{}, pose: 'sneaking').texts.single.dim, isTrue);
      expect(player(const <String, Object?>{}).texts.single.dim, isFalse);
    });

    test('should list the body types when the type is not one', () {
      final NpcScene scene = npcScene(NpcLook.fromDefinition(<String, Object?>{'type': 'minecraft:zombie'}));
      expect(scene.notes.single, allOf(contains('minecraft:zombie is not a body type'), contains('minecraft:armor_stand'), contains('minecraft:ender_dragon')));
    });
  });

  group('text and copy', () {
    test('should scramble obfuscated text to the same length and keep spaces', () {
      final String scrambled = mcObfuscate('Hidden name');
      expect(scrambled, hasLength(11));
      expect(scrambled[6], ' ');
      expect(scrambled, isNot('Hidden name'));
      expect(mcObfuscate('Hidden name'), scrambled, reason: 'one fixed frame, nothing animates');
    });

    test('should explain skin failures with a next step', () {
      expect(skinFailureText(const FormatException('A skin is 64x64 or 64x32 pixels; this image is 10x10.')), endsWith('Pick a 64x64 Minecraft skin PNG.'));
      expect(skinFailureText(StateError('HTTP 404')), contains('Nothing is at that address'));
      expect(skinFailureText(StateError('HTTP 500')), contains('HTTP 500'));
      expect(skinFailureText(Exception('TypeError: Failed to fetch')), contains('Save the PNG and use Load skin PNG'));
    });
  });
}
