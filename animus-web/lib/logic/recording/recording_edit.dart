/// Edits a recording allows: its details (description, tags, playback,
/// speed), trimming the start and end, and checking a Code draft against
/// the rules the Java `RecordingDocument` enforces, so an invalid draft is
/// refused with the server's own reason before it is applied.
library;

import '../../model/animus_document.dart';
import '../../model/document_kind.dart';
import '../gui/gui_json.dart';

const int recordingDescriptionLimit = 256;
const int recordingTagLimit = 32;
const double recordingMinimumSpeed = 0.25;
const double recordingMaximumSpeed = 4;
const List<String> recordingPlaybacks = <String>['once', 'loop', 'pingpong'];
final RegExp recordingTagPattern = RegExp(r'^[a-z0-9][a-z0-9_-]{0,31}$');
final RegExp recordingIdPattern = RegExp(r'^[a-z0-9][a-z0-9_-]{0,63}$');

const int _maximumDuration = 72000;
const int _maximumSampleTicks = 20;
const int _maximumEvents = 4096;
const double _maximumCoordinate = 30000000;
const List<String> _slots = <String>['main_hand', 'off_hand', 'head', 'chest', 'legs', 'feet'];
const List<String> _detailKeys = <String>['description', 'tags', 'playback', 'speed'];
const Set<String> _fields = <String>{'schemaVersion', 'revision', 'id', 'recordedBy', 'world', 'origin', 'sampleTicks', 'durationTicks', ..._detailKeys, 'frames', 'events'};
final RegExp _uuid = RegExp(r'^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$');
final RegExp _namespaced = RegExp(r'^[a-z0-9_.-]+:[a-z0-9_./-]+$');

/// The details an owner sets on a recording. An empty description, no tags,
/// no playback and speed 1 are not stored.
class RecordingDetails {
  const RecordingDetails({this.description = '', this.tags = const <String>[], this.playback, this.speed = 1});

  /// The details in [json], leniently: values of the wrong type read as unset.
  factory RecordingDetails.read(JsonMap json) => RecordingDetails(
    description: asString(json['description']) ?? '',
    tags: <String>[
      for (final Object? tag in asList(json['tags']))
        if (tag is String) tag,
    ],
    playback: recordingPlaybacks.contains(json['playback']) ? json['playback']! as String : null,
    speed: asDouble(json['speed']) ?? 1,
  );

  final String description;
  final List<String> tags;

  /// once, loop or pingpong; null leaves it to the NPC or action, else loop.
  final String? playback;

  /// Recorded ticks per game tick.
  final double speed;

  RecordingDetails copyWith({String? description, List<String>? tags, String? Function()? playback, double? speed}) =>
      RecordingDetails(description: description ?? this.description, tags: tags ?? this.tags, playback: playback == null ? this.playback : playback(), speed: speed ?? this.speed);

  /// [json] with these details, stored after `durationTicks` and before
  /// `frames` as the server writes them; defaults remove their keys.
  JsonMap writeTo(JsonMap json) {
    final JsonMap stored = <String, Object?>{
      if (description.isNotEmpty) 'description': description,
      if (tags.isNotEmpty) 'tags': <Object?>[...tags],
      'playback': ?playback,
      if (speed != 1) 'speed': _tidy(speed),
    };
    final JsonMap out = <String, Object?>{};
    bool placed = false;
    for (final MapEntry<String, Object?> entry in json.entries) {
      if (_detailKeys.contains(entry.key)) continue;
      if (entry.key == 'frames' && !placed) {
        out.addAll(stored);
        placed = true;
      }
      out[entry.key] = entry.value;
    }
    if (!placed) out.addAll(stored);
    return out;
  }

  @override
  bool operator ==(Object other) =>
      other is RecordingDetails &&
      other.description == description &&
      other.playback == playback &&
      other.speed == speed &&
      other.tags.length == tags.length &&
      Iterable<int>.generate(tags.length).every((int index) => other.tags[index] == tags[index]);

  @override
  int get hashCode => Object.hash(description, playback, speed, Object.hashAll(tags));

  @override
  String toString() => 'RecordingDetails($description, $tags, $playback, $speed)';
}

/// Why [details] cannot be stored, or null.
String? recordingDetailsProblem(RecordingDetails details) {
  final List<String> problems = <String>[];
  _checkDetails(details.writeTo(<String, Object?>{}), problems);
  return problems.isEmpty ? null : problems.first;
}

/// Every tag the workspace's recordings use, sorted.
List<String> recordingTags(List<AnimusDocument> documents) => <String>{
  for (final AnimusDocument document in documents)
    if (document.kind == DocumentKind.recording) ...RecordingDetails.read(asMap(document.json)).tags,
}.toList()..sort();

/// The ticks of the readable frames, in order.
List<int> recordingFrameTicks(JsonMap json) => <int>[
  for (final Object? frame in asList(json['frames']))
    if (asInt(asList(frame).firstOrNull) case final int tick) tick,
];

/// The frame tick of [ticks] nearest [tick]; the earlier one on a tie.
int snapRecordingTick(List<int> ticks, num tick) {
  int best = ticks.first;
  for (final int candidate in ticks) {
    if ((candidate - tick).abs() < (best - tick).abs()) best = candidate;
  }
  return best;
}

/// A trimmed recording, or why the range cannot be kept.
class RecordingTrim {
  const RecordingTrim.kept(JsonMap this.json) : problem = null;
  const RecordingTrim.refused(String this.problem) : json = null;

  final JsonMap? json;
  final String? problem;
}

/// [json] cut to the frames from [fromTick] to [toTick], both frame ticks:
/// the first kept frame becomes tick 0 and the origin, the last sets
/// `durationTicks`, offsets are rebased so every position stays where it
/// was, events outside the range go, and the equipment each slot wore at
/// [fromTick] is set again at tick 0 so the NPC starts dressed the same.
RecordingTrim trimRecording(JsonMap json, int fromTick, int toTick) {
  final List<String> before = recordingProblems(json);
  if (before.isNotEmpty) return RecordingTrim.refused('Fix the recording first: ${before.first}');
  if (fromTick >= toTick) return const RecordingTrim.refused('Keep at least two frames: the start must be before the end.');
  final List<Object?> frames = asList(json['frames']);
  final List<int> ticks = recordingFrameTicks(json);
  if (!ticks.contains(fromTick) || !ticks.contains(toTick)) return const RecordingTrim.refused('Start and end must each be on a frame.');
  if (fromTick == 0 && toTick == ticks.last) return RecordingTrim.kept(json);
  final List<Object?> first = asList(frames[ticks.indexOf(fromTick)]);
  final double fx = asDouble(first[1])!, fy = asDouble(first[2])!, fz = asDouble(first[3])!;
  final JsonMap origin = asMap(json['origin']);
  final List<Object?> kept = <Object?>[
    for (final Object? frame in frames)
      if (asInt(asList(frame)[0])! case final int tick when tick >= fromTick && tick <= toTick)
        <Object?>[
          tick - fromTick,
          _offset(asDouble(asList(frame)[1])! - fx),
          _offset(asDouble(asList(frame)[2])! - fy),
          _offset(asDouble(asList(frame)[3])! - fz),
          asList(frame)[4],
          asList(frame)[5],
          asList(frame)[6],
        ],
  ];
  final List<JsonMap> events = <JsonMap>[for (final Object? event in asList(json['events'])) asMap(event)];
  final Map<String, JsonMap> worn = <String, JsonMap>{};
  final Set<String> setAtStart = <String>{};
  for (final JsonMap event in events) {
    if (event['type'] != 'equipment') continue;
    final int tick = asInt(event['tick'])!;
    if (tick < fromTick) worn[event['slot']! as String] = event;
    if (tick == fromTick) setAtStart.add(event['slot']! as String);
  }
  final List<JsonMap> carried = <JsonMap>[
    for (final String slot in _slots)
      if (worn[slot] case final JsonMap event when !setAtStart.contains(slot)) <String, Object?>{...event, 'tick': 0},
  ];
  final List<JsonMap> moved = <JsonMap>[
    for (final JsonMap event in events)
      if (asInt(event['tick'])! case final int tick when tick >= fromTick && tick <= toTick) <String, Object?>{...event, 'tick': tick - fromTick},
  ];
  final List<JsonMap> ordered = <JsonMap>[...carried, ...moved];
  // A stable sort by tick then type, the order RecordingEvent.ORDER checks.
  final List<int> order = List<int>.generate(ordered.length, (int index) => index)
    ..sort((int a, int b) {
      final int byTick = asInt(ordered[a]['tick'])!.compareTo(asInt(ordered[b]['tick'])!);
      if (byTick != 0) return byTick;
      final int byType = (ordered[a]['type']! as String).compareTo(ordered[b]['type']! as String);
      return byType != 0 ? byType : a.compareTo(b);
    });
  if (ordered.length > _maximumEvents) return const RecordingTrim.refused('The kept range would hold more than 4096 events.');
  final JsonMap trimmed = <String, Object?>{
    for (final MapEntry<String, Object?> entry in json.entries)
      entry.key: switch (entry.key) {
        'origin' => <String, Object?>{
          ...origin,
          'x': _position(asDouble(origin['x'])! + fx),
          'y': _position(asDouble(origin['y'])! + fy),
          'z': _position(asDouble(origin['z'])! + fz),
          'yaw': first[4],
          'pitch': first[5],
        },
        'durationTicks' => toTick - fromTick,
        'frames' => kept,
        'events' => <Object?>[for (final int index in order) ordered[index]],
        _ => entry.value,
      },
  };
  final List<String> after = recordingProblems(trimmed);
  if (after.isNotEmpty) return RecordingTrim.refused('That range breaks a recording rule: ${after.first}');
  return RecordingTrim.kept(trimmed);
}

/// Every rule `RecordingDocument` checks, as `$.path: message` lines in the
/// server's words; empty when [json] is a valid recording. With [id], the
/// recording's id must be the document's.
List<String> recordingProblems(Object? json, {String? id}) {
  if (json is! Map<String, Object?>) return <String>[r'$: expected a JSON object'];
  final List<String> errors = <String>[];
  for (final String key in json.keys) {
    if (!_fields.contains(key)) errors.add('\$.$key: unknown field');
  }
  _integer(json['schemaVersion'], r'$.schemaVersion', 1, 1, errors);
  // The Java bound is Long.MAX_VALUE; JavaScript numbers stop at 2^53 - 1.
  _integer(json['revision'], r'$.revision', 1, 9007199254740991, errors, expected: 'integer 1..9223372036854775807');
  final Object? recordingId = json['id'];
  if (recordingId is! String || recordingId.trim().isEmpty) {
    errors.add(r'$.id: expected nonblank string');
  } else if (!recordingIdPattern.hasMatch(recordingId)) {
    errors.add(r'$.id: expected 1-64 lowercase letters, digits, _ or -');
  } else if (id != null && recordingId != id) {
    errors.add('\$.id: the recording id is $recordingId but the document is $id; use Rename to move it');
  }
  final Object? recorder = json['recordedBy'];
  if (recorder is! String || !_uuid.hasMatch(recorder)) errors.add(r'$.recordedBy: expected canonical player UUID');
  final Object? world = json['world'];
  if (world is! String || world.trim().isEmpty) errors.add(r'$.world: expected nonblank string');
  _checkOrigin(json['origin'], errors);
  final int? sampleTicks = _integer(json['sampleTicks'], r'$.sampleTicks', 1, _maximumSampleTicks, errors);
  final int? durationTicks = _integer(json['durationTicks'], r'$.durationTicks', 1, _maximumDuration, errors);
  final List<int>? ticks = _checkFrames(json['frames'], sampleTicks, durationTicks, errors);
  final List<(int, String)>? events = _checkEvents(json['events'], durationTicks ?? _maximumDuration, errors);
  _checkDetails(json, errors);
  if (errors.isNotEmpty || ticks == null || events == null || sampleTicks == null || durationTicks == null) return errors;
  final int maximum = durationTicks ~/ sampleTicks + 1;
  if (ticks.length < 2) {
    errors.add(r'$.frames: expected at least 2 frames');
  } else if (ticks.length > maximum) {
    errors.add('\$.frames: expected at most $maximum frames for $durationTicks ticks sampled every $sampleTicks');
  } else {
    if (ticks.first != 0) errors.add(r'$.frames[0][0]: expected the first frame at tick 0');
    for (int index = 1; index < ticks.length; index++) {
      if (ticks[index] <= ticks[index - 1]) errors.add("\$.frames[$index][0]: expected a tick above the previous frame's ${ticks[index - 1]}");
    }
    if (ticks.last != durationTicks) errors.add('\$.frames[${ticks.length - 1}][0]: expected the last frame at durationTicks $durationTicks');
  }
  for (int index = 1; index < events.length; index++) {
    final (int tick, String type) = events[index];
    final (int previousTick, String previousType) = events[index - 1];
    if (previousTick > tick || (previousTick == tick && previousType.compareTo(type) > 0)) {
      errors.add('\$.events[$index]: expected events sorted by tick then type');
    }
  }
  return errors;
}

void _checkDetails(JsonMap json, List<String> errors) {
  if (json.containsKey('description')) {
    final Object? text = json['description'];
    if (text is! String || text.length > recordingDescriptionLimit) {
      errors.add('\$.description: expected text of at most $recordingDescriptionLimit characters');
    }
  }
  if (json.containsKey('tags')) {
    final Object? tags = json['tags'];
    if (tags is! List<Object?>) {
      errors.add(r'$.tags: expected array');
    } else if (tags.length > recordingTagLimit) {
      errors.add('\$.tags: expected at most $recordingTagLimit tags');
    } else {
      for (int index = 0; index < tags.length; index++) {
        final Object? tag = tags[index];
        if (tag is! String || !recordingTagPattern.hasMatch(tag)) {
          errors.add('\$.tags[$index]: expected 1-32 lowercase letters, digits, _ or -');
        } else if (tags.sublist(0, index).contains(tag)) {
          errors.add('\$.tags[$index]: duplicate tag $tag');
        }
      }
    }
  }
  if (json.containsKey('playback') && !recordingPlaybacks.contains(json['playback'])) errors.add(r'$.playback: expected once, loop or pingpong');
  if (json.containsKey('speed')) {
    final double? speed = asDouble(json['speed']);
    if (speed == null || !speed.isFinite || speed < recordingMinimumSpeed || speed > recordingMaximumSpeed) errors.add(r'$.speed: expected a number 0.25..4');
  }
}

void _checkOrigin(Object? value, List<String> errors) {
  if (value is! Map<String, Object?>) {
    errors.add(r'$.origin: expected object');
    return;
  }
  for (final String key in value.keys) {
    if (!const <String>['x', 'y', 'z', 'yaw', 'pitch'].contains(key)) errors.add('\$.origin.$key: unknown field');
  }
  for (final String axis in const <String>['x', 'y', 'z']) {
    final double? number = asDouble(value[axis]);
    if (number == null || !number.isFinite || number.abs() > _maximumCoordinate) errors.add('\$.origin.$axis: expected finite coordinate within ±30000000');
  }
  final double? yaw = asDouble(value['yaw']), pitch = asDouble(value['pitch']);
  if (yaw == null || !(yaw >= -360 && yaw <= 360)) errors.add(r'$.origin.yaw: expected yaw -360..360');
  if (pitch == null || !(pitch >= -90 && pitch <= 90)) errors.add(r'$.origin.pitch: expected pitch -90..90');
}

/// The frame ticks, or null when the frames could not be read.
List<int>? _checkFrames(Object? value, int? sampleTicks, int? durationTicks, List<String> errors) {
  if (value is! List<Object?>) {
    errors.add(r'$.frames: expected array');
    return null;
  }
  if (sampleTicks != null && durationTicks != null && value.length > durationTicks ~/ sampleTicks + 1) {
    errors.add('\$.frames: expected at most ${durationTicks ~/ sampleTicks + 1} frames for $durationTicks ticks sampled every $sampleTicks');
    return null;
  }
  if (value.length > _maximumDuration + 1) {
    errors.add(r'$.frames: too many frames');
    return null;
  }
  final int start = errors.length;
  final List<int> ticks = <int>[];
  for (int index = 0; index < value.length; index++) {
    final String at = '\$.frames[$index]';
    final Object? frame = value[index];
    if (frame is! List<Object?> || frame.length != 7) {
      errors.add('$at: expected [tick, dx, dy, dz, yaw, pitch, flags]');
      continue;
    }
    final int before = errors.length;
    final int? tick = _integer(frame[0], '$at[0]', 0, _maximumDuration, errors);
    for (int part = 1; part <= 5; part++) {
      final double? number = asDouble(frame[part]);
      if (number == null || !number.isFinite) errors.add('$at[$part]: expected a number');
    }
    if (errors.length == before) {
      for (int part = 1; part <= 3; part++) {
        if (_offset(asDouble(frame[part])!).abs() > _maximumCoordinate) errors.add('$at[$part]: expected offset within ±30000000');
      }
      final double yaw = _angle(asDouble(frame[4])!), pitch = _angle(asDouble(frame[5])!);
      if (yaw < -360 || yaw > 360) errors.add('$at[4]: expected yaw -360..360');
      if (pitch < -90 || pitch > 90) errors.add('$at[5]: expected pitch -90..90');
    }
    _integer(frame[6], '$at[6]', 0, 63, errors, expected: 'flags 0..63');
    if (tick != null) ticks.add(tick);
  }
  return errors.length == start ? ticks : null;
}

/// Each event's tick and type, or null when the events could not be read.
List<(int, String)>? _checkEvents(Object? value, int durationTicks, List<String> errors) {
  if (value is! List<Object?>) {
    errors.add(r'$.events: expected array');
    return null;
  }
  if (value.length > _maximumEvents) {
    errors.add('\$.events: expected at most $_maximumEvents events');
    return null;
  }
  final int start = errors.length;
  final List<(int, String)> read = <(int, String)>[];
  for (int index = 0; index < value.length; index++) {
    final String at = '\$.events[$index]';
    final Object? event = value[index];
    if (event is! Map<String, Object?>) {
      errors.add('$at: expected object');
      continue;
    }
    final int? tick = _integer(event['tick'], '$at.tick', 0, durationTicks, errors);
    final Object? type = event['type'];
    final List<String>? fields = switch (type) {
      'swing' => const <String>['tick', 'type'],
      'equipment' => const <String>['tick', 'type', 'slot', 'item'],
      'use' => const <String>['tick', 'type', 'x', 'y', 'z', 'block'],
      _ => null,
    };
    if (fields == null) {
      errors.add('$at.type: expected swing, equipment or use');
      continue;
    }
    for (final String key in event.keys) {
      if (!fields.contains(key)) errors.add('$at.$key: unknown field');
    }
    if (type == 'equipment') {
      if (!_slots.contains(event['slot'])) errors.add('$at.slot: expected main_hand, off_hand, head, chest, legs or feet');
      _checkItem(event, at, errors);
    } else if (type == 'use') {
      for (final String axis in const <String>['x', 'y', 'z']) {
        _integer(event[axis], '$at.$axis', -30000000, 30000000, errors, expected: 'integer coordinate within ±30000000');
      }
      final Object? block = event['block'];
      if (block is! String || block.length > 128 || !_namespaced.hasMatch(block)) errors.add('$at.block: expected namespace:block');
    }
    if (tick != null) read.add((tick, type! as String));
  }
  return errors.length == start ? read : null;
}

void _checkItem(JsonMap event, String at, List<String> errors) {
  if (!event.containsKey('item') || (event['item'] != null && event['item'] is! Map<String, Object?>)) {
    errors.add('$at.item: expected item object or null');
    return;
  }
  final Object? value = event['item'];
  if (value is! Map<String, Object?>) return;
  if (value.containsKey('imported')) {
    errors.add('$at.item.imported: not allowed in a recording');
    return;
  }
  for (final String key in value.keys) {
    if (!const <String>['id', 'count', 'components'].contains(key)) errors.add('$at.item.$key: unknown field');
  }
  final Object? itemId = value['id'];
  if (itemId is! String || itemId.length > 128 || !_namespaced.hasMatch(itemId)) errors.add('$at.item.id: expected namespace:item');
  if (value.containsKey('count')) _integer(value['count'], '$at.item.count', 1, 99, errors);
  if (value.containsKey('components')) {
    final Object? text = value['components'];
    if (text is! String || text.length > 4096 || !text.startsWith('[') || !text.endsWith(']')) {
      errors.add('$at.item.components: expected [component=value,...] of at most 4096 characters');
    }
  }
}

int? _integer(Object? value, String at, int minimum, int maximum, List<String> errors, {String? expected}) {
  final int? number = asInt(value);
  if (number == null || number < minimum || number > maximum) {
    errors.add('$at: expected ${expected ?? 'integer $minimum..$maximum'}');
    return null;
  }
  return number;
}

/// An offset on the stored 0.01 grid, as `RecordingFrame.position` rounds it.
num _offset(double value) => _tidy((value * 100).round() / 100);

double _angle(double value) => (value * 2).round() / 2;

/// A world coordinate without the float noise an addition leaves.
num _position(double value) => _tidy(double.parse(value.toStringAsFixed(9)));

num _tidy(double value) => value == value.roundToDouble() && value.abs() < 1e15 ? value.toInt() : value;
