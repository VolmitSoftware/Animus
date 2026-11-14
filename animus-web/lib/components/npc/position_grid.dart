/// A top-down block grid for picking positions, drawn like the route
/// editor's: numbered marks, the NPC, axis lines and the same legend. A
/// click on the grid places the picked mark there and a drag moves one. From
/// the keyboard each mark is a button: arrows move it a quarter block (Shift
/// for a whole block) and Enter picks it.
library;

import 'dart:js_interop';
import 'dart:math' as math;

import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:jaspr/dom.dart' as dom;
import 'package:jaspr/jaspr.dart' show Component;
import 'package:web/web.dart' as web;

import '../../config/docs_links.dart';
import '../../logic/npc/route_model.dart';
import '../info_tip.dart';
import '../rig/rig_gizmo.dart' show eventNumber;
import 'npc_layout.dart';

int _nextPositionGridId = 0;

/// One mark: its number on the grid, its accessible name and where it is.
class PositionMark {
  const PositionMark({required this.number, required this.name, required this.x, required this.y, required this.z});

  final String number;
  final String name;
  final double x;
  final double y;
  final double z;
}

class PositionGrid extends StatefulWidget {
  const PositionGrid({
    required this.label,
    required this.marks,
    required this.onMove,
    required this.onNudge,
    this.picked,
    this.onPick,
    this.origin,
    super.key,
  });

  /// Names the grid for assistive technology ("Respawn locations").
  final String label;
  final List<PositionMark> marks;

  /// Mark [index] dropped or placed at block [x], [z].
  final void Function(int index, double x, double z) onMove;

  /// Mark [index] moved by [dx], [dz] blocks from the keyboard.
  final void Function(int index, double dx, double dz) onNudge;

  /// The mark a click on the grid moves; the first when null.
  final int? picked;
  final void Function(int index)? onPick;

  /// The NPC's x, y and z, drawn for reference.
  final (double, double, double)? origin;

  @override
  State<PositionGrid> createState() => _PositionGridState();
}

class _PositionGridState extends State<PositionGrid> {
  final String _gridId = 'animus-position-grid-${_nextPositionGridId++}';

  /// A mark being dragged and where it is now, reported on release.
  (int, double, double)? _drag;
  RouteBounds? _dragBounds;
  JSFunction? _move;
  JSFunction? _up;

  @override
  void dispose() {
    _release();
    super.dispose();
  }

  static String _n(double value) => value.toStringAsFixed(2);

  static String _coord(double value) => value == value.roundToDouble() ? '${value.toInt()}' : value.toStringAsFixed(2);

  RouteBounds get _bounds {
    final (double, double, double)? origin = component.origin;
    return RouteBounds.around(const <RouteNode>[], extra: <(double, double)>[
      for (final PositionMark mark in component.marks) (mark.x, mark.z),
      if (origin != null) (origin.$1, origin.$3),
    ]);
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

  void _grab(int index, RouteBounds bounds) {
    _release();
    component.onPick?.call(index);
    setState(() => _dragBounds = bounds);
    _move = ((web.Event event) {
      final (double, double)? at = _toGrid(event, bounds);
      if (at != null) setState(() => _drag = (index, snapBlock(at.$1), snapBlock(at.$2)));
    }).toJS;
    _up = ((web.Event _) {
      final (int, double, double)? drag = _drag;
      _release();
      setState(() {
        _drag = null;
        _dragBounds = null;
      });
      if (drag != null) component.onMove(drag.$1, drag.$2, drag.$3);
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

  void _place(web.Event event, RouteBounds bounds) {
    if (component.marks.isEmpty) return;
    final (double, double)? at = _toGrid(event, bounds);
    if (at == null) return;
    final int index = (component.picked ?? 0).clamp(0, component.marks.length - 1);
    component.onMove(index, snapBlock(at.$1), snapBlock(at.$2));
  }

  void _key(web.Event event, int index) {
    final web.KeyboardEvent keys = event as web.KeyboardEvent;
    if (keys.key == 'Enter' || keys.key == ' ') {
      event.preventDefault();
      component.onPick?.call(index);
      return;
    }
    final (double, double)? step = routeNudge(keys.key, shift: keys.shiftKey);
    if (step == null) return;
    event.preventDefault();
    component.onPick?.call(index);
    component.onNudge(index, step.$1, step.$2);
  }

  @override
  Widget build(BuildContext context) {
    final RouteBounds bounds = _dragBounds ?? _bounds;
    final double step = bounds.step;
    final double radius = math.max(0.3, bounds.width / 70);
    final (double, double, double)? origin = component.origin;
    final int? picked = component.picked;
    return dom.div(classes: 'animus-route-grid-wrap animus-position-grid', attributes: const <String, String>{'data-size-exempt': ''}, <Widget>[
      dom.svg(
        <Component>[
          for (double x = (bounds.minX / step).ceilToDouble() * step; x <= bounds.maxX; x += step)
            dom.line(const <Component>[], x1: _n(x), y1: _n(bounds.minZ), x2: _n(x), y2: _n(bounds.maxZ), classes: 'animus-route-gridline${x == 0 ? ' is-axis' : ''}'),
          for (double z = (bounds.minZ / step).ceilToDouble() * step; z <= bounds.maxZ; z += step)
            dom.line(const <Component>[], x1: _n(bounds.minX), y1: _n(z), x2: _n(bounds.maxX), y2: _n(z), classes: 'animus-route-gridline${z == 0 ? ' is-axis' : ''}'),
          if (origin != null) ...<Component>[
            dom.circle(const <Component>[], cx: _n(origin.$1), cy: _n(origin.$3), r: _n(radius * 1.3), classes: 'animus-route-npc'),
            _label(origin.$1, origin.$3 - radius * 2, 'NPC', radius, 'is-npc'),
          ],
          for (int index = 0; index < component.marks.length; index++) _mark(index, radius, bounds, selected: picked == index),
        ],
        id: _gridId,
        classes: 'animus-route-grid${component.marks.isEmpty ? '' : ' is-adding'}',
        viewBox: '${_n(bounds.minX)} ${_n(bounds.minZ)} ${_n(bounds.width)} ${_n(bounds.depth)}',
        attributes: <String, String>{
          'role': 'img',
          'aria-label': '${component.label}, top down: ${component.marks.length} marked, x ${bounds.minX.toInt()} to ${bounds.maxX.toInt()}, z ${bounds.minZ.toInt()} to ${bounds.maxZ.toInt()}',
          'data-min-x': _n(bounds.minX),
          'data-min-z': _n(bounds.minZ),
          'data-size': _n(bounds.width),
          'preserveAspectRatio': 'none',
        },
        events: <String, void Function(web.Event)>{'click': (web.Event event) => _place(event, bounds)},
      ),
      dom.div(styles: legendStyles, <Widget>[
        dom.span(<Widget>[
          withTip(
            Component.text('x ${bounds.minX.toInt()} to ${bounds.maxX.toInt()}, z ${bounds.minZ.toInt()} to ${bounds.maxZ.toInt()}; north is up; grid every ${step.toInt()} block${step == 1 ? '' : 's'}'),
            'Click the grid to place the picked mark, or drag a mark. With a mark focused, arrows move it a quarter block, Shift+arrows a whole block.',
            docs: Docs.editorKeyboard,
          ),
        ]),
      ]),
    ]);
  }

  Component _label(double x, double z, String text, double radius, String kind) => Component.element(
        tag: 'text',
        classes: 'animus-route-label $kind',
        attributes: <String, String>{'x': _n(x), 'y': _n(z), 'font-size': _n(radius * 1.6), 'text-anchor': 'middle', 'dominant-baseline': 'central'},
        children: <Component>[Component.text(text)],
      );

  Component _mark(int index, double radius, RouteBounds bounds, {required bool selected}) {
    final PositionMark mark = component.marks[index];
    final (int, double, double)? drag = _drag;
    final double x = drag != null && drag.$1 == index ? drag.$2 : mark.x;
    final double z = drag != null && drag.$1 == index ? drag.$3 : mark.z;
    return Component.element(
      tag: 'g',
      key: ValueKey<String>('mark:$index'),
      classes: 'animus-route-node${selected ? ' is-selected' : ''}',
      children: <Component>[
        dom.circle(
          const <Component>[],
          cx: _n(x),
          cy: _n(z),
          r: _n(radius),
          classes: 'animus-route-dot',
          attributes: <String, String>{
            'id': '$_gridId-mark-$index',
            'data-mark': '$index',
            'role': 'button',
            'tabindex': '0',
            'aria-label': '${mark.name} at ${_coord(x)}, ${_coord(mark.y)}, ${_coord(z)}',
            'aria-pressed': '$selected',
            'aria-keyshortcuts': 'ArrowLeft ArrowRight ArrowUp ArrowDown',
          },
          events: <String, void Function(web.Event)>{
            'keydown': (web.Event event) => _key(event, index),
            'pointerdown': (web.Event event) {
              event.preventDefault();
              event.stopPropagation();
              _grab(index, bounds);
            },
            'click': (web.Event event) => event.stopPropagation(),
          },
        ),
        _label(x, z, mark.number, radius, 'is-point'),
      ],
    );
  }
}
