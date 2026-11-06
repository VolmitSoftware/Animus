/// What a gizmo drag or an arrow-key nudge does to a rig or an animation.
/// Without an animation the gizmo edits the rest pose; with one it keys the
/// bone at the playhead on the selected track, changing that track's own
/// value (not the combined pose).
library;

import 'rig_edit.dart';
import 'rig_sampler.dart';

enum GizmoMode {
  move('Move'),
  rotate('Rotate'),
  scale('Scale');

  const GizmoMode(this.label);

  final String label;

  TrackTarget get target => switch (this) {
    GizmoMode.move => TrackTarget.translation,
    GizmoMode.rotate => TrackTarget.rotation,
    GizmoMode.scale => TrackTarget.scale,
  };

  /// One arrow-key step: 1/16 block, 5 degrees or 5 percent; a quarter of
  /// that with Shift.
  double nudge(int direction, {bool fine = false}) {
    final double scale = fine ? 0.25 : 1;
    return switch (this) {
      GizmoMode.move => direction * scale / 16,
      GizmoMode.rotate => direction * scale * 5,
      GizmoMode.scale => 1 + direction * scale * 0.05,
    };
  }
}

double _snap(double value, double step) => (value / step).roundToDouble() * step;

double _scaled(double base, double factor) => _snap((base * factor).clamp(0.01, 64).toDouble(), 0.01);

List<double> _vector(Object? value, double fallback) =>
    value is List<Object?> && value.length == 3
        ? <double>[for (final Object? component in value) component is num ? component.toDouble() : fallback]
        : <double>[fallback, fallback, fallback];

num _round(double value) {
  final double rounded = (value * 100000).roundToDouble() / 100000;
  return rounded == rounded.roundToDouble() ? rounded.round() : rounded;
}

List<num> cleanVector(List<double> values) => <num>[for (final double value in values) _round(value)];

/// Rest pose: move shifts the bone's pivot with its subtree by [amount]
/// blocks, rotate turns its rest rotation by [amount] degrees, scale
/// multiplies its visual's scale by [amount].
JsonMap restGizmoEdit(JsonMap rig, String bone, GizmoMode mode, int axis, double amount) {
  switch (mode) {
    case GizmoMode.move:
      final double delta = _snap(amount, 1 / 64);
      JsonMap out = rig;
      for (final String id in subtree(rig, bone)) {
        out = updateBone(out, id, (JsonMap entry) {
          final List<double> pivot = _vector(entry['pivot'], 0);
          pivot[axis] += delta;
          entry['pivot'] = cleanVector(pivot);
        });
      }
      return out;
    case GizmoMode.rotate:
      return updateBone(rig, bone, (JsonMap entry) {
        final List<double> rotation = _vector(entry['rotation'], 0);
        rotation[axis] = _snap(rotation[axis] + amount, 0.5);
        entry['rotation'] = cleanVector(rotation);
      });
    case GizmoMode.scale:
      return updateBone(rig, bone, (JsonMap entry) {
        final Object? visual = entry['visual'];
        if (visual is! JsonMap || visual['type'] == 'none') throw const RigEditException('Only a bone with a visual has a scale.');
        final List<double> scale = _vector(visual['scale'], 1);
        scale[axis] = _scaled(scale[axis], amount);
        visual['scale'] = cleanVector(scale);
      });
  }
}

/// The blend of [track], or [fallback] for a track still to be made.
Blend trackBlend(JsonMap animation, int? track, Blend fallback) {
  if (track == null) return fallback;
  final Object? blend = tracksOf(animation)[track]['blend'];
  return blend is String ? Blend.values.byName(blend) : Blend.replace;
}

/// A vector channel's value as [track] holds it at [time], or, for a new
/// track, the value that leaves [pose] unchanged under [blend].
List<double> ownTrackValue(JsonMap animation, int? track, TrackTarget target, Blend blend, double time, BonePose? pose) {
  final Object? current = track == null ? null : trackValueAt(tracksOf(animation)[track], time);
  if (current is List<double>) return current;
  final List<double> channel = switch (target) {
    TrackTarget.rotation => pose?.rotation ?? <double>[0, 0, 0],
    TrackTarget.translation => pose?.translation ?? <double>[0, 0, 0],
    _ => pose?.scale ?? <double>[1, 1, 1],
  };
  return neutralValue(blend, target, channel);
}

/// Keys [bone] at [time] for [mode], on [track] when it is this bone and
/// channel, else the first such track, else a new [blend] track.
JsonMap keyGizmoEdit(
  JsonMap animation,
  String bone,
  GizmoMode mode,
  int axis,
  double amount,
  double time, {
  int? track,
  Blend blend = Blend.replace,
  BonePose? pose,
}) {
  final TrackTarget target = mode.target;
  final int? keyed = trackFor(animation, bone, target, preferred: track);
  final List<double> value = List<double>.of(ownTrackValue(animation, keyed, target, trackBlend(animation, keyed, blend), time, pose));
  switch (mode) {
    case GizmoMode.move:
      value[axis] = _snap(value[axis] + amount, 1 / 64);
    case GizmoMode.rotate:
      value[axis] = _snap(value[axis] + amount, 0.5);
    case GizmoMode.scale:
      value[axis] = _scaled(value[axis], amount);
  }
  return setKeyframe(animation, bone, target, time, cleanVector(value), track: keyed, blend: blend);
}
