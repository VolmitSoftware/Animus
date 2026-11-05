/// The ender dragon: the 26.3 client's `EnderDragonModel.createBodyLayer`
/// (256x256 texture) posed the way `setupAnim` poses it at flap time 0 with
/// no movement history: the neck and tail chains laid out from their first
/// segment, wings half spread, legs tucked. Model space, Y down; the root
/// sits 2 blocks up and 3 blocks forward of the body origin, as the client
/// puts it.
library;

import 'dart:math' as math;

import '../scene/mc_math.dart';
import 'mc_entity_rigs.dart';
import 'mc_rig.dart';

const List<McBox> _spine = <McBox>[
  McBox(origin: McVec3(-5, -5, -5), size: McVec3(10, 10, 10), u: 192, v: 104),
  McBox(origin: McVec3(-1, -9, -3), size: McVec3(2, 4, 6), u: 48, v: 0),
];

McRig mcEnderDragonRig() {
  const double flap = 0;
  final double bounceBase = math.sin(flap - 1) + 1;
  final double bounce = (bounceBase * bounceBase + bounceBase * 2) * 0.05;

  final List<McRigPart> neck = <McRigPart>[];
  double x = 0, y = 20, z = -12;
  for (int i = 0; i < 5; i++) {
    final double xRot = math.cos(i * 0.45 + flap) * 0.15;
    neck.add(McRigPart(name: 'neck$i', pivot: McVec3(x, y, z), rotationDeg: mcRadians(xRot, 0, 0), boxes: _spine));
    y += math.sin(xRot) * 10;
    z -= math.cos(xRot) * 10;
  }
  final McRigPart head = McRigPart(
    name: 'head',
    pivot: McVec3(x, y, z),
    boxes: const <McBox>[
      McBox(origin: McVec3(-6, -1, -24), size: McVec3(12, 5, 16), u: 176, v: 44),
      McBox(origin: McVec3(-8, -8, -10), size: McVec3(16, 16, 16), u: 112, v: 30),
      McBox(origin: McVec3(-5, -12, -4), size: McVec3(2, 4, 6), u: 0, v: 0, mirror: true),
      McBox(origin: McVec3(-5, -3, -22), size: McVec3(2, 2, 4), u: 112, v: 0, mirror: true),
      McBox(origin: McVec3(3, -12, -4), size: McVec3(2, 4, 6), u: 0, v: 0, mirror: true),
      McBox(origin: McVec3(3, -3, -22), size: McVec3(2, 2, 4), u: 112, v: 0, mirror: true),
    ],
    children: <McRigPart>[
      McRigPart(
        name: 'jaw',
        pivot: const McVec3(0, 4, -8),
        rotationDeg: mcRadians((math.sin(flap) + 1) * 0.2, 0, 0),
        boxes: const <McBox>[McBox(origin: McVec3(-6, 0, -16), size: McVec3(12, 4, 16), u: 176, v: 65)],
      ),
    ],
  );

  final List<McRigPart> tail = <McRigPart>[];
  double tailXRot = 0;
  x = 0;
  y = 10;
  z = 60;
  for (int i = 0; i < 12; i++) {
    tailXRot += math.sin(i * 0.45 + flap) * 0.05;
    tail.add(McRigPart(name: 'tail$i', pivot: McVec3(x, y, z), rotationDeg: mcRadians(tailXRot, math.pi, 0), boxes: _spine));
    y += math.sin(tailXRot) * 10;
    z -= math.cos(math.pi) * math.cos(tailXRot) * 10;
    x -= math.sin(math.pi) * math.cos(tailXRot) * 10;
  }

  final double wingX = 0.125 - math.cos(flap) * 0.2;
  const double wingY = -0.25;
  final double wingZ = -(math.sin(flap) + 0.125) * 0.8;
  final double tipZ = (math.sin(flap + 2) + 0.5) * 0.75;

  List<McRigPart> limbs(double side, bool mirror) => <McRigPart>[
    McRigPart(
      name: side > 0 ? 'left_wing' : 'right_wing',
      pivot: McVec3(12 * side, -15, -6),
      rotationDeg: mcRadians(wingX, wingY * side, wingZ * side),
      boxes: <McBox>[
        McBox(origin: McVec3(side > 0 ? 0 : -56, -4, -4), size: const McVec3(56, 8, 8), u: 112, v: 88, mirror: mirror),
        McBox(origin: McVec3(side > 0 ? 0 : -56, 0, 2), size: const McVec3(56, 0, 56), u: -56, v: 88, mirror: mirror),
      ],
      children: <McRigPart>[
        McRigPart(
          name: side > 0 ? 'left_wing_tip' : 'right_wing_tip',
          pivot: McVec3(56 * side, 0, 0),
          rotationDeg: mcRadians(0, 0, tipZ * side),
          boxes: <McBox>[
            McBox(origin: McVec3(side > 0 ? 0 : -56, -2, -2), size: const McVec3(56, 4, 4), u: 112, v: 136, mirror: mirror),
            McBox(origin: McVec3(side > 0 ? 0 : -56, 0, 2), size: const McVec3(56, 0, 56), u: -56, v: 144, mirror: mirror),
          ],
        ),
      ],
    ),
    McRigPart(
      name: side > 0 ? 'left_front_leg' : 'right_front_leg',
      pivot: McVec3(12 * side, 0, -6),
      rotationDeg: mcRadians(1.3 + bounce * 0.1, 0, 0),
      boxes: const <McBox>[McBox(origin: McVec3(-4, -4, -4), size: McVec3(8, 24, 8), u: 112, v: 104)],
      children: <McRigPart>[
        McRigPart(
          name: side > 0 ? 'left_front_leg_tip' : 'right_front_leg_tip',
          pivot: const McVec3(0, 20, -1),
          rotationDeg: mcRadians(-0.5 - bounce * 0.1, 0, 0),
          boxes: const <McBox>[McBox(origin: McVec3(-3, -1, -3), size: McVec3(6, 24, 6), u: 226, v: 138)],
          children: <McRigPart>[
            McRigPart(
              name: side > 0 ? 'left_front_foot' : 'right_front_foot',
              pivot: const McVec3(0, 23, 0),
              rotationDeg: mcRadians(0.75 + bounce * 0.1, 0, 0),
              boxes: const <McBox>[McBox(origin: McVec3(-4, 0, -12), size: McVec3(8, 4, 16), u: 144, v: 104)],
            ),
          ],
        ),
      ],
    ),
    McRigPart(
      name: side > 0 ? 'left_hind_leg' : 'right_hind_leg',
      pivot: McVec3(16 * side, -4, 34),
      rotationDeg: mcRadians(1 + bounce * 0.1, 0, 0),
      boxes: const <McBox>[McBox(origin: McVec3(-8, -4, -8), size: McVec3(16, 32, 16), u: 0, v: 0)],
      children: <McRigPart>[
        McRigPart(
          name: side > 0 ? 'left_hind_leg_tip' : 'right_hind_leg_tip',
          pivot: const McVec3(0, 32, -4),
          rotationDeg: mcRadians(0.5 + bounce * 0.1, 0, 0),
          boxes: const <McBox>[McBox(origin: McVec3(-6, -2, 0), size: McVec3(12, 32, 12), u: 196, v: 0)],
          children: <McRigPart>[
            McRigPart(
              name: side > 0 ? 'left_hind_foot' : 'right_hind_foot',
              pivot: const McVec3(0, 31, 4),
              rotationDeg: mcRadians(0.75 + bounce * 0.1, 0, 0),
              boxes: const <McBox>[McBox(origin: McVec3(-9, 0, -20), size: McVec3(18, 6, 24), u: 112, v: 0)],
            ),
          ],
        ),
      ],
    ),
  ];

  final McRigPart body = McRigPart(
    name: 'body',
    pivot: const McVec3(0, 20, 8),
    boxes: const <McBox>[
      McBox(origin: McVec3(-12, -16, -16), size: McVec3(24, 24, 64), u: 0, v: 0),
      McBox(origin: McVec3(-1, -22, -10), size: McVec3(2, 6, 12), u: 220, v: 53),
      McBox(origin: McVec3(-1, -22, 10), size: McVec3(2, 6, 12), u: 220, v: 53),
      McBox(origin: McVec3(-1, -22, 30), size: McVec3(2, 6, 12), u: 220, v: 53),
    ],
    children: <McRigPart>[...limbs(1, true), ...limbs(-1, false)],
  );

  return McRig(
    id: 'ender_dragon',
    textureWidth: 256,
    textureHeight: 256,
    heightBlocks: 8,
    parts: <McRigPart>[
      McRigPart(
        name: 'root',
        pivot: McVec3(0, (bounce - 2) * 16, -48),
        rotationDeg: mcRadians(bounce * 2 * math.pi / 180, 0, 0),
        children: <McRigPart>[...neck, head, ...tail, body],
      ),
    ],
  );
}
