import 'package:animus_editor/logic/gui/gui_json.dart';
import 'package:animus_editor/logic/npc/route_model.dart';
import 'package:animus_editor/logic/recording/recording_model.dart';
import 'package:animus_editor/logic/recording/recording_route.dart';
import 'package:test/test.dart';

/// A recording from 10.5 64 2.5: [frames] are `[tick, dx, dy, dz, flags]`.
Recording _recording(List<List<num>> frames) => Recording.parse(<String, Object?>{
      'world': 'world',
      'origin': <String, Object?>{'x': 10.5, 'y': 64, 'z': 2.5, 'yaw': 0, 'pitch': 0},
      'frames': <Object?>[
        for (final List<num> frame in frames) <Object?>[frame[0], frame[1], frame[2], frame[3], 0, 0, frame[4]],
      ],
    })!;

void main() {
  group('recordingRoute', () {
    test('test_recordingRoute_styleChanges_keepTheStylePerLeg', () {
      final JsonMap route = recordingRoute(_recording(<List<num>>[
        <num>[0, 0, 0, 0, 1],
        <num>[20, 3, 0, 0, 1 | 2],
        <num>[40, 6, 0, 0, 1 | 2],
        <num>[60, 9, 0, 0, 1 | 4],
        <num>[80, 12, -1.2, 0, 8],
        <num>[100, 15, -1.2, 0, 8],
      ]))!;
      expect(route['waitTicks'], 0);
      expect(asList(route['points']), <Object?>[
        <String, Object?>{'x': 10.5, 'y': 64, 'z': 2.5},
        <String, Object?>{'x': 13.5, 'y': 64, 'z': 2.5},
        <String, Object?>{'x': 19.5, 'y': 64, 'z': 2.5, 'style': 'sneak'},
        <String, Object?>{'x': 22.5, 'y': 62, 'z': 2.5, 'style': 'sprint'},
        <String, Object?>{'x': 25.5, 'y': 62, 'z': 2.5, 'style': 'swim'},
      ]);
      expect(RouteSpec(route).problems(), isEmpty);
    });

    test('test_recordingRoute_longWalk_placesAPointEverySpacing', () {
      final JsonMap route = recordingRoute(_recording(<List<num>>[
        for (int tick = 0; tick <= 40; tick++) <num>[tick, tick * 0.5, 0, 0, 1],
      ]))!;
      final List<Object?> points = asList(route['points']);
      expect(points.length, 4, reason: 'a 20 block walk: start, 8, 16 and the end');
      expect(points.every((Object? point) => !asMap(point).containsKey('style')), isTrue);
    });

    test('test_recordingRoute_flags_mapToStyles', () {
      expect(recordingFrameStyle(1), 'walk');
      expect(recordingFrameStyle(1 | 2), 'sneak');
      expect(recordingFrameStyle(4), 'sprint');
      expect(recordingFrameStyle(8 | 4), 'swim');
      expect(recordingFrameStyle(16), 'glide');
      expect(recordingRoute(_recording(<List<num>>[<num>[0, 0, 0, 0, 1]])), isNull);
    });
  });
}
