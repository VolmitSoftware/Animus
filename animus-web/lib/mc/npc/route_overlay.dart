/// A route drawn into the NPC stage around the body: block markers for the
/// points (lime) and guides (light blue), thin bars along the links the
/// runtime may walk, and the point numbers above them. Positions are the
/// route's world coordinates relative to the NPC, whose feet are the origin.
/// Recording steps have no position and are not drawn.
library;

import 'dart:math' as math;

import '../../logic/npc/route_model.dart';
import '../scene/mc_math.dart';
import '../scene/mc_scene.dart';
import 'npc_scene.dart';

const double _marker = 0.4;
const double _bar = 0.08;

/// [base] with [spec] drawn around it; [origin] is the NPC's position.
NpcScene withRoute(NpcScene base, RouteSpec spec, (double, double, double) origin) {
  final List<RouteNode> placed = spec.placed;
  if (placed.isEmpty) return base;
  McVec3 at(RouteNode node) => McVec3(node.x - origin.$1, node.y - origin.$2, node.z - origin.$3);
  final List<McSceneNode> extra = <McSceneNode>[];
  final List<NpcText> texts = <NpcText>[];
  final List<RouteEntry> nodes = spec.nodes;
  for (int index = 0; index < nodes.length; index++) {
    final RouteEntry node = nodes[index];
    if (node is! RouteNode) continue;
    final McVec3 point = at(node);
    final bool guide = node.guide;
    final double size = guide ? _marker * 0.7 : _marker;
    extra.add(McBlockNode(
      key: 'route:node:$index',
      blockKey: guide ? 'light_blue_concrete' : 'lime_concrete',
      transform: McMat4.translation(point.x, point.y, point.z).multiply(McMat4.scale(size, size, size)).multiply(McMat4.translation(-0.5, 0, -0.5)),
    ));
    texts.add(NpcText(anchor: McVec3(point.x, point.y + size + 0.15, point.z), lines: <String>['$index'], nameplate: false));
  }
  for (final (int a, int b) in spec.links) {
    final McVec3 from = at(spec.positionOf(a)!) + const McVec3(0, _marker / 2, 0);
    final McVec3 to = at(spec.positionOf(b)!) + const McVec3(0, _marker / 2, 0);
    final double dx = to.x - from.x, dy = to.y - from.y, dz = to.z - from.z;
    final double horizontal = math.sqrt(dx * dx + dz * dz);
    final double length = math.sqrt(horizontal * horizontal + dy * dy);
    if (length < 1e-6) continue;
    final double yaw = math.atan2(dx, dz) * 180 / math.pi;
    final double pitch = -math.atan2(dy, horizontal) * 180 / math.pi;
    extra.add(McBlockNode(
      key: 'route:link:$a-$b',
      blockKey: spec.order == 'random' ? 'light_blue_stained_glass' : 'white_concrete',
      transform: McMat4.translation(from.x, from.y, from.z)
          .multiply(McMat4.rotationY(yaw))
          .multiply(McMat4.rotationX(pitch))
          .multiply(McMat4.scale(_bar, _bar, length))
          .multiply(McMat4.translation(-0.5, -0.5, 0)),
    ));
  }
  return NpcScene(
    scene: McScene(<McSceneNode>[...base.scene.nodes, ...extra], frame: <McVec3>[...base.scene.frame, for (final RouteNode node in placed) at(node)]),
    texts: <NpcText>[...base.texts, ...texts],
    notes: base.notes,
    presentable: base.presentable,
  );
}
