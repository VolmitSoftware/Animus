// One name per document across the list, the heading, Recent and Search: an
// NPC named only with colour codes goes by its id, a behavior by the words of
// its id, a skin by its label or the NPCs that wear it, never its hash.
import 'package:animus_editor/logic/gui/gui_json.dart';
import 'package:animus_editor/logic/shared_schemas.dart';
import 'package:animus_editor/logic/skin_label.dart';
import 'package:animus_editor/model/document_kind.dart';
import 'package:animus_editor/state/editor_store.dart';
import 'package:animus_editor/state/workspace_repository.dart';
import 'package:test/test.dart';

void main() {
  const String hash = '39f699d629ca5b0d7e2c4a1f8e3b6d9c0a7f5e2b1c4d8e6f3a9b7c5d2e1f0a8b';

  test('should name a skin by its label, then its wearers, then a short hash', () {
    expect(skinLabel(hash, label: 'Guard coat'), 'Guard coat');
    expect(skinLabel(hash, label: hash), 'Unused skin');
    expect(skinLabel(hash, wornBy: const <String>['Baker']), 'Worn by Baker');
    expect(skinLabel(hash, wornBy: const <String>['Baker', 'Smith', 'Guard']), 'Worn by Baker and 2 more');
    expect(skinLabel(hash), 'Unused skin');
  });

  test('should give the library one plain name for odd real data', () async {
    final EditorStore store = EditorStore(repository: MemoryWorkspaceRepository(), schemas: SharedSchemas.embedded, newId: () => 'ws-names');
    await store.createWorkspace('Names');
    Map<String, Object?> npc(String id, String name, {String? skin}) => <String, Object?>{
          'schemaVersion': 1,
          'id': id.split('/').last,
          'group': id.split('/').first,
          'name': name,
          'type': 'minecraft:player',
          'spawned': true,
          'position': <String, Object?>{'world': 'world', 'x': 1.5, 'y': 64, 'z': 2.5},
          'skin': ?skin,
        };
    await store.commit(documents: <DocumentChange>[
      DocumentChange(DocumentKind.npc, 'world/citizens-60', prettyJson(npc('world/citizens-60', '&f', skin: hash))),
      DocumentChange(DocumentKind.npc, 'town/baker', prettyJson(npc('town/baker', '&6Baker', skin: hash))),
      DocumentChange(DocumentKind.skin, hash, prettyJson(<String, Object?>{'textureRaw': 'e30=', 'signature': 'AA=='})),
      DocumentChange(DocumentKind.behavior, 'denizen:world/anica_world', prettyJson(<String, Object?>{'schemaVersion': 1, 'id': 'denizen:world/anica_world', 'entry': 'main', 'routines': <String, Object?>{}})),
    ]);
    expect(store.libraryEntry(DocumentKind.npc, 'world/citizens-60')!.name, 'Unnamed NPC');
    expect(store.libraryEntry(DocumentKind.npc, 'town/baker')!.name, 'Baker');
    expect(store.libraryEntry(DocumentKind.behavior, 'denizen:world/anica_world')!.name, 'Anica world');
    expect(store.libraryEntry(DocumentKind.skin, hash)!.name, 'Worn by Baker and 1 more');
    await store.commit(documents: <DocumentChange>[DocumentChange(DocumentKind.npc, 'town/baker', prettyJson(npc('town/baker', 'Smith', skin: hash)))]);
    expect(store.libraryEntry(DocumentKind.skin, hash)!.name, 'Worn by Smith and 1 more');
  });

  test('should count NPCs with their own route for Movement', () async {
    final EditorStore store = EditorStore(repository: MemoryWorkspaceRepository(), schemas: SharedSchemas.embedded, newId: () => 'ws-routes');
    await store.createWorkspace('Routes');
    Map<String, Object?> walker(String id, {bool own = true}) => <String, Object?>{
          'schemaVersion': 1,
          'id': id,
          'group': 'town',
          'name': id,
          'type': 'minecraft:player',
          'spawned': true,
          'position': <String, Object?>{'world': 'world', 'x': 1.5, 'y': 64, 'z': 2.5},
          'modules': <String, Object?>{
            'animus:navigation': own
                ? <String, Object?>{'enabled': true, 'points': <Object?>[<String, Object?>{'x': 1, 'y': 64, 'z': 1}, <String, Object?>{'x': 4, 'y': 64, 'z': 1}]}
                : <String, Object?>{'enabled': true, 'route': 'town:loop'},
          },
        };
    await store.commit(documents: <DocumentChange>[
      for (final String id in <String>['a', 'b', 'c']) DocumentChange(DocumentKind.npc, 'town/$id', prettyJson(walker(id))),
      DocumentChange(DocumentKind.npc, 'town/d', prettyJson(walker('d', own: false))),
    ]);
    expect(store.libraryOwnRoutes(), 3);
    await store.commit(documents: <DocumentChange>[DocumentChange(DocumentKind.npc, 'town/a', prettyJson(walker('a', own: false)))]);
    expect(store.libraryOwnRoutes(), 2);
  });
}
