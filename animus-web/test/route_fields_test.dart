import 'dart:convert';

import 'package:animus_editor/logic/gui/gui_json.dart';
import 'package:animus_editor/logic/npc/route_fields.dart';
import 'package:animus_editor/logic/npc/route_model.dart';
import 'package:test/test.dart';

void main() {
  group('seconds and ticks', () {
    test('a field shows ticks as seconds', () {
      expect(ticksToSeconds(20), 1);
      expect(ticksToSeconds(30), 1.5);
      expect(ticksToSeconds(1), 0.05);
      expect(ticksToSeconds(0), 0);
      expect(ticksToSeconds(12000), 600);
    });

    test('typed seconds are stored as whole ticks', () {
      expect(secondsToTicks(1), 20);
      expect(secondsToTicks(1.5), 30);
      expect(secondsToTicks(0.05), 1);
      expect(secondsToTicks(0.07), 1);
      expect(secondsToTicks(2.49), 50);
    });

    test('every tick count survives the round trip through seconds', () {
      for (int ticks = 0; ticks <= routeMaxWait; ticks += 7) {
        expect(secondsToTicks(ticksToSeconds(ticks)), ticks);
      }
    });

    test('waits read in seconds', () {
      expect(secondsText(20), '1 s');
      expect(secondsText(30), '1.5 s');
      expect(waitText(const RouteWait(40, 40)), '2 s');
      expect(waitText(const RouteWait(20, 60)), '1-3 s');
    });

    test('a wait in seconds writes ticks, a range as [min, max]', () {
      expect(waitFromSeconds(2, null, range: false), 40);
      expect(waitFromSeconds(1, 3, range: true), <int>[20, 60]);
      expect(waitFromSeconds(null, 3, range: true), <int>[60, 60]);
      expect(waitFromSeconds(null, null, range: true), isNull);
      expect(waitFromSeconds(null, null, range: false), isNull);
    });
  });

  group('labels', () {
    test('problem paths read as point numbers from 1', () {
      expect(routeFieldName('points[3].next[1].to'), 'Point 4, next point 2');
      expect(routeFieldName('guides[0]'), 'Guide 1');
      expect(routeFieldName('waitTicks'), 'Wait');
      expect(routeFieldName('points[0].waitTicks'), 'Point 1, wait');
      expect(routeFieldName('linkDistance'), 'Link reach');
    });

    test('wait problems read in seconds', () {
      expect(routeProblemText('waitTicks', 'must be 0..12000 or [min, max]'), 'Use 0 to 600 seconds.');
      expect(routeProblemText('points[1].waitTicks', 'min above max'), 'The shortest wait is longer than the longest.');
      expect(routeProblemText('jitter', 'must be 0..8'), 'Must be 0..8.');
      final List<(String, String)> problems = <(String, String)>[('points[1].waitTicks', 'min above max'), ('points[1].next[0].to', 'out of range'), ('jitter', 'must be 0..8')];
      expect(routeProblemsAt(problems, 'points[1].waitTicks').single.message, 'The shortest wait is longer than the longest.');
      expect(routeProblemsAt(problems, 'points[1]'), isEmpty);
      expect(routeProblemsAt(problems, 'points[1]', within: true).length, 2);
    });

    test('a problem path names the point it is on', () {
      expect(routeFieldNode('points[3].next[1].to'), ('points', 3));
      expect(routeFieldNode('guides[2]'), ('guides', 2));
      expect(routeFieldNode('jitter'), isNull);
    });

    test('every order, mode, style, seed and start has a plain label', () {
      for (final String order in routeOrders) {
        expect(routeOrderLabels[order], isNotNull, reason: order);
      }
      for (final String mode in routeModes) {
        expect(routeModeLabels[mode], isNotNull, reason: mode);
      }
      for (final String style in movementStyles) {
        expect(movementStyleLabels[style], isNotNull, reason: style);
      }
      for (final String seed in routeSeeds) {
        expect(routeSeedLabels[seed], isNotNull, reason: seed);
      }
      for (final String start in routeStartOffsets) {
        expect(routeStartLabels[start], isNotNull, reason: start);
      }
      expect(routeOrderLabels.values.any((String label) => label.contains('pingpong')), isFalse);
    });
  });

  group('Simple edits keep what Simple does not show', () {
    final JsonMap route = jsonDecode('''
      {"enabled": true, "schemaVersion": 1, "order": "sequence", "loop": true, "mode": "swim", "style": "sneak",
       "speed": 2.5, "waitTicks": [20, 60], "seed": "random", "startOffset": "random", "jitter": 2,
       "pause": {"near": 4, "lockoutTicks": 40}, "custom": {"kept": [1, 2.50, "x"]},
       "points": [
         {"x": 4, "y": 64, "z": -6, "weight": 3, "teleport": {"x": 1, "y": 70, "z": 2, "yaw": 90, "world": "nether"},
          "next": [{"to": 1, "weight": 3}, {"to": 2}], "jitter": 1, "style": "sprint", "speed": 3, "waitTicks": [40, 80]},
         {"x": 12, "y": 64, "z": -6},
         {"recording": "market-loop", "fromTick": 20, "toTick": 60}
       ]}
    ''') as JsonMap;

    String encoded(Object? value) => jsonEncode(value);

    void keepsEveryOtherKey(JsonMap before, JsonMap after, Set<String> edited) {
      for (final String key in before.keys) {
        if (edited.contains(key)) continue;
        expect(after.containsKey(key), isTrue, reason: '$key is kept');
        expect(encoded(after[key]), encoded(before[key]), reason: '$key keeps its value byte for byte');
      }
    }

    test('speed and loop', () {
      final JsonMap faster = routeWith(route, 'speed', 4);
      expect(faster['speed'], 4);
      keepsEveryOtherKey(route, faster, <String>{'speed'});
      final JsonMap once = routeWith(faster, 'loop', null);
      expect(once.containsKey('loop'), isFalse);
      keepsEveryOtherKey(route, once, <String>{'speed', 'loop'});
    });

    test('clearing a field removes only that key', () {
      final JsonMap cleared = routeWith(route, 'speed', null);
      expect(cleared.containsKey('speed'), isFalse);
      keepsEveryOtherKey(route, cleared, <String>{'speed'});
    });

    test('a point edit keeps the point keys Simple hides', () {
      final JsonMap moved = setRouteNodeField(route, 'points', 0, 'x', 5);
      final JsonMap point = asMap(asList(moved['points'])[0]);
      final JsonMap before = asMap(asList(route['points'])[0]);
      expect(point['x'], 5);
      keepsEveryOtherKey(before, point, <String>{'x'});
      keepsEveryOtherKey(route, moved, <String>{'points'});
      final JsonMap waited = setRouteNodeField(route, 'points', 1, 'waitTicks', waitFromSeconds(2, null, range: false));
      expect(asMap(asList(waited['points'])[1])['waitTicks'], 40);
      expect(encoded(asList(waited['points'])[0]), encoded(asList(route['points'])[0]));
      expect(encoded(asList(waited['points'])[2]), encoded(asList(route['points'])[2]));
    });
  });
}
