/// A native waypoint route (`modules["animus:navigation"]` inline, or a
/// `routes/<id>.json` document) drawn top down on a block grid: points in
/// walking order with their waits, guides for order random, links the
/// runtime may walk, wander areas, and the NPC. Click the grid to add a
/// point or guide, drag one to move it, pick one to edit its height, wait
/// and place in the order.
library;

import 'dart:js_interop';
import 'dart:math' as math;

import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:jaspr/dom.dart' as dom;
import 'package:jaspr/jaspr.dart' show Component;
import 'package:web/web.dart' as web;

import '../../logic/gui/gui_json.dart';
import '../../logic/npc/route_model.dart';
import '../../model/animus_document.dart';
import '../gui/gui_fields.dart';
import '../rig/rig_gizmo.dart' show eventNumber;

int _nextGridId = 0;

enum RouteTool { point, guide, select }

class RouteEditor extends StatefulWidget {
  const RouteEditor({
    required this.value,
    required this.onChanged,
    this.origin,
    this.path = r'$',
    this.diagnostics = const <Diagnostic>[],
    this.readOnly = false,
    super.key,
  });

  /// The route fields (a module object or a route document).
  final JsonMap value;
  final void Function(JsonMap value) onChanged;

  /// The NPC's x, y and z, drawn on the grid and used for new points.
  final (double, double, double)? origin;

  /// Where [value] sits in its document, for diagnostics.
  final String path;
  final List<Diagnostic> diagnostics;
  final bool readOnly;

  @override
  State<RouteEditor> createState() => _RouteEditorState();
}

class _RouteEditorState extends State<RouteEditor> {
  final String _gridId = 'animus-route-grid-${_nextGridId++}';
  RouteTool _tool = RouteTool.point;

  /// The picked node: `points` or `guides`, and its index.
  (String, int)? _picked;

  /// A node being dragged and where it is now, committed on release.
  (String, int, double, double)? _drag;
  RouteBounds? _dragBounds;
  JSFunction? _move;
  JSFunction? _up;

  @override
  void dispose() {
    _release();
    super.dispose();
  }

  JsonMap get _route => component.value;

  void _write(JsonMap next) => component.onChanged(next);

  void _field(String key, Object? value) {
    final JsonMap next = <String, Object?>{..._route};
    if (value == null) {
      next.remove(key);
    } else {
      next[key] = value;
    }
    _write(next);
  }

  (double, double)? _toGrid(web.Event event, RouteBounds bounds) {
    final web.Element? svg = web.document.getElementById(_gridId);
    if (svg == null) return null;
    final web.DOMRect rect = svg.getBoundingClientRect();
    if (rect.width <= 0 || rect.height <= 0) return null;
    final double x = bounds.minX + (eventNumber(event, 'clientX') - rect.left) / rect.width * bounds.width;
    final double z = bounds.minZ + (eventNumber(event, 'clientY') - rect.top) / rect.height * bounds.depth;
    return (x, z);
  }

  void _grab(String key, int index, RouteBounds bounds) {
    if (component.readOnly) return;
    _release();
    setState(() {
      _picked = (key, index);
      _dragBounds = bounds;
    });
    _move = ((web.Event event) {
      final (double, double)? at = _toGrid(event, bounds);
      if (at == null) return;
      setState(() => _drag = (key, index, snapBlock(at.$1), snapBlock(at.$2)));
    }).toJS;
    _up = ((web.Event _) {
      final (String, int, double, double)? drag = _drag;
      _release();
      setState(() {
        _drag = null;
        _dragBounds = null;
      });
      if (drag != null) _write(moveRouteNode(_route, drag.$1, drag.$2, drag.$3, drag.$4));
    }).toJS;
    web.window.addEventListener('pointermove', _move);
    web.window.addEventListener('pointerup', _up);
    web.window.addEventListener('pointercancel', _up);
  }

  void _release() {
    if (_move != null) web.window.removeEventListener('pointermove', _move);
    if (_up != null) {
      web.window.removeEventListener('pointerup', _up);
      web.window.removeEventListener('pointercancel', _up);
    }
    _move = null;
    _up = null;
  }

  void _add(web.Event event, RouteBounds bounds) {
    if (component.readOnly || _tool == RouteTool.select) return;
    final (double, double)? at = _toGrid(event, bounds);
    if (at == null) return;
    final RouteSpec spec = RouteSpec(_route);
    final bool guide = _tool == RouteTool.guide;
    final List<RouteNode> same = guide ? spec.guides : spec.points;
    final double y = same.isNotEmpty ? same.last.y : (spec.points.isNotEmpty ? spec.points.last.y : (component.origin?.$2 ?? 64).floorToDouble());
    _write(addRouteNode(_route, snapBlock(at.$1), y, snapBlock(at.$2), guide: guide));
    setState(() => _picked = (guide ? 'guides' : 'points', same.length));
  }

  RouteSpec get _shownSpec {
    final (String, int, double, double)? drag = _drag;
    return RouteSpec(drag == null ? _route : moveRouteNode(_route, drag.$1, drag.$2, drag.$3, drag.$4));
  }

  @override
  Widget build(BuildContext context) {
    final RouteSpec spec = _shownSpec;
    final List<(String, String)> problems = RouteSpec(_route).problems();
    final (String, int)? picked = _picked != null && asList(_route[_picked!.$1]).length > _picked!.$2 ? _picked : null;
    return dom.div(classes: 'animus-route-editor', attributes: const <String, String>{'role': 'group', 'aria-label': 'Route editor'}, <Widget>[
      if (!component.readOnly) _settings(spec),
      if (!component.readOnly)
        dom.div(classes: 'animus-row animus-route-tools', attributes: const <String, String>{'role': 'group', 'aria-label': 'Grid tool'}, <Widget>[
          _toolButton(RouteTool.point, 'Add points'),
          if (spec.order == 'random') _toolButton(RouteTool.guide, 'Add guides'),
          _toolButton(RouteTool.select, 'Pick and drag'),
          dom.span(classes: 'animus-gui-help', <Widget>[
            Component.text(_tool == RouteTool.select ? 'Drag a point to move it.' : 'Click the grid to add; drag a point to move it.'),
          ]),
        ]),
      _grid(spec, picked),
      if (problems.isNotEmpty || _problems.isNotEmpty)
        dom.ul(classes: 'animus-route-problems', <Widget>[
          for (final (String field, String message) in problems)
            dom.li(classes: 'animus-gui-problem is-error', <Widget>[dom.code(<Widget>[Component.text(field)]), Component.text(' $message')]),
          for (final Diagnostic problem in _problems)
            dom.li(classes: 'animus-gui-problem is-${problem.severity.name}', <Widget>[dom.code(<Widget>[Component.text(problem.path)]), Component.text(' ${problem.message}')]),
        ]),
      if (picked != null && !component.readOnly) _pickedEditor(picked),
      _nodeList(spec, picked),
    ]);
  }

  List<Diagnostic> get _problems => diagnosticsAt(component.diagnostics, component.path, nested: true);

  Widget _toolButton(RouteTool tool, String label) => Button(
        label: label,
        size: ButtonSize.small,
        variant: _tool == tool ? ButtonVariant.primary : ButtonVariant.ghost,
        attributes: <String, String>{'aria-pressed': '${_tool == tool}'},
        onPressed: () => setState(() => _tool = tool),
      );

  Widget _settings(RouteSpec spec) {
    final JsonMap route = _route;
    final JsonMap wander = asMap(route['wander']);
    return dom.div(classes: 'animus-route-settings', <Widget>[
      GuiRow(
        label: 'order',
        help: 'sequence walks the points in order; pingpong back and forth; random picks a point and walks over points and guides; wander walks near a centre.',
        child: GuiSelect(value: asString(route['order']), options: routeOrders, allowNone: true, noneLabel: 'sequence (default)', onChanged: (String? value) {
          if (value == null) {
            _write(setRouteOrder(route, 'sequence')..remove('order'));
          } else {
            _write(setRouteOrder(route, value));
          }
          if (value != 'random' && _tool == RouteTool.guide) setState(() => _tool = RouteTool.point);
        }),
      ),
      GuiRow(
        label: 'mode',
        child: GuiSelect(value: asString(route['mode']), options: routeModes, allowNone: true, noneLabel: 'walk (default)', onChanged: (String? value) => _field('mode', value)),
      ),
      GuiRow(
        label: 'speed',
        help: 'Blocks per second, 1-5.',
        child: GuiNumberInput(value: asDouble(route['speed']), integer: false, label: 'route speed', onChanged: (num? value) => _field('speed', value)),
      ),
      GuiRow(
        label: 'waitTicks',
        help: 'Wait before the first leg and after each arrival, 0-$routeMaxWait. 20 by default.',
        child: GuiNumberInput(value: asInt(route['waitTicks']), label: 'route waitTicks', onChanged: (num? value) => _field('waitTicks', value?.toInt())),
      ),
      if (spec.order == 'sequence')
        GuiRow(
          label: 'loop',
          help: 'Start again from the first point after the last.',
          child: GuiSwitch(value: route['loop'] == true, label: 'Loop', onChanged: (bool value) => _field('loop', value ? true : null)),
        ),
      if (spec.order == 'random')
        GuiRow(
          label: 'linkDistance',
          help: 'The longest link between two points or guides, 1-256. 32 by default.',
          child: GuiNumberInput(value: asDouble(route['linkDistance']), integer: false, label: 'route linkDistance', onChanged: (num? value) => _field('linkDistance', value)),
        ),
      if (spec.order == 'wander') ...<Widget>[
        GuiRow(
          label: 'wander.radius',
          help: 'Blocks from a centre on x and z, 1-32. 25 by default.',
          child: GuiNumberInput(value: asInt(wander['radius']), label: 'wander radius', onChanged: (num? value) => _field('wander', _wander(wander, 'radius', value))),
        ),
        GuiRow(
          label: 'wander.height',
          help: 'Blocks up or down, 0-8. 3 by default.',
          child: GuiNumberInput(value: asInt(wander['height']), label: 'wander height', onChanged: (num? value) => _field('wander', _wander(wander, 'height', value))),
        ),
      ],
    ]);
  }

  JsonMap? _wander(JsonMap wander, String key, num? value) {
    final JsonMap next = <String, Object?>{...wander};
    if (value == null) {
      next.remove(key);
    } else {
      next[key] = value.toInt();
    }
    return next.isEmpty ? null : next;
  }

  static String _n(double value) => value.toStringAsFixed(2);

  Widget _grid(RouteSpec spec, (String, int)? picked) {
    final (double, double, double)? origin = component.origin;
    final RouteBounds bounds = _dragBounds ??
        RouteBounds.around(spec.nodes, extra: <(double, double)>[
          if (origin != null) (origin.$1, origin.$3),
          if (spec.order == 'wander')
            for (final RouteNode point in spec.points) ...<(double, double)>[(point.x - spec.wanderRadius, point.z - spec.wanderRadius), (point.x + spec.wanderRadius, point.z + spec.wanderRadius)],
        ]);
    final double step = bounds.step;
    final double radius = math.max(0.3, bounds.width / 70);
    final List<Component> grid = <Component>[
      for (double x = (bounds.minX / step).ceilToDouble() * step; x <= bounds.maxX; x += step)
        dom.line(const <Component>[], x1: _n(x), y1: _n(bounds.minZ), x2: _n(x), y2: _n(bounds.maxZ), classes: 'animus-route-gridline${x == 0 ? ' is-axis' : ''}'),
      for (double z = (bounds.minZ / step).ceilToDouble() * step; z <= bounds.maxZ; z += step)
        dom.line(const <Component>[], x1: _n(bounds.minX), y1: _n(z), x2: _n(bounds.maxX), y2: _n(z), classes: 'animus-route-gridline${z == 0 ? ' is-axis' : ''}'),
    ];
    final List<RouteNode> nodes = spec.nodes;
    final List<Component> links = <Component>[
      for (final (int a, int b) in spec.links)
        dom.line(
          const <Component>[],
          x1: _n(nodes[a].x),
          y1: _n(nodes[a].z),
          x2: _n(nodes[b].x),
          y2: _n(nodes[b].z),
          classes: 'animus-route-link${spec.order == 'random' ? ' is-mesh' : ''}',
        ),
      if (spec.order == 'wander')
        for (final RouteNode point in spec.points)
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
        _label(origin.$1, origin.$3 - radius * 2, 'NPC', radius, 'is-npc'),
      ],
      for (int index = 0; index < spec.guides.length; index++) _node('guides', index, spec.guides[index], radius * 0.8, picked, bounds, '${spec.points.length + index}'),
      for (int index = 0; index < spec.points.length; index++) _node('points', index, spec.points[index], radius, picked, bounds, '$index'),
    ];
    return dom.div(classes: 'animus-route-grid-wrap', <Widget>[
      dom.svg(
        <Component>[...grid, ...links, ...markers],
        id: _gridId,
        classes: 'animus-route-grid${component.readOnly ? ' is-readonly' : ''}${_tool == RouteTool.select ? '' : ' is-adding'}',
        viewBox: '${_n(bounds.minX)} ${_n(bounds.minZ)} ${_n(bounds.width)} ${_n(bounds.depth)}',
        attributes: <String, String>{
          'role': 'img',
          'aria-label': 'Route grid, top down: ${spec.points.length} points, ${spec.guides.length} guides, x ${bounds.minX.toInt()} to ${bounds.maxX.toInt()}, z ${bounds.minZ.toInt()} to ${bounds.maxZ.toInt()}',
          'data-min-x': _n(bounds.minX),
          'data-min-z': _n(bounds.minZ),
          'data-size': _n(bounds.width),
          'preserveAspectRatio': 'none',
        },
        events: <String, void Function(web.Event)>{
          'click': (web.Event event) => _add(event, bounds),
        },
      ),
      dom.div(classes: 'animus-route-legend', <Widget>[
        dom.span(<Widget>[Component.text('x ${bounds.minX.toInt()} to ${bounds.maxX.toInt()}, z ${bounds.minZ.toInt()} to ${bounds.maxZ.toInt()}; north is up; grid every ${step.toInt()} block${step == 1 ? '' : 's'}')]),
      ]),
    ]);
  }

  Component _label(double x, double z, String text, double radius, String kind) => Component.element(
        tag: 'text',
        classes: 'animus-route-label $kind',
        attributes: <String, String>{'x': _n(x), 'y': _n(z), 'font-size': _n(radius * 1.6), 'text-anchor': 'middle', 'dominant-baseline': 'central'},
        children: <Component>[Component.text(text)],
      );

  Component _node(String key, int index, RouteNode node, double radius, (String, int)? picked, RouteBounds bounds, String number) {
    final bool selected = picked != null && picked.$1 == key && picked.$2 == index;
    final bool guide = key == 'guides';
    final String name = guide ? 'Guide $index' : 'Point $index';
    return Component.element(
      tag: 'g',
      key: ValueKey<String>('$key:$index'),
      classes: 'animus-route-node${guide ? ' is-guide' : ''}${selected ? ' is-selected' : ''}',
      children: <Component>[
        if (guide)
          dom.rect(
            const <Component>[],
            x: _n(node.x - radius),
            y: _n(node.z - radius),
            width: _n(radius * 2),
            height: _n(radius * 2),
            classes: 'animus-route-dot',
            attributes: <String, String>{'data-node': '$key:$index', 'role': 'button', 'aria-label': name, 'transform': 'rotate(45 ${_n(node.x)} ${_n(node.z)})'},
            events: _nodeEvents(key, index, bounds),
          )
        else
          dom.circle(
            const <Component>[],
            cx: _n(node.x),
            cy: _n(node.z),
            r: _n(radius),
            classes: 'animus-route-dot',
            attributes: <String, String>{'data-node': '$key:$index', 'role': 'button', 'aria-label': name},
            events: _nodeEvents(key, index, bounds),
          ),
        _label(node.x, node.z, number, radius, guide ? 'is-guide' : 'is-point'),
      ],
    );
  }

  Map<String, void Function(web.Event)> _nodeEvents(String key, int index, RouteBounds bounds) => <String, void Function(web.Event)>{
        'pointerdown': (web.Event event) {
          event.preventDefault();
          event.stopPropagation();
          if (component.readOnly) {
            setState(() => _picked = (key, index));
          } else {
            _grab(key, index, bounds);
          }
        },
        'click': (web.Event event) => event.stopPropagation(),
      };

  Widget _pickedEditor((String, int) picked) {
    final (String key, int index) = picked;
    final JsonMap node = asMap(asList(_route[key])[index]);
    final bool guide = key == 'guides';
    void set(String field, num? value) {
      final List<Object?> list = List<Object?>.of(asList(_route[key]));
      final JsonMap next = <String, Object?>{...node};
      if (value == null) {
        next.remove(field);
      } else {
        next[field] = value;
      }
      list[index] = next;
      _write(<String, Object?>{..._route, key: list});
    }

    final int count = asList(_route[key]).length;
    final String label = guide ? 'Guide $index' : 'Point $index';
    return dom.div(classes: 'animus-route-picked', attributes: <String, String>{'role': 'group', 'aria-label': 'Selected $label'}, <Widget>[
      dom.strong(<Widget>[Component.text(label)]),
      dom.div(classes: 'animus-route-coords', <Widget>[
        for (final String axis in const <String>['x', 'y', 'z'])
          dom.label(<Widget>[
            dom.span(<Widget>[Component.text(axis)]),
            GuiNumberInput(value: asDouble(node[axis]), integer: false, label: '$label $axis', onChanged: (num? value) => set(axis, value ?? 0)),
          ]),
        if (!guide)
          dom.label(<Widget>[
            const dom.span(<Widget>[Component.text('waitTicks')]),
            GuiNumberInput(value: asInt(node['waitTicks']), label: '$label waitTicks', placeholder: 'route', onChanged: (num? value) => set('waitTicks', value?.toInt())),
          ]),
      ]),
      dom.div(classes: 'animus-row', <Widget>[
        if (!guide) ...<Widget>[
          Button.ghost(
            label: 'Earlier',
            size: ButtonSize.small,
            disabled: index == 0,
            onPressed: () {
              _write(reorderRoutePoint(_route, index, index - 1));
              setState(() => _picked = (key, index - 1));
            },
          ),
          Button.ghost(
            label: 'Later',
            size: ButtonSize.small,
            disabled: index >= count - 1,
            onPressed: () {
              _write(reorderRoutePoint(_route, index, index + 1));
              setState(() => _picked = (key, index + 1));
            },
          ),
        ],
        Button.ghost(
          label: 'Remove $label',
          size: ButtonSize.small,
          onPressed: () {
            _write(removeRouteNode(_route, key, index));
            setState(() => _picked = null);
          },
        ),
      ]),
    ]);
  }

  Widget _nodeList(RouteSpec spec, (String, int)? picked) {
    if (spec.nodes.isEmpty) {
      return const dom.p(classes: 'animus-muted', <Widget>[Component.text('No points yet.')]);
    }
    String wait(RouteNode node) => node.guide ? '-' : (node.waitTicks == null ? 'route (${spec.waitTicks})' : '${node.waitTicks}');
    return dom.ol(classes: 'animus-route-list', attributes: const <String, String>{'aria-label': 'Route points'}, <Widget>[
      for (int index = 0; index < spec.nodes.length; index++)
        dom.li(
          classes: picked != null && picked == (spec.nodes[index].guide ? 'guides' : 'points', spec.nodes[index].guide ? index - spec.points.length : index) ? 'is-selected' : null,
          <Widget>[
            dom.button(
              classes: 'animus-route-list-item',
              attributes: const <String, String>{'type': 'button'},
              events: <String, void Function(web.Event)>{
                'click': (web.Event _) => setState(() => _picked = spec.nodes[index].guide ? ('guides', index - spec.points.length) : ('points', index)),
              },
              <Widget>[
                dom.code(<Widget>[Component.text('$index')]),
                dom.span(<Widget>[Component.text(spec.nodes[index].guide ? 'guide' : 'point')]),
                dom.span(<Widget>[Component.text('${_coord(spec.nodes[index].x)}, ${_coord(spec.nodes[index].y)}, ${_coord(spec.nodes[index].z)}')]),
                dom.span(classes: 'animus-muted', <Widget>[Component.text('wait ${wait(spec.nodes[index])}')]),
              ],
            ),
          ],
        ),
    ]);
  }

  static String _coord(double value) => value == value.roundToDouble() ? '${value.toInt()}' : '$value';
}
