import 'package:animus_editor/logic/gui/gui_json.dart';
import 'package:animus_editor/logic/npc/route_model.dart';
import 'package:test/test.dart';

/// The fields RouteSpec.problems() names, in order.
List<String> _fields(JsonMap route) => <String>[for (final (String field, String _) in RouteSpec(route).problems()) field];

String? _message(JsonMap route, String field) {
  for (final (String at, String message) in RouteSpec(route).problems()) {
    if (at == field) return message;
  }
  return null;
}

JsonMap _point(int x) => <String, Object?>{'x': x, 'y': 64, 'z': 0};

void main() {
  group('route waits', () {
    test('test_routeWait_toJson_writesIntOrRange', () {
      expect(const RouteWait(20, 20).toJson(), 20);
      expect(const RouteWait(10, 40).toJson(), <int>[10, 40]);
    });

    test('test_routeWaitProblem_minAboveMax_namesTheField', () {
      expect(routeWaitProblem(<Object?>[60, 20]), 'min above max');
      expect(routeWaitProblem(<Object?>[0, 12001]), 'must be 0..12000 or [min, max]');
      expect(routeWaitProblem(<Object?>[1, 2, 3]), 'must be 0..12000 or [min, max]');
      expect(routeWaitProblem(-1), 'must be 0..12000 or [min, max]');
      expect(routeWaitProblem(1.5), 'expected whole number');
      expect(routeWaitProblem(<Object?>[0, 12000]), isNull);
      expect(routeWaitProblem(40), isNull);
    });

    test('test_routeSpec_waitProblems_reportRouteAndPointFields', () {
      final JsonMap route = <String, Object?>{
        'waitTicks': <Object?>[50, 10],
        'points': <Object?>[
          _point(0),
          _point(1),
          <String, Object?>{..._point(2), 'waitTicks': <Object?>[9, 3]},
        ],
      };
      expect(_message(route, 'waitTicks'), 'min above max');
      expect(_message(route, 'points[2].waitTicks'), 'min above max');
    });
  });

  group('random route options', () {
    test('test_routeSpec_seedAndStartOffset_refuseUnknownValues', () {
      final JsonMap route = <String, Object?>{'seed': 'clock', 'startOffset': 'middle', 'points': <Object?>[_point(0)]};
      expect(_message(route, 'seed'), 'must be npc or random');
      expect(_message(route, 'startOffset'), 'must be first or random');
      expect(_fields(<String, Object?>{'seed': 'random', 'startOffset': 'random', 'points': <Object?>[_point(0)]}), isEmpty);
    });

    test('test_routeSpec_jitter_rangeAndWanderRules', () {
      expect(_message(<String, Object?>{'jitter': 9, 'points': <Object?>[_point(0)]}, 'jitter'), 'must be 0..8 blocks');
      expect(_message(<String, Object?>{'order': 'wander', 'jitter': 2}, 'jitter'), 'is not allowed with order wander; wander.radius spreads its goals');
      expect(
        _message(<String, Object?>{'order': 'wander', 'points': <Object?>[<String, Object?>{..._point(0), 'jitter': 1}]}, 'points[0].jitter'),
        'is not allowed with order wander',
      );
      expect(
        _message(<String, Object?>{'points': <Object?>[<String, Object?>{'recording': 'market-loop', 'jitter': 1}]}, 'points[0].jitter'),
        'a recording step has no jitter',
      );
      expect(_message(<String, Object?>{'points': <Object?>[<String, Object?>{..._point(0), 'jitter': 12}]}, 'points[0].jitter'), 'must be 0..8 blocks');
    });

    test('test_routeSpec_weight_onlyRandomAndInRange', () {
      expect(_message(<String, Object?>{'points': <Object?>[<String, Object?>{..._point(0), 'weight': 2}]}, 'points[0].weight'), 'is only allowed with order random');
      expect(
        _message(<String, Object?>{'order': 'random', 'points': <Object?>[<String, Object?>{..._point(0), 'weight': 0}, _point(1)]}, 'points[0].weight'),
        'must be 1..1000',
      );
      expect(_fields(<String, Object?>{'order': 'random', 'points': <Object?>[<String, Object?>{..._point(0), 'weight': 1000}, _point(1)]}), isEmpty);
    });

    test('test_routeSpec_next_rulesMatchTheServer', () {
      JsonMap withNext(Object? next, {String order = 'sequence'}) => <String, Object?>{
        'order': order,
        'points': <Object?>[<String, Object?>{..._point(0), 'next': next}, _point(1), _point(2)],
      };
      expect(_message(withNext(<Object?>[<String, Object?>{'to': 1}], order: 'pingpong'), 'points[0].next'), 'is only allowed with order sequence');
      expect(_message(withNext(<Object?>[]), 'points[0].next'), 'requires 1..8 {"to": index, "weight": 1..1000} entries');
      expect(_message(withNext(<Object?>[for (int i = 0; i < 9; i++) <String, Object?>{'to': 1}]), 'points[0].next'), 'requires 1..8 {"to": index, "weight": 1..1000} entries');
      expect(_message(withNext(<Object?>[<String, Object?>{'to': 1}, <String, Object?>{'to': 3}]), 'points[0].next[1].to'), 'out of range');
      expect(_message(withNext(<Object?>[<String, Object?>{'weight': 2}]), 'points[0].next[0].to'), 'required');
      expect(_message(withNext(<Object?>[<String, Object?>{'to': 1, 'weight': 1001}]), 'points[0].next[0].weight'), 'must be 1..1000');
      expect(_message(withNext(<Object?>[<String, Object?>{'to': 1, 'chance': 2}]), 'points[0].next[0].chance'), 'unknown field');
      expect(_fields(withNext(<Object?>[<String, Object?>{'to': 2, 'weight': 3}, <String, Object?>{'to': 1}])), isEmpty);
    });

    test('test_routeSpec_loop_onlySequence', () {
      expect(_message(<String, Object?>{'order': 'random', 'loop': true, 'points': <Object?>[_point(0), _point(1)]}, 'loop'), 'is only allowed with order sequence');
    });
  });

  group('junction edits', () {
    final JsonMap route = <String, Object?>{
      'points': <Object?>[_point(0), _point(1), _point(2), _point(3)],
    };

    test('test_addRouteJunction_picksAnotherPoint', () {
      final JsonMap one = addRouteJunction(route, 0);
      expect(asList(asMap(asList(one['points'])[0])['next']), <Object?>[<String, Object?>{'to': 1}]);
      final JsonMap two = addRouteJunction(one, 0);
      expect(asList(asMap(asList(two['points'])[0])['next']).length, 2);
    });

    test('test_setAndRemoveRouteJunction_dropsEmptyNext', () {
      JsonMap next = addRouteJunction(route, 1);
      next = setRouteJunction(next, 1, 0, to: 3, weight: 5);
      expect(asList(asMap(asList(next['points'])[1])['next']), <Object?>[<String, Object?>{'to': 3, 'weight': 5}]);
      next = setRouteJunction(next, 1, 0, weight: null);
      expect(asList(asMap(asList(next['points'])[1])['next']), <Object?>[<String, Object?>{'to': 3}]);
      next = removeRouteJunction(next, 1, 0);
      expect(asMap(asList(next['points'])[1]).containsKey('next'), isFalse);
    });

    test('test_removeRoutePoint_remapsJunctionTargets', () {
      final JsonMap linked = <String, Object?>{
        'points': <Object?>[
          <String, Object?>{..._point(0), 'next': <Object?>[<String, Object?>{'to': 1}, <String, Object?>{'to': 3, 'weight': 2}]},
          _point(1),
          _point(2),
          _point(3),
        ],
      };
      final JsonMap removed = removeRouteNode(linked, 'points', 1);
      expect(asList(asMap(asList(removed['points'])[0])['next']), <Object?>[<String, Object?>{'to': 2, 'weight': 2}]);
      final JsonMap alone = removeRouteNode(removeRouteNode(linked, 'points', 3), 'points', 1);
      expect(asMap(asList(alone['points'])[0]).containsKey('next'), isFalse);
    });

    test('test_reorderRoutePoint_followsJunctionTargets', () {
      final JsonMap linked = <String, Object?>{
        'points': <Object?>[
          <String, Object?>{..._point(0), 'next': <Object?>[<String, Object?>{'to': 2}]},
          _point(1),
          _point(2),
        ],
      };
      final JsonMap moved = reorderRoutePoint(linked, 2, 1);
      expect(asList(asMap(asList(moved['points'])[0])['next']), <Object?>[<String, Object?>{'to': 1}]);
      expect(asMap(asList(moved['points'])[1])['x'], 2);
    });

    test('test_routeSpec_sequenceLinks_followJunctions', () {
      final RouteSpec spec = RouteSpec(<String, Object?>{
        'loop': true,
        'points': <Object?>[
          _point(0),
          <String, Object?>{..._point(1), 'next': <Object?>[<String, Object?>{'to': 3}, <String, Object?>{'to': 0}]},
          _point(2),
          _point(3),
        ],
      });
      expect(spec.links, <(int, int)>[(0, 1), (2, 3), (3, 0)]);
      expect(spec.junctionLinks, <(int, int)>[(1, 3), (1, 0)]);
    });
  });

  group('order changes', () {
    test('test_setRouteOrder_dropsFieldsTheOrderRefuses', () {
      final JsonMap route = <String, Object?>{
        'order': 'random',
        'jitter': 2,
        'points': <Object?>[
          <String, Object?>{..._point(0), 'weight': 3, 'jitter': 1},
          <String, Object?>{..._point(1), 'next': <Object?>[<String, Object?>{'to': 0}]},
        ],
      };
      final JsonMap sequence = setRouteOrder(route, 'sequence');
      expect(asMap(asList(sequence['points'])[0]).containsKey('weight'), isFalse);
      expect(asMap(asList(sequence['points'])[1]).containsKey('next'), isTrue);
      final JsonMap pingpong = setRouteOrder(sequence, 'pingpong');
      expect(asMap(asList(pingpong['points'])[1]).containsKey('next'), isFalse);
      final JsonMap wander = setRouteOrder(route, 'wander');
      expect(wander.containsKey('jitter'), isFalse);
      expect(asMap(asList(wander['points'])[0]).containsKey('jitter'), isFalse);
      expect(RouteSpec(wander).problems(), isEmpty);
    });
  });

  group('navigation sources', () {
    test('test_setNavigationRecording_dropsRouteFields', () {
      final JsonMap module = <String, Object?>{'enabled': true, 'order': 'random', 'points': <Object?>[_point(0)], 'kept': 1};
      final JsonMap played = setNavigationRecording(module, 'market-loop');
      expect(played, <String, Object?>{'enabled': true, 'kept': 1, 'recording': 'market-loop'});
      expect(navigationSource(played), NavigationSource.recording);
      final JsonMap routed = setNavigationRecording(<String, Object?>{'enabled': true, 'route': 'town'}, 'x', playback: 'pingpong');
      expect(routed, <String, Object?>{'enabled': true, 'recording': 'x', 'playback': 'pingpong'});
    });

    test('test_navigationSource_readsTheModule', () {
      expect(navigationSource(<String, Object?>{'route': 'a'}), NavigationSource.route);
      expect(navigationSource(<String, Object?>{'points': <Object?>[]}), NavigationSource.points);
    });

    test('test_navigationProblems_matchRecordingSource', () {
      expect(navigationProblems(<String, Object?>{'enabled': true, 'recording': 'a', 'route': 'b'}), <(String, String)>[('recording', 'cannot be combined with route')]);
      expect(navigationProblems(<String, Object?>{'enabled': true, 'recording': 'a', 'points': <Object?>[]}), <(String, String)>[('recording', 'cannot be combined with points')]);
      expect(navigationProblems(<String, Object?>{'enabled': true, 'playback': 'loop', 'points': <Object?>[]}), <(String, String)>[('playback', 'requires recording')]);
      expect(navigationProblems(<String, Object?>{'enabled': true, 'recording': 'Bad'}), <(String, String)>[('recording', 'expected 1-64 lowercase letters, digits, _ or -')]);
      expect(navigationProblems(<String, Object?>{'enabled': true, 'recording': 'a', 'playback': 'twice'}), <(String, String)>[('playback', 'expected once, loop or pingpong')]);
      expect(navigationProblems(<String, Object?>{'enabled': true, 'recording': 'a', 'playback': 'once'}), isEmpty);
    });
  });
}
