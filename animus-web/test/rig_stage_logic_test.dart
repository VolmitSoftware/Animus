import 'package:animus_editor/logic/rig_edit.dart';
import 'package:animus_editor/logic/rig_gizmo_edit.dart';
import 'package:animus_editor/logic/rig_sampler.dart';
import 'package:animus_editor/logic/rig_view.dart';
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
      expect(notes, contains('Elytra is worn on the back'));
      expect(notes, contains('Player head is worn on the head'));
      expect(notes, contains('White carpet is not drawn: players do not show the body slot'));
      expect(notes, isNot(contains('minecraft:')));
      expect(scene.scene.byKey['equipment:head'], isNull);
    });

    test('should dim a sneaking player nameplate', () {
      expect(player(const <String, Object?>{}, pose: 'sneaking').texts.single.dim, isTrue);
      expect(player(const <String, Object?>{}).texts.single.dim, isFalse);
    });

    test('should name a type that is not a body and point at the body list', () {
      final NpcScene scene = npcScene(NpcLook.fromDefinition(<String, Object?>{'type': 'minecraft:marker'}));
      expect(scene.notes.single, 'Marker is not a body Animus shows. Pick a body from the list.');
    });
  });

  group('rig editor view', () {
    test('should open an animation on the Timeline tab and a rig on Bones', () {
      expect(initialRigPane(animation: true), RigPane.timeline);
      expect(initialRigPane(animation: false), RigPane.bones);
      expect(<String>[for (final RigPane pane in RigPane.values) pane.label], <String>['Bones', 'Inspector', 'Timeline']);
    });

    test('should commit a bone name only when it changed', () {
      expect(boneRenameTarget('arm', ' arm '), isNull);
      expect(boneRenameTarget('arm', '   '), isNull);
      expect(boneRenameTarget('arm', ' right-arm '), 'right-arm');
    });

    test('should count the tracks a bone keys across animations', () {
      expect(boneTracks(<JsonMap>[walk(), walk()], 'arm'), (tracks: 4, animations: 2));
      expect(boneTracks(<JsonMap>[walk()], 'ghost'), (tracks: 0, animations: 0));
    });

    test('should say what deleting a bone takes with it', () {
      expect(
        deleteBoneMessage(parent: 'torso', children: 0, tracks: 2, animations: 1),
        'Its 2 tracks in 1 animation go too. Undo brings it back.',
      );
      expect(
        deleteBoneMessage(parent: null, children: 2, tracks: 1, animations: 1),
        'Its 1 track in 1 animation goes too. Its 2 child bones move to the rig origin. Undo brings it back.',
      );
      expect(deleteBoneMessage(parent: 'torso', children: 1, tracks: 0, animations: 0), 'Its child bone moves to torso. Undo brings it back.');
    });

    test('should show seconds beside ticks', () {
      expect(secondsLabel(20), '1 s');
      expect(secondsLabel(30), '1.5 s');
      expect(secondsLabel(7.5), '0.38 s');
      expect(playheadReadout(7.5, 20), '7.5 / 20 ticks · 0.38 s');
    });

    test('should name a track by its bone, channel and a blend other than replace', () {
      expect(trackLabel(<String, Object?>{'bone': 'torso', 'target': 'rotation'}), 'torso rotation');
      expect(trackLabel(<String, Object?>{'bone': 'head', 'target': 'translation', 'blend': 'add'}), 'head position · add');
    });
  });

  group('gizmo handles on screen', () {
    test('should hide the handles while the bone is off the stage', () {
      expect(placeHandles(-10, 50, <(double, double)?>[(30, 50), (-10, 10), null], 400, 300), isEmpty);
    });

    test('should pull a handle that would leave the stage back inside the safe area', () {
      final List<HandleSpot> spots = placeHandles(100, 100, <(double, double)?>[null, (100, -200), null], 400, 300);
      expect(spots.single.axis, 1);
      expect(spots.single.x, closeTo(100, 1e-9));
      expect(spots.single.y, closeTo(gizmoSafeInset, 1e-9));
    });

    test('should give an arm seen end on a length that can be grabbed', () {
      final HandleSpot spot = placeHandles(200, 150, <(double, double)?>[(203, 150), null, null], 400, 300).single;
      expect(spot.x - 200, closeTo(gizmoMinArm, 1e-9));
      expect(spot.y, closeTo(150, 1e-9));
    });

    test('should spread handles that land on each other', () {
      final List<HandleSpot> spots = placeHandles(200, 150, <(double, double)?>[(240, 150), (240, 151), (200, 150)], 400, 300);
      expect(spots, hasLength(3));
      for (int a = 0; a < spots.length; a++) {
        expect(spots[a].x, inInclusiveRange(gizmoSafeInset, 400 - gizmoSafeInset));
        expect(spots[a].y, inInclusiveRange(gizmoSafeInset, 300 - gizmoSafeInset));
        for (int b = a + 1; b < spots.length; b++) {
          final double dx = spots[a].x - spots[b].x, dy = spots[a].y - spots[b].y;
          expect(dx * dx + dy * dy, greaterThanOrEqualTo(gizmoHandleGap * gizmoHandleGap - 1e-6), reason: 'handles $a and $b overlap');
        }
      }
    });

    test('should keep 44 px touch handles apart', () {
      final List<HandleSpot> spots = placeHandles(200, 150, <(double, double)?>[(240, 150), (200, 120), (190, 160)], 400, 300, gap: gizmoTouchGap, minArm: gizmoTouchArm);
      expect(spots, hasLength(3));
      for (int a = 0; a < spots.length; a++) {
        final double ox = spots[a].x - 200, oy = spots[a].y - 150;
        expect(ox * ox + oy * oy, greaterThanOrEqualTo(gizmoTouchArm * gizmoTouchArm - 1e-6), reason: 'handle $a sits on the bone');
        for (int b = a + 1; b < spots.length; b++) {
          final double dx = spots[a].x - spots[b].x, dy = spots[a].y - spots[b].y;
          expect(dx * dx + dy * dy, greaterThanOrEqualTo(gizmoTouchGap * gizmoTouchGap - 1e-6), reason: 'handles $a and $b overlap');
        }
      }
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
      expect(skinFailureText(Exception('TypeError: Failed to fetch')), contains('cross-origin reads. Use From PNG file.'));
    });
  });
}
