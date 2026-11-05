/// The player, wide and slim, with every overlay layer, and the humanoid
/// armour layers that ride its parts.
///
/// Boxes and UVs are the client's `PlayerModel`: head 8x8x8 at (0,0) with
/// the hat at (32,0) inflated 0.5; body 8x12x4 at (16,16) with the jacket at
/// (16,32) inflated 0.25; right arm 4x12x4 at (40,16) (slim: 3 wide), sleeve
/// (40,32); left arm (32,48), sleeve (48,48); right leg 4x12x4 at (0,16),
/// pants (0,32); left leg (16,48), pants (0,48). Pivots: head and body at
/// (0,0,0), arms at (+-5, 2, 0), legs at (+-1.9, 12, 0). Rendered at the
/// client's 0.9375 scale; feet at model y = 24. Adapted from Gloss Web.
library;

import '../scene/mc_math.dart';
import 'mc_rig.dart';

const String mcRigPlayer = 'player';

McRig mcPlayerRig({required bool slim}) {
  final double armW = slim ? 3 : 4;
  final double armX = slim ? -2 : -3;
  return McRig(
    id: slim ? 'player_slim' : mcRigPlayer,
    textureWidth: 64,
    textureHeight: 64,
    scale: 0.9375,
    heightBlocks: 1.8,
    parts: <McRigPart>[
      const McRigPart(
        name: 'head',
        pivot: McVec3.zero,
        boxes: <McBox>[
          McBox(origin: McVec3(-4, -8, -4), size: McVec3(8, 8, 8), u: 0, v: 0),
          McBox(origin: McVec3(-4, -8, -4), size: McVec3(8, 8, 8), u: 32, v: 0, inflate: 0.5),
        ],
      ),
      const McRigPart(
        name: 'body',
        pivot: McVec3.zero,
        boxes: <McBox>[
          McBox(origin: McVec3(-4, 0, -2), size: McVec3(8, 12, 4), u: 16, v: 16),
          McBox(origin: McVec3(-4, 0, -2), size: McVec3(8, 12, 4), u: 16, v: 32, inflate: 0.25),
        ],
      ),
      McRigPart(
        name: 'right_arm',
        pivot: const McVec3(-5, 2, 0),
        boxes: <McBox>[
          McBox(origin: McVec3(armX, -2, -2), size: McVec3(armW, 12, 4), u: 40, v: 16),
          McBox(origin: McVec3(armX, -2, -2), size: McVec3(armW, 12, 4), u: 40, v: 32, inflate: 0.25),
        ],
      ),
      McRigPart(
        name: 'left_arm',
        pivot: const McVec3(5, 2, 0),
        boxes: <McBox>[
          McBox(origin: const McVec3(-1, -2, -2), size: McVec3(armW, 12, 4), u: 32, v: 48),
          McBox(origin: const McVec3(-1, -2, -2), size: McVec3(armW, 12, 4), u: 48, v: 48, inflate: 0.25),
        ],
      ),
      const McRigPart(
        name: 'right_leg',
        pivot: McVec3(-1.9, 12, 0),
        boxes: <McBox>[
          McBox(origin: McVec3(-2, 0, -2), size: McVec3(4, 12, 4), u: 0, v: 16),
          // Nudged up by the inflate so the pants stay flush with the feet
          // line instead of poking through the floor.
          McBox(origin: McVec3(-2, -0.25, -2), size: McVec3(4, 12, 4), u: 0, v: 32, inflate: 0.25),
        ],
      ),
      const McRigPart(
        name: 'left_leg',
        pivot: McVec3(1.9, 12, 0),
        boxes: <McBox>[
          McBox(origin: McVec3(-2, 0, -2), size: McVec3(4, 12, 4), u: 16, v: 48),
          McBox(origin: McVec3(-2, -0.25, -2), size: McVec3(4, 12, 4), u: 0, v: 48, inflate: 0.25),
        ],
      ),
    ],
  );
}

/// The armour slots that draw as a layer over a humanoid body.
enum McArmorLayer { head, chest, legs, feet }

/// `HumanoidModel.createArmorMeshSet`: helmet, chestplate and boots use the
/// outer deformation (1.0), leggings the inner (0.5); legs shrink by 0.1;
/// textures are 64x32. [headPivotY] and [legPivotY] are 0 and 12 for the
/// player, 1 and 11 for the armor stand (`ArmorStandArmorModel`), so a layer
/// rides its body's parts exactly. [scale] is the body's.
McRig mcHumanoidArmorRig(
  McArmorLayer layer, {
  double scale = 0.9375,
  double headPivotY = 0,
  double legPivotY = 12,
  double heightBlocks = 1.8,
}) {
  final double g = layer == McArmorLayer.legs ? 0.5 : 1.0;
  final List<McRigPart> parts = <McRigPart>[
    if (layer == McArmorLayer.head)
      McRigPart(
        name: 'head',
        pivot: McVec3(0, headPivotY, 0),
        boxes: <McBox>[
          McBox(origin: const McVec3(-4, -8, -4), size: const McVec3(8, 8, 8), u: 0, v: 0, inflate: g),
          McBox(origin: const McVec3(-4, -8, -4), size: const McVec3(8, 8, 8), u: 32, v: 0, inflate: g + 0.5),
        ],
      ),
    if (layer == McArmorLayer.chest || layer == McArmorLayer.legs)
      McRigPart(
        name: 'body',
        pivot: McVec3.zero,
        boxes: <McBox>[McBox(origin: const McVec3(-4, 0, -2), size: const McVec3(8, 12, 4), u: 16, v: 16, inflate: g)],
      ),
    if (layer == McArmorLayer.chest) ...<McRigPart>[
      McRigPart(
        name: 'right_arm',
        pivot: const McVec3(-5, 2, 0),
        boxes: <McBox>[McBox(origin: const McVec3(-3, -2, -2), size: const McVec3(4, 12, 4), u: 40, v: 16, inflate: g)],
      ),
      McRigPart(
        name: 'left_arm',
        pivot: const McVec3(5, 2, 0),
        boxes: <McBox>[McBox(origin: const McVec3(-1, -2, -2), size: const McVec3(4, 12, 4), u: 40, v: 16, inflate: g, mirror: true)],
      ),
    ],
    if (layer == McArmorLayer.legs || layer == McArmorLayer.feet) ...<McRigPart>[
      McRigPart(
        name: 'right_leg',
        pivot: McVec3(-1.9, legPivotY, 0),
        boxes: <McBox>[McBox(origin: const McVec3(-2, 0, -2), size: const McVec3(4, 12, 4), u: 0, v: 16, inflate: g - 0.1)],
      ),
      McRigPart(
        name: 'left_leg',
        pivot: McVec3(1.9, legPivotY, 0),
        boxes: <McBox>[McBox(origin: const McVec3(-2, 0, -2), size: const McVec3(4, 12, 4), u: 0, v: 16, inflate: g - 0.1, mirror: true)],
      ),
    ],
  ];
  return McRig(
    id: 'armor_${layer.name}',
    textureWidth: 64,
    textureHeight: 32,
    scale: scale,
    heightBlocks: heightBlocks,
    parts: parts,
  );
}
