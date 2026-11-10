/// Performance recordings (`recordings/<id>.json`), read for display: the
/// frames as poses in world space, the events, and a summary. The Java
/// `RecordingDocument` defines the format and stays authoritative, so this reader is lenient and skips what it cannot
/// read instead of refusing the file.
library;

import 'dart:convert';
import 'dart:math' as math;

import '../gui/gui_json.dart';

/// Server ticks per second.
const int recordingTicksPerSecond = 20;

/// The `flags` bits of a frame, in bit order.
const List<(int, String)> recordingFlags = <(int, String)>[
  (1, 'on ground'),
  (2, 'sneaking'),
  (4, 'sprinting'),
  (8, 'swimming'),
  (16, 'gliding'),
  (32, 'jumping'),
];

/// The names of the bits set in [flags].
List<String> recordingFlagNames(int flags) => <String>[
      for (final (int bit, String name) in recordingFlags)
        if (flags & bit != 0) name,
    ];

/// Where the recording started.
class RecordingOrigin {
  const RecordingOrigin(this.x, this.y, this.z, this.yaw, this.pitch);

  final double x;
  final double y;
  final double z;
  final double yaw;
  final double pitch;
}

/// One frame: `[tick, dx, dy, dz, yaw, pitch, flags]`, position relative to
/// the origin.
class RecordingFrame {
  const RecordingFrame(this.tick, this.dx, this.dy, this.dz, this.yaw, this.pitch, this.flags);

  final int tick;
  final double dx;
  final double dy;
  final double dz;
  final double yaw;
  final double pitch;
  final int flags;

  static RecordingFrame? fromJson(Object? value) {
    final List<Object?> list = asList(value);
    if (list.length != 7 || list.any((Object? item) => item is! num)) return null;
    final int? tick = asInt(list[0]);
    final int? flags = asInt(list[6]);
    if (tick == null || flags == null) return null;
    double at(int index) => (list[index]! as num).toDouble();
    return RecordingFrame(tick, at(1), at(2), at(3), at(4), at(5), flags);
  }
}

/// One event: `swing`, `equipment` or `use`, with its fields.
class RecordingEvent {
  const RecordingEvent(this.tick, this.type, this.json);

  final int tick;
  final String type;
  final JsonMap json;

  String? get slot => asString(json['slot']);

  /// The equipment item id, or null for an emptied slot.
  String? get item => asString(asMap(json['item'])['id']);
  String? get block => asString(json['block']);

  /// A one-line description for lists.
  String describe() => switch (type) {
        'swing' => 'Arm swing',
        'equipment' => item == null ? 'Emptied ${slot ?? 'a slot'}' : '${slot ?? 'slot'}: $item',
        'use' => 'Used ${block ?? 'a block'} at ${json['x']}, ${json['y']}, ${json['z']}',
        _ => 'Unknown event type $type',
      };
}

/// A pose in world space at one tick.
class RecordingPose {
  const RecordingPose(this.x, this.y, this.z, this.yaw, this.pitch, this.flags);

  final double x;
  final double y;
  final double z;
  final double yaw;
  final double pitch;
  final int flags;
}

class Recording {
  const Recording({
    required this.world,
    required this.recordedBy,
    required this.origin,
    required this.sampleTicks,
    required this.durationTicks,
    required this.revision,
    required this.frames,
    required this.events,
  });

  final String? world;
  final String? recordedBy;
  final RecordingOrigin origin;
  final int? sampleTicks;
  final int durationTicks;
  final int? revision;

  /// Readable frames in file order; unreadable ones are left out.
  final List<RecordingFrame> frames;
  final List<RecordingEvent> events;

  /// Null when [json] is not an object.
  static Recording? parse(Object? json) {
    if (json is! Map<String, Object?>) return null;
    final JsonMap origin = asMap(json['origin']);
    final List<RecordingFrame> frames = <RecordingFrame>[
      for (final Object? frame in asList(json['frames']))
        if (RecordingFrame.fromJson(frame) case final RecordingFrame read) read,
    ];
    final List<RecordingEvent> events = <RecordingEvent>[
      for (final Object? event in asList(json['events']))
        if (asInt(asMap(event)['tick']) case final int tick) RecordingEvent(tick, asString(asMap(event)['type']) ?? 'unknown', asMap(event)),
    ];
    return Recording(
      world: asString(json['world']),
      recordedBy: asString(json['recordedBy']),
      origin: RecordingOrigin(
        asDouble(origin['x']) ?? 0,
        asDouble(origin['y']) ?? 0,
        asDouble(origin['z']) ?? 0,
        asDouble(origin['yaw']) ?? 0,
        asDouble(origin['pitch']) ?? 0,
      ),
      sampleTicks: asInt(json['sampleTicks']),
      durationTicks: asInt(json['durationTicks']) ?? (frames.isEmpty ? 0 : frames.last.tick),
      revision: asInt(json['revision']),
      frames: frames,
      events: events,
    );
  }

  RecordingPose _pose(RecordingFrame frame) =>
      RecordingPose(origin.x + frame.dx, origin.y + frame.dy, origin.z + frame.dz, frame.yaw, frame.pitch, frame.flags);

  /// The pose at [tick], interpolated between the frames around it as
  /// playback does: position and pitch linearly, yaw the short way round,
  /// flags from the earlier frame. Clamped to the first and last frame.
  RecordingPose poseAt(num tick) {
    if (frames.isEmpty) return RecordingPose(origin.x, origin.y, origin.z, origin.yaw, origin.pitch, 0);
    if (tick <= frames.first.tick) return _pose(frames.first);
    if (tick >= frames.last.tick) return _pose(frames.last);
    int index = 0;
    while (index < frames.length - 2 && frames[index + 1].tick <= tick) {
      index++;
    }
    final RecordingFrame a = frames[index], b = frames[index + 1];
    final int span = b.tick - a.tick;
    final double u = span <= 0 ? 0 : (tick - a.tick) / span;
    double lerp(double from, double to) => from + (to - from) * u;
    double turn = (b.yaw - a.yaw) % 360;
    if (turn > 180) turn -= 360;
    return RecordingPose(
      origin.x + lerp(a.dx, b.dx),
      origin.y + lerp(a.dy, b.dy),
      origin.z + lerp(a.dz, b.dz),
      a.yaw + turn * u,
      lerp(a.pitch, b.pitch),
      a.flags,
    );
  }

  /// Frames whose flags differ from the frame before them.
  List<RecordingFrame> flagChanges() => <RecordingFrame>[
        for (int index = 1; index < frames.length; index++)
          if (frames[index].flags != frames[index - 1].flags) frames[index],
      ];

  /// The x and z extent of every frame in world space: min x, min z, max x,
  /// max z. The origin alone when there are no frames.
  (double, double, double, double) bounds() {
    if (frames.isEmpty) return (origin.x, origin.z, origin.x, origin.z);
    double minX = double.infinity, minZ = double.infinity, maxX = -double.infinity, maxZ = -double.infinity;
    for (final RecordingFrame frame in frames) {
      final double x = origin.x + frame.dx, z = origin.z + frame.dz;
      minX = math.min(minX, x);
      maxX = math.max(maxX, x);
      minZ = math.min(minZ, z);
      maxZ = math.max(maxZ, z);
    }
    return (minX, minZ, maxX, maxZ);
  }
}

/// What the list and the facts show for one recording.
class RecordingSummary {
  const RecordingSummary({
    required this.durationTicks,
    required this.frames,
    required this.events,
    required this.eventsByType,
    required this.bytes,
    required this.world,
  });

  factory RecordingSummary.of(Recording recording, String source) {
    final Map<String, int> byType = <String, int>{};
    for (final RecordingEvent event in recording.events) {
      byType[event.type] = (byType[event.type] ?? 0) + 1;
    }
    final List<String> types = byType.keys.toList()..sort();
    return RecordingSummary(
      durationTicks: recording.durationTicks,
      frames: recording.frames.length,
      events: recording.events.length,
      eventsByType: <String, int>{for (final String type in types) type: byType[type]!},
      bytes: utf8.encode(source).length,
      world: recording.world,
    );
  }

  final int durationTicks;
  final int frames;
  final int events;

  /// Event counts by type, sorted by type.
  final Map<String, int> eventsByType;

  /// The file's size in UTF-8 bytes.
  final int bytes;
  final String? world;

  double get seconds => durationTicks / recordingTicksPerSecond;
}

/// `80 ticks (4 s)`, `1500 ticks (1 min 15 s)`.
String formatRecordingLength(int ticks) {
  final double seconds = ticks / recordingTicksPerSecond;
  String clock(double value) => value == value.roundToDouble() ? '${value.toInt()}' : value.toStringAsFixed(1);
  if (seconds < 60) return '$ticks ticks (${clock(seconds)} s)';
  final int minutes = seconds ~/ 60;
  final double rest = seconds - minutes * 60;
  return '$ticks ticks ($minutes min${rest == 0 ? '' : ' ${clock(rest)} s'})';
}

/// `900 B`, `2.0 KiB`, `1.5 MiB`.
String formatRecordingSize(int bytes) {
  if (bytes < 1024) return '$bytes B';
  if (bytes < 1024 * 1024) return '${(bytes / 1024).toStringAsFixed(1)} KiB';
  return '${(bytes / (1024 * 1024)).toStringAsFixed(1)} MiB';
}
