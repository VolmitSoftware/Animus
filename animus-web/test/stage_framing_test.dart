import 'dart:math' as math;

import 'package:animus_editor/logic/npc/route_model.dart';
import 'package:animus_editor/mc/mesh/mc_mesh.dart';
import 'package:animus_editor/mc/npc/npc_look.dart';
import 'package:animus_editor/mc/npc/npc_scene.dart';
import 'package:animus_editor/mc/npc/route_overlay.dart';
import 'package:animus_editor/mc/rigs/mc_rig_mesher.dart';
import 'package:animus_editor/mc/scene/mc_camera.dart';
import 'package:animus_editor/mc/scene/mc_math.dart';
import 'package:animus_editor/mc/scene/mc_scene.dart';
import 'package:test/test.dart';

/// Stage sizes the preview is drawn at: beside the form at 1600, in the
/// 240 px band at 761-1099, and on a phone.
const List<(double, double)> _sizes = <(double, double)>[(526, 395), (700, 240), (356, 267)];

/// Every vertex of every rig layer [scene] draws, where it stands.
List<McVec3> _drawn(McScene scene) => <McVec3>[
  for (final McSceneNode node in scene.nodes)
    if (node is McRigNode)
      for (final McMesh mesh in <McMesh>[mcMeshRig(node.rig, pose: node.pose)])
        for (int v = 0; v < mesh.vertexCount; v++) node.transform.transformPoint(McVec3(mesh.positions[v * 3], mesh.positions[v * 3 + 1], mesh.positions[v * 3 + 2])),
];

/// The part of a [w] x [h] view the [points] cover, in pixels. The home
/// framing keeps what it frames 7% in from every edge.
({double left, double top, double right, double bottom}) _box(McCamera camera, Iterable<McVec3> points, double w, double h) {
  double left = double.infinity, top = double.infinity, right = -double.infinity, bottom = -double.infinity;
  for (final McVec3 point in points) {
    final McVec3 at = mcProjectToPixels(camera, point, widthPx: w, heightPx: h)!;
    left = math.min(left, at.x);
    right = math.max(right, at.x);
    top = math.min(top, at.y);
    bottom = math.max(bottom, at.y);
  }
  return (left: left, top: top, right: right, bottom: bottom);
}

/// An imported training stand: posed arms, base plate, leather armour and
/// a sword and shield, its name hidden.
const Map<String, Object?> _stand = <String, Object?>{
  'type': 'minecraft:armor_stand',
  'name': 'Dummy',
  'nameplate': <String, Object?>{'visible': false},
  'armorStand': <String, Object?>{
    'small': false,
    'arms': true,
    'basePlate': true,
    'invisible': false,
    'head': <double>[0, 0, 0],
    'body': <double>[0, 0, 0],
    'leftArm': <double>[-10, 0, -10],
    'rightArm': <double>[-15, 0, 10],
    'leftLeg': <double>[-1, 0, -1],
    'rightLeg': <double>[1, 0, 1],
  },
  'equipment': <String, Object?>{
    'main_hand': <String, Object?>{'id': 'minecraft:wooden_sword'},
    'off_hand': <String, Object?>{'id': 'minecraft:shield'},
    'head': <String, Object?>{'id': 'minecraft:leather_helmet'},
    'chest': <String, Object?>{'id': 'minecraft:leather_chestplate'},
    'legs': <String, Object?>{'id': 'minecraft:leather_leggings'},
    'feet': <String, Object?>{'id': 'minecraft:leather_boots'},
  },
};

/// A route of [count] points winding round a square about 30 blocks
/// across, starting a few blocks from the NPC at the origin.
RouteSpec _longRoute(int count) => RouteSpec(<String, Object?>{
  'points': <Object?>[
    for (int point = 0; point < count; point++) <String, Object?>{'x': 15 * math.cos(point * 2 * math.pi / count) + point % 3 - 12, 'y': 64, 'z': 15 * math.sin(point * 2 * math.pi / count)},
  ],
});

void main() {
  group('home framing', () {
    test('an armoured armor stand stays whole inside the view at every stage size and facing', () {
      for (final double yaw in <double>[0, -32, 45, 90, 150]) {
        final NpcLook look = NpcLook.fromDefinition(<String, Object?>{
          ..._stand,
          'position': <String, Object?>{'world': 'world', 'x': 0, 'y': 64, 'z': 0, 'yaw': yaw},
        });
        final NpcScene scene = npcScene(look);
        final List<McVec3> drawn = _drawn(scene.scene);
        expect(drawn, isNotEmpty);
        for (final (double w, double h) in _sizes) {
          final McCamera home = mcHomeCamera(scene.scene.frame, scene.texts, yawDeg: look.yawDeg, widthPx: w, heightPx: h);
          final ({double left, double top, double right, double bottom}) box = _box(home, drawn, w, h);
          expect(box.top, greaterThanOrEqualTo(h * 0.05), reason: '$w x $h, yaw $yaw: the helmet leaves the top');
          expect(box.bottom, lessThanOrEqualTo(h * 0.95), reason: '$w x $h, yaw $yaw: the base plate leaves the bottom');
          expect(box.left, greaterThanOrEqualTo(w * 0.05), reason: '$w x $h, yaw $yaw');
          expect(box.right, lessThanOrEqualTo(w * 0.95), reason: '$w x $h, yaw $yaw');
          expect(box.bottom - box.top, greaterThan(h * 0.6), reason: '$w x $h: the stand still fills the view');
        }
      }
    });

    test('a long route frames the NPC first and only the points close to it', () {
      final NpcLook look = NpcLook.fromDefinition(const <String, Object?>{'type': 'minecraft:player', 'name': 'Fisher'});
      final NpcScene body = npcScene(look);
      final NpcScene routed = withRoute(body, _longRoute(34), (0, 64, 0));
      for (final (double w, double h) in _sizes) {
        final McCamera home = mcHomeCamera(routed.scene.frame, routed.texts, yawDeg: look.yawDeg, widthPx: w, heightPx: h);
        final ({double left, double top, double right, double bottom}) subject = _box(home, _drawn(body.scene), w, h);
        expect(subject.bottom - subject.top, greaterThan(h * 0.3), reason: '$w x $h: the NPC is more than a few pixels');
        expect((subject.left + subject.right) / 2, inInclusiveRange(w * 0.4, w * 0.6), reason: '$w x $h: the NPC stays in the middle');
        expect(subject.top, greaterThanOrEqualTo(0));
        expect(subject.bottom, lessThanOrEqualTo(h));
      }
      // A few nearby points are framed with the NPC (each with its mirror
      // through the NPC, so it stays in the middle), never the whole route.
      final int framed = (routed.scene.frame.length - body.scene.frame.length) ~/ 2;
      expect(framed, inInclusiveRange(1, 33));
    });

    test('a short route close by is framed whole', () {
      final NpcLook look = NpcLook.fromDefinition(const <String, Object?>{'type': 'minecraft:player', 'name': 'Walker'});
      final NpcScene body = npcScene(look);
      final RouteSpec spec = RouteSpec(const <String, Object?>{
        'points': <Object?>[
          <String, Object?>{'x': 2, 'y': 64, 'z': 0},
          <String, Object?>{'x': -2, 'y': 64, 'z': 1},
          <String, Object?>{'x': 0, 'y': 64, 'z': -2},
        ],
      });
      final NpcScene routed = withRoute(body, spec, (0, 64, 0));
      expect(routed.scene.frame.length - body.scene.frame.length, 6, reason: 'three points and their mirrors');
    });

    test('a dragon frames its body and keeps the framed points off the edges', () {
      final NpcLook look = NpcLook.fromDefinition(const <String, Object?>{'type': 'minecraft:ender_dragon', 'name': 'Dragon'});
      final NpcScene body = npcScene(look);
      final RouteSpec spec = RouteSpec(const <String, Object?>{
        'points': <Object?>[
          <String, Object?>{'x': 30, 'y': 80, 'z': 10},
          <String, Object?>{'x': 0, 'y': 64, 'z': 4},
          <String, Object?>{'x': -40, 'y': 90, 'z': -20},
        ],
      });
      final NpcScene routed = withRoute(body, spec, (0, 64, 0));
      final List<McVec3> points = routed.scene.frame.skip(body.scene.frame.length).toList();
      for (final (double w, double h) in _sizes) {
        final McCamera home = mcHomeCamera(routed.scene.frame, routed.texts, yawDeg: look.yawDeg, widthPx: w, heightPx: h);
        final ({double left, double top, double right, double bottom}) subject = _box(home, _drawn(body.scene), w, h);
        expect(subject.bottom - subject.top, greaterThan(h * 0.3), reason: '$w x $h');
        if (points.isEmpty) continue;
        final ({double left, double top, double right, double bottom}) marks = _box(home, points, w, h);
        expect(marks.left, greaterThanOrEqualTo(w * 0.05), reason: '$w x $h: a framed point sits on the left edge');
        expect(marks.right, lessThanOrEqualTo(w * 0.95), reason: '$w x $h: a framed point sits on the right edge');
      }
    });
  });

  group('route labels', () {
    test('point numbers give way to other text and show only near the camera', () {
      final NpcScene body = npcScene(NpcLook.fromDefinition(const <String, Object?>{'type': 'minecraft:player', 'name': 'Fisher'}));
      final NpcScene routed = withRoute(body, _longRoute(34), (0, 64, 0));
      final List<NpcText> labels = routed.texts.skip(body.texts.length).toList();
      expect(labels, hasLength(34));
      expect(labels.every((NpcText text) => text.yields && text.nearOnly), isTrue);
    });

    test('the selected point keeps its number', () {
      final NpcScene body = npcScene(NpcLook.fromDefinition(const <String, Object?>{'type': 'minecraft:player', 'name': 'Fisher'}));
      final NpcScene routed = withRoute(body, _longRoute(34), (0, 64, 0), selected: 5);
      final List<NpcText> labels = routed.texts.skip(body.texts.length).toList();
      expect(labels.where((NpcText text) => !text.yields).single.lines, <String>['6']);
    });

    test('point numbers are editor labels on a dark plate', () {
      final NpcScene body = npcScene(NpcLook.fromDefinition(const <String, Object?>{'type': 'minecraft:player', 'name': 'Fisher'}));
      final NpcScene routed = withRoute(body, _longRoute(4), (0, 64, 0));
      expect(routed.texts.skip(body.texts.length).every((NpcText text) => text.nameplate), isTrue);
    });
  });
}
