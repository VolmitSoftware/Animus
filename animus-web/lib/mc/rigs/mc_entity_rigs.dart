/// The non-player bodies Animus presents: armor stand, horse (with saddle and
/// armour layers), turtle, frog, llama (with its carpet layer) and armadillo.
/// Boxes, UVs and pivots are the 26.3 client's `LayerDefinition`s
/// (`ArmorStandModel`, `AbstractEquineModel`, `EquineSaddleModel`,
/// `AdultTurtleModel`, `FrogModel`, `LlamaModel`, `AdultArmadilloModel`);
/// parts only an animation shows (chests, egg belly, croaking throat, the
/// rolled-up armadillo) are left out. Model space, Y down, feet at y = 24.
library;

import 'dart:math' as math;

import '../scene/mc_math.dart';
import 'mc_rig.dart';

const double _degPerRad = 180 / math.pi;

/// `PartPose` radians as the rig's degrees.
McVec3 mcRadians(double x, double y, double z) => McVec3(x * _degPerRad, y * _degPerRad, z * _degPerRad);

// --- armor stand -----------------------------------------------------------

/// `ArmorStand`'s default part angles, degrees (`DEFAULT_*_POSE`).
const Map<String, McVec3> mcArmorStandDefaultAngles = <String, McVec3>{
  'head': McVec3.zero,
  'body': McVec3.zero,
  'left_arm': McVec3(-10, 0, -10),
  'right_arm': McVec3(-15, 0, 10),
  'left_leg': McVec3(-1, 0, -1),
  'right_leg': McVec3(1, 0, 1),
};

McRig mcArmorStandRig() => const McRig(
  id: 'armor_stand',
  textureWidth: 64,
  textureHeight: 64,
  heightBlocks: 1.975,
  parts: <McRigPart>[
    McRigPart(name: 'head', pivot: McVec3(0, 1, 0), boxes: <McBox>[McBox(origin: McVec3(-1, -7, -1), size: McVec3(2, 7, 2), u: 0, v: 0)]),
    McRigPart(name: 'body', pivot: McVec3.zero, boxes: <McBox>[McBox(origin: McVec3(-6, 0, -1.5), size: McVec3(12, 3, 3), u: 0, v: 26)]),
    McRigPart(name: 'right_arm', pivot: McVec3(-5, 2, 0), boxes: <McBox>[McBox(origin: McVec3(-2, -2, -1), size: McVec3(2, 12, 2), u: 24, v: 0)]),
    McRigPart(
      name: 'left_arm',
      pivot: McVec3(5, 2, 0),
      boxes: <McBox>[McBox(origin: McVec3(0, -2, -1), size: McVec3(2, 12, 2), u: 32, v: 16, mirror: true)],
    ),
    McRigPart(name: 'right_leg', pivot: McVec3(-1.9, 12, 0), boxes: <McBox>[McBox(origin: McVec3(-1, 0, -1), size: McVec3(2, 11, 2), u: 8, v: 0)]),
    McRigPart(
      name: 'left_leg',
      pivot: McVec3(1.9, 12, 0),
      boxes: <McBox>[McBox(origin: McVec3(-1, 0, -1), size: McVec3(2, 11, 2), u: 40, v: 16, mirror: true)],
    ),
    McRigPart(name: 'right_body_stick', pivot: McVec3.zero, boxes: <McBox>[McBox(origin: McVec3(-3, 3, -1), size: McVec3(2, 7, 2), u: 16, v: 0)]),
    McRigPart(name: 'left_body_stick', pivot: McVec3.zero, boxes: <McBox>[McBox(origin: McVec3(1, 3, -1), size: McVec3(2, 7, 2), u: 48, v: 16)]),
    McRigPart(name: 'shoulder_stick', pivot: McVec3.zero, boxes: <McBox>[McBox(origin: McVec3(-4, 10, -1), size: McVec3(8, 2, 2), u: 0, v: 48)]),
    McRigPart(name: 'base_plate', pivot: McVec3(0, 12, 0), boxes: <McBox>[McBox(origin: McVec3(-6, 11, -6), size: McVec3(12, 1, 12), u: 0, v: 32)]),
  ],
);

/// `HumanoidModel.BABY_TRANSFORMER` as the small armor stand uses it: the
/// head moves down 16 px and scales by 0.75, every other root part moves down
/// 24 px and halves, so the feet stay on the ground.
McRig mcSmallTransform(McRig rig, {Set<String> headParts = const <String>{'head'}}) {
  McRigPart scaled(McRigPart part) {
    final bool head = headParts.contains(part.name);
    final double scale = head ? 0.75 : 0.5;
    final double drop = head ? 16 : 24;
    return McRigPart(
      name: part.name,
      pivot: McVec3(part.pivot.x * scale, (part.pivot.y + drop) * scale, part.pivot.z * scale),
      rotationDeg: part.rotationDeg,
      scale: part.scale * scale,
      boxes: part.boxes,
      children: part.children,
    );
  }

  return rig.copyWith(id: '${rig.id}_small', parts: <McRigPart>[for (final McRigPart part in rig.parts) scaled(part)], heightBlocks: rig.heightBlocks / 2);
}

// --- horse -----------------------------------------------------------------

/// `AbstractEquineModel.createBodyMesh(g)`, scaled 1.1 like the living horse.
/// [g] is 0 for the body and 0.1 for the armour layer; [saddle] adds the
/// `EquineSaddleModel` parts (drawn with the saddle texture, whose body area
/// is empty).
McRig mcHorseRig({double g = 0, bool saddle = false}) {
  final McVec3 thirty = mcRadians(math.pi / 6, 0, 0);
  return McRig(
    id: saddle ? 'horse_saddle' : (g == 0 ? 'horse' : 'horse_armor'),
    textureWidth: 64,
    textureHeight: 64,
    scale: 1.1,
    heightBlocks: 1.6,
    parts: <McRigPart>[
      McRigPart(
        name: 'body',
        pivot: const McVec3(0, 11, 5),
        boxes: <McBox>[
          const McBox(origin: McVec3(-5, -8, -17), size: McVec3(10, 10, 22), u: 0, v: 32, inflate: 0.05),
          if (saddle) const McBox(origin: McVec3(-5, -8, -9), size: McVec3(10, 9, 9), u: 26, v: 0, inflate: 0.5),
        ],
        children: <McRigPart>[
          McRigPart(
            name: 'tail',
            pivot: const McVec3(0, -5, 2),
            rotationDeg: thirty,
            boxes: <McBox>[McBox(origin: const McVec3(-1.5, 0, 0), size: const McVec3(3, 14, 4), u: 42, v: 36, inflate: g)],
          ),
        ],
      ),
      McRigPart(
        name: 'head_parts',
        pivot: const McVec3(0, 4, -12),
        rotationDeg: thirty,
        boxes: const <McBox>[McBox(origin: McVec3(-2.05, -6, -2), size: McVec3(4, 12, 7), u: 0, v: 35)],
        children: <McRigPart>[
          McRigPart(
            name: 'head',
            pivot: McVec3.zero,
            boxes: <McBox>[McBox(origin: const McVec3(-3, -11, -2), size: const McVec3(6, 5, 7), u: 0, v: 13, inflate: g)],
            children: const <McRigPart>[
              McRigPart(name: 'left_ear', pivot: McVec3.zero, boxes: <McBox>[McBox(origin: McVec3(0.55, -13, 4), size: McVec3(2, 3, 1), u: 19, v: 16, inflate: -0.001)]),
              McRigPart(name: 'right_ear', pivot: McVec3.zero, boxes: <McBox>[McBox(origin: McVec3(-2.55, -13, 4), size: McVec3(2, 3, 1), u: 19, v: 16, inflate: -0.001)]),
            ],
          ),
          McRigPart(name: 'mane', pivot: McVec3.zero, boxes: <McBox>[McBox(origin: const McVec3(-1, -11, 5.01), size: const McVec3(2, 16, 2), u: 56, v: 36, inflate: g)]),
          McRigPart(name: 'upper_mouth', pivot: McVec3.zero, boxes: <McBox>[McBox(origin: const McVec3(-2, -11, -7), size: const McVec3(4, 5, 5), u: 0, v: 25, inflate: g)]),
          if (saddle) ...<McRigPart>[
            const McRigPart(name: 'left_saddle_mouth', pivot: McVec3.zero, boxes: <McBox>[McBox(origin: McVec3(2, -9, -6), size: McVec3(1, 2, 2), u: 29, v: 5)]),
            const McRigPart(name: 'right_saddle_mouth', pivot: McVec3.zero, boxes: <McBox>[McBox(origin: McVec3(-3, -9, -6), size: McVec3(1, 2, 2), u: 29, v: 5)]),
            const McRigPart(name: 'head_saddle', pivot: McVec3.zero, boxes: <McBox>[McBox(origin: McVec3(-3, -11, -1.9), size: McVec3(6, 5, 6), u: 1, v: 1, inflate: 0.22)]),
            const McRigPart(name: 'mouth_saddle_wrap', pivot: McVec3.zero, boxes: <McBox>[McBox(origin: McVec3(-2, -11, -4), size: McVec3(4, 5, 2), u: 19, v: 0, inflate: 0.2)]),
          ],
        ],
      ),
      McRigPart(
        name: 'left_hind_leg',
        pivot: const McVec3(4, 14, 7),
        boxes: <McBox>[McBox(origin: const McVec3(-3, -1.01, -1), size: const McVec3(4, 11, 4), u: 48, v: 21, mirror: true, inflate: g)],
      ),
      McRigPart(
        name: 'right_hind_leg',
        pivot: const McVec3(-4, 14, 7),
        boxes: <McBox>[McBox(origin: const McVec3(-1, -1.01, -1), size: const McVec3(4, 11, 4), u: 48, v: 21, inflate: g)],
      ),
      McRigPart(
        name: 'left_front_leg',
        pivot: const McVec3(4, 14, -10),
        boxes: <McBox>[McBox(origin: const McVec3(-3, -1.01, -1.9), size: const McVec3(4, 11, 4), u: 48, v: 21, mirror: true, inflate: g)],
      ),
      McRigPart(
        name: 'right_front_leg',
        pivot: const McVec3(-4, 14, -10),
        boxes: <McBox>[McBox(origin: const McVec3(-1, -1.01, -1.9), size: const McVec3(4, 11, 4), u: 48, v: 21, inflate: g)],
      ),
    ],
  );
}

// --- turtle ----------------------------------------------------------------

McRig mcTurtleRig() {
  final McVec3 ninety = mcRadians(math.pi / 2, 0, 0);
  return McRig(
    id: 'turtle',
    textureWidth: 128,
    textureHeight: 64,
    heightBlocks: 0.4,
    parts: <McRigPart>[
      const McRigPart(name: 'head', pivot: McVec3(0, 19, -10), boxes: <McBox>[McBox(origin: McVec3(-3, -1, -3), size: McVec3(6, 5, 6), u: 3, v: 0)]),
      McRigPart(
        name: 'body',
        pivot: const McVec3(0, 11, -10),
        rotationDeg: ninety,
        boxes: const <McBox>[
          McBox(origin: McVec3(-9.5, 3, -10), size: McVec3(19, 20, 6), u: 7, v: 37),
          McBox(origin: McVec3(-5.5, 3, -13), size: McVec3(11, 18, 3), u: 31, v: 1),
        ],
      ),
      const McRigPart(name: 'right_hind_leg', pivot: McVec3(-3.5, 22, 11), boxes: <McBox>[McBox(origin: McVec3(-2, 0, 0), size: McVec3(4, 1, 10), u: 1, v: 23)]),
      const McRigPart(name: 'left_hind_leg', pivot: McVec3(3.5, 22, 11), boxes: <McBox>[McBox(origin: McVec3(-2, 0, 0), size: McVec3(4, 1, 10), u: 1, v: 12)]),
      const McRigPart(name: 'right_front_leg', pivot: McVec3(-5, 21, -4), boxes: <McBox>[McBox(origin: McVec3(-13, 0, -2), size: McVec3(13, 1, 5), u: 27, v: 30)]),
      const McRigPart(name: 'left_front_leg', pivot: McVec3(5, 21, -4), boxes: <McBox>[McBox(origin: McVec3(0, 0, -2), size: McVec3(13, 1, 5), u: 27, v: 24)]),
    ],
  );
}

// --- frog ------------------------------------------------------------------

McRig mcFrogRig() => const McRig(
  id: 'frog',
  textureWidth: 48,
  textureHeight: 48,
  heightBlocks: 0.55,
  parts: <McRigPart>[
    McRigPart(
      name: 'root',
      pivot: McVec3(0, 24, 0),
      children: <McRigPart>[
        McRigPart(
          name: 'body',
          pivot: McVec3(0, -2, 4),
          boxes: <McBox>[
            McBox(origin: McVec3(-3.5, -2, -8), size: McVec3(7, 3, 9), u: 3, v: 1),
            McBox(origin: McVec3(-3.5, -1, -8), size: McVec3(7, 0, 9), u: 23, v: 22),
          ],
          children: <McRigPart>[
            McRigPart(
              name: 'head',
              pivot: McVec3(0, -2, -1),
              boxes: <McBox>[
                McBox(origin: McVec3(-3.5, -1, -7), size: McVec3(7, 0, 9), u: 23, v: 13),
                McBox(origin: McVec3(-3.5, -2, -7), size: McVec3(7, 3, 9), u: 0, v: 13),
              ],
              children: <McRigPart>[
                McRigPart(
                  name: 'eyes',
                  pivot: McVec3(-0.5, 0, 2),
                  children: <McRigPart>[
                    McRigPart(name: 'right_eye', pivot: McVec3(-1.5, -3, -6.5), boxes: <McBox>[McBox(origin: McVec3(-1.5, -1, -1.5), size: McVec3(3, 2, 3), u: 0, v: 0)]),
                    McRigPart(name: 'left_eye', pivot: McVec3(2.5, -3, -6.5), boxes: <McBox>[McBox(origin: McVec3(-1.5, -1, -1.5), size: McVec3(3, 2, 3), u: 0, v: 5)]),
                  ],
                ),
              ],
            ),
            McRigPart(name: 'tongue', pivot: McVec3(0, -1.01, 1), boxes: <McBox>[McBox(origin: McVec3(-2, 0, -7.1), size: McVec3(4, 0, 7), u: 17, v: 13)]),
            McRigPart(
              name: 'left_arm',
              pivot: McVec3(4, -1, -6.5),
              boxes: <McBox>[McBox(origin: McVec3(-1, 0, -1), size: McVec3(2, 3, 3), u: 0, v: 32)],
              children: <McRigPart>[
                McRigPart(name: 'left_hand', pivot: McVec3(0, 3, -1), boxes: <McBox>[McBox(origin: McVec3(-4, 0.01, -4), size: McVec3(8, 0, 8), u: 18, v: 40)]),
              ],
            ),
            McRigPart(
              name: 'right_arm',
              pivot: McVec3(-4, -1, -6.5),
              boxes: <McBox>[McBox(origin: McVec3(-1, 0, -1), size: McVec3(2, 3, 3), u: 0, v: 38)],
              children: <McRigPart>[
                McRigPart(name: 'right_hand', pivot: McVec3(0, 3, 0), boxes: <McBox>[McBox(origin: McVec3(-4, 0.01, -5), size: McVec3(8, 0, 8), u: 2, v: 40)]),
              ],
            ),
          ],
        ),
        McRigPart(
          name: 'left_leg',
          pivot: McVec3(3.5, -3, 4),
          boxes: <McBox>[McBox(origin: McVec3(-1, 0, -2), size: McVec3(3, 3, 4), u: 14, v: 25)],
          children: <McRigPart>[
            McRigPart(name: 'left_foot', pivot: McVec3(2, 3, 0), boxes: <McBox>[McBox(origin: McVec3(-4, 0.01, -4), size: McVec3(8, 0, 8), u: 2, v: 32)]),
          ],
        ),
        McRigPart(
          name: 'right_leg',
          pivot: McVec3(-3.5, -3, 4),
          boxes: <McBox>[McBox(origin: McVec3(-2, 0, -2), size: McVec3(3, 3, 4), u: 0, v: 25)],
          children: <McRigPart>[
            McRigPart(name: 'right_foot', pivot: McVec3(-2, 3, 0), boxes: <McBox>[McBox(origin: McVec3(-4, 0.01, -4), size: McVec3(8, 0, 8), u: 18, v: 32)]),
          ],
        ),
      ],
    ),
  ],
);

// --- llama -----------------------------------------------------------------

/// `LlamaModel.createBodyLayer(g)`: 0 for the body, 0.5 for the carpet
/// (`LLAMA_DECOR`).
McRig mcLlamaRig({double g = 0}) {
  McBox leg() => McBox(origin: const McVec3(-2, 0, -2), size: const McVec3(4, 14, 4), u: 29, v: 29, inflate: g);
  return McRig(
    id: g == 0 ? 'llama' : 'llama_decor',
    textureWidth: 128,
    textureHeight: 64,
    heightBlocks: 1.87,
    parts: <McRigPart>[
      McRigPart(
        name: 'head',
        pivot: const McVec3(0, 7, -6),
        boxes: <McBox>[
          McBox(origin: const McVec3(-2, -14, -10), size: const McVec3(4, 4, 9), u: 0, v: 0, inflate: g),
          McBox(origin: const McVec3(-4, -16, -6), size: const McVec3(8, 18, 6), u: 0, v: 14, inflate: g),
          McBox(origin: const McVec3(-4, -19, -4), size: const McVec3(3, 3, 2), u: 17, v: 0, inflate: g),
          McBox(origin: const McVec3(1, -19, -4), size: const McVec3(3, 3, 2), u: 17, v: 0, inflate: g),
        ],
      ),
      McRigPart(
        name: 'body',
        pivot: const McVec3(0, 5, 2),
        rotationDeg: mcRadians(math.pi / 2, 0, 0),
        boxes: <McBox>[McBox(origin: const McVec3(-6, -10, -7), size: const McVec3(12, 18, 10), u: 29, v: 0, inflate: g)],
      ),
      McRigPart(name: 'right_hind_leg', pivot: const McVec3(-3.5, 10, 6), boxes: <McBox>[leg()]),
      McRigPart(name: 'left_hind_leg', pivot: const McVec3(3.5, 10, 6), boxes: <McBox>[leg()]),
      McRigPart(name: 'right_front_leg', pivot: const McVec3(-3.5, 10, -5), boxes: <McBox>[leg()]),
      McRigPart(name: 'left_front_leg', pivot: const McVec3(3.5, 10, -5), boxes: <McBox>[leg()]),
    ],
  );
}

// --- armadillo -------------------------------------------------------------

McRig mcArmadilloRig() => McRig(
  id: 'armadillo',
  textureWidth: 64,
  textureHeight: 64,
  heightBlocks: 0.65,
  parts: <McRigPart>[
    McRigPart(
      name: 'body',
      pivot: const McVec3(0, 21, 4),
      boxes: const <McBox>[
        McBox(origin: McVec3(-4, -7, -10), size: McVec3(8, 8, 12), u: 0, v: 20, inflate: 0.3),
        McBox(origin: McVec3(-4, -7, -10), size: McVec3(8, 8, 12), u: 0, v: 40),
      ],
      children: <McRigPart>[
        McRigPart(
          name: 'tail',
          pivot: const McVec3(0, -3, 1),
          rotationDeg: mcRadians(0.5061, 0, 0),
          boxes: const <McBox>[McBox(origin: McVec3(-0.5, -0.0865, 0.0933), size: McVec3(1, 6, 1), u: 44, v: 53)],
        ),
        McRigPart(
          name: 'head',
          pivot: const McVec3(0, -2, -11),
          children: <McRigPart>[
            McRigPart(
              name: 'head_cube',
              pivot: McVec3.zero,
              rotationDeg: mcRadians(-0.3927, 0, 0),
              boxes: const <McBox>[McBox(origin: McVec3(-1.5, -1, -1), size: McVec3(3, 5, 2), u: 43, v: 15)],
            ),
            McRigPart(
              name: 'right_ear',
              pivot: const McVec3(-1, -1, 0),
              children: <McRigPart>[
                McRigPart(
                  name: 'right_ear_cube',
                  pivot: const McVec3(-0.5, 0, -0.6),
                  rotationDeg: mcRadians(0.1886, -0.3864, -0.0718),
                  boxes: const <McBox>[McBox(origin: McVec3(-2, -3, 0), size: McVec3(2, 5, 0), u: 43, v: 10)],
                ),
              ],
            ),
            McRigPart(
              name: 'left_ear',
              pivot: const McVec3(1, -2, 0),
              children: <McRigPart>[
                McRigPart(
                  name: 'left_ear_cube',
                  pivot: const McVec3(0.5, 1, -0.6),
                  rotationDeg: mcRadians(0.1886, 0.3864, 0.0718),
                  boxes: const <McBox>[McBox(origin: McVec3(0, -3, 0), size: McVec3(2, 5, 0), u: 47, v: 10)],
                ),
              ],
            ),
          ],
        ),
      ],
    ),
    const McRigPart(name: 'right_hind_leg', pivot: McVec3(-2, 21, 4), boxes: <McBox>[McBox(origin: McVec3(-1, 0, -1), size: McVec3(2, 3, 2), u: 51, v: 31)]),
    const McRigPart(name: 'left_hind_leg', pivot: McVec3(2, 21, 4), boxes: <McBox>[McBox(origin: McVec3(-1, 0, -1), size: McVec3(2, 3, 2), u: 42, v: 31)]),
    const McRigPart(name: 'right_front_leg', pivot: McVec3(-2, 21, -4), boxes: <McBox>[McBox(origin: McVec3(-1, 0, -1), size: McVec3(2, 3, 2), u: 51, v: 43)]),
    const McRigPart(name: 'left_front_leg', pivot: McVec3(2, 21, -4), boxes: <McBox>[McBox(origin: McVec3(-1, 0, -1), size: McVec3(2, 3, 2), u: 42, v: 43)]),
  ],
);
