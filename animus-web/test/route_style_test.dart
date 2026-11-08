import 'package:animus_editor/logic/gui/gui_json.dart';
import 'package:animus_editor/logic/npc/route_model.dart';
import 'package:test/test.dart';

void main() {
  group('route styles', () {
    test('test_routeSpec_styles_refuseUnknownNamesAndSteps', () {
      final JsonMap route = <String, Object?>{
        'style': 'run',
        'points': <Object?>[
          <String, Object?>{'x': 0, 'y': 64, 'z': 0, 'style': 'sneak'},
          <String, Object?>{'x': 4, 'y': 64, 'z': 0, 'style': 'hop'},
          <String, Object?>{'recording': 'walk', 'style': 'sneak'},
        ],
      };
      expect(RouteSpec(route).problems(), <(String, String)>[
        ('style', 'must be walk, sneak, sprint, swim, crawl, glide'),
        ('points[1].style', 'must be walk, sneak, sprint, swim, crawl, glide'),
        ('points[2].style', 'a recording step plays its recorded movement; it has no style'),
      ]);
    });

    test('test_routePointToStep_dropsTheStyle', () {
      final JsonMap route = <String, Object?>{
        'points': <Object?>[
          <String, Object?>{'x': 0, 'y': 64, 'z': 0, 'style': 'sneak', 'waitTicks': 5},
        ],
      };
      expect(asMap(asList(routePointToStep(route, 0, 'walk')['points']).first), <String, Object?>{'recording': 'walk', 'waitTicks': 5});
    });

    test('test_setNavigationRecording_dropsTheRouteStyle', () {
      expect(setNavigationRecording(<String, Object?>{'enabled': true, 'style': 'sneak', 'points': <Object?>[]}, 'walk'),
          <String, Object?>{'enabled': true, 'recording': 'walk'});
    });
  });
}
