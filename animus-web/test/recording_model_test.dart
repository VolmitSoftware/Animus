import 'dart:io';

import 'package:animus_editor/logic/recording/recording_model.dart';
import 'package:test/test.dart';

import 'fixtures.dart';

void main() {
  final Object? marketJson = readFixture('recordings/market-loop.json');
  final String marketSource = File('${schemaDirectory.path}/fixtures/recordings/market-loop.json').readAsStringSync();

  group('Recording.parse', () {
    test('reads the market-loop fixture', () {
      final Recording recording = Recording.parse(marketJson)!;
      expect(recording.world, 'world');
      expect(recording.recordedBy, '8667ba71-b85a-4004-af54-457a9734eed7');
      expect(recording.sampleTicks, 2);
      expect(recording.durationTicks, 80);
      expect(recording.origin.x, 100.5);
      expect(recording.frames, hasLength(5));
      expect(recording.frames[1].dx, 1.5);
      expect(recording.frames[3].flags, 33);
      expect(recording.events, hasLength(6));
      expect(recording.events[3].type, 'use');
      expect(recording.events[3].block, 'minecraft:oak_door');
    });

    test('returns null for a value that is not an object', () {
      expect(Recording.parse(null), isNull);
      expect(Recording.parse(<Object?>[]), isNull);
    });

    test('skips frames that are not seven numbers', () {
      final Recording recording = Recording.parse(<String, Object?>{
        'durationTicks': 4,
        'frames': <Object?>[
          <Object?>[0, 0, 0, 0, 0, 0, 1],
          <Object?>[2, 'x'],
          <Object?>[4, 1, 0, 0, 0, 0, 1],
        ],
      })!;
      expect(recording.frames.map((RecordingFrame frame) => frame.tick), <int>[0, 4]);
    });
  });

  group('RecordingSummary', () {
    test('counts frames, events by type and bytes', () {
      final RecordingSummary summary = RecordingSummary.of(Recording.parse(marketJson)!, marketSource);
      expect(summary.durationTicks, 80);
      expect(summary.seconds, 4.0);
      expect(summary.frames, 5);
      expect(summary.events, 6);
      expect(summary.eventsByType, <String, int>{'equipment': 3, 'swing': 2, 'use': 1});
      expect(summary.bytes, marketSource.length);
    });

    test('formats lengths and sizes', () {
      expect(formatRecordingLength(80), '80 ticks (4 s)');
      expect(formatRecordingLength(1180), '1180 ticks (59 s)');
      expect(formatRecordingLength(1500), '1500 ticks (1 min 15 s)');
      expect(formatRecordingSize(900), '900 B');
      expect(formatRecordingSize(2048), '2.0 KiB');
      expect(formatRecordingSize(1572864), '1.5 MiB');
    });
  });

  group('poseAt', () {
    final Recording recording = Recording.parse(marketJson)!;

    test('returns frame poses at frame ticks, in world space', () {
      final RecordingPose pose = recording.poseAt(20);
      expect(pose.x, closeTo(102.0, 1e-9));
      expect(pose.z, closeTo(-20.25, 1e-9));
      expect(pose.yaw, closeTo(92.5, 1e-9));
      expect(pose.flags, 5);
    });

    test('interpolates position between frames and keeps the earlier flags', () {
      final RecordingPose pose = recording.poseAt(50);
      expect(pose.x, closeTo(100.5 + 3.2, 1e-9));
      expect(pose.y, closeTo(64.21, 1e-9));
      expect(pose.pitch, closeTo(3.5, 1e-9));
      expect(pose.flags, 5);
    });

    test('turns yaw the short way across 180 degrees', () {
      // 179.5 to -175.5 is a 5 degree turn, not 355.
      final RecordingPose pose = recording.poseAt(50);
      expect(pose.yaw, closeTo(182.0, 1e-9));
    });

    test('clamps before the first and after the last frame', () {
      expect(recording.poseAt(-5).x, closeTo(100.5, 1e-9));
      expect(recording.poseAt(500).x, closeTo(100.5, 1e-9));
    });
  });

  test('flag names and changes', () {
    expect(recordingFlagNames(33), <String>['on ground', 'jumping']);
    expect(recordingFlagNames(0), isEmpty);
    final Recording recording = Recording.parse(marketJson)!;
    expect(recording.flagChanges().map((RecordingFrame frame) => frame.tick), <int>[20, 60, 80]);
  });

  test('bounds cover every frame position', () {
    final Recording recording = Recording.parse(marketJson)!;
    final (double, double, double, double) bounds = recording.bounds();
    expect(bounds.$1, closeTo(100.5, 1e-9));
    expect(bounds.$2, closeTo(-20.5, 1e-9));
    expect(bounds.$3, closeTo(103.7, 1e-9));
    expect(bounds.$4, closeTo(-20.25, 1e-9));
  });

  test('the bad fixtures still parse for display', () {
    expect(Recording.parse(readFixture('recordings/bad-event.json'))!.events.single.type, 'jump');
    expect(Recording.parse(readFixture('recordings/too-many-frames.json'))!.frames, hasLength(5));
  });

  group('advanceRecordingTick', () {
    test('should advance by elapsed time, not by callbacks', () {
      expect(advanceRecordingTick(0, 4500, 1, 200), 90);
      double tick = 0;
      for (final double gap in <double>[50, 120, 300, 30, 4000]) {
        tick = advanceRecordingTick(tick, gap, 1, 200);
      }
      expect(tick, 90, reason: 'late or bunched timer callbacks add up to the same elapsed time');
    });

    test('should scale by speed and stop at the last tick', () {
      expect(advanceRecordingTick(10, 1000, 2, 200), 50);
      expect(advanceRecordingTick(10, 1000, 0.25, 200), 15);
      expect(advanceRecordingTick(70, 1000, 1, 80), 80);
      expect(advanceRecordingTick(30, -5, 1, 80), 30, reason: 'a clock that steps back does not rewind');
    });
  });
}
