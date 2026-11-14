/// A recording in words people read: times in seconds, facts with plain
/// labels (the engine's sample ticks, flag changes, revision and origin
/// only in Advanced, renamed), events as short sentences.
library;

import '../../model/animus_document.dart';
import '../diagnostic_text.dart';
import '../item_ids.dart';
import 'recording_edit.dart';
import 'recording_model.dart';

/// The playback modes by their stored value.
const Map<String, String> playbackLabels = <String, String>{'once': 'Once', 'loop': 'Loop', 'pingpong': 'Back and forth'};

/// The problems inside each event, by the event's place in `events`, said
/// for that event alone ("Item is required"): the list beside the drawing
/// shows them at the event's time, where the page's problem line only
/// counts them.
Map<int, List<String>> eventProblems(List<Diagnostic> diagnostics) {
  final Map<int, List<String>> out = <int, List<String>>{};
  for (final Diagnostic diagnostic in diagnostics) {
    final RegExpMatch? match = _eventPath.firstMatch(diagnostic.path);
    if (match == null) continue;
    final List<String> lines = out[int.parse(match[1]!)] ??= <String>[];
    final String line = diagnosticSentence(diagnostic);
    if (!lines.contains(line)) lines.add(line);
  }
  return out;
}

final RegExp _eventPath = RegExp(r'^\$\.events\[(\d+)\]');

/// One decimal of seconds, `2.1 s`; from a minute on `1:05.3`.
String clockText(double ticks) {
  final double seconds = ticks / recordingTicksPerSecond;
  if (seconds < 60) return '${seconds.toStringAsFixed(1)} s';
  final int minutes = seconds ~/ 60;
  final String rest = (seconds - minutes * 60).toStringAsFixed(1).padLeft(4, '0');
  return '$minutes:$rest';
}

/// Where playback stands: `2.1 s of 4.0 s`.
String playbackStatus(double tick, int durationTicks) => '${clockText(tick)} of ${clockText(durationTicks.toDouble())}';

String _number(double value) => value == value.roundToDouble() ? '${value.toInt()}' : '$value';

/// A length: `4 s`, `1.5 s`, `1 min 15 s`.
String lengthText(int ticks) {
  final double seconds = ticks / recordingTicksPerSecond;
  if (seconds < 60) return '${_number(seconds)} s';
  final int minutes = seconds ~/ 60;
  final double rest = seconds - minutes * 60;
  return rest == 0 ? '$minutes min' : '$minutes min ${_number(double.parse(rest.toStringAsFixed(1)))} s';
}

/// One line of the facts list.
class RecordingFact {
  const RecordingFact(this.label, this.value, {this.advanced = false, this.tip});

  final String label;
  final String value;

  /// Shown in Advanced only.
  final bool advanced;
  final String? tip;
}

String _count(int count, String one, String many) => '$count ${count == 1 ? one : many}';

/// `2 arm swings, 1 item change`, in a fixed order.
String eventCounts(Map<String, int> byType) {
  final List<String> parts = <String>[
    if (byType['swing'] case final int swings) _count(swings, 'arm swing', 'arm swings'),
    if (byType['equipment'] case final int changes) _count(changes, 'item change', 'item changes'),
    if (byType['use'] case final int uses) _count(uses, 'block use', 'block uses'),
  ];
  final int other = byType.entries.where((MapEntry<String, int> entry) => !const <String>{'swing', 'equipment', 'use'}.contains(entry.key)).fold(0, (int sum, MapEntry<String, int> entry) => sum + entry.value);
  if (other > 0) parts.add(_count(other, 'other', 'others'));
  return parts.isEmpty ? 'None' : parts.join(', ');
}

/// The facts of [recording], Simple ones first.
List<RecordingFact> recordingFacts(Recording recording, RecordingSummary summary) {
  final RecordingOrigin origin = recording.origin;
  final List<RecordingFrame> changes = recording.flagChanges();
  return <RecordingFact>[
    RecordingFact('Length', lengthText(summary.durationTicks)),
    RecordingFact('Events', eventCounts(summary.eventsByType)),
    RecordingFact('World', recording.world ?? 'Not set'),
    RecordingFact('Frames', '${summary.frames}', advanced: true),
    RecordingFact('Frame gap', recording.sampleTicks == null ? 'Not set' : lengthText(recording.sampleTicks!), advanced: true, tip: 'Time between two saved frames. Playback moves smoothly between them.'),
    RecordingFact(
      'Starts at',
      '${_number(origin.x)}, ${_number(origin.y)}, ${_number(origin.z)}, facing ${_number(origin.yaw)}',
      advanced: true,
      tip: 'Where the recording began. Every frame is stored as an offset from here.',
    ),
    RecordingFact(
      'Pose changes',
      changes.isEmpty ? 'None' : '${changes.length}, at ${changes.take(12).map((RecordingFrame frame) => lengthText(frame.tick)).join(', ')}${changes.length > 12 ? ', ...' : ''}',
      advanced: true,
      tip: 'Moments the recorder started or stopped sneaking, sprinting, swimming, gliding or jumping.',
    ),
    RecordingFact('Recorded by', recording.recordedBy ?? 'Unknown', advanced: true),
    RecordingFact('Size', formatRecordingSize(summary.bytes), advanced: true),
    RecordingFact('Version', recording.revision == null ? 'Not set' : '${recording.revision}', advanced: true, tip: 'Goes up by one each time the file is saved.'),
  ];
}

const Map<String, String> _slots = <String, String>{
  'main_hand': 'Main hand',
  'off_hand': 'Off hand',
  'head': 'Head',
  'chest': 'Chest',
  'legs': 'Legs',
  'feet': 'Feet',
};

/// One event as a short sentence.
String eventText(RecordingEvent event) {
  final String slot = _slots[event.slot] ?? event.slot ?? 'A slot';
  return switch (event.type) {
    'swing' => 'Arm swing',
    'equipment' => event.item == null ? '$slot emptied' : '$slot: ${itemName(event.item!)}',
    'use' => 'Used ${event.block == null ? 'a block' : itemName(event.block!).toLowerCase()} at ${event.json['x']}, ${event.json['y']}, ${event.json['z']}',
    _ => 'Unknown event',
  };
}

/// [text] as tags: split at commas and spaces, lower case, each once.
List<String> parseRecordingTags(String text) => <String>{
      for (final String part in text.split(RegExp(r'[,\s]+')))
        if (part.trim().isNotEmpty) part.trim().toLowerCase(),
    }.toList();

/// Why [tags] cannot be stored, or null.
String? recordingTagsProblem(List<String> tags) {
  if (tags.length > recordingTagLimit) return 'Use at most $recordingTagLimit tags.';
  for (final String tag in tags) {
    if (!recordingTagPattern.hasMatch(tag)) return '"$tag": use a-z, 0-9, _ and -, up to 32 characters.';
  }
  return null;
}
