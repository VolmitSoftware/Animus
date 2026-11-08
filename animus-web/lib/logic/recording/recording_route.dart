/// A recording made into a reusable route: points along the recorded path,
/// each leg walked in the movement style the recorder used on it, so a
/// sneaked stretch stays sneaked and a swum one swum.
library;

import 'dart:math' as math;

import '../gui/gui_json.dart';
import '../npc/route_model.dart';
import 'recording_model.dart';

/// The longest straight stretch between two route points, in blocks.
const double recordingRouteSpacing = 8;

/// The movement style a frame's flags show: swimming, gliding, sneaking and
/// sprinting in that order of precedence, else walk.
String recordingFrameStyle(int flags) {
  if (flags & 8 != 0) return 'swim';
  if (flags & 16 != 0) return 'glide';
  if (flags & 2 != 0) return 'sneak';
  if (flags & 4 != 0) return 'sprint';
  return 'walk';
}

/// A route document following [recording]: a point where the recorder's
/// style changed, at least every [recordingRouteSpacing] blocks, and at the
/// end. Each point carries the style of the leg that ends there, walk left
/// out. The points stand on whole-block heights and the route waits nowhere.
/// Null when the recording has fewer than two frames.
JsonMap? recordingRoute(Recording recording) {
  final List<RecordingFrame> frames = recording.frames;
  if (frames.length < 2) return null;
  double spacing = recordingRouteSpacing;
  List<JsonMap> points = _points(recording, spacing);
  while (points.length > routeMaxPoints) {
    spacing *= 2;
    points = _points(recording, spacing);
  }
  return <String, Object?>{'schemaVersion': 1, 'waitTicks': 0, 'points': points};
}

List<JsonMap> _points(Recording recording, double spacing) {
  final List<RecordingFrame> frames = recording.frames;
  final RecordingOrigin origin = recording.origin;
  JsonMap point(RecordingFrame frame, String style) => <String, Object?>{
        'x': _round(origin.x + frame.dx),
        'y': (origin.y + frame.dy + 0.000001).floorToDouble().toInt(),
        'z': _round(origin.z + frame.dz),
        if (style != 'walk') 'style': style,
      };
  final List<JsonMap> points = <JsonMap>[point(frames.first, 'walk')..remove('style')];
  RecordingFrame last = frames.first;
  String style = recordingFrameStyle(frames.first.flags);
  for (int index = 1; index < frames.length; index++) {
    final RecordingFrame frame = frames[index];
    final String now = recordingFrameStyle(frame.flags);
    final bool far = math.sqrt(math.pow(frame.dx - last.dx, 2) + math.pow(frame.dz - last.dz, 2)) >= spacing;
    if (now != style || far || index == frames.length - 1) {
      final bool moved = (frame.dx - last.dx).abs() > 0.000001 || (frame.dy - last.dy).abs() > 0.000001 || (frame.dz - last.dz).abs() > 0.000001;
      if (moved) {
        points.add(point(frame, style));
        last = frame;
      }
      style = now;
    }
  }
  return points;
}

/// Two decimals keep a point within a centimetre of the frame.
num _round(double value) {
  final double rounded = (value * 100).roundToDouble() / 100;
  return rounded == rounded.roundToDouble() ? rounded.toInt() : rounded;
}
