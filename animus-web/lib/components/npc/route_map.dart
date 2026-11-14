/// The route drawn top down on a block grid: terrain tiles under it when the
/// server shares them, grid lines, the legs the runtime may walk, wander
/// areas, the NPC and every point and guide, numbered from 1. It draws
/// again only when what it shows changes ([RouteMap.signature]), so typing
/// in a route field does not redraw the map. Pointer and key handling stays
/// with the editor, which reads its newest state when an event arrives.
library;

import 'dart:math' as math;

import 'package:jaspr/dom.dart' as dom;
import 'package:jaspr/jaspr.dart' show Component;
import 'package:web/web.dart' as web;

import 'package:arcane_jaspr/arcane_jaspr.dart';

import '../../logic/npc/npc_display.dart';
import '../../logic/npc/route_model.dart';
import '../../logic/terrain/terrain_snap.dart';

/// What the map hands back to the editor.
class RouteMapEvents {
  const RouteMapEvents({required this.onGrid, required this.onNodeKey, required this.onNodePointer});

  /// A click on the grid itself, with the bounds it was drawn with.
  final void Function(web.Event event, RouteBounds bounds) onGrid;
  final void Function(web.Event event, String key, int index) onNodeKey;
  final void Function(web.Event event, String key, int index, RouteBounds bounds) onNodePointer;
}

class RouteMap extends StatelessWidget {
  const RouteMap({
    required this.id,
    required this.spec,
    required this.bounds,
    required this.signature,
    required this.events,
    this.origin,
    this.picked,
    this.readOnly = false,
    this.adding = true,
    this.tiles = const <Component>[],
    this.issues = const <(String, int), TerrainIssue>{},
    this.steep = const <(int, int), (int, int, int)>{},
    super.key,
  });

  final String id;
  final RouteSpec spec;
  final RouteBounds bounds;

  /// Everything the drawing depends on, as text; equal text skips a rebuild.
  final String signature;
  final RouteMapEvents events;
  final (double, double, double)? origin;
  final (String, int)? picked;
  final bool readOnly;

  /// A click on the grid adds a point or guide.
  final bool adding;

  /// Terrain images under the grid.
  final List<Component> tiles;
  final Map<(String, int), TerrainIssue> issues;
  final Map<(int, int), (int, int, int)> steep;

  @override
  bool shouldRebuild(RouteMap newComponent) => newComponent.signature != signature || newComponent.id != id;

  /// The element id of node [index] of [key] (`points` or `guides`).
  static String nodeId(String grid, String key, int index) => '$grid-$key-$index';

  static String _n(double value) => value.toStringAsFixed(2);

  static String _coord(double value) => shownNumber(value);

  @override
  Widget build(BuildContext context) {
    final double step = bounds.step;
    final double radius = math.max(0.3, bounds.width / 70);
    final (double, double, double)? origin = this.origin;
    final List<Component> grid = <Component>[
      ...tiles,
      for (double x = (bounds.minX / step).ceilToDouble() * step; x <= bounds.maxX; x += step)
        dom.line(const <Component>[], x1: _n(x), y1: _n(bounds.minZ), x2: _n(x), y2: _n(bounds.maxZ), classes: 'animus-route-gridline${x == 0 ? ' is-axis' : ''}'),
      for (double z = (bounds.minZ / step).ceilToDouble() * step; z <= bounds.maxZ; z += step)
        dom.line(const <Component>[], x1: _n(bounds.minX), y1: _n(z), x2: _n(bounds.maxX), y2: _n(z), classes: 'animus-route-gridline${z == 0 ? ' is-axis' : ''}'),
    ];
    Component link((int, int) pair, String kind, Map<String, String> attributes) => dom.line(
          const <Component>[],
          x1: _n(spec.positionOf(pair.$1)!.x),
          y1: _n(spec.positionOf(pair.$1)!.z),
          x2: _n(spec.positionOf(pair.$2)!.x),
          y2: _n(spec.positionOf(pair.$2)!.z),
          classes: 'animus-route-link$kind',
          attributes: attributes,
        );
    final List<Component> links = <Component>[
      for (final (int, int) pair in spec.links)
        link(pair, '${spec.order == 'random' ? ' is-mesh' : ''}${steep.containsKey(pair) ? ' is-steep' : ''}', <String, String>{if (steep.containsKey(pair)) 'data-steep': '${pair.$1}:${pair.$2}'}),
      for (final (int, int) pair in spec.junctionLinks) link(pair, ' is-junction', <String, String>{'data-junction': '${pair.$1}:${pair.$2}'}),
      if (spec.order == 'wander')
        for (final RouteNode point in spec.points.whereType<RouteNode>())
          dom.rect(
            const <Component>[],
            x: _n(point.x - spec.wanderRadius),
            y: _n(point.z - spec.wanderRadius),
            width: _n(spec.wanderRadius * 2.0),
            height: _n(spec.wanderRadius * 2.0),
            classes: 'animus-route-wander',
          ),
    ];
    final List<Component> markers = <Component>[
      if (origin != null) ...<Component>[
        dom.circle(const <Component>[], cx: _n(origin.$1), cy: _n(origin.$3), r: _n(radius * 1.3), classes: 'animus-route-npc'),
        _npcLabel(origin, radius, spec.nodes.whereType<RouteNode>()),
      ],
      for (int index = 0; index < spec.guides.length; index++)
        if (spec.guides[index] case final RouteNode guide) _node('guides', index, guide, radius * 0.8, spec.points.length + index + 1),
      for (int index = 0; index < spec.points.length; index++)
        if (spec.points[index] case final RouteNode point) _node('points', index, point, radius, index + 1),
    ];
    final int steps = spec.points.whereType<RouteStep>().length;
    return dom.svg(
      <Component>[...grid, ...links, ...markers],
      id: id,
      classes: 'animus-route-grid animus-route-map${readOnly ? ' is-readonly' : ''}${adding && !readOnly ? ' is-adding' : ''}',
      viewBox: '${_n(bounds.minX)} ${_n(bounds.minZ)} ${_n(bounds.width)} ${_n(bounds.depth)}',
      // Blocks across, so a short route draws a small map (see the CSS).
      styles: dom.Styles(raw: <String, String>{'--route-span': _n(bounds.width)}),
      attributes: <String, String>{
        'role': 'img',
        'aria-label': 'Route map, top down: ${spec.points.length} points${steps == 0 ? '' : ' ($steps recording ${steps == 1 ? 'step' : 'steps'}, not drawn)'}, '
            '${spec.guides.length} guides, x ${bounds.minX.toInt()} to ${bounds.maxX.toInt()}, z ${bounds.minZ.toInt()} to ${bounds.maxZ.toInt()}',
        'data-min-x': _n(bounds.minX),
        'data-min-z': _n(bounds.minZ),
        'data-size': _n(bounds.width),
        'preserveAspectRatio': 'none',
      },
      events: <String, void Function(web.Event)>{'click': (web.Event event) => events.onGrid(event, bounds)},
    );
  }

  /// "NPC" above its circle, else below or beside it, wherever no point
  /// is drawn (a route usually starts right at the NPC).
  Component _npcLabel((double, double, double) origin, double radius, Iterable<RouteNode> nodes) {
    final List<(double, double)> spots = <(double, double)>[
      (origin.$1, origin.$3 - radius * 2),
      (origin.$1, origin.$3 + radius * 2.4),
      (origin.$1 - radius * 3.2, origin.$3),
      (origin.$1 + radius * 3.2, origin.$3),
    ];
    bool clear((double, double) spot) => nodes.every((RouteNode node) => (node.x - spot.$1).abs() > radius * 2.6 || (node.z - spot.$2).abs() > radius * 1.8);
    final (double, double) at = spots.firstWhere(clear, orElse: () => spots.first);
    return _label(at.$1, at.$2, 'NPC', radius, 'is-npc');
  }

  Component _label(double x, double z, String text, double radius, String kind) => Component.element(
        tag: 'text',
        classes: 'animus-route-label $kind',
        attributes: <String, String>{'x': _n(x), 'y': _n(z), 'font-size': _n(radius * 1.6), 'text-anchor': 'middle', 'dominant-baseline': 'central'},
        children: <Component>[Component.text(text)],
      );

  Component _node(String key, int index, RouteNode node, double radius, int number) {
    final bool selected = picked != null && picked!.$1 == key && picked!.$2 == index;
    final bool guide = key == 'guides';
    final TerrainIssue? issue = issues[(key, index)];
    final String name = guide ? 'Guide $number' : 'Point $number';
    final Map<String, String> attributes = <String, String>{
      'id': nodeId(id, key, index),
      'data-node': '$key:$index',
      'data-y': _coord(node.y),
      if (issue != null) 'data-issue': issue.name,
      'role': 'button',
      'tabindex': '0',
      'aria-label': '$name at ${_coord(node.x)}, ${_coord(node.y)}, ${_coord(node.z)}${issue == null ? '' : ', ${issue.text}'}',
      'aria-pressed': '$selected',
      if (!readOnly) 'aria-keyshortcuts': 'ArrowLeft ArrowRight ArrowUp ArrowDown Delete',
    };
    final Map<String, void Function(web.Event)> handlers = <String, void Function(web.Event)>{
      'keydown': (web.Event event) => events.onNodeKey(event, key, index),
      'pointerdown': (web.Event event) => events.onNodePointer(event, key, index, bounds),
      'click': (web.Event event) => event.stopPropagation(),
    };
    return Component.element(
      tag: 'g',
      key: ValueKey<String>('$key:$index'),
      classes: 'animus-route-node${guide ? ' is-guide' : ''}${selected ? ' is-selected' : ''}${issue == null ? '' : ' is-flagged'}',
      children: <Component>[
        if (guide)
          dom.rect(
            const <Component>[],
            x: _n(node.x - radius),
            y: _n(node.z - radius),
            width: _n(radius * 2),
            height: _n(radius * 2),
            classes: 'animus-route-dot',
            attributes: <String, String>{...attributes, 'transform': 'rotate(45 ${_n(node.x)} ${_n(node.z)})'},
            events: handlers,
          )
        else
          dom.circle(const <Component>[], cx: _n(node.x), cy: _n(node.z), r: _n(radius), classes: 'animus-route-dot', attributes: attributes, events: handlers),
        _label(node.x, node.z, '$number', radius, guide ? 'is-guide' : 'is-point'),
      ],
    );
  }
}
