import 'package:animus_editor/components/recording/movement_rows.dart';
import 'package:animus_editor/logic/gui/gui_json.dart';
import 'package:animus_editor/model/animus_document.dart';
import 'package:animus_editor/model/document_kind.dart';
import 'package:animus_editor/model/document_summary.dart';
import 'package:animus_editor/state/library.dart';
import 'package:animus_editor/ui/library_query.dart';
import 'package:test/test.dart';

JsonMap npc(String name, {Object? navigation, String world = 'world'}) => <String, Object?>{
      'schemaVersion': 1,
      'name': name,
      'type': 'minecraft:player',
      'position': <String, Object?>{'world': world, 'x': 8.5, 'y': 64, 'z': 0.5},
      if (navigation != null) 'modules': <String, Object?>{'animus:navigation': navigation},
    };

final JsonMap ownPoints = <String, Object?>{
  'points': <Object?>[
    <String, Object?>{'x': 1, 'y': 64, 'z': 1},
    <String, Object?>{'x': 4, 'y': 64, 'z': 1},
    <String, Object?>{'x': 4, 'y': 64, 'z': 6},
  ],
};

/// A workspace held as library entries plus the documents read so far.
class Library {
  final Map<String, AnimusDocument> documents = <String, AnimusDocument>{};
  final List<LibraryEntry> npcs = <LibraryEntry>[];
  final List<LibraryEntry> routes = <LibraryEntry>[];
  final List<LibraryEntry> recordings = <LibraryEntry>[];

  void add(DocumentKind kind, String id, Object? json, {bool loaded = true, Map<String, Object?>? summary}) {
    final DocumentSummary fields = summary == null ? summarizeDocument(kind, id, json) : DocumentSummary(summary);
    final LibraryEntry entry = LibraryEntry(kind: kind, id: id, path: '${kind.wireName}/$id.json', name: fields.name ?? id, summary: fields, loaded: loaded);
    switch (kind) {
      case DocumentKind.npc:
        npcs.add(entry);
      case DocumentKind.route:
        routes.add(entry);
      default:
        recordings.add(entry);
    }
    if (loaded) documents['${kind.wireName}:$id'] = AnimusDocument(kind: kind, id: id, path: entry.path, source: '$json', json: json, diagnostics: const <Diagnostic>[]);
  }

  AnimusDocument? find(DocumentKind kind, String id) => documents['${kind.wireName}:$id'];

  List<LibraryRow> routeList() => routeRows(workspaceId: 'ws', routes: routes, npcs: npcs, document: find);
}

void main() {
  test('route documents and NPCs with a route of their own are listed together', () {
    final Library library = Library()
      ..add(DocumentKind.route, 'market-round', <String, Object?>{'points': <Object?>[<String, Object?>{'x': 1, 'y': 64, 'z': 1}, <String, Object?>{'x': 2, 'y': 64, 'z': 1}]})
      ..add(DocumentKind.npc, 'harbor/porter', npc('Porter', navigation: <String, Object?>{'route': 'market-round'}))
      ..add(DocumentKind.npc, 'town/watchman', npc('Watchman', navigation: ownPoints))
      ..add(DocumentKind.npc, 'town/baker', npc('Baker'));
    final List<LibraryRow> routes = library.routeList();
    expect(<String>[for (final LibraryRow row in routes) row.title], <String>['market-round', "Watchman's route"]);
    final LibraryRow shared = routes.first;
    expect(shared.meta, '2 points · Used by Porter');
    expect(shared.tags, contains(sharedRoutesFilter.id));
    expect(shared.href, '#/workspace/ws/route/market-round');
    final LibraryRow own = routes.last;
    expect(own.href, '#/workspace/ws/npc/town%2Fwatchman?tab=movement', reason: 'an NPC\'s own route opens on its Movement tab');
    expect(own.meta, '3 points · world');
    expect(own.tags, contains(ownRoutesFilter.id));
    expect(own.badges, isEmpty, reason: 'its title names the NPC; a badge on every own route repeats it');
    expect(ownRoutesFilter.label, 'On NPCs', reason: "the page's line says on NPCs too");
    expect(own.key, isNot(shared.key));
  });

  test('NPCs not read yet are listed from their summary, as the nav counts them', () {
    final Library library = Library()
      ..add(DocumentKind.npc, 'town/walker', null, loaded: false, summary: <String, Object?>{'name': 'Walker', 'world': 'world', 'points': 4, 'features': <String>['route']})
      ..add(DocumentKind.npc, 'town/porter', null, loaded: false, summary: <String, Object?>{'name': 'Porter', 'features': <String>['route']})
      ..add(DocumentKind.npc, 'town/rambler', null, loaded: false, summary: <String, Object?>{'name': 'Rambler', 'features': <String>['combat']})
      ..add(DocumentKind.npc, 'town/watchman', npc('Watchman', navigation: ownPoints));
    final List<LibraryRow> routes = library.routeList();
    expect(<String>[for (final LibraryRow row in routes) row.title], <String>["Walker's route", "Watchman's route"]);
    expect(routes.first.meta, '4 points · world');
  });

  test('an NPC following a named route or a recording has no route of its own', () {
    final Library library = Library()
      ..add(DocumentKind.npc, 'town/guide', npc('Guide', navigation: <String, Object?>{...ownPoints, 'route': 'market-round'}))
      ..add(DocumentKind.npc, 'town/actor', npc('Actor', navigation: <String, Object?>{...ownPoints, 'recording': 'market-loop'}));
    expect(library.routeList(), isEmpty);
  });

  test('a route used by no read NPC says nothing about users', () {
    final Library library = Library()..add(DocumentKind.route, 'spare', <String, Object?>{'points': <Object?>[]});
    expect(library.routeList().single.meta, '0 points');
  });

  test('recordings show their length, world and the NPCs that play them', () {
    final Library library = Library()
      ..add(DocumentKind.recording, 'market-loop', <String, Object?>{'durationTicks': 80, 'world': 'world', 'description': 'Market walk'})
      ..add(DocumentKind.npc, 'town/stall-keeper', npc('Stall Keeper', navigation: <String, Object?>{'recording': 'market-loop'}));
    final List<LibraryRow> rows = recordingRows(workspaceId: 'ws', recordings: library.recordings, npcs: library.npcs, document: library.find);
    expect(rows.single.title, 'Market walk');
    expect(rows.single.meta, '4 s · world · Used by Stall Keeper');
    expect(rows.single.href, '#/workspace/ws/recording/market-loop');
  });

  test('500 NPCs and their routes build quickly', () {
    final Library library = Library();
    for (int index = 0; index < 500; index++) {
      library.add(DocumentKind.npc, 'world/npc-$index', npc('Citizen $index', navigation: index % 5 == 1 ? ownPoints : null), loaded: index < 400,
          summary: index < 400 ? null : <String, Object?>{'name': 'Citizen $index', if (index % 5 == 1) 'points': 3, 'features': index % 5 == 1 ? <String>['route'] : <String>[]});
    }
    final Stopwatch watch = Stopwatch()..start();
    final List<LibraryRow> routes = library.routeList();
    watch.stop();
    expect(routes.length, 100);
    expect(watch.elapsedMilliseconds, lessThan(200));
  });
}
