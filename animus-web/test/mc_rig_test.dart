import 'dart:math' as math;

import 'package:animus_editor/mc/mesh/mc_mesh.dart';
import 'package:animus_editor/mc/npc/npc_look.dart';
import 'package:animus_editor/mc/npc/npc_pose.dart';
import 'package:animus_editor/mc/rigs/mc_dragon_rig.dart';
import 'package:animus_editor/mc/rigs/mc_entity_rigs.dart';
import 'package:animus_editor/mc/rigs/mc_player_rig.dart';
import 'package:animus_editor/mc/rigs/mc_rig.dart';
import 'package:animus_editor/mc/rigs/mc_rig_mesher.dart';
import 'package:animus_editor/mc/rigs/mc_rigs.dart';
import 'package:animus_editor/mc/scene/mc_camera.dart';
import 'package:animus_editor/mc/scene/mc_math.dart';
import 'package:test/test.dart';

/// Min and max of a mesh's positions after [transform].
(McVec3, McVec3) bounds(McMesh mesh, [McMat4? transform]) {
  final McMat4 m = transform ?? McMat4.identity();
  McVec3 lo = const McVec3(double.infinity, double.infinity, double.infinity);
  McVec3 hi = const McVec3(-double.infinity, -double.infinity, -double.infinity);
  for (int v = 0; v < mesh.vertexCount; v++) {
    final McVec3 p = m.transformPoint(McVec3(mesh.positions[v * 3], mesh.positions[v * 3 + 1], mesh.positions[v * 3 + 2]));
    lo = McVec3(math.min(lo.x, p.x), math.min(lo.y, p.y), math.min(lo.z, p.z));
    hi = McVec3(math.max(hi.x, p.x), math.max(hi.y, p.y), math.max(hi.z, p.z));
  }
  return (lo, hi);
}

void expectVec(McVec3 actual, McVec3 expected, {double within = 1e-6}) {
  expect(actual.x, closeTo(expected.x, within), reason: '$actual vs $expected');
  expect(actual.y, closeTo(expected.y, within), reason: '$actual vs $expected');
  expect(actual.z, closeTo(expected.z, within), reason: '$actual vs $expected');
}

void main() {
  group('rig space', () {
    test('model space maps to feet at the origin, Y up, facing +Z', () {
      final McRig rig = mcPlayerRig(slim: false);
      final McMat4 toWorld = mcRigModelToWorld(rig);
      expectVec(toWorld.transformPoint(const McVec3(0, 1.5, 0)), McVec3.zero);
      expectVec(toWorld.transformPoint(McVec3.zero), const McVec3(0, 1.5 * 0.9375, 0));
      // Model -Z is the face; it ends up toward +Z.
      expect(toWorld.transformPoint(const McVec3(0, 0, -1)).z, greaterThan(0));
    });

    test('the standing player is 1.875 blocks to the top of the head', () {
      final (McVec3 lo, McVec3 hi) = bounds(mcMeshRig(mcPlayerRig(slim: false)));
      expect(lo.y, closeTo(0, 1e-9));
      // Hat: head top at -8 px, inflated 0.5, at 0.9375 scale.
      expect(hi.y, closeTo((24 + 8.5) / 16 * 0.9375, 1e-9));
      expect(hi.x, closeTo(-lo.x, 1e-9));
    });

    test('slim arms are a pixel narrower', () {
      final (McVec3 wideLo, _) = bounds(mcMeshRig(mcPlayerRig(slim: false)));
      final (McVec3 slimLo, _) = bounds(mcMeshRig(mcPlayerRig(slim: true)));
      expect(slimLo.x - wideLo.x, closeTo(1 / 16 * 0.9375, 1e-9));
    });

    test('a hidden part hides everything under it', () {
      final McRig rig = mcFrogRig();
      final Map<String, McMat4> all = mcRigPartMatrices(rig, McRigPose.rest);
      final Map<String, McMat4> headless = mcRigPartMatrices(rig, const McRigPose(hidden: <String>{'head'}));
      expect(all.keys, containsAll(<String>['head', 'eyes', 'left_eye']));
      expect(headless.keys, isNot(contains('left_eye')));
      expect(headless.keys, contains('body'));
    });

    test('pose rotations replace the rest rotation and offsets move the pivot', () {
      final McRig rig = mcHorseRig();
      final McMat4 rest = mcRigPartMatrices(rig, McRigPose.rest)['head_parts']!;
      final McMat4 level = mcRigPartMatrices(rig, const McRigPose(partRotationsDeg: <String, McVec3>{'head_parts': McVec3.zero}))['head_parts']!;
      // The rest pose tips the neck 30 degrees; replacing it stands it upright.
      expect(rest.transformPoint(const McVec3(0, -1, 0)).z, lessThan(-12 / 16 - 0.1));
      expectVec(level.transformPoint(const McVec3(0, -1, 0)), const McVec3(0, 4 / 16 - 1, -12 / 16));
      final McMat4 moved = mcRigPartMatrices(rig, const McRigPose(partOffsetsPx: <String, McVec3>{'body': McVec3(0, 16, 0)}))['body']!;
      expectVec(moved.transformPoint(McVec3.zero), const McVec3(0, 27 / 16, 5 / 16));
    });

    test('every presented body type has a rig and a texture, and stands on the ground', () {
      for (final String type in mcBodyTypes) {
        final McRig rig = mcBodyRig(type)!;
        expect(mcBodyTexture(type), isNotNull, reason: type);
        final McMesh mesh = mcMeshRig(rig);
        expect(mesh.triangleCount, greaterThan(10), reason: type);
        final (McVec3 lo, McVec3 hi) = bounds(mesh);
        if (type != 'minecraft:ender_dragon') expect(lo.y, closeTo(0, 0.08), reason: type);
        expect(hi.y, greaterThan(0.2), reason: type);
      }
      expect(mcBodyRig('minecraft:zombie'), isNull);
    });

    test('the horse is the client size: 1.1 scale, feet on the ground', () {
      final (McVec3 lo, McVec3 hi) = bounds(mcMeshRig(mcHorseRig()));
      expect(lo.y, closeTo(0.01 / 16 * 1.1, 1e-9));
      expect(hi.z - lo.z, greaterThan(1.8));
    });

    test('the small armor stand keeps its feet down and is half size', () {
      final (McVec3 bigLo, McVec3 bigHi) = bounds(mcMeshRig(mcArmorStandRig()));
      final (McVec3 smallLo, McVec3 smallHi) = bounds(mcMeshRig(mcSmallTransform(mcArmorStandRig())));
      expect(bigLo.y, closeTo(0, 1e-9));
      expect(smallLo.y, closeTo(0, 1e-9));
      expect(smallHi.y, lessThan(bigHi.y * 0.6));
    });

    test('the dragon neck chain ends where the head sits', () {
      final McRig rig = mcEnderDragonRig();
      final McRigPart neck4 = rig.part('neck4')!;
      final McRigPart head = rig.part('head')!;
      final double xRot = math.cos(4 * 0.45) * 0.15;
      expect(head.pivot.z, closeTo(neck4.pivot.z - math.cos(xRot) * 10, 1e-9));
      expect(head.pivot.y, closeTo(neck4.pivot.y + math.sin(xRot) * 10, 1e-9));
      expect(rig.part('tail11')!.pivot.z, greaterThan(rig.part('tail0')!.pivot.z + 100));
    });

    test('armour layers ride the body pivots and inflate by slot', () {
      final McRig legs = mcHumanoidArmorRig(McArmorLayer.legs);
      final McRig feet = mcHumanoidArmorRig(McArmorLayer.feet);
      expect(legs.part('body'), isNotNull);
      expect(feet.part('body'), isNull);
      expect(legs.part('right_leg')!.boxes.single.inflate, closeTo(0.4, 1e-9));
      expect(feet.part('right_leg')!.boxes.single.inflate, closeTo(0.9, 1e-9));
      final McRig stand = mcHumanoidArmorRig(McArmorLayer.head, scale: 1, headPivotY: 1);
      expect(stand.part('head')!.pivot, const McVec3(0, 1, 0));
      expect(stand.part('head')!.boxes.last.inflate, 1.5);
    });
  });

  group('poses', () {
    test('sneaking crouches the body and drops two pixels', () {
      final McBodyPose pose = mcPlayerPose(NpcPose.sneaking);
      expect(pose.rig.partRotationsDeg['body']!.x, closeTo(0.5 * 180 / math.pi, 1e-9));
      expect(pose.rig.partOffsetsPx['head'], const McVec3(0, 4.2, 0));
      expectVec(pose.frame.transformPoint(McVec3.zero), const McVec3(0, -2 / 16, 0));
      expect(pose.heightBlocks, 1.5);
    });

    test('sitting lowers the feet by the vehicle attachment and folds the legs', () {
      final McBodyPose pose = mcPlayerPose(NpcPose.sitting);
      expectVec(pose.frame.transformPoint(McVec3.zero), const McVec3(0, -0.6, 0));
      expect(pose.rig.partRotationsDeg['right_leg']!.x, closeTo(-81, 0.01));
    });

    test('swimming lays the body face down along its facing', () {
      final McBodyPose pose = mcPlayerPose(NpcPose.swimming);
      final McRig rig = mcPlayerRig(slim: false);
      final McMat4 m = pose.frame.multiply(mcRigModelToWorld(rig));
      final McVec3 feet = m.transformPoint(const McVec3(0, 1.5, 0));
      final McVec3 crown = m.transformPoint(const McVec3(0, -0.5, 0));
      // The head points forward (+Z), the body lies low.
      expect(crown.z, greaterThan(feet.z + 1));
      expect((crown.y - feet.y).abs(), lessThan(0.2));
      expect(pose.heightBlocks, 0.6);
    });

    test('sleeping lies flat whatever the yaw', () {
      for (final double yaw in <double>[0, 90, -135]) {
        final McBodyPose pose = mcPlayerPose(NpcPose.sleeping, yawDeg: yaw);
        final McMat4 m = McMat4.rotationY(-yaw).multiply(pose.frame).multiply(mcRigModelToWorld(mcPlayerRig(slim: false)));
        final (McVec3 lo, McVec3 hi) = bounds(mcMeshRig(mcPlayerRig(slim: false)), m);
        expect(hi.y - lo.y, lessThan(0.6), reason: 'yaw $yaw');
      }
    });

    test('a held item raises the arm 18 degrees', () {
      final McBodyPose pose = mcPlayerPose(NpcPose.standing, rightHeld: true);
      expect(pose.rig.partRotationsDeg['right_arm']!.x, closeTo(-18, 1e-9));
      expect(pose.rig.partRotationsDeg['left_arm']!.x, closeTo(0, 1e-9));
      final McBodyPose crouched = mcPlayerPose(NpcPose.sneaking, leftHeld: true);
      expect(crouched.rig.partRotationsDeg['left_arm']!.x, closeTo(-18 + 0.4 * 180 / math.pi, 1e-9));
    });

    test('armor stand angles default to the client and follow the definition', () {
      final McRigPose rest = mcArmorStandPose(const NpcArmorStand());
      expect(rest.partRotationsDeg['left_arm'], const McVec3(-10, 0, -10));
      expect(rest.hidden, containsAll(<String>['left_arm', 'right_arm']));
      final McRigPose posed = mcArmorStandPose(const NpcArmorStand(
        arms: true,
        basePlate: false,
        angles: <String, List<double>>{'body': <double>[5, 10, 0], 'right_arm': <double>[-90, 0, 0]},
      ));
      expect(posed.partRotationsDeg['right_arm'], const McVec3(-90, 0, 0));
      expect(posed.partRotationsDeg['shoulder_stick'], const McVec3(5, 10, 0));
      expect(posed.hidden, <String>{'base_plate'});
    });
  });

  group('camera', () {
    test('the home framing keeps the subject on screen', () {
      const McBounds subject = McBounds(McVec3(-0.4, 0, -0.3), McVec3(0.4, 2.4, 0.3));
      final McCamera camera = mcCameraFraming(subject, yawDeg: 210);
      for (final double x in <double>[subject.min.x, subject.max.x]) {
        for (final double y in <double>[subject.min.y, subject.max.y]) {
          final McVec3 at = mcProjectToPixels(camera, McVec3(x, y, 0), widthPx: 400, heightPx: 300)!;
          expect(at.x, inInclusiveRange(0, 400));
          expect(at.y, inInclusiveRange(0, 300));
        }
      }
      expectVec(mcProjectToPixels(camera, subject.centre, widthPx: 400, heightPx: 300)!, McVec3(200, 150, camera.distance), within: 1e-6);
    });

    test('orbit and dolly stay inside their clamps', () {
      final McCamera camera = mcCameraFraming(const McBounds(McVec3(-1, 0, -1), McVec3(1, 2, 1)));
      expect(camera.orbitBy(0, 100000).pitchDeg, 89);
      expect(camera.dollyBy(1e6).distance, mcCameraMaxDistance);
      expect(camera.dollyBy(-1e6).distance, mcCameraMinDistance);
    });
  });
}
