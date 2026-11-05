/// Definition poses as the client draws them: part angles from
/// `HumanoidModel.setupAnim` and the body turn from
/// `LivingEntityRenderer.setupRotations` / `AvatarRenderer.setupRotations`,
/// for a body that stands still (no walk, swing or bob).
library;

import 'dart:math' as math;

import '../rigs/mc_entity_rigs.dart';
import '../rigs/mc_rig.dart';
import '../scene/mc_math.dart';
import 'npc_look.dart';

/// A pose's part state and its body frame.
final class McBodyPose {
  const McBodyPose({required this.rig, required this.frame, required this.heightBlocks});

  final McRigPose rig;

  /// Applied between the yaw turn and the rig (`Ry(-yaw) * frame * rig`): the
  /// client's extra turns and offsets, in a body frame facing +Z.
  final McMat4 frame;

  /// The pose's hitbox height, where the client puts riding text.
  final double heightBlocks;
}

/// The client's `Avatar.DEFAULT_VEHICLE_ATTACHMENT`: a seated player's feet
/// sit this far below its seat.
const double mcPlayerVehicleAttachment = 0.6;

/// [rightHeld] and [leftHeld] raise an arm holding an item the way
/// `HumanoidModel.poseRightArm` does for `ArmPose.ITEM`: half the arm's
/// swing, then 18 degrees up, before the crouch adds its own.
McBodyPose mcPlayerPose(NpcPose pose, {double yawDeg = 0, bool rightHeld = false, bool leftHeld = false}) {
  final McBodyPose base = _playerPose(pose, yawDeg);
  if (pose == NpcPose.swimming || (!rightHeld && !leftHeld)) return base;
  double armX(bool held) {
    double x = pose == NpcPose.sitting ? -math.pi / 5 : 0;
    if (held) x = x * 0.5 - math.pi / 10;
    if (pose == NpcPose.sneaking) x += 0.4;
    return x;
  }

  return McBodyPose(
    rig: McRigPose(
      partRotationsDeg: <String, McVec3>{
        ...base.rig.partRotationsDeg,
        'right_arm': mcRadians(armX(rightHeld), 0, 0),
        'left_arm': mcRadians(armX(leftHeld), 0, 0),
      },
      partOffsetsPx: base.rig.partOffsetsPx,
      hidden: base.rig.hidden,
    ),
    frame: base.frame,
    heightBlocks: base.heightBlocks,
  );
}

McBodyPose _playerPose(NpcPose pose, double yawDeg) {
  switch (pose) {
    case NpcPose.standing:
      return McBodyPose(rig: McRigPose.rest, frame: McMat4.identity(), heightBlocks: 1.8);
    case NpcPose.sneaking:
      return McBodyPose(
        rig: McRigPose(
          partRotationsDeg: <String, McVec3>{
            'body': mcRadians(0.5, 0, 0),
            'right_arm': mcRadians(0.4, 0, 0),
            'left_arm': mcRadians(0.4, 0, 0),
          },
          partOffsetsPx: const <String, McVec3>{
            'head': McVec3(0, 4.2, 0),
            'body': McVec3(0, 3.2, 0),
            'right_arm': McVec3(0, 3.2, 0),
            'left_arm': McVec3(0, 3.2, 0),
            'right_leg': McVec3(0, 0, 4),
            'left_leg': McVec3(0, 0, 4),
          },
        ),
        // `AvatarRenderer.getRenderOffset`: crouching drops 2/16.
        frame: McMat4.translation(0, -2 / 16, 0),
        heightBlocks: 1.5,
      );
    case NpcPose.swimming:
      // Out of water: `xRot = -90` and `translate(0, -1, 0.3)`; the swim
      // stroke at its first frame: arms raised overhead, head tipped -45.
      return McBodyPose(
        rig: McRigPose(
          partRotationsDeg: <String, McVec3>{
            'head': const McVec3(-45, 0, 0),
            'right_arm': mcRadians(0, math.pi, math.pi),
            'left_arm': mcRadians(0, math.pi, math.pi),
            'right_leg': mcRadians(0.3, 0, 0),
            'left_leg': mcRadians(-0.3, 0, 0),
          },
        ),
        frame: _fromVanilla(McMat4.rotationX(-90).multiply(McMat4.translation(0, -1, 0.3))),
        heightBlocks: 0.6,
      );
    case NpcPose.sleeping:
      // No bed: `Ry(bodyRot) * Rz(90) * Ry(270)` instead of `Ry(180 - bodyRot)`,
      // so the frame undoes the yaw turn the scene applies.
      return McBodyPose(
        rig: McRigPose.rest,
        frame: McMat4.rotationY(2 * yawDeg)
            .multiply(McMat4.rotationZ(90))
            .multiply(McMat4.rotationY(270))
            .multiply(McMat4.rotationY(-180)),
        heightBlocks: 0.2,
      );
    case NpcPose.sitting:
      // `isPassenger` on a marker seat at the definition's position.
      return McBodyPose(
        rig: McRigPose(
          partRotationsDeg: <String, McVec3>{
            'right_arm': mcRadians(-math.pi / 5, 0, 0),
            'left_arm': mcRadians(-math.pi / 5, 0, 0),
            'right_leg': mcRadians(-1.4137167, math.pi / 10, 0.07853982),
            'left_leg': mcRadians(-1.4137167, -math.pi / 10, -0.07853982),
          },
        ),
        frame: McMat4.translation(0, -mcPlayerVehicleAttachment, 0),
        heightBlocks: 1.8,
      );
  }
}

/// A turn the client applies after `Ry(180 - bodyRot)`, moved into the body
/// frame the rig mesher uses: `Ry(180) * m * Ry(-180)`.
McMat4 _fromVanilla(McMat4 m) => McMat4.rotationY(180).multiply(m).multiply(McMat4.rotationY(-180));

/// Armor stand part angles: the definition's, else the client's defaults;
/// the body sticks follow the body; arms and base plate per flag.
McRigPose mcArmorStandPose(NpcArmorStand look) {
  final Map<String, McVec3> angles = <String, McVec3>{
    for (final MapEntry<String, McVec3> entry in mcArmorStandDefaultAngles.entries)
      entry.key: look.angles[entry.key] == null
          ? entry.value
          : McVec3(look.angles[entry.key]![0], look.angles[entry.key]![1], look.angles[entry.key]![2]),
  };
  final McVec3 body = angles['body']!;
  angles['right_body_stick'] = body;
  angles['left_body_stick'] = body;
  angles['shoulder_stick'] = body;
  return McRigPose(
    partRotationsDeg: angles,
    hidden: <String>{
      if (!look.arms) ...<String>{'left_arm', 'right_arm'},
      if (!look.basePlate) 'base_plate',
    },
  );
}
