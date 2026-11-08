/// A native waypoint route (`modules["animus:navigation"]` inline, or a
/// `routes/<id>.json` document) drawn top down on a block grid: points in
/// walking order with their waits, guides for order random, links the
/// runtime may walk, wander areas, and the NPC. Click the grid to add a
/// point or guide, drag one to move it, pick one to edit its height, wait
/// and place in the order. From the keyboard, Tab reaches each point:
/// arrows move it a quarter block (Shift for a whole block), Delete removes
/// it, Enter picks it, and "Add point at" adds one by its x, y and z.
/// Recording steps have no position: they are listed and numbered with the
/// points, picked from the list, and edited by recording and tick range.
/// With a paired server that shares terrain, the grid draws the surface
/// under the points, a placed or dragged point lands on a standable block,
/// points and legs that cannot be walked are flagged, and 3D shows the
/// route over the blocks.
library;

import 'dart:js_interop';
import 'dart:math' as math;

import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:jaspr/dom.dart' as dom;
import 'package:jaspr/jaspr.dart' show Component;
import 'package:web/web.dart' as web;

import '../../config/docs_links.dart';
import '../../logic/gui/gui_json.dart';
import '../../logic/npc/route_model.dart';
import '../../logic/terrain/terrain_snap.dart';
import '../../logic/terrain/terrain_volume.dart';
import '../../model/animus_document.dart';
import '../../model/document_kind.dart';
import '../gui/gui_fields.dart';
import '../info_tip.dart';
import '../inspect/inspect_fields.dart';
import '../rig/rig_gizmo.dart' show eventNumber;
import 'route_terrain.dart';
import 'route_view3d.dart';

int _nextGridId = 0;

/// The recording documents in [documents] by id, with their durationTicks.
Map<String, int?> routeRecordings(List<AnimusDocument> documents) => <String, int?>{
      for (final AnimusDocument document in documents)
        if (document.kind == DocumentKind.recording) document.id: asInt(asMap(document.json)['durationTicks']),
    };

enum RouteTool { point, guide, select }

/// A `waitTicks` field: one count, or with Range pressed a Min and a Max
/// drawn per arrival. Writes an int, `[min, max]`, or null when [optional]
/// and emptied. [id] prefixes the inputs: `<id>`, `<id>-min`, `<id>-max`
/// and the `<id>-range` toggle.
class RouteWaitInput extends StatefulWidget {
  const RouteWaitInput({required this.id, required this.label, required this.value, required this.onChanged, this.placeholder, super.key});

  final String id;

  /// What the inputs are named for assistive technology, such as `Point 2`.
  final String label;
  final Object? value;
  final void Function(Object? value) onChanged;

  /// Shown in the empty single field: the wait that applies without one.
  final String? placeholder;

  @override
  State<RouteWaitInput> createState() => _RouteWaitInputState();
}

class _RouteWaitInputState extends State<RouteWaitInput> {
  /// Range chosen before both ends were typed.
  bool? _range;

  bool get _ranged => _range ?? component.value is List<Object?>;

  List<int?> get _ends {
    final Object? value = component.value;
    if (value is List<Object?>) return <int?>[value.isNotEmpty ? asInt(value[0]) : null, value.length > 1 ? asInt(value[1]) : null];
    final int? fixed = asInt(value);
    return <int?>[fixed, fixed];
  }

  void _toggle() {
    final List<int?> ends = _ends;
    final bool range = !_ranged;
    setState(() => _range = range);
    if (range) {
      if (ends[0] != null) component.onChanged(<int>[ends[0]!, ends[1] ?? ends[0]!]);
    } else {
      component.onChanged(ends[0]);
    }
  }

  void _end(int which, num? typed) {
    final List<int?> ends = _ends;
    ends[which] = typed?.toInt();
    final int? low = ends[0] ?? ends[1], high = ends[1] ?? ends[0];
    component.onChanged(low == null || high == null ? null : <int>[low, high]);
  }

  @override
  Widget build(BuildContext context) {
    final bool ranged = _ranged;
    final List<int?> ends = _ends;
    final String label = component.label;
    return dom.div(classes: 'animus-route-wait', attributes: <String, String>{'role': 'group', 'aria-label': '$label wait'}, <Widget>[
      if (ranged) ...<Widget>[
        dom.label(classes: 'animus-route-wait-end', <Widget>[
          const dom.span(<Widget>[Component.text('Min')]),
          GuiNumberInput(id: component.id, value: ends[0], label: '$label waitTicks min', placeholder: '0', onChanged: (num? value) => _end(0, value)),
        ]),
        dom.label(classes: 'animus-route-wait-end', <Widget>[
          const dom.span(<Widget>[Component.text('Max')]),
          GuiNumberInput(id: '${component.id}-max', value: ends[1], label: '$label waitTicks max', placeholder: '$routeMaxWait', onChanged: (num? value) => _end(1, value)),
        ]),
      ] else
        GuiNumberInput(
          id: component.id,
          value: asInt(component.value),
          label: '$label waitTicks',
          placeholder: component.placeholder,
          onChanged: (num? value) => component.onChanged(value?.toInt()),
        ),
      Button(
        id: '${component.id}-range',
        label: 'Range',
        size: ButtonSize.small,
        variant: ButtonVariant.ghost,
        attributes: <String, String>{'aria-pressed': '$ranged', 'aria-label': '$label wait range'},
        onPressed: _toggle,
      ),
    ]);
  }
}

class RouteEditor extends StatefulWidget {
  const RouteEditor({
    required this.value,
    required this.onChanged,
    this.origin,
    this.world,
    this.path = r'$',
    this.diagnostics = const <Diagnostic>[],
    this.readOnly = false,
    this.recordings = const <String, int?>{},
    super.key,
  });

  /// The route fields (a module object or a route document).
  final JsonMap value;
  /// The new route and the route it was worked out from, which can be
  /// ahead of [value] while quick edits are still being saved.
  final void Function(JsonMap value, JsonMap from) onChanged;

  /// The NPC's x, y and z, drawn on the grid and used for new points.
  final (double, double, double)? origin;

  /// The NPC's world, whose terrain the grid draws; null for a route
  /// document, which belongs to no world.
  final String? world;

  /// Where [value] sits in its document, for diagnostics.
  final String path;
  final List<Diagnostic> diagnostics;
  final bool readOnly;

  /// The workspace's recordings by id, with their durationTicks when known.
  final Map<String, int?> recordings;

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

  /// The "Add point at" form; empty fields fall back to the last point.
  num? _addX;
  num? _addY;
  num? _addZ;

  late final RouteTerrain _terrain = RouteTerrain(() {
    if (mounted) setState(() {});
  });
  bool _show3d = false;

  @override
  void dispose() {
    _release();
    _terrain.dispose();
    super.dispose();
  }


  /// The last route reported and not yet echoed back, so quick key repeats
  /// build on each other instead of on the value from before.
  JsonMap? _sent;

  @override
  void didUpdateWidget(RouteEditor oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (!identical(oldWidget.value, component.value)) _sent = null;
  }

  JsonMap get _route => _sent ?? component.value;

  void _write(JsonMap next) {
    final JsonMap from = _route;
    _sent = next;
    component.onChanged(next, from);
  }

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
      if (drag != null) _write(_terrain.placed(_route, drag.$1, drag.$2, drag.$3, drag.$4));
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
    final List<RouteEntry> same = guide ? spec.guides : spec.points;
    final RouteNode? sameLast = _lastPlaced(same), pointLast = _lastPlaced(spec.points);
    final double near = sameLast?.y ?? pointLast?.y ?? (component.origin?.$2 ?? 64).floorToDouble();
    final double y = _terrain.snap(snapBlock(at.$1), snapBlock(at.$2), near)?.toDouble() ?? near;
    _write(addRouteNode(_route, snapBlock(at.$1), y, snapBlock(at.$2), guide: guide));
    setState(() => _picked = (guide ? 'guides' : 'points', same.length));
  }

  /// Moves, removes or picks the focused node from the keyboard.
  void _key(web.Event event, String key, int index) {
    final web.KeyboardEvent keys = event as web.KeyboardEvent;
    if (keys.key == 'Enter' || keys.key == ' ') {
      event.preventDefault();
      setState(() => _picked = (key, index));
      return;
    }
    if (component.readOnly) return;
    if (keys.key == 'Delete' || keys.key == 'Backspace') {
      event.preventDefault();
      final int left = asList(_route[key]).length - 1;
      _write(removeRouteNode(_route, key, index));
      setState(() => _picked = null);
      if (left > 0) _focusNode(key, index.clamp(0, left - 1));
      return;
    }
    final (double, double)? step = routeNudge(keys.key, shift: keys.shiftKey);
    if (step == null) return;
    event.preventDefault();
    _write(nudgeRouteNode(_route, key, index, step.$1, step.$2));
    setState(() => _picked = (key, index));
  }

  String _nodeId(String key, int index) => '$_gridId-$key-$index';

  void _focusNode(String key, int index) {
    Future<void>.delayed(Duration.zero, () {
      final web.Element? element = web.document.getElementById(_nodeId(key, index));
      if (element != null) (element as web.HTMLElement).focus();
    });
  }

  static RouteNode? _lastPlaced(List<RouteEntry> entries) => entries.whereType<RouteNode>().lastOrNull;

  /// Where "Add point at" puts a node: the typed axes, else the last point
  /// with a position, else the NPC.
  (double, double, double) _addAt(RouteSpec spec) {
    final RouteNode? last = _lastPlaced(spec.points);
    final (double, double, double)? origin = component.origin;
    final double x = _addX?.toDouble() ?? last?.x ?? origin?.$1.floorToDouble() ?? 0;
    final double y = _addY?.toDouble() ?? last?.y ?? origin?.$2.floorToDouble() ?? 64;
    final double z = _addZ?.toDouble() ?? last?.z ?? origin?.$3.floorToDouble() ?? 0;
    return (x, y, z);
  }

  Widget _addForm(RouteSpec spec) {
    final (double x, double y, double z) = _addAt(spec);
    void add(bool guide) {
      final int index = guide ? spec.guides.length : spec.points.length;
      _write(addRouteNode(_route, x, y, z, guide: guide));
      setState(() {
        _picked = (guide ? 'guides' : 'points', index);
        _addX = null;
        _addY = null;
        _addZ = null;
      });
    }

    final bool walking = (asString(_route['mode']) ?? 'walk') == 'walk';
    return dom.div(classes: 'animus-route-add', attributes: const <String, String>{'role': 'group', 'aria-label': 'Add point at'}, <Widget>[
      const dom.strong(<Widget>[Component.text('Add point at')]),
      dom.div(classes: 'animus-route-coords', <Widget>[
        for (final (String axis, double shown, void Function(num?) set) in <(String, double, void Function(num?))>[
          ('x', x, (num? value) => setState(() => _addX = value)),
          ('y', y, (num? value) => setState(() => _addY = value)),
          ('z', z, (num? value) => setState(() => _addZ = value)),
        ])
          dom.label(<Widget>[
            dom.span(<Widget>[Component.text(axis)]),
            GuiNumberInput(value: shown, integer: false, label: 'New point $axis', onChanged: set),
          ]),
      ]),
      dom.div(classes: 'animus-row', <Widget>[
        Button.secondary(label: 'Add point', size: ButtonSize.small, disabled: spec.points.length >= routeMaxPoints, onPressed: () => add(false)),
        if (spec.order == 'random') Button.ghost(label: 'Add guide', size: ButtonSize.small, onPressed: () => add(true)),
        if (spec.order != 'wander')
          Button.ghost(
            label: 'Add recording step',
            size: ButtonSize.small,
            disabled: spec.points.length >= routeMaxPoints,
            onPressed: () {
              _write(addRouteStep(_route, component.recordings.keys.firstOrNull ?? ''));
              setState(() => _picked = ('points', spec.points.length));
            },
          ),
        if (walking && y != y.roundToDouble())
          const dom.span(classes: 'animus-gui-problem is-warning', <Widget>[Component.text('Walking points need a whole y.')]),
      ]),
    ]);
  }

  RouteSpec get _shownSpec {
    final (String, int, double, double)? drag = _drag;
    return RouteSpec(drag == null ? _route : _terrain.placed(_route, drag.$1, drag.$2, drag.$3, drag.$4));
  }

  @override
  Widget build(BuildContext context) {
    final RouteSpec spec = _shownSpec;
    final (String, int)? picked = _picked != null && asList(_route[_picked!.$1]).length > _picked!.$2 ? _picked : null;
    // Problems of the route fields and of the picked point show at their
    // field; the rest are listed under the grid.
    final String? pickedField = picked == null || component.readOnly ? null : '${picked.$1}[${picked.$2}]';
    final List<(String, String)> problems = <(String, String)>[
      for (final (String field, String message) in RouteSpec(_route).problems())
        if (component.readOnly || !(_inlineFields.contains(field) || (pickedField != null && (field.startsWith('$pickedField.') || field == pickedField)))) (field, message),
    ];
    return dom.div(classes: 'animus-route-editor', attributes: const <String, String>{'role': 'group', 'aria-label': 'Route editor'}, <Widget>[
      if (!component.readOnly) _settings(spec),
      if (!component.readOnly)
        dom.div(classes: 'animus-row animus-route-tools', attributes: const <String, String>{'role': 'group', 'aria-label': 'Grid tool'}, <Widget>[
          _toolButton(RouteTool.point, 'Add points'),
          if (spec.order == 'random') _toolButton(RouteTool.guide, 'Add guides'),
          _toolButton(RouteTool.select, 'Pick and drag'),
          if (component.world != null && _terrain.available)
            Button(
              label: '3D',
              size: ButtonSize.small,
              variant: ButtonVariant.ghost,
              attributes: <String, String>{'aria-pressed': '$_show3d', 'data-route-3d-toggle': ''},
              onPressed: () => setState(() => _show3d = !_show3d),
            ),
          dom.span(classes: 'animus-gui-help', <Widget>[
            InfoTip(
              tip: 'Tab to a point: arrows move it, Shift moves a whole block, Delete removes it.',
              docs: Docs.routes,
              child: Component.text(_tool == RouteTool.select ? 'Drag to move.' : 'Click to add, drag to move.'),
            ),
          ]),
        ]),
      _grid(spec, picked),
      if (_show3d && component.world != null && _terrain.available)
        RouteView3d(spec: spec, world: component.world, focus: routeFocus(spec, picked, component.origin), onVolume: (TerrainVolume? volume) => setState(() => _terrain.volume = volume)),
      if (!component.readOnly) _addForm(spec),
      if (problems.isNotEmpty || _problems.isNotEmpty)
        dom.ul(classes: 'animus-route-problems', <Widget>[
          for (final (String field, String message) in problems)
            dom.li(classes: 'animus-gui-problem is-error', <Widget>[dom.code(<Widget>[Component.text(field)]), Component.text(' $message')]),
          for (final Diagnostic problem in _problems)
            dom.li(classes: 'animus-gui-problem is-${problem.severity.name}', <Widget>[dom.code(<Widget>[Component.text(problem.path)]), Component.text(' ${problem.message}')]),
        ]),
      if (picked != null && !component.readOnly) _pickedEditor(picked, RouteSpec(_route)),
      _nodeList(spec, picked),
    ]);
  }

  List<Diagnostic> get _problems => diagnosticsAt(component.diagnostics, component.path, nested: true);

  /// Route fields whose problems show in their own row.
  static const Set<String> _inlineFields = <String>{'waitTicks', 'seed', 'startOffset', 'jitter', 'style'};

  /// The browser's problems at exactly [field] (`jitter`, `points[2].next[0].to`).
  List<Diagnostic> _inline(String field) => <Diagnostic>[
        for (final (String at, String message) in RouteSpec(_route).problems())
          if (at == field) Diagnostic(DiagnosticSeverity.error, at, message),
      ];

  /// The browser's problems at [field] and inside it.
  List<Diagnostic> _inlineWithin(String field) => <Diagnostic>[
        for (final (String at, String message) in RouteSpec(_route).problems())
          if (at == field || at.startsWith('$field.') || at.startsWith('$field[')) Diagnostic(DiagnosticSeverity.error, at, message),
      ];

  Widget _toolButton(RouteTool tool, String label) => Button(
        label: label,
        size: ButtonSize.small,
        variant: ButtonVariant.ghost,
        attributes: <String, String>{'aria-pressed': '${_tool == tool}'},
        onPressed: () => setState(() => _tool = tool),
      );

  Widget _settings(RouteSpec spec) {
    final JsonMap route = _route;
    final JsonMap wander = asMap(route['wander']);
    return dom.div(classes: 'animus-route-settings', <Widget>[
      InspectRow(
        label: 'Order',
        labelFor: '$_gridId-order',
        help: 'Sequence walks in order, pingpong back and forth, random picks points by guides, wander roams near a center.',
        child: GuiSelect(id: '$_gridId-order', value: asString(route['order']), options: routeOrders, allowNone: true, noneLabel: 'sequence (default)', onChanged: (String? value) {
          if (value == null) {
            _write(setRouteOrder(route, 'sequence')..remove('order'));
          } else {
            _write(setRouteOrder(route, value));
          }
          if (value != 'random' && _tool == RouteTool.guide) setState(() => _tool = RouteTool.point);
        }),
      ),
      InspectRow(
        label: 'Mode',
        labelFor: '$_gridId-mode',
        help: 'Only walk needs whole-block points.',
        child: GuiSelect(id: '$_gridId-mode', value: asString(route['mode']), options: routeModes, allowNone: true, noneLabel: 'walk (default)', onChanged: (String? value) => _field('mode', value)),
      ),
      InspectRow(
        label: 'Style',
        labelFor: '$_gridId-style',
        help: 'Sneak and crawl 0.3x speed, sprint and swim 1.3x. Empty uses each NPC\'s own.',
        problems: _inline('style'),
        child: GuiSelect(id: '$_gridId-style', value: asString(route['style']), options: movementStyles, allowNone: true, noneLabel: 'NPC style', onChanged: (String? value) => _field('style', value)),
      ),
      GuiRow(
        label: 'Speed',
        help: 'Blocks per second, 1-5.',
        child: GuiNumberInput(value: asDouble(route['speed']), integer: false, label: 'route speed', onChanged: (num? value) => _field('speed', value)),
      ),
      InspectRow(
        label: 'Wait ticks',
        labelFor: '$_gridId-wait',
        help: '0-$routeMaxWait at each point, default 20.',
        problems: _inline('waitTicks'),
        child: RouteWaitInput(id: '$_gridId-wait', label: 'route', value: route['waitTicks'], placeholder: '20', onChanged: (Object? value) => _field('waitTicks', value)),
      ),
      InspectRow(
        label: 'Seed',
        labelFor: '$_gridId-seed',
        help: 'npc: stable per NPC. random: new each run.',
        problems: _inline('seed'),
        child: GuiSelect(id: '$_gridId-seed', value: asString(route['seed']), options: routeSeeds, allowNone: true, noneLabel: 'npc (default)', onChanged: (String? value) => _field('seed', value)),
      ),
      InspectRow(
        label: 'Start offset',
        labelFor: '$_gridId-start',
        help: 'random spreads NPCs sharing the route.',
        problems: _inline('startOffset'),
        child: GuiSelect(
          id: '$_gridId-start',
          value: asString(route['startOffset']),
          options: routeStartOffsets,
          allowNone: true,
          noneLabel: 'first (default)',
          onChanged: (String? value) => _field('startOffset', value),
        ),
      ),
      if (spec.order != 'wander' || route.containsKey('jitter'))
        InspectRow(
          label: 'Jitter',
          labelFor: '$_gridId-jitter',
          help: 'Random offset, 0-$routeMaxJitter blocks.',
          problems: _inline('jitter'),
          child: GuiNumberInput(id: '$_gridId-jitter', value: asInt(route['jitter']), label: 'route jitter', placeholder: '0', onChanged: (num? value) => _field('jitter', value?.toInt())),
        ),
      if (spec.order == 'sequence')
        GuiRow(
          label: 'Loop',
          help: 'Restart after the last point.',
          child: GuiSwitch(value: route['loop'] == true, label: 'Loop', onChanged: (bool value) => _field('loop', value ? true : null)),
        ),
      if (spec.order == 'random')
        GuiRow(
          label: 'Link distance',
          help: 'Max link length, 1-256, default 32.',
          child: GuiNumberInput(value: asDouble(route['linkDistance']), integer: false, label: 'route linkDistance', onChanged: (num? value) => _field('linkDistance', value)),
        ),
      if (spec.order == 'wander') ...<Widget>[
        GuiRow(
          label: 'Wander radius',
          help: 'x/z blocks, 1-32, default 25.',
          child: GuiNumberInput(value: asInt(wander['radius']), label: 'wander radius', onChanged: (num? value) => _field('wander', _wander(wander, 'radius', value))),
        ),
        GuiRow(
          label: 'Wander height',
          help: 'Vertical blocks, 0-8, default 3.',
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
        RouteBounds.around(spec.placed, extra: <(double, double)>[
          if (origin != null) (origin.$1, origin.$3),
          if (spec.order == 'wander')
            for (final RouteNode point in spec.points.whereType<RouteNode>()) ...<(double, double)>[(point.x - spec.wanderRadius, point.z - spec.wanderRadius), (point.x + spec.wanderRadius, point.z + spec.wanderRadius)],
        ]);
    final double step = bounds.step;
    final double radius = math.max(0.3, bounds.width / 70);
    _terrain.cover(component.world, bounds);
    final Map<(int, int), (int, int, int)> steep = _terrain.steepLinks(spec);
    final Map<(String, int), TerrainIssue> issues = _terrain.issues(spec);
    final List<Component> grid = <Component>[
      ..._terrain.images(bounds),
      for (double x = (bounds.minX / step).ceilToDouble() * step; x <= bounds.maxX; x += step)
        dom.line(const <Component>[], x1: _n(x), y1: _n(bounds.minZ), x2: _n(x), y2: _n(bounds.maxZ), classes: 'animus-route-gridline${x == 0 ? ' is-axis' : ''}'),
      for (double z = (bounds.minZ / step).ceilToDouble() * step; z <= bounds.maxZ; z += step)
        dom.line(const <Component>[], x1: _n(bounds.minX), y1: _n(z), x2: _n(bounds.maxX), y2: _n(z), classes: 'animus-route-gridline${z == 0 ? ' is-axis' : ''}'),
    ];
    final List<Component> links = <Component>[
      for (final (int a, int b) in spec.links)
        dom.line(
          const <Component>[],
          x1: _n(spec.positionOf(a)!.x),
          y1: _n(spec.positionOf(a)!.z),
          x2: _n(spec.positionOf(b)!.x),
          y2: _n(spec.positionOf(b)!.z),
          classes: 'animus-route-link${spec.order == 'random' ? ' is-mesh' : ''}${steep.containsKey((a, b)) ? ' is-steep' : ''}',
          attributes: <String, String>{if (steep.containsKey((a, b))) 'data-steep': '$a:$b'},
        ),
      for (final (int a, int b) in spec.junctionLinks)
        dom.line(
          const <Component>[],
          x1: _n(spec.positionOf(a)!.x),
          y1: _n(spec.positionOf(a)!.z),
          x2: _n(spec.positionOf(b)!.x),
          y2: _n(spec.positionOf(b)!.z),
          classes: 'animus-route-link is-junction',
          attributes: <String, String>{'data-junction': '$a:$b'},
        ),
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
        _label(origin.$1, origin.$3 - radius * 2, 'NPC', radius, 'is-npc'),
      ],
      for (int index = 0; index < spec.guides.length; index++)
        if (spec.guides[index] case final RouteNode guide) _node('guides', index, guide, radius * 0.8, picked, bounds, '${spec.points.length + index}', issues[('guides', index)]),
      for (int index = 0; index < spec.points.length; index++)
        if (spec.points[index] case final RouteNode point) _node('points', index, point, radius, picked, bounds, '$index', issues[('points', index)]),
    ];
    // Points on the grid are sized by the map's scale, not the button
    // system; the point list below gives each one a full-size target.
    return dom.div(classes: 'animus-route-grid-wrap', attributes: const <String, String>{'data-size-exempt': ''}, <Widget>[
      dom.svg(
        <Component>[...grid, ...links, ...markers],
        id: _gridId,
        classes: 'animus-route-grid${component.readOnly ? ' is-readonly' : ''}${_tool == RouteTool.select ? '' : ' is-adding'}',
        viewBox: '${_n(bounds.minX)} ${_n(bounds.minZ)} ${_n(bounds.width)} ${_n(bounds.depth)}',
        attributes: <String, String>{
          'role': 'img',
          'aria-label': 'Route grid, top down: ${spec.points.length} points${_steps(spec)}, ${spec.guides.length} guides, x ${bounds.minX.toInt()} to ${bounds.maxX.toInt()}, z ${bounds.minZ.toInt()} to ${bounds.maxZ.toInt()}',
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
        if (_terrain.note() case final String note) dom.span(attributes: const <String, String>{'data-terrain-note': ''}, <Widget>[Component.text(note)]),
      ]),
      if (issues.isNotEmpty || steep.isNotEmpty) routeTerrainProblems(issues, steep),
    ]);
  }

  /// How many of the points are recording steps, which the grid cannot draw.
  static String _steps(RouteSpec spec) {
    final int steps = spec.points.whereType<RouteStep>().length;
    return steps == 0 ? '' : ' ($steps recording step${steps == 1 ? '' : 's'}, not drawn)';
  }

  Component _label(double x, double z, String text, double radius, String kind) => Component.element(
        tag: 'text',
        classes: 'animus-route-label $kind',
        attributes: <String, String>{'x': _n(x), 'y': _n(z), 'font-size': _n(radius * 1.6), 'text-anchor': 'middle', 'dominant-baseline': 'central'},
        children: <Component>[Component.text(text)],
      );

  Component _node(String key, int index, RouteNode node, double radius, (String, int)? picked, RouteBounds bounds, String number, TerrainIssue? issue) {
    final bool selected = picked != null && picked.$1 == key && picked.$2 == index;
    final bool guide = key == 'guides';
    final String name = guide ? 'Guide $index' : 'Point $index';
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
            attributes: <String, String>{..._nodeAttributes(key, index, name, node, issue), 'transform': 'rotate(45 ${_n(node.x)} ${_n(node.z)})'},
            events: _nodeEvents(key, index, bounds),
          )
        else
          dom.circle(
            const <Component>[],
            cx: _n(node.x),
            cy: _n(node.z),
            r: _n(radius),
            classes: 'animus-route-dot',
            attributes: _nodeAttributes(key, index, name, node, issue),
            events: _nodeEvents(key, index, bounds),
          ),
        _label(node.x, node.z, number, radius, guide ? 'is-guide' : 'is-point'),
      ],
    );
  }

  Map<String, String> _nodeAttributes(String key, int index, String name, RouteNode node, TerrainIssue? issue) => <String, String>{
        'id': _nodeId(key, index),
        'data-node': '$key:$index',
        'data-y': _coord(node.y),
        if (issue != null) 'data-issue': issue.name,
        'role': 'button',
        'tabindex': '0',
        'aria-label': '$name at ${_coord(node.x)}, ${_coord(node.y)}, ${_coord(node.z)}${issue == null ? '' : ', ${issue.text}'}',
        if (!component.readOnly) 'aria-keyshortcuts': 'ArrowLeft ArrowRight ArrowUp ArrowDown Delete',
      };

  Map<String, void Function(web.Event)> _nodeEvents(String key, int index, RouteBounds bounds) => <String, void Function(web.Event)>{
        'keydown': (web.Event event) => _key(event, key, index),
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

  Widget _pickedEditor((String, int) picked, RouteSpec spec) {
    final (String key, int index) = picked;
    final JsonMap node = asMap(asList(_route[key])[index]);
    final bool guide = key == 'guides';
    final bool step = !guide && node.containsKey('recording');
    void set(String field, Object? value) => _write(setRouteNodeField(_route, key, index, field, value));

    final int count = asList(_route[key]).length;
    final String label = guide ? 'Guide $index' : (step ? 'Step $index' : 'Point $index');
    final String field = '$key[$index]';
    final Widget waitField = InspectRow(
      label: 'Wait ticks',
      labelFor: '$_gridId-point-wait',
      help: 'Empty uses the route\'s wait (${spec.waitTicks}).',
      problems: _inline('$field.waitTicks'),
      child: RouteWaitInput(
        key: ValueKey<String>('wait:$key:$index'),
        id: '$_gridId-point-wait',
        label: label,
        value: node['waitTicks'],
        placeholder: 'route',
        onChanged: (Object? value) => set('waitTicks', value),
      ),
    );
    return dom.div(classes: 'animus-route-picked', attributes: <String, String>{'role': 'group', 'aria-label': 'Selected $label'}, <Widget>[
      dom.strong(<Widget>[Component.text(label)]),
      if (step)
        _stepFields(node, label, set)
      else
        dom.div(classes: 'animus-route-coords', <Widget>[
          for (final String axis in const <String>['x', 'y', 'z'])
            dom.label(<Widget>[
              dom.span(<Widget>[Component.text(axis)]),
              GuiNumberInput(value: asDouble(node[axis]), integer: false, label: '$label $axis', onChanged: (num? value) => set(axis, value ?? 0)),
            ]),
        ]),
      if (!guide) ...<Widget>[
        waitField,
        if (!step && (spec.order != 'wander' || node.containsKey('jitter')))
          InspectRow(
            label: 'Jitter',
            labelFor: '$_gridId-point-jitter',
            help: 'Blocks, 0-$routeMaxJitter. Empty uses the route\'s.',
            problems: _inline('$field.jitter'),
            child: GuiNumberInput(id: '$_gridId-point-jitter', value: asInt(node['jitter']), label: '$label jitter', placeholder: 'route', onChanged: (num? value) => set('jitter', value?.toInt())),
          ),
        if (step && node.containsKey('jitter'))
          for (final Diagnostic problem in _inline('$field.jitter'))
            dom.div(classes: 'animus-row', <Widget>[
              dom.span(classes: 'animus-gui-problem is-error', <Widget>[dom.code(<Widget>[Component.text(problem.path)]), Component.text(' ${problem.message}')]),
              Button.ghost(label: 'Remove jitter', size: ButtonSize.small, onPressed: () => set('jitter', null)),
            ]),
        if (!step)
          InspectRow(
            label: 'Style',
            labelFor: '$_gridId-point-style',
            help: 'How the NPC moves on the leg to this point. Empty uses the route\'s.',
            problems: _inline('$field.style'),
            child: GuiSelect(
              id: '$_gridId-point-style',
              value: asString(node['style']),
              options: movementStyles,
              allowNone: true,
              noneLabel: 'route style',
              onChanged: (String? value) => set('style', value),
            ),
          ),
        if (spec.order == 'random' || node.containsKey('weight'))
          InspectRow(
            label: 'Weight',
            labelFor: '$_gridId-point-weight',
            help: 'Relative pick chance, 1-$routeMaxWeight.',
            problems: _inline('$field.weight'),
            child: GuiNumberInput(id: '$_gridId-point-weight', value: asInt(node['weight']), label: '$label weight', placeholder: '1', onChanged: (num? value) => set('weight', value?.toInt())),
          ),
        if (spec.order == 'sequence' || node.containsKey('next')) _junctions(node, index, label, field),
      ],
      for (final Diagnostic problem in _inlineWithin(field))
        if (!<String>['waitTicks', 'jitter', 'weight', 'next', 'style'].any((String covered) => problem.path == '$field.$covered' || problem.path.startsWith('$field.next')))
          dom.span(classes: 'animus-gui-problem is-error', <Widget>[dom.code(<Widget>[Component.text(problem.path)]), Component.text(' ${problem.message}')]),
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
          if (step)
            Button.ghost(
              label: 'Make a position',
              size: ButtonSize.small,
              onPressed: () {
                final (double x, double y, double z) = _addAt(spec);
                _write(routeStepToPosition(_route, index, x, y, z));
              },
            )
          else if (spec.order != 'wander')
            Button.ghost(
              label: 'Make a recording step',
              size: ButtonSize.small,
              onPressed: () => _write(routePointToStep(_route, index, component.recordings.keys.firstOrNull ?? '')),
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

  /// A step's recording, tick range and wait. The recording is picked from
  /// the workspace's recordings, or typed when the workspace has none.
  /// A point's `next` junctions: each leads to a point by weight.
  Widget _junctions(JsonMap node, int index, String label, String field) {
    final List<Object?> next = asList(node['next']);
    final int count = asList(_route['points']).length;
    final List<String> targets = <String>[
      for (int point = 0; point < count; point++)
        if (point != index) '$point',
    ];
    return dom.div(classes: 'animus-route-junctions', attributes: <String, String>{'role': 'group', 'aria-label': '$label next points'}, <Widget>[
      dom.div(classes: 'animus-route-junctions-head', <Widget>[
        const dom.span(classes: 'animus-gui-label', <Widget>[Component.text('Next points')]),
        dom.span(classes: 'animus-gui-help', <Widget>[
          Component.text(next.isEmpty
              ? 'None. Walks on to point ${index + 1 < count ? index + 1 : 'after the last'}.'
              : 'Picked by weight instead of point ${index + 1}.'),
        ]),
      ]),
      for (int junction = 0; junction < next.length; junction++)
        dom.div(key: ValueKey<String>('junction:$index:$junction'), classes: 'animus-route-junction', <Widget>[
          dom.label(<Widget>[
            const dom.span(<Widget>[Component.text('To point')]),
            GuiSelect(
              id: '$_gridId-next-$junction-to',
              value: asInt(asMap(next[junction])['to'])?.toString(),
              options: targets,
              labels: <String, String>{for (final String target in targets) target: 'Point $target'},
              onChanged: (String? value) {
                final int? to = value == null ? null : int.tryParse(value);
                if (to != null) _write(setRouteJunction(_route, index, junction, to: to));
              },
            ),
          ]),
          dom.label(<Widget>[
            const dom.span(<Widget>[Component.text('Weight')]),
            GuiNumberInput(
              id: '$_gridId-next-$junction-weight',
              value: asInt(asMap(next[junction])['weight']),
              label: '$label junction ${junction + 1} weight',
              placeholder: '1',
              onChanged: (num? value) => _write(setRouteJunction(_route, index, junction, weight: value?.toInt())),
            ),
          ]),
          GuiIconButton(
            icon: ArcaneIcon.x(size: IconSize.sm),
            label: 'Remove junction ${junction + 1}',
            onPressed: () => _write(removeRouteJunction(_route, index, junction)),
          ),
          for (final Diagnostic problem in _inlineWithin('$field.next[$junction]'))
            dom.span(classes: 'animus-gui-problem is-error', <Widget>[dom.code(<Widget>[Component.text(problem.path)]), Component.text(' ${problem.message}')]),
        ]),
      for (final Diagnostic problem in _inline('$field.next'))
        dom.span(classes: 'animus-gui-problem is-error', <Widget>[dom.code(<Widget>[Component.text(problem.path)]), Component.text(' ${problem.message}')]),
      Button.ghost(
        id: '$_gridId-next-add',
        label: 'Add junction',
        size: ButtonSize.small,
        disabled: next.length >= routeMaxJunctions || count < 2,
        onPressed: () => _write(addRouteJunction(_route, index)),
      ),
    ]);
  }

  Widget _stepFields(JsonMap node, String label, void Function(String field, Object? value) set) {
    final String recording = asString(node['recording']) ?? '';
    final Map<String, int?> recordings = component.recordings;
    final int? duration = recordings[recording];
    final String recordingId = '$_gridId-recording';
    return dom.div(classes: 'animus-route-step', <Widget>[
      GuiRow(
        label: 'Recording',
        forId: recordingId,
        help: recordings.isEmpty
            ? 'No recordings. Type an id.'
            : 'Walks to the segment\'s first frame, plays it once, then goes on from its last frame.',
        child: recordings.isEmpty
            ? GuiTextInput(id: recordingId, value: recording, placeholder: 'recording id', label: '$label recording', onChanged: (String value) => set('recording', value.trim()))
            : GuiSelect(references: DocumentKind.recording, id: recordingId, value: recording.isEmpty ? null : recording, options: recordings.keys.toList(), onChanged: (String? value) => set('recording', value ?? '')),
      ),
      dom.div(classes: 'animus-route-coords', <Widget>[
        dom.label(<Widget>[
          const dom.span(<Widget>[Component.text('From tick')]),
          GuiNumberInput(value: asInt(node['fromTick']), label: '$label fromTick', placeholder: '0', onChanged: (num? value) => set('fromTick', value?.toInt())),
        ]),
        dom.label(<Widget>[
          const dom.span(<Widget>[Component.text('To tick')]),
          GuiNumberInput(
            value: asInt(node['toTick']),
            label: '$label toTick',
            placeholder: duration == null ? 'end' : 'end ($duration)',
            onChanged: (num? value) => set('toTick', value?.toInt()),
          ),
        ]),
      ]),
    ]);
  }

  Widget _nodeList(RouteSpec spec, (String, int)? picked) {
    if (spec.nodes.isEmpty) {
      return dom.p(classes: 'animus-muted', <Widget>[
        Component.text(component.readOnly
            ? 'No points. Add them in the route document.'
            : 'No points yet. Click the grid or use Add point.'),
      ]);
    }
    String wait(RouteEntry node) => node.guide ? '-' : (node.waitTicks == null ? 'route (${spec.waitTicks})' : '${node.waitTicks}');
    String kind(RouteEntry node) => switch (node) {
          RouteStep() => 'step',
          _ when node.guide => 'guide',
          _ => 'point',
        };
    String where(RouteEntry node) => switch (node) {
          RouteNode() => '${_coord(node.x)}, ${_coord(node.y)}, ${_coord(node.z)}',
          RouteStep() => '${node.recording.isEmpty ? 'no recording' : node.recording}, ticks ${node.fromTick} to ${node.toTick ?? 'end'}',
          RouteOther() => 'missing x, y or z',
        };
    return dom.ol(classes: 'animus-route-list', attributes: const <String, String>{'aria-label': 'Route points'}, <Widget>[
      for (int index = 0; index < spec.nodes.length; index++)
        dom.li(
          classes: picked != null && picked == _listKey(spec, index) ? 'is-selected' : null,
          <Widget>[
            dom.button(
              classes: 'animus-route-list-item',
              attributes: const <String, String>{'type': 'button'},
              events: <String, void Function(web.Event)>{
                'click': (web.Event _) => setState(() => _picked = _listKey(spec, index)),
              },
              <Widget>[
                dom.code(<Widget>[Component.text('$index')]),
                dom.span(<Widget>[Component.text(kind(spec.nodes[index]))]),
                dom.span(<Widget>[Component.text(where(spec.nodes[index]))]),
                dom.span(classes: 'animus-muted', <Widget>[Component.text('wait ${wait(spec.nodes[index])}${_extras(index < spec.points.length ? asMap(asList(_route['points'])[index]) : const <String, Object?>{})}')]),
              ],
            ),
          ],
        ),
    ]);
  }

  /// A point's weight, jitter, style and junctions for its list row.
  static String _extras(JsonMap point) => <String>[
        if (point['weight'] != null) ', weight ${point['weight']}',
        if (point['jitter'] != null) ', jitter ${point['jitter']}',
        if (point['style'] != null) ', ${point['style']}',
        if (point['next'] is List<Object?>) ', next ${asList(point['next']).map((Object? junction) => asMap(junction)['to']).join(' or ')}',
      ].join();

  static (String, int) _listKey(RouteSpec spec, int number) => number < spec.points.length ? ('points', number) : ('guides', number - spec.points.length);

  static String _coord(double value) => value == value.roundToDouble() ? '${value.toInt()}' : '$value';
}
