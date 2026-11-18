import 'package:animus_editor/logic/npc/named_places.dart';
import 'package:test/test.dart';

void main() {
  final Map<String, Object?> anchors = <String, Object?>{
    'gate': <String, Object?>{'x': 1, 'y': 64, 'z': 2},
    'mine': <String, Object?>{'x': 5, 'y': 20, 'z': 5, 'world': 'mines'},
  };

  test('should add an anchor where the NPC stands, under a free name', () {
    final Map<String, Object?> next = withNewAnchor(anchors, (x: 10.5, y: 65, z: -3.5));
    expect(next.keys, <String>['gate', 'mine', 'anchor']);
    expect(next['anchor'], <String, Object?>{'x': 10.5, 'y': 65, 'z': -3.5});
    expect(withNewAnchor(next, (x: 0, y: 0, z: 0)).keys.last, 'anchor-2');
    expect(withNewAnchor(null, (x: 0, y: 64, z: 0)), <String, Object?>{'anchor': <String, Object?>{'x': 0, 'y': 64, 'z': 0}});
  });

  test('should add a named facing that starts at the NPC facing', () {
    expect(withNewFacing(null, 90), <String, Object?>{'facing': <String, Object?>{'yaw': 90}});
    expect(withNewFacing(<String, Object?>{'facing': <String, Object?>{'yaw': 0}}, -45).keys.last, 'facing-2');
  });

  test('should rename a place in place, keeping the order', () {
    final Map<String, Object?>? next = renamedPlace(anchors, 'gate', 'north-gate');
    expect(next!.keys, <String>['north-gate', 'mine']);
    expect(next['north-gate'], anchors['gate']);
  });

  test('should refuse a name the server refuses or one already used', () {
    expect(placeNameProblem('North Gate', anchors, 'gate'), 'Use 1-32 lowercase letters, digits, _ or -, starting with a letter or digit.');
    expect(placeNameProblem('mine', anchors, 'gate'), 'Another one is already called mine.');
    expect(placeNameProblem('gate', anchors, 'gate'), isNull);
    expect(placeNameProblem('north_gate-2', anchors, 'gate'), isNull);
  });

  test('should set one value and drop the map when the last place goes', () {
    expect(withPlaceValue(anchors, 'mine', 'world', null)!['mine'], <String, Object?>{'x': 5, 'y': 20, 'z': 5});
    expect(withPlaceValue(anchors, 'gate', 'y', 70)!['gate'], <String, Object?>{'x': 1, 'y': 70, 'z': 2});
    expect(withoutPlace(<String, Object?>{'gate': <String, Object?>{}}, 'gate'), isNull);
    expect(withoutPlace(anchors, 'gate')!.keys, <String>['mine']);
  });
}
