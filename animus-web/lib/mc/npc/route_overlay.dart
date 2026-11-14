/// A route drawn into the NPC stage around the body: block markers for the
/// points (lime) and guides (light blue), thin bars along the links the
/// runtime may walk, and the point numbers above them. Positions are the
/// route's world coordinates relative to the NPC, whose feet are the origin.
/// Recording steps have no position and are not drawn. The home view frames
/// the body first and the points close to it; a long route runs on past the
/// view's edges, and its numbers show only near the camera.
library;

import 'dart:math' as math;

import '../../logic/npc/route_model.dart';
import '../scene/mc_camera.dart';
import '../scene/mc_math.dart';
import '../scene/mc_scene.dart';
import 'npc_scene.dart';

const double _marker = 0.4;
const double _bar = 0.08;

/// The share of the view's height the body's larger side keeps in the home
/// framing: route points that would shrink it further are not framed.
const double mcRouteBodyShare = 0.35;

/// [base] with [spec] drawn around it; [origin] is the NPC's position and
/// [yawDeg] the way it faces (the home view looks at its front). The point
/// at index [selected] keeps its number wherever the camera is.
NpcScene withRoute(NpcScene base, RouteSpec spec, (double, double, double) origin, {double yawDeg = 0, int? selected}) {
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
    final bool picked = index == selected;
    texts.add(NpcText(anchor: McVec3(point.x, point.y + size + 0.15, point.z), lines: <String>['${index + 1}'], nameplate: true, yields: !picked, nearOnly: !picked));
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
    scene: McScene(
      <McSceneNode>[...base.scene.nodes, ...extra],
      frame: <McVec3>[...base.scene.frame, ..._framedPoints(base.scene.frame, <McVec3>[for (final RouteNode node in placed) at(node)], yawDeg)],
      glow: base.scene.glow,
    ),
    texts: <NpcText>[...base.texts, ...texts],
    notes: base.notes,
    presentable: base.presentable,
  );
}

/// What the home view frames of [points] besides [body]: nearest first,
/// while the body still fills [mcRouteBodyShare] of a 4:3 view along its
/// larger side, each with its mirror through the body's centre, so the
/// body stays in the middle of the view.
List<McVec3> _framedPoints(List<McVec3> body, List<McVec3> points, double yawDeg) {
  if (body.isEmpty) return points;
  final McBounds own = McBounds.of(body);
  final McVec3 centre = own.centre;
  final double size = math.max(1, math.max(own.size.y, math.max(own.size.x, own.size.z)));
  double away(McVec3 point) {
    final double dx = point.x - centre.x, dz = point.z - centre.z;
    return dx * dx + dz * dz;
  }

  final List<McVec3> nearest = <McVec3>[...points]..sort((McVec3 a, McVec3 b) => away(a).compareTo(away(b)));
  final List<McVec3> framed = <McVec3>[...own.corners];
  final List<McVec3> chosen = <McVec3>[];
  final double span = 2 * math.tan(mcStageFovYDeg * math.pi / 360);
  for (final McVec3 point in nearest) {
    final McVec3 mirror = centre * 2 - point;
    final McCamera camera = mcCameraFraming(<McVec3>[...framed, point, mirror], yawDeg: yawDeg + 180);
    if (size / (span * camera.distance) < mcRouteBodyShare) break;
    framed.addAll(<McVec3>[point, mirror]);
    chosen.addAll(<McVec3>[point, mirror]);
  }
  return chosen;
}
