import 'package:animus_editor/logic/gui/gui_json.dart';
import 'package:animus_editor/logic/npc/route_model.dart';
import 'package:test/test.dart';

List<(String, String)> _problems(String order, JsonMap point) => RouteSpec(<String, Object?>{
      'order': order,
      'points': <Object?>[point],
    }).problems();

void main() {
  group('route point speed and teleport', () {
    test('test_routeSpec_speedAndTeleport_validPointHasNoProblems', () {
      expect(
        _problems('sequence', <String, Object?>{
          'x': 0, 'y': 64, 'z': 0, 'speed': 4.5, //
          'teleport': <String, Object?>{'x': 10.5, 'y': 70.25, 'z': -3, 'yaw': 90},
        }),
        isEmpty,
      );
    });

    test('test_routeSpec_badSpeedAndTeleport_nameTheField', () {
      final List<String> fields = <String>[
        for (final (String field, String _) in _problems('sequence', <String, Object?>{
          'x': 0, 'y': 64, 'z': 0, 'speed': 6, //
          'teleport': <String, Object?>{'x': 0, 'y': 64, 'world': 'two words'},
        }))
          field,
      ];
      expect(fields, <String>['points[0].speed', 'points[0].teleport']);
    });

    test('test_routeSpec_wanderAndSteps_refuseSpeedAndTeleport', () {
      expect(<String>[for (final (String field, String _) in _problems('wander', <String, Object?>{'x': 0, 'y': 64, 'z': 0, 'speed': 2})) field], <String>['points[0].speed']);
      expect(
        <String>[for (final (String field, String _) in _problems('sequence', <String, Object?>{'recording': 'walk', 'teleport': <String, Object?>{'x': 0, 'y': 0, 'z': 0}})) field],
        contains('points[0].teleport'),
      );
    });

    test('test_setRouteOrder_wander_dropsSpeedAndTeleport', () {
      final JsonMap route = setRouteOrder(<String, Object?>{
        'order': 'sequence',
        'points': <Object?>[
          <String, Object?>{'x': 0, 'y': 64, 'z': 0, 'speed': 3, 'teleport': <String, Object?>{'x': 1, 'y': 64, 'z': 1}},
        ],
      }, 'wander');
      expect(asMap(asList(route['points']).first).keys, <String>['x', 'y', 'z']);
    });
  });
}
