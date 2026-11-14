import 'package:animus_editor/logic/gui/gui_json.dart';
import 'package:animus_editor/logic/npc/route_usage.dart';
import 'package:animus_editor/model/animus_document.dart';
import 'package:animus_editor/model/document_kind.dart';
import 'package:test/test.dart';

JsonMap npc(String name, {Object? navigation, Object? position}) => <String, Object?>{
      'schemaVersion': 1,
      'name': name,
      'type': 'minecraft:player',
      'position': position ?? <String, Object?>{'world': 'world', 'x': 8.5, 'y': 64, 'z': 0.5},
      if (navigation != null) 'modules': <String, Object?>{'animus:navigation': navigation},
    };

AnimusDocument document(DocumentKind kind, String id, Object? json) =>
    AnimusDocument(kind: kind, id: id, path: '${kind.wireName}/$id.json', source: '$json', json: json, diagnostics: const <Diagnostic>[]);

void main() {
  final JsonMap own = <String, Object?>{
    'enabled': true,
    'points': <Object?>[
      <String, Object?>{'x': 1, 'y': 64, 'z': 1},
      <String, Object?>{'x': 4, 'y': 64, 'z': 1},
    ],
  };

  test('an NPC with points of its own carries an inline route', () {
    expect(inlineRouteOf(npc('Watchman', navigation: own)), same(own));
    expect(routeIdOf(npc('Watchman', navigation: own)), isNull);
  });

  test('an NPC naming a route has no inline route', () {
    final JsonMap named = npc('Porter', navigation: <String, Object?>{'enabled': true, 'route': 'market-round'});
    expect(inlineRouteOf(named), isNull);
    expect(routeIdOf(named), 'market-round');
  });

  test('a recording, an empty point list or no navigation is no route', () {
    expect(inlineRouteOf(npc('Stall Keeper', navigation: <String, Object?>{'recording': 'market-loop'})), isNull);
    expect(inlineRouteOf(npc('Idle', navigation: <String, Object?>{'points': <Object?>[]})), isNull);
    expect(inlineRouteOf(npc('Plain')), isNull);
    expect(inlineRouteOf('not a map'), isNull);
    expect(recordingIdOf(npc('Stall Keeper', navigation: <String, Object?>{'recording': 'market-loop'})), 'market-loop');
  });

  test('the NPCs using a route, in workspace order', () {
    final List<AnimusDocument> documents = <AnimusDocument>[
      document(DocumentKind.npc, 'harbor/porter', npc('Porter', navigation: <String, Object?>{'route': 'market-round'})),
      document(DocumentKind.npc, 'town/watchman', npc('Watchman', navigation: own)),
      document(DocumentKind.route, 'market-round', <String, Object?>{'points': <Object?>[]}),
      document(DocumentKind.npc, 'town/guard', npc('Guard', navigation: <String, Object?>{'route': 'market-round'}, position: <String, Object?>{'world': 'nether', 'x': 1, 'y': 70, 'z': 2})),
    ];
    expect(<String>[for (final AnimusDocument user in routeUsers(documents, 'market-round')) user.id], <String>['harbor/porter', 'town/guard']);
    expect(routeUsers(documents, 'other'), isEmpty);
  });

  test('where an NPC stands', () {
    final RouteUserPlace place = routeUserPlace(npc('Guard', position: <String, Object?>{'world': 'nether', 'x': 1.5, 'y': 70, 'z': -2}));
    expect(place.world, 'nether');
    expect(place.origin, (1.5, 70.0, -2.0));
    expect(routeUserPlace(<String, Object?>{'position': <String, Object?>{'world': 'world'}}).origin, isNull);
  });

  test('the used-by line names the first user and counts the rest', () {
    expect(usedByLine(const <String>[]), isNull);
    expect(usedByLine(const <String>['Porter']), 'Used by Porter');
    expect(usedByLine(const <String>['Porter', 'Guard']), 'Used by Porter and Guard');
    expect(usedByLine(const <String>['Porter', 'Guard', 'Baker']), 'Used by Porter and 2 more');
  });
}
