/// A waypoint route (`modules["animus:navigation"]` inline, or a
/// `routes/<id>.json` document): the map first, the points beside it on a
/// wide editor and under it on a narrow one, the picked point's fields, then
/// the route's settings. One tool switch picks what a click on the map does
/// (add points, add guides, move) or shows the route in 3D over the
/// server's blocks. From the keyboard, Tab reaches each point on the map:
/// arrows move it a quarter block (Shift for a whole block), Delete removes
/// it, Enter picks it; Add at end adds one beside the last. Recording steps
/// have no position: they are listed and numbered with the points. With a
/// paired server that shares terrain, the map draws the surface under the
/// points, a placed or dragged point lands on a standable block, and points
/// and legs that cannot be walked are listed with a link to each point.
library;

import 'dart:convert';
import 'dart:js_interop';

import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:jaspr/dom.dart' as dom;
import 'package:jaspr/jaspr.dart' show Component;
import 'package:web/web.dart' as web;

import '../../logic/diagnostic_text.dart';
import '../../logic/gui/gui_json.dart';
import '../../logic/npc/route_fields.dart';
import '../../logic/npc/npc_display.dart';
import '../../logic/npc/route_model.dart';
import '../../logic/terrain/terrain_path.dart';
import '../../logic/terrain/terrain_snap.dart';
import '../../logic/terrain/terrain_volume.dart';
import '../../model/animus_document.dart';
import '../../model/document_kind.dart';
import '../../ui/action_row.dart';
import '../../config/docs_links.dart';
import '../../ui/info_tip.dart';
import '../../ui/library_list.dart';
import '../../ui/library_query.dart';
import '../../ui/menu.dart';
import '../../ui/mode.dart';
import '../../ui/tone.dart';
import '../../ui/toggle_choice.dart';
import '../gui/gui_fields.dart';
import '../rig/rig_gizmo.dart' show eventNumber;
import 'route_map.dart';
import 'route_point_editor.dart';
import 'route_settings.dart';
import 'route_terrain.dart';
import 'route_view3d.dart';
import 'npc_layout.dart';

int _nextGridId = 0;

/// The recording documents in [documents] by id, with their durationTicks.
Map<String, int?> routeRecordings(List<AnimusDocument> documents) => <String, int?>{
  for (final AnimusDocument document in documents)
    if (document.kind == DocumentKind.recording) document.id: asInt(asMap(document.json)['durationTicks']),
};

/// What a click on the map does, or the 3D view in its place.
enum RouteTool { point, guide, select, view3d }

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
    this.ownStyle,
    this.ownSpeed,
    super.key,
  });

  /// The route fields (a module object or a route document).
  final JsonMap value;

  /// The new route and the route it was worked out from, which can be
  /// ahead of [value] while quick edits are still being saved.
  final void Function(JsonMap value, JsonMap from) onChanged;

  /// The NPC's x, y and z, drawn on the map and used for new points.
  final (double, double, double)? origin;

  /// The world whose terrain the map draws; null shows no terrain.
  final String? world;

  /// Where [value] sits in its document, for diagnostics.
  final String path;
  final List<Diagnostic> diagnostics;
  final bool readOnly;

  /// The workspace's recordings by id, with their durationTicks when known.
  final Map<String, int?> recordings;

  /// The NPC's own `movement.style` for an inline route; null for a route document.
  final String? ownStyle;

  /// The NPC's own `movement.speed` for an inline route, which it walks at
  /// while the route sets none.
  final num? ownSpeed;

  @override
  State<RouteEditor> createState() => _RouteEditorState();
}

bool _sameDiagnostics(List<Diagnostic> a, List<Diagnostic> b) {
  if (identical(a, b)) return true;
  if (a.length != b.length) return false;
  for (int index = 0; index < a.length; index++) {
    if (a[index].path != b[index].path || a[index].message != b[index].message || a[index].severity != b[index].severity) return false;
  }
  return true;
}

bool _sameRecordings(Map<String, int?> a, Map<String, int?> b) =>
    identical(a, b) || (a.length == b.length && a.entries.every((MapEntry<String, int?> entry) => b.containsKey(entry.key) && b[entry.key] == entry.value));

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

  late final RouteTerrain _terrain = RouteTerrain(() {
    if (mounted) setState(() {});
  });

  /// The last route reported and not yet echoed back, so quick key repeats
  /// build on each other instead of on the value from before.
  JsonMap? _sent;

  /// The parent form builds again on every keystroke anywhere in it; the
  /// editor only when what it edits or shows changed. Callbacks read the
  /// newest [component] when they run.
  @override
  bool shouldRebuild(RouteEditor newComponent) =>
      !identical(newComponent.value, component.value) ||
      newComponent.origin != component.origin ||
      newComponent.world != component.world ||
      newComponent.path != component.path ||
      newComponent.readOnly != component.readOnly ||
      newComponent.ownStyle != component.ownStyle ||
      newComponent.ownSpeed != component.ownSpeed ||
      !_sameDiagnostics(newComponent.diagnostics, component.diagnostics) ||
      !_sameRecordings(newComponent.recordings, component.recordings);

  @override
  void didUpdateWidget(RouteEditor oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (!identical(oldWidget.value, component.value)) _sent = null;
  }

  @override
  void dispose() {
    _release();
    _terrain.dispose();
    super.dispose();
  }

  JsonMap get _route => _sent ?? component.value;

  void _write(JsonMap next) {
    final JsonMap from = _route;
    _sent = next;
    component.onChanged(next, from);
  }

  void _pick((String, int)? next) => setState(() => _picked = next);

  bool get _canShow3d => component.world != null && _terrain.available;

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

  /// A click on the map: adds a point or guide where it landed.
  void _onGrid(web.Event event, RouteBounds bounds) {
    if (component.readOnly || (_tool != RouteTool.point && _tool != RouteTool.guide)) return;
    final (double, double)? at = _toGrid(event, bounds);
    if (at == null) return;
    final RouteSpec spec = RouteSpec(_route);
    final bool guide = _tool == RouteTool.guide;
    final List<RouteEntry> same = guide ? spec.guides : spec.points;
    final RouteNode? sameLast = _lastPlaced(same), pointLast = _lastPlaced(spec.points);
    final double near = sameLast?.y ?? pointLast?.y ?? (component.origin?.$2 ?? 64).floorToDouble();
    final double y = _terrain.snap(snapBlock(at.$1), snapBlock(at.$2), near)?.toDouble() ?? near;
    _write(addRouteNode(_route, snapBlock(at.$1), y, snapBlock(at.$2), guide: guide));
    _pick((guide ? 'guides' : 'points', same.length));
  }

  void _onNodePointer(web.Event event, String key, int index, RouteBounds bounds) {
    event.preventDefault();
    event.stopPropagation();
    if (component.readOnly) {
      _pick((key, index));
    } else {
      _grab(key, index, bounds);
    }
  }

  /// Moves, removes or picks the focused node from the keyboard.
  void _onNodeKey(web.Event event, String key, int index) {
    final web.KeyboardEvent keys = event as web.KeyboardEvent;
    if (keys.key == 'Enter' || keys.key == ' ') {
      event.preventDefault();
      _pick((key, index));
      return;
    }
    if (component.readOnly) return;
    if (keys.key == 'Delete' || keys.key == 'Backspace') {
      event.preventDefault();
      final int left = asList(_route[key]).length - 1;
      _write(removeRouteNode(_route, key, index));
      _pick(null);
      if (left > 0) _focusNode(key, index.clamp(0, left - 1));
      return;
    }
    final (double, double)? step = routeNudge(keys.key, shift: keys.shiftKey);
    if (step == null) return;
    event.preventDefault();
    _write(nudgeRouteNode(_route, key, index, step.$1, step.$2));
    _pick((key, index));
  }

  void _focus(String id) => Future<void>.delayed(Duration.zero, () {
    final web.Element? element = web.document.getElementById(id);
    if (element != null) (element as web.HTMLElement).focus();
  });

  void _focusNode(String key, int index) => _focus(RouteMap.nodeId(_gridId, key, index));

  static RouteNode? _lastPlaced(List<RouteEntry> entries) => entries.whereType<RouteNode>().lastOrNull;

  /// Where a new point goes: two blocks east of the last one, else of the
  /// NPC, on the ground when the terrain there is known.
  (double, double, double) _addAt(RouteSpec spec, {bool guide = false}) {
    final RouteNode? last = _lastPlaced(guide ? spec.guides : spec.points) ?? _lastPlaced(spec.points);
    final (double, double, double)? origin = component.origin;
    final double x = (last?.x ?? origin?.$1.floorToDouble() ?? 0) + 2;
    final double z = last?.z ?? origin?.$3.floorToDouble() ?? 0;
    final double near = last?.y ?? origin?.$2.floorToDouble() ?? 64;
    return (x, _terrain.snap(x, z, near)?.toDouble() ?? near, z);
  }

  void _addNode({required bool guide}) {
    final RouteSpec spec = RouteSpec(_route);
    final (double x, double y, double z) = _addAt(spec, guide: guide);
    final int index = guide ? spec.guides.length : spec.points.length;
    _write(addRouteNode(_route, x, y, z, guide: guide));
    _pick((guide ? 'guides' : 'points', index));
    _focus('$_gridId-point-x');
  }

  void _addStep() {
    final int index = RouteSpec(_route).points.length;
    _write(addRouteStep(_route, component.recordings.keys.firstOrNull ?? ''));
    _pick(('points', index));
  }

  RouteSpec get _shownSpec {
    final (String, int, double, double)? drag = _drag;
    return RouteSpec(drag == null ? _route : _terrain.placed(_route, drag.$1, drag.$2, drag.$3, drag.$4));
  }

  List<Diagnostic> get _documentProblems => diagnosticsAt(component.diagnostics, component.path, nested: true);

  /// Route-wide fields whose problems show at the field itself.
  static const Set<String> _settingFields = <String>{'speed', 'mode', 'style', 'waitTicks', 'seed', 'startOffset', 'jitter', 'linkDistance', 'wander'};

  /// Point fields the picked point's form shows.
  static const Set<String> _pointFields = <String>{'x', 'y', 'z', 'waitTicks', 'jitter', 'style', 'speed', 'teleport', 'weight', 'next'};

  bool _shownAtField(String field, (String, int)? picked) {
    if (_settingFields.contains(field.split('.').first)) return true;
    if (picked == null) return false;
    final String prefix = '${picked.$1}[${picked.$2}].';
    return field.startsWith(prefix) && _pointFields.contains(field.substring(prefix.length).split(RegExp(r'[.\[]')).first);
  }

  @override
  Widget build(BuildContext context) {
    final RouteSpec spec = _shownSpec;
    final JsonMap route = _route;
    final (String, int)? picked = _picked != null && asList(route[_picked!.$1]).length > _picked!.$2 ? _picked : null;
    final List<(String, String)> problems = RouteSpec(route).problems(ownStyle: component.ownStyle);
    final bool readOnly = component.readOnly;
    final bool show3d = _tool == RouteTool.view3d && _canShow3d;
    final RouteBounds bounds = _bounds(spec);
    _terrain.cover(component.world, bounds, terrainRoutePath(spec, home: component.origin));
    final Map<(int, int), (int, int, int)> steep = _terrain.steepLinks(spec);
    final Map<(String, int), TerrainIssue> issues = _terrain.issues(spec);
    return dom.div(
      classes: 'animus-route-editor',
      attributes: const <String, String>{'role': 'group', 'aria-label': 'Route editor'},
      <Widget>[
        if (!readOnly) _toolbar(spec, ModeScope.advanced(context)),
        dom.div(classes: 'animus-route-body', <Widget>[
          dom.div(classes: 'animus-route-view', styles: stackStyles(), <Widget>[
            if (show3d)
              RouteView3d(
                spec: spec,
                world: component.world,
                focus: routeFocus(spec, picked, component.origin),
                origin: component.origin,
                onVolume: (TerrainVolume? volume) => setState(() => _terrain.volume = volume),
              )
            else ...<Widget>[
              RouteMap(
                id: _gridId,
                spec: spec,
                bounds: bounds,
                signature: _signature(route, spec, bounds, picked),
                origin: component.origin,
                picked: picked,
                readOnly: readOnly,
                adding: _tool == RouteTool.point || _tool == RouteTool.guide,
                tiles: _terrain.images(bounds),
                issues: issues,
                steep: steep,
                events: RouteMapEvents(onGrid: _onGrid, onNodeKey: _onNodeKey, onNodePointer: _onNodePointer),
              ),
              _legend(bounds, spec.guides.isNotEmpty),
            ],
            if (issues.isNotEmpty || steep.isNotEmpty) _issues('Terrain problems', routeTerrainIssues(spec, issues, steep), terrain: true),
            if (_remaining(problems, picked) case final List<RouteIssue> left when left.isNotEmpty) _issues('Route problems', left),
          ]),
          if (picked != null && !readOnly)
            RoutePointEditor(
              key: ValueKey<String>('point:${picked.$1}:${picked.$2}'),
              grid: _gridId,
              route: route,
              picked: picked,
              problems: problems,
              recordings: component.recordings,
              onWrite: _write,
              onPick: _pick,
              addAt: _addAt(RouteSpec(route)),
            ),
          dom.div(classes: 'animus-route-points', <Widget>[_points(spec, picked, issues)]),
        ]),
        if (!readOnly)
          RouteSettings(
            grid: _gridId,
            route: route,
            ownSpeed: component.ownSpeed,
            problems: problems,
            onWrite: _write,
            onOrder: (String order) {
              if (order != 'random' && _tool == RouteTool.guide) setState(() => _tool = RouteTool.point);
            },
          ),
      ],
    );
  }

  RouteBounds _bounds(RouteSpec spec) {
    final (double, double, double)? origin = component.origin;
    return _dragBounds ??
        RouteBounds.around(
          spec.placed,
          extra: <(double, double)>[
            if (origin != null) (origin.$1, origin.$3),
            if (spec.order == 'wander')
              for (final RouteNode point in spec.points.whereType<RouteNode>()) ...<(double, double)>[
                (point.x - spec.wanderRadius, point.z - spec.wanderRadius),
                (point.x + spec.wanderRadius, point.z + spec.wanderRadius),
              ],
          ],
        );
  }

  /// What the map draws, as text: positions and links, the pick, the tool,
  /// the terrain loaded and the bounds.
  String _signature(JsonMap route, RouteSpec spec, RouteBounds bounds, (String, int)? picked) => <Object?>[
    jsonEncode(<Object?>[route['points'], route['guides'], route['order'], route['loop'], route['linkDistance'], route['wander']]),
    if (_drag case final (String, int, double, double) drag) '${drag.$1}${drag.$2}${drag.$3}${drag.$4}',
    picked,
    _tool,
    component.readOnly,
    component.origin,
    _terrain.revision,
    '${bounds.minX},${bounds.minZ},${bounds.maxX},${bounds.maxZ}',
  ].join('|');

  Widget _toolbar(RouteSpec spec, bool advanced) {
    // Guides are an Advanced tool; Simple shows the ones a route has.
    final bool random = spec.order == 'random' && advanced;
    final bool full = spec.points.length >= routeMaxPoints;
    final RouteTool tool = (_tool == RouteTool.guide && !random) || (_tool == RouteTool.view3d && !_canShow3d) ? RouteTool.point : _tool;
    return ActionRow(
      dense: true,
      label: 'Route tools',
      moreId: '$_gridId-more',
      leading: <Widget>[
        // The tool switch keeps its size: a narrow toolbar folds Add at end
        // into its menu instead of clipping the switch.
        wrapItem('none', ToggleChoice<RouteTool>(
            label: 'Route tool',
            options: <ChoiceOption<RouteTool>>[
              const ChoiceOption<RouteTool>(RouteTool.point, 'Add points'),
              if (random) const ChoiceOption<RouteTool>(RouteTool.guide, 'Add guides'),
              const ChoiceOption<RouteTool>(RouteTool.select, 'Move'),
              if (_canShow3d) const ChoiceOption<RouteTool>(RouteTool.view3d, '3D'),
            ],
            value: tool,
            onChanged: (RouteTool next) => setState(() => _tool = next),
          )),
      ],
      more: <AppMenuEntry>[if (random) AppMenuEntry('Add guide', () => _addNode(guide: true)), if (spec.order != 'wander') AppMenuEntry('Add recording step', full ? null : _addStep)],
      secondary: <RowAction>[RowAction('Add at end', full ? null : () => _addNode(guide: false), icon: ArcaneIcon.plus(), id: '$_gridId-add')],
    );
  }

  Widget _legend(RouteBounds bounds, bool guides) {
    final String? note = _terrain.note();
    return dom.div(styles: legendStyles, <Widget>[
      if (guides)
        const dom.span(attributes: <String, String>{'data-route': 'guides-note'}, <Widget>[
          TipLabel(
            label: Component.text('Diamonds are guides.'),
            tip: 'Helper spots a random route may pass through on the way to a point. The NPC never waits at them.',
            docs: Docs.routes,
          ),
        ]),
      dom.span(<Widget>[
        TipLabel(
          label: Component.text('x ${bounds.minX.toInt()} to ${bounds.maxX.toInt()}, z ${bounds.minZ.toInt()} to ${bounds.maxZ.toInt()}. North is up.'),
          tip: component.readOnly
              ? 'Pick a point to see where it is.'
              : 'With Add points, click the map to add a point. Drag a point to move it. On a focused point, arrows move it a quarter block, Shift a whole block, and Delete removes it.',
        ),
      ]),
      if (note != null) dom.span(attributes: const <String, String>{'data-terrain-note': ''}, <Widget>[Component.text(note)]),
    ]);
  }

  /// Problems the fields on screen do not show, worded with point numbers.
  List<RouteIssue> _remaining(List<(String, String)> problems, (String, int)? picked) => <RouteIssue>[
    for (final (String field, String message) in problems)
      if (component.readOnly || !_shownAtField(field, picked)) RouteIssue(routeFieldName(field), routeProblemText(field, message), node: routeFieldNode(field)),
    for (final Diagnostic problem in _documentProblems) RouteIssue(diagnosticField(problem.path) ?? 'Route', diagnosticSentence(problem)),
  ];

  /// A list of problems; one on a point links to that point.
  Widget _issues(String title, List<RouteIssue> issues, {bool terrain = false}) {
    final String id = '$_gridId-${terrain ? 'terrain' : 'problems'}';
    return fieldStack(<Widget>[
      dom.h3(id: '$id-title', styles: groupHeadingStyles, <Widget>[Component.text(title)]),
      dom.ul(
        styles: plainListStyles,
        attributes: <String, String>{'aria-labelledby': '$id-title'},
        <Widget>[
          for (final RouteIssue issue in issues)
            dom.li(
              attributes: <String, String>{if (terrain) 'data-terrain-issue': ''},
              <Widget>[
                if (issue.node case final (String, int) node)
                  Button(
                    label: issue.label,
                    variant: ButtonVariant.link,
                    size: ButtonSize.sm,
                    attributes: <String, String>{'aria-label': 'Show ${issue.label}'},
                    onPressed: () {
                      _pick(node);
                      if (_tool == RouteTool.view3d) setState(() => _tool = RouteTool.select);
                      _focusNode(node.$1, node.$2);
                    },
                  )
                else
                  dom.strong(<Widget>[Component.text(issue.label)]),
                dom.span(<Widget>[Component.text(' ${issue.text}')]),
              ],
            ),
        ],
      ),
    ]);
  }

  /// The points list: kit rows in walking order, then the guides.
  Widget _points(RouteSpec spec, (String, int)? picked, Map<(String, int), TerrainIssue> issues) {
    String place(RouteEntry entry) => switch (entry) {
      RouteNode() => '${_coord(entry.x)}, ${_coord(entry.y)}, ${_coord(entry.z)}',
      RouteStep() => '${entry.recording.isEmpty ? 'No recording' : entry.recording}, ${secondsText(entry.fromTick)} to ${entry.toTick == null ? 'end' : secondsText(entry.toTick!)}',
      RouteOther() => 'Missing x, y or z',
    };
    LibraryRow row(String key, int index, RouteEntry entry, int number) {
      final bool guide = key == 'guides';
      final JsonMap json = asMap(asList(_route[key]).elementAtOrNull(index));
      final TerrainIssue? issue = issues[(key, index)];
      final String title = guide ? 'Guide $number' : (entry is RouteStep ? 'Step $number' : 'Point $number');
      return LibraryRow(
        key: '$key:$index',
        title: title,
        meta: <String>[
          place(entry),
          if (!guide) 'waits ${waitText(entry.waitTicks ?? spec.waitTicks)}',
          if (json['style'] case final String style) movementStyleLabels[style] ?? style,
          if (json['teleport'] is Map<String, Object?>) 'teleports',
          if (json['next'] is List<Object?>) 'then ${asList(json['next']).map((Object? junction) => '${(asInt(asMap(junction)['to']) ?? -1) + 1}').join(' or ')}',
        ].join(' · '),
        badges: <LibraryBadge>[
          if (picked == (key, index)) const LibraryBadge('Editing', tone: BadgeTone.info),
          if (issue != null) LibraryBadge(issue.text, tone: BadgeTone.warning),
        ],
        sortValues: <String, String>{LibrarySort.name.id: '${guide ? 1 : 0}${'$index'.padLeft(5, '0')}'},
        keywords: title,
      );
    }

    final List<LibraryRow> rows = <LibraryRow>[
      for (int index = 0; index < spec.points.length; index++) row('points', index, spec.points[index], index + 1),
      for (int index = 0; index < spec.guides.length; index++) row('guides', index, spec.guides[index], spec.points.length + index + 1),
    ];
    return LibraryList(
      key: ValueKey<String>('points:$_gridId'),
      id: '$_gridId-list',
      label: 'Route points',
      noun: 'point',
      rows: rows,
      fill: false,
      grow: true,
      emptyMessage: component.readOnly ? 'No points.' : 'No points yet. Click the map or press Add at end.',
      onOpen: (LibraryRow row) {
        final List<String> parts = row.key.split(':');
        final int? index = int.tryParse(parts.last);
        if (index != null) _pick((parts.first, index));
      },
    );
  }

  static String _coord(double value) => shownNumber(value);
}
