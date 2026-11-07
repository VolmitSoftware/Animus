import 'dart:convert';
import 'dart:math' as math;

import 'package:animus_editor/logic/gui/gui_json.dart';
import 'package:animus_editor/logic/recording/recording_edit.dart';
import 'package:test/test.dart';

import 'fixtures.dart';

JsonMap _market() => jsonDecode(jsonEncode(readFixture('recordings/market-loop.json'))) as JsonMap;

/// A valid recording with frames at [ticks] (the first 0), random-ish
/// offsets and a few events between them.
JsonMap _generated(List<int> ticks, int sampleTicks, math.Random random) {
  double offset() => (random.nextInt(4001) - 2000) / 100;
  final List<Object?> frames = <Object?>[
    for (final int tick in ticks) <Object?>[tick, offset(), offset(), offset(), (random.nextInt(721) - 360) / 2, (random.nextInt(361) - 180) / 2, random.nextInt(64)],
  ];
  final List<JsonMap> events =
      <JsonMap>[
        for (int index = 0; index < 6; index++)
          switch (index % 3) {
            0 => <String, Object?>{'tick': random.nextInt(ticks.last + 1), 'type': 'swing'},
            1 => <String, Object?>{
              'tick': random.nextInt(ticks.last + 1),
              'type': 'equipment',
              'slot': 'head',
              'item': <String, Object?>{'id': 'minecraft:stone_$index'},
            },
            _ => <String, Object?>{'tick': random.nextInt(ticks.last + 1), 'type': 'use', 'x': 1, 'y': 64, 'z': 2, 'block': 'minecraft:oak_door'},
          },
      ]..sort((JsonMap a, JsonMap b) {
        final int byTick = (a['tick']! as int).compareTo(b['tick']! as int);
        return byTick != 0 ? byTick : (a['type']! as String).compareTo(b['type']! as String);
      });
  return <String, Object?>{
    'schemaVersion': 1,
    'revision': 1,
    'id': 'walk',
    'recordedBy': '8667ba71-b85a-4004-af54-457a9734eed7',
    'world': 'world',
    'origin': <String, Object?>{'x': offset() * 10, 'y': 64, 'z': offset() * 10, 'yaw': 0, 'pitch': 0},
    'sampleTicks': sampleTicks,
    'durationTicks': ticks.last,
    'frames': frames,
    'events': events,
  };
}

List<int> _ticks(math.Random random, int sampleTicks) {
  final List<int> ticks = <int>[0];
  final int count = 2 + random.nextInt(30);
  while (ticks.length < count) {
    ticks.add(ticks.last + sampleTicks * (1 + random.nextInt(3)));
  }
  return ticks;
}

/// World position of every frame, rounded to the stored 0.01 grid.
List<String> _positions(JsonMap json) {
  final JsonMap origin = asMap(json['origin']);
  String at(Object? frame, int index, String axis) => (asDouble(origin[axis])! + asDouble(asList(frame)[index])!).toStringAsFixed(2);
  return <String>[for (final Object? frame in asList(json['frames'])) '${at(frame, 1, 'x')} ${at(frame, 2, 'y')} ${at(frame, 3, 'z')}'];
}

void main() {
  group('RecordingDetails', () {
    test('reads absent details as defaults', () {
      final RecordingDetails details = RecordingDetails.read(_market());
      expect(details.description, '');
      expect(details.tags, isEmpty);
      expect(details.playback, isNull);
      expect(details.speed, 1);
    });

    test('round-trips through the document, after durationTicks and before frames', () {
      const RecordingDetails details = RecordingDetails(description: 'Stall walk', tags: <String>['market', 'day-1'], playback: 'pingpong', speed: 1.5);
      final JsonMap written = details.writeTo(_market());
      expect(RecordingDetails.read(written), details);
      final List<String> keys = written.keys.toList();
      expect(keys.sublist(keys.indexOf('durationTicks'), keys.indexOf('frames') + 1), <String>['durationTicks', 'description', 'tags', 'playback', 'speed', 'frames']);
      expect(recordingProblems(written, id: 'market-loop'), isEmpty);
    });

    test('defaults leave the document without the keys', () {
      final JsonMap written = const RecordingDetails(description: 'x', tags: <String>['a'], playback: 'once', speed: 2).writeTo(_market());
      final JsonMap cleared = const RecordingDetails().writeTo(written);
      for (final String key in <String>['description', 'tags', 'playback', 'speed']) {
        expect(cleared.containsKey(key), isFalse, reason: key);
      }
      expect(cleared, _market());
    });
  });

  group('recordingProblems', () {
    test('agrees with the Java verdict on every shared recording fixture', () {
      for (final Object? entry in asList(fixtureIndex()['documents'])) {
        final JsonMap fixture = asMap(entry);
        if (fixture['kind'] != 'recording') continue;
        final bool java = asMap(fixture['expect'])['java'] == true;
        final List<String> problems = recordingProblems(readFixture(fixture['path']! as String), id: fixture['id'] as String?);
        expect(problems.isEmpty, java, reason: '${fixture['path']}: $problems');
      }
    });

    test('names each rule RecordingDocument checks', () {
      JsonMap with_(void Function(JsonMap json) change) => _market()..let(change);
      String first(JsonMap json, {String? id = 'market-loop'}) => recordingProblems(json, id: id).first;
      expect(first(with_((JsonMap json) => json['id'] = 'Market')), startsWith(r'$.id:'));
      expect(first(_market(), id: 'other'), contains('Rename'));
      expect(first(with_((JsonMap json) => json['extra'] = 1)), r'$.extra: unknown field');
      expect(first(with_((JsonMap json) => json['revision'] = 0)), startsWith(r'$.revision:'));
      expect(first(with_((JsonMap json) => json['recordedBy'] = 'not-a-uuid')), startsWith(r'$.recordedBy:'));
      expect(first(with_((JsonMap json) => json['world'] = ' ')), startsWith(r'$.world:'));
      expect(first(with_((JsonMap json) => asMap(json['origin'])['yaw'] = 400)), startsWith(r'$.origin.yaw:'));
      expect(first(with_((JsonMap json) => json['sampleTicks'] = 21)), startsWith(r'$.sampleTicks:'));
      expect(first(with_((JsonMap json) => json['durationTicks'] = 72001)), startsWith(r'$.durationTicks:'));
      expect(first(with_((JsonMap json) => asList(asList(json['frames'])[0])[0] = 1)), r'$.frames[0][0]: expected the first frame at tick 0');
      expect(first(with_((JsonMap json) => asList(asList(json['frames'])[2])[0] = 20)), r"$.frames[2][0]: expected a tick above the previous frame's 20");
      expect(first(with_((JsonMap json) => json['durationTicks'] = 79)), contains('frames'));
      expect(first(with_((JsonMap json) => json['durationTicks'] = 6)), r'$.frames: expected at most 4 frames for 6 ticks sampled every 2');
      expect(first(with_((JsonMap json) => json['frames'] = <Object?>[asList(json['frames'])[0]])), r'$.frames: expected at least 2 frames');
      expect(first(with_((JsonMap json) => asList(asList(json['frames'])[1])[6] = 64)), startsWith(r'$.frames[1][6]:'));
      expect(first(with_((JsonMap json) => asList(asList(json['frames'])[1])[5] = 91)), startsWith(r'$.frames[1][5]:'));
      expect(first(with_((JsonMap json) => asMap(asList(json['events'])[5])['tick'] = 81)), r'$.events[5].tick: expected integer 0..80');
      expect(first(with_((JsonMap json) => asMap(asList(json['events'])[1])['tick'] = 50)), r'$.events[2]: expected events sorted by tick then type');
      expect(first(with_((JsonMap json) => asMap(asList(json['events'])[1])['type'] = 'jump')), r'$.events[1].type: expected swing, equipment or use');
      expect(first(with_((JsonMap json) => asMap(asList(json['events'])[0])['slot'] = 'saddle')), startsWith(r'$.events[0].slot:'));
      expect(first(with_((JsonMap json) => asMap(asList(json['events'])[3])['block'] = 'door')), r'$.events[3].block: expected namespace:block');
      expect(first(with_((JsonMap json) => json['speed'] = 5)), r'$.speed: expected a number 0.25..4');
      expect(first(with_((JsonMap json) => json['tags'] = <Object?>['a', 'a'])), r'$.tags[1]: duplicate tag a');
      expect(first(with_((JsonMap json) => json['playback'] = 'reverse')), r'$.playback: expected once, loop or pingpong');
      expect(recordingProblems(<Object?>[]), <String>[r'$: expected a JSON object']);
    });
  });

  group('trimRecording', () {
    test('keeps the range, rebases ticks and keeps positions where they were', () {
      final RecordingTrim trim = trimRecording(_market(), 20, 60);
      expect(trim.problem, isNull);
      final JsonMap json = trim.json!;
      expect(json['durationTicks'], 40);
      expect(<Object?>[for (final Object? frame in asList(json['frames'])) asList(frame)[0]], <Object?>[0, 20, 40]);
      expect(asList(asList(json['frames'])[0]).sublist(1, 4), <Object?>[0, 0, 0]);
      expect(asMap(json['origin']), <String, Object?>{'x': 102, 'y': 64, 'z': -20.25, 'yaw': 92.5, 'pitch': -3});
      expect(_positions(json), _positions(_market()).sublist(1, 4));
      expect(recordingProblems(json, id: 'market-loop'), isEmpty);
    });

    test('drops events outside the range and carries each slot\'s equipment to the start', () {
      final JsonMap json = trimRecording(_market(), 20, 60).json!;
      expect(asList(json['events']), <Object?>[
        <String, Object?>{
          'tick': 0,
          'type': 'equipment',
          'slot': 'main_hand',
          'item': <String, Object?>{'id': 'minecraft:iron_sword'},
        },
        <String, Object?>{'tick': 0, 'type': 'swing'},
        <String, Object?>{
          'tick': 20,
          'type': 'equipment',
          'slot': 'off_hand',
          'item': <String, Object?>{'id': 'minecraft:torch', 'count': 16, 'components': '[custom_name="Lamp"]'},
        },
        <String, Object?>{'tick': 20, 'type': 'use', 'x': 104, 'y': 64, 'z': -18, 'block': 'minecraft:oak_door'},
        <String, Object?>{'tick': 40, 'type': 'equipment', 'slot': 'main_hand', 'item': null},
        <String, Object?>{'tick': 40, 'type': 'swing'},
      ]);
    });

    test('the whole range changes nothing', () {
      expect(trimRecording(_market(), 0, 80).json, _market());
    });

    test('refuses ranges that are not two frames apart or not on frames', () {
      expect(trimRecording(_market(), 40, 40).problem, isNotNull);
      expect(trimRecording(_market(), 60, 20).problem, isNotNull);
      expect(trimRecording(_market(), 10, 60).problem, contains('frame'));
      expect(trimRecording(<String, Object?>{}, 0, 1).problem, isNotNull);
    });

    test('refuses a range whose frames break the frame-count bound, with the reason', () {
      final JsonMap dense = _generated(<int>[0, 4, 5, 6], 2, math.Random(1));
      expect(recordingProblems(dense, id: 'walk'), isEmpty);
      final RecordingTrim trim = trimRecording(dense, 4, 6);
      expect(trim.json, isNull);
      expect(trim.problem, contains('at most 2 frames'));
    });

    test('every trim of generated recordings stays valid and keeps every kept position', () {
      final math.Random random = math.Random(7);
      for (int round = 0; round < 60; round++) {
        final int sample = 1 + random.nextInt(4);
        final JsonMap json = _generated(_ticks(random, sample), sample, random);
        expect(recordingProblems(json, id: 'walk'), isEmpty);
        final List<int> ticks = recordingFrameTicks(json);
        final int from = random.nextInt(ticks.length - 1);
        final int to = from + 1 + random.nextInt(ticks.length - from - 1);
        final RecordingTrim trim = trimRecording(json, ticks[from], ticks[to]);
        expect(trim.problem, isNull, reason: 'round $round');
        final JsonMap trimmed = trim.json!;
        expect(recordingProblems(trimmed, id: 'walk'), isEmpty, reason: 'round $round');
        expect(recordingFrameTicks(trimmed), <int>[for (final int tick in ticks.sublist(from, to + 1)) tick - ticks[from]]);
        expect(trimmed['durationTicks'], ticks[to] - ticks[from]);
        expect(_positions(trimmed), _positions(json).sublist(from, to + 1), reason: 'round $round');
        for (final Object? event in asList(trimmed['events'])) {
          expect(asInt(asMap(event)['tick']), inInclusiveRange(0, ticks[to] - ticks[from]));
        }
      }
    });
  });

  test('snapRecordingTick picks the nearest frame tick', () {
    expect(snapRecordingTick(<int>[0, 20, 40, 60, 80], 29), 20);
    expect(snapRecordingTick(<int>[0, 20, 40, 60, 80], 31), 40);
    expect(snapRecordingTick(<int>[0, 20, 40, 60, 80], 200), 80);
    expect(snapRecordingTick(<int>[0, 20, 40, 60, 80], -5), 0);
  });
}

extension<T> on T {
  T let(void Function(T value) change) {
    change(this);
    return this;
  }
}
