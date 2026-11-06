import 'dart:convert';

import 'package:animus_editor/logic/gui/gui_json.dart';
import 'package:animus_editor/logic/npc/route_model.dart';
import 'package:test/test.dart';

/// A route with every point kind, written the way the editor saves it.
const String _mixed = '''{
  "schemaVersion": 1,
  "order": "sequence",
  "seed": "npc",
  "startOffset": "random",
  "waitTicks": [
    20,
    60
  ],
  "jitter": 2,
  "laterField": {
    "kept": true
  },
  "points": [
    {
      "x": 0,
      "y": 64,
      "z": 0.5,
      "waitTicks": [
        10,
        40
      ],
      "jitter": 1,
      "next": [
        {
          "to": 1,
          "weight": 3
        },
        {
          "to": 2
        }
      ]
    },
    {
      "recording": "market-loop",
      "fromTick": 0,
      "toTick": 400,
      "playback": "once",
      "waitTicks": 5,
      "weight": 2,
      "note": "unknown to the editor"
    },
    {
      "x": 12,
      "y": 64,
      "z": -3.25,
      "weight": 1000,
      "custom": [
        1,
        2.5
      ]
    }
  ]
}
''';

JsonMap _route() => jsonDecode(_mixed) as JsonMap;

void main() {
  group('route steps', () {
    test('test_routeModel_mixedRoute_roundTripsByteForByte', () {
      final JsonMap route = _route();
      final RouteSpec spec = RouteSpec(route);
      expect(spec.points, hasLength(3));
      expect(prettyJson(route), _mixed);

      final JsonMap moved = moveRouteNode(route, 'points', 2, 13, -3);
      final JsonMap back = moveRouteNode(moved, 'points', 2, 12, -3.25);
      expect(prettyJson(back), _mixed, reason: 'moving a point and back leaves every other key and number alone');

      final JsonMap stepEdited = setRouteNodeField(setRouteNodeField(route, 'points', 1, 'toTick', 200), 'points', 1, 'toTick', 400);
      expect(prettyJson(stepEdited), _mixed);

      expect(moveRouteNode(route, 'points', 1, 5, 5), same(route), reason: 'a step has no position to move');
      expect(nudgeRouteNode(route, 'points', 1, 1, 1), same(route));
    });

    test('test_routeSpec_step_isNumberedAndCountedAsPoint', () {
      final RouteSpec spec = RouteSpec(_route());
      expect(spec.nodes, hasLength(3));
      expect(spec.points[1], isA<RouteStep>());
      final RouteStep step = spec.points[1] as RouteStep;
      expect(step.recording, 'market-loop');
      expect(step.toTick, 400);
      expect(step.waitTicks, const RouteWait(5, 5));
      expect(spec.positionOf(2)!.x, 12, reason: 'the point after the step keeps number 2');
      expect(spec.positionOf(1), isNull);
      expect(spec.placed, hasLength(2));
      expect(spec.links, isEmpty, reason: 'no link is drawn to or from a step');
      expect(spec.waitTicks, const RouteWait(20, 60));
      expect(spec.points[0].waitTicks.toString(), '10-40');
      expect(
        spec.problems(),
        <(String, String)>[('points[1].weight', 'is only allowed with order random'), ('points[2].weight', 'is only allowed with order random')],
        reason: 'the mixed route carries every key; the server refuses weight outside order random',
      );
    });

    test('test_routeSpec_unreadablePoint_keepsItsNumber', () {
      final RouteSpec spec = RouteSpec(<String, Object?>{
        'points': <Object?>[
          <String, Object?>{'x': 0, 'z': 0},
          <String, Object?>{'x': 4, 'y': 64, 'z': 0},
        ],
      });
      expect(spec.points.first, isA<RouteOther>());
      expect(spec.positionOf(1)!.x, 4);
    });

    test('test_routePointConversion_keepsSharedFieldsInPlace', () {
      final JsonMap route = _route();
      final JsonMap step = routePointToStep(route, 2, 'market-loop');
      expect(asMap(asList(step['points'])[2]).keys, <String>['recording', 'weight', 'custom']);
      final JsonMap position = routeStepToPosition(route, 1, 3, 64, 4);
      expect(asMap(asList(position['points'])[1]), <String, Object?>{'x': 3, 'y': 64, 'z': 4, 'waitTicks': 5, 'weight': 2, 'note': 'unknown to the editor'});
      final JsonMap added = addRouteStep(route, 'market-loop');
      expect(asList(added['points']).last, <String, Object?>{'recording': 'market-loop'});
    });

    test('test_routeSpec_stepProblems_nameTheField', () {
      final List<String> fields = <String>[
        for (final (String field, String _) in RouteSpec(<String, Object?>{
          'order': 'wander',
          'points': <Object?>[
            <String, Object?>{'recording': 'Bad Id', 'fromTick': 50, 'toTick': 50},
          ],
        }).problems())
          field,
      ];
      expect(fields, <String>['points[0].recording', 'points[0].recording', 'points[0].fromTick']);
    });
  });
}
