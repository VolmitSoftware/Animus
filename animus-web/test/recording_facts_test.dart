import 'package:animus_editor/logic/recording/recording_facts.dart';
import 'package:animus_editor/logic/recording/recording_model.dart';
import 'package:animus_editor/model/animus_document.dart';
import 'package:test/test.dart';

const Map<String, Object?> json = <String, Object?>{
  'schemaVersion': 1,
  'revision': 3,
  'id': 'market-loop',
  'recordedBy': '8667ba71-b85a-4004-af54-457a9734eed7',
  'world': 'world',
  'origin': <String, Object?>{'x': 100.5, 'y': 64, 'z': -20.5, 'yaw': 90, 'pitch': 0},
  'sampleTicks': 2,
  'durationTicks': 80,
  'frames': <Object?>[
    <Object?>[0, 0, 0, 0, 90, 0, 1],
    <Object?>[20, 1, 0, 0, 90, 0, 3],
    <Object?>[40, 2, 0, 0, 90, 0, 1],
    <Object?>[80, 3, 0, 0, 90, 0, 1],
  ],
  'events': <Object?>[
    <String, Object?>{'tick': 0, 'type': 'equipment', 'slot': 'main_hand', 'item': <String, Object?>{'id': 'minecraft:iron_sword'}},
    <String, Object?>{'tick': 20, 'type': 'swing'},
    <String, Object?>{'tick': 40, 'type': 'swing'},
    <String, Object?>{'tick': 60, 'type': 'equipment', 'slot': 'off_hand'},
  ],
};

void main() {
  test('the playback line reads in seconds', () {
    expect(playbackStatus(42, 80), '2.1 s of 4.0 s');
    expect(playbackStatus(0, 80), '0.0 s of 4.0 s');
    expect(playbackStatus(1306, 2400), '1:05.3 of 2:00.0');
  });

  test('lengths read in seconds and minutes', () {
    expect(lengthText(80), '4 s');
    expect(lengthText(30), '1.5 s');
    expect(lengthText(1500), '1 min 15 s');
    expect(lengthText(1200), '1 min');
  });

  test('Simple facts carry no jargon; the rest wait for Advanced', () {
    final Recording recording = Recording.parse(json)!;
    final List<RecordingFact> facts = recordingFacts(recording, RecordingSummary.of(recording, '$json'));
    final List<String> simple = <String>[for (final RecordingFact fact in facts) if (!fact.advanced) fact.label];
    expect(simple, <String>['Length', 'Events', 'World']);
    final Map<String, String> all = <String, String>{for (final RecordingFact fact in facts) fact.label: fact.value};
    expect(all['Length'], '4 s');
    expect(all['Events'], '2 arm swings, 2 item changes');
    expect(all['Frame gap'], '0.1 s');
    expect(all['Starts at'], '100.5, 64, -20.5, facing 90');
    expect(all['Version'], '3');
    expect(all['Pose changes'], '2, at 1 s, 2 s');
    for (final String jargon in <String>['Sample ticks', 'Flag changes', 'Revision', 'Origin']) {
      expect(all.containsKey(jargon), isFalse, reason: jargon);
    }
    expect(all.values.any((String value) => value.contains('tick')), isFalse);
  });

  test('events read as plain words', () {
    final Recording recording = Recording.parse(json)!;
    expect(<String>[for (final RecordingEvent event in recording.events) eventText(event)], <String>[
      'Main hand: Iron sword',
      'Arm swing',
      'Arm swing',
      'Off hand emptied',
    ]);
  });

  test('a modded item is named as the item pickers name it', () {
    const RecordingEvent event = RecordingEvent(0, 'equipment', <String, Object?>{'slot': 'main_hand', 'item': <String, Object?>{'id': 'mymod:ruby_pick'}});
    expect(eventText(event), 'Main hand: Ruby pick');
  });

  test('tags are typed as one comma list', () {
    expect(parseRecordingTags('Market, night  market,,day'), <String>['market', 'night', 'day']);
    expect(parseRecordingTags(''), isEmpty);
    expect(recordingTagsProblem(<String>['market', 'night']), isNull);
    expect(recordingTagsProblem(<String>['ok', 'no!']), contains('"no!"'));
    expect(recordingTagsProblem(<String>[for (int index = 0; index < 33; index++) 'tag-$index']), 'Use at most 32 tags.');
  });

  test('playback modes have plain names', () {
    expect(playbackLabels, <String, String>{'once': 'Once', 'loop': 'Loop', 'pingpong': 'Back and forth'});
  });

  test('each event keeps its own problems, said for that event', () {
    Diagnostic error(String path, String message) => Diagnostic(DiagnosticSeverity.error, path, message);
    final Map<int, List<String>> problems = eventProblems(<Diagnostic>[
      error(r'$.events[3].item', 'is required'),
      error(r'$.events[7].item', 'is required'),
      error(r'$.events[7].item', 'is required'),
      error(r'$.speed', 'expected at most 4'),
    ]);
    expect(problems, <int, List<String>>{
      3: <String>['Item is required'],
      7: <String>['Item is required'],
    });
  });
}
