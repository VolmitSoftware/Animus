/// The stage overlay for the rig editor: the skeleton (a dot per bone, a line
/// to its parent) and, on the selected bone, three axis handles for the
/// current gizmo mode. Handles are dragged along their axis as it appears on
/// screen; the editor turns the drag into a value.
library;

import 'dart:js_interop';
import 'dart:js_interop_unsafe';
import 'dart:math' as math;

import 'package:jaspr/dom.dart' as dom;
import 'package:jaspr/jaspr.dart' show Component;
import 'package:web/web.dart' as web;

import '../../logic/rig_gizmo_edit.dart';
import '../../logic/rig_sampler.dart';
import '../../mc/rig/rig_scene.dart';
import '../../mc/scene/mc_camera.dart';
import '../../mc/scene/mc_math.dart';

/// Gizmo arms are this long in blocks.
const double gizmoArmBlocks = 0.45;

/// A handle grabbed on screen.
class GizmoGrab {
  const GizmoGrab(this.axis, this.originX, this.originY, this.directionX, this.directionY, this.armPixels, this.startX, this.startY);

  final int axis;
  final double originX;
  final double originY;

  /// The axis on screen, unit length.
  final double directionX;
  final double directionY;

  /// Screen length of a [gizmoArmBlocks] arm.
  final double armPixels;
  final double startX;
  final double startY;

  /// Pixels the pointer moved along the axis since the grab.
  double along(double x, double y) => (x - startX) * directionX + (y - startY) * directionY;

  /// Blocks the pointer moved along the axis since the grab.
  double blocks(double x, double y) => armPixels <= 0 ? 0 : along(x, y) / armPixels * gizmoArmBlocks;
}

/// World-space unit axes of [affine]'s rotation (scale removed).
List<McVec3> frameAxes(Affine? affine) {
  if (affine == null) return const <McVec3>[McVec3(1, 0, 0), McVec3(0, 1, 0), McVec3(0, 0, 1)];
  final List<double> m = affine.m;
  return <McVec3>[
    for (int column = 0; column < 3; column++) _unit(McVec3(m[column], m[4 + column], m[8 + column]), column),
  ];
}

McVec3 _unit(McVec3 v, int axis) {
  final double length = v.length;
  if (length < 1e-9) return <McVec3>[const McVec3(1, 0, 0), const McVec3(0, 1, 0), const McVec3(0, 0, 1)][axis];
  return v * (1 / length);
}

class GizmoOverlay {
  const GizmoOverlay({
    required this.bones,
    required this.parents,
    required this.poses,
    required this.selected,
    required this.mode,
    required this.onSelect,
    required this.onGrab,
    required this.onNudge,
  });

  /// Bone ids in document order.
  final List<String> bones;
  final Map<String, String?> parents;
  final Map<String, BonePose> poses;
  final String? selected;
  final GizmoMode mode;
  final void Function(String bone) onSelect;
  final void Function(GizmoGrab grab, web.PointerEvent event) onGrab;

  /// An arrow key on a focused handle: [axis], +1 or -1, and whether Shift
  /// asked for a finer step.
  final void Function(int axis, int direction, bool fine) onNudge;

  static const List<String> _axisNames = <String>['x', 'y', 'z'];

  List<Component> build(McCamera camera, double width, double height) {
    McVec3? screen(McVec3 point) => mcProjectToPixels(camera, point, widthPx: width, heightPx: height);
    final Map<String, McVec3?> at = <String, McVec3?>{
      for (final String bone in bones)
        if (poses[bone] != null) bone: screen(originOf(poses[bone]!.model)),
    };
    final List<Component> lines = <Component>[];
    final List<Component> dots = <Component>[];
    for (final String bone in bones) {
      final McVec3? point = at[bone];
      if (point == null) continue;
      final String? parent = parents[bone];
      final McVec3? from = parent == null ? null : at[parent];
      if (from != null) {
        lines.add(dom.line(
          const <Component>[],
          x1: _px(from.x),
          y1: _px(from.y),
          x2: _px(point.x),
          y2: _px(point.y),
          classes: 'animus-rig-bone-link',
        ));
      }
      dots.add(dom.circle(
        const <Component>[],
        cx: _px(point.x),
        cy: _px(point.y),
        r: bone == selected ? '7' : '5',
        classes: 'animus-rig-bone-dot${bone == selected ? ' is-selected' : ''}',
        // Pointer shortcut only: the bone tree is the keyboard route.
        attributes: <String, String>{'data-gizmo': 'bone', 'data-bone': bone, 'aria-hidden': 'true'},
        events: <String, void Function(web.Event)>{
          'pointerdown': (web.Event event) {
            event.preventDefault();
            onSelect(bone);
          },
        },
      ));
    }
    final List<Component> handles = <Component>[];
    final BonePose? pose = selected == null ? null : poses[selected];
    final McVec3? origin = selected == null ? null : at[selected];
    if (pose != null && origin != null) {
      final String? parent = parents[selected];
      final List<McVec3> axes = frameAxes(mode == GizmoMode.move ? (parent == null ? null : poses[parent]?.model) : pose.model);
      final McVec3 world = originOf(pose.model);
      for (int axis = 0; axis < 3; axis++) {
        final McVec3? end = screen(world + axes[axis] * gizmoArmBlocks);
        if (end == null) continue;
        final double dx = end.x - origin.x, dy = end.y - origin.y;
        final double length = math.sqrt(dx * dx + dy * dy);
        final String name = _axisNames[axis];
        handles.add(dom.line(
          const <Component>[],
          x1: _px(origin.x),
          y1: _px(origin.y),
          x2: _px(end.x),
          y2: _px(end.y),
          classes: 'animus-rig-axis is-$name',
        ));
        handles.add(dom.circle(
          const <Component>[],
          cx: _px(end.x),
          cy: _px(end.y),
          r: '9',
          classes: 'animus-rig-handle is-$name is-${mode.name}',
          attributes: <String, String>{
            'data-gizmo': 'handle',
            'data-axis': name,
            'aria-label': '${mode.label} ${name.toUpperCase()}',
            'role': 'button',
            'tabindex': '0',
            'focusable': 'true',
          },
          events: <String, void Function(web.Event)>{
            'keydown': (web.Event event) {
              final web.KeyboardEvent key = event as web.KeyboardEvent;
              final int direction = switch (key.key) {
                'ArrowRight' || 'ArrowUp' => 1,
                'ArrowLeft' || 'ArrowDown' => -1,
                _ => 0,
              };
              if (direction == 0) return;
              event.preventDefault();
              event.stopPropagation();
              onNudge(axis, direction, key.shiftKey);
            },
            'pointerdown': (web.Event event) {
              event.preventDefault();
              final web.PointerEvent pointer = event as web.PointerEvent;
              onGrab(
                GizmoGrab(
                  axis,
                  origin.x,
                  origin.y,
                  length < 1e-6 ? 1 : dx / length,
                  length < 1e-6 ? 0 : dy / length,
                  length,
                  eventNumber(pointer, 'clientX'),
                  eventNumber(pointer, 'clientY'),
                ),
                pointer,
              );
            },
          },
        ));
      }
    }
    return <Component>[
      dom.svg(
        <Component>[...lines, ...dots, ...handles],
        classes: 'animus-rig-gizmo',
        attributes: <String, String>{'width': _px(width), 'height': _px(height), 'viewBox': '0 0 ${_px(width)} ${_px(height)}'},
      ),
    ];
  }

  static String _px(double value) => value.toStringAsFixed(1);
}

/// A numeric event property read untyped: `package:web` declares `clientX`
/// as `int` and browsers hand back fractions.
double eventNumber(web.Event event, String property) {
  final JSAny? value = (event as JSObject).getProperty<JSAny?>(property.toJS);
  return value != null && value.isA<JSNumber>() ? (value as JSNumber).toDartDouble : 0;
}
