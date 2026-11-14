/// What the rig editor shows, worked out without the page: the tab a
/// narrow screen opens on, the words for channels, blends, loops and
/// easings, readouts in ticks and seconds, what a bone delete takes with
/// it, and where the gizmo handles sit inside the stage.
library;

import 'dart:math' as math;

import 'plural.dart';
import 'rig_edit.dart';
import 'rig_sampler.dart' show TrackTarget;

/// The panes under the stage; in a narrow editor one shows at a time as a
/// tab.
enum RigPane {
  bones('Bones'),
  inspector('Inspector'),
  timeline('Timeline');

  const RigPane(this.label);

  final String label;
}

/// An animation opens on the timeline; a rig on its bones.
RigPane initialRigPane({required bool animation}) => animation ? RigPane.timeline : RigPane.bones;

/// The name a bone gets when its name field commits, or null when the
/// field holds nothing new.
String? boneRenameTarget(String from, String draft) {
  final String target = draft.trim();
  return target.isEmpty || target == from ? null : target;
}

/// How many tracks key [bone] and in how many of [animations].
({int tracks, int animations}) boneTracks(Iterable<JsonMap> animations, String bone) {
  int tracks = 0, keyed = 0;
  for (final JsonMap animation in animations) {
    final int own = tracksOf(animation).where((JsonMap track) => track['bone'] == bone).length;
    tracks += own;
    if (own > 0) keyed++;
  }
  return (tracks: tracks, animations: keyed);
}

/// The confirmation line for deleting a bone: its tracks leave every
/// animation, its children move up to its [parent].
String deleteBoneMessage({required String? parent, required int children, required int tracks, required int animations}) => <String>[
      if (tracks > 0) 'Its ${countOf(tracks, 'track')} in ${countOf(animations, 'animation')} ${tracks == 1 ? 'goes' : 'go'} too.',
      if (children == 1) 'Its child bone moves to ${parent ?? 'the rig origin'}.',
      if (children > 1) 'Its $children child bones move to ${parent ?? 'the rig origin'}.',
      'Undo brings it back.',
    ].join(' ');

/// [value] with at most [decimals] decimals and no trailing zeros.
String trimmedNumber(double value, {int decimals = 3}) {
  final double scale = math.pow(10, decimals).toDouble();
  final double rounded = (value * scale).roundToDouble() / scale;
  return rounded == rounded.roundToDouble() ? '${rounded.round()}' : '$rounded';
}

/// [ticks] as seconds (20 ticks a second): "1 s", "0.38 s".
String secondsLabel(double ticks) => '${trimmedNumber(ticks / 20, decimals: 2)} s';

/// The playhead under the transport: "7.5 / 20 ticks · 0.38 s".
String playheadReadout(double time, double duration) => '${trimmedNumber(time)} / ${trimmedNumber(duration)} ticks · ${secondsLabel(time)}';

const Map<TrackTarget, String> channelLabels = <TrackTarget, String>{
  TrackTarget.rotation: 'Rotation',
  TrackTarget.translation: 'Position',
  TrackTarget.scale: 'Scale',
  TrackTarget.visible: 'Visible',
};

const Map<String, String> blendLabels = <String, String>{'replace': 'Replace', 'add': 'Add', 'multiply': 'Multiply'};

const Map<String, String> loopLabels = <String, String>{'once': 'Plays once', 'loop': 'Loops', 'pingpong': 'Ping-pong', 'hold': 'Holds at end'};

const Map<String, String> easingLabels = <String, String>{
  'linear': 'Linear',
  'hold': 'Hold',
  'ease_in': 'Ease in',
  'ease_out': 'Ease out',
  'ease_in_out': 'Ease in and out',
  'back_out': 'Back out',
  'bezier': 'Curve',
};

/// A lane's name: the bone, its channel, and the blend when it is not
/// replace ("head position · add").
String trackLabel(JsonMap track) {
  final TrackTarget? target = TrackTarget.values.where((TrackTarget each) => each.name == track['target']).firstOrNull;
  final String channel = target == null ? '${track['target']}' : channelLabels[target]!.toLowerCase();
  final Object? blend = track['blend'];
  return '${track['bone']} $channel${blend is String && blend != 'replace' ? ' · $blend' : ''}';
}

/// Handles keep this far from the stage's edge (the touch handle's radius).
const double gizmoSafeInset = 24;

/// An arm shorter than this on screen (seen end on) is drawn this long.
const double gizmoMinArm = 36;

/// Handle centres keep at least this far apart.
const double gizmoHandleGap = 22;

/// On touch screens handles are 44 px: they keep this far apart, with arms
/// at least [gizmoTouchArm] long.
const double gizmoTouchGap = 46;
const double gizmoTouchArm = 56;

/// Where one axis handle is drawn.
typedef HandleSpot = ({int axis, double x, double y});

/// Places the handles of a bone whose origin is at ([originX], [originY])
/// on a [width] x [height] stage. [ends] holds each axis's projected arm
/// end (null when it is behind the camera). Handles stay inside the safe
/// area, arms seen end on get [gizmoMinArm], and handles that would sit on
/// one another are turned apart, [gap] apart. Nothing shows while the
/// origin is outside the safe area.
List<HandleSpot> placeHandles(
  double originX,
  double originY,
  List<(double, double)?> ends,
  double width,
  double height, {
  double gap = gizmoHandleGap,
  double minArm = gizmoMinArm,
}) {
  final double minX = gizmoSafeInset, maxX = width - gizmoSafeInset;
  final double minY = gizmoSafeInset, maxY = height - gizmoSafeInset;
  bool inside(double x, double y) => x >= minX - 1e-9 && x <= maxX + 1e-9 && y >= minY - 1e-9 && y <= maxY + 1e-9;
  if (maxX <= minX || maxY <= minY || !inside(originX, originY)) return const <HandleSpot>[];

  /// The farthest point toward ([dx], [dy]) within [length] that stays inside.
  (double, double) reach(double dx, double dy, double length) {
    double t = length;
    if (dx > 1e-9) t = math.min(t, (maxX - originX) / dx);
    if (dx < -1e-9) t = math.min(t, (minX - originX) / dx);
    if (dy > 1e-9) t = math.min(t, (maxY - originY) / dy);
    if (dy < -1e-9) t = math.min(t, (minY - originY) / dy);
    t = math.max(0, t);
    return (originX + dx * t, originY + dy * t);
  }

  const List<(double, double)> fallback = <(double, double)>[(1, 0), (0, -1), (-math.sqrt1_2, math.sqrt1_2)];
  final List<HandleSpot> placed = <HandleSpot>[];
  for (int axis = 0; axis < ends.length; axis++) {
    final (double, double)? end = ends[axis];
    if (end == null) continue;
    double dx = end.$1 - originX, dy = end.$2 - originY;
    final double length = math.sqrt(dx * dx + dy * dy);
    if (length < 1e-6) {
      (dx, dy) = fallback[axis % fallback.length];
    } else {
      dx /= length;
      dy /= length;
    }
    final double arm = math.max(length, minArm);
    bool clear((double, double) at) => placed.every((HandleSpot other) {
          final double ox = other.x - at.$1, oy = other.y - at.$2;
          return ox * ox + oy * oy >= gap * gap;
        });
    (double, double) at = reach(dx, dy, arm);
    // Turn the arm 30 degrees at a time, alternating sides, until it clears.
    for (int turn = 1; !clear(at) && turn <= 11; turn++) {
      final double angle = (turn + 1) ~/ 2 * (turn.isOdd ? 1 : -1) * math.pi / 6;
      final double cos = math.cos(angle), sin = math.sin(angle);
      at = reach(dx * cos - dy * sin, dx * sin + dy * cos, arm);
    }
    placed.add((axis: axis, x: at.$1, y: at.$2));
  }
  return placed;
}
