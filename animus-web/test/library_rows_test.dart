// List rows from the library: a shop's line names its offers and who opens
// it, and the rows of a kind come back as the same list until that kind (or,
// for shops, the NPCs) changes.
import 'package:animus_editor/logic/gui/gui_json.dart';
import 'package:animus_editor/logic/shared_schemas.dart';
import 'package:animus_editor/model/document_kind.dart';
import 'package:animus_editor/state/editor_store.dart';
import 'package:animus_editor/state/workspace_repository.dart';
import 'package:animus_editor/ui/library_query.dart';
import 'package:animus_editor/ui/library_rows.dart';
import 'package:test/test.dart';

Map<String, Object?> npc(String id, String name, {String? shop}) => <String, Object?>{
      'schemaVersion': 1,
      'id': id.split('/').last,
      'group': id.split('/').first,
      'name': name,
      'type': 'minecraft:player',
      if (shop != null) 'modules': <String, Object?>{'animus:shop': <String, Object?>{'shop': shop}},
    };

void main() {
  late EditorStore store;

  setUp(() async {
    store = EditorStore(repository: MemoryWorkspaceRepository(), schemas: SharedSchemas.embedded, newId: () => 'ws-rows');
    await store.createWorkspace('Rows');
    await store.commit(documents: <DocumentChange>[
      DocumentChange(DocumentKind.npc, 'town/baker', prettyJson(npc('town/baker', '&6Baker', shop: 'town:bakery'))),
      DocumentChange(DocumentKind.npc, 'town/guard', prettyJson(npc('town/guard', 'Guard'))),
      DocumentChange(DocumentKind.shop, 'town:bakery', prettyJson(<String, Object?>{
        'schemaVersion': 1,
        'id': 'bakery',
        'title': '&6Bakery',
        'pages': <Object?>[
          <String, Object?>{
            'buy': <String, Object?>{
              '0': <String, Object?>{'id': 'bread'},
              '1': <String, Object?>{'id': 'cake'},
            },
          },
        ],
      })),
    ]);
  });

  test('should line a shop with its offers and the NPC that opens it', () {
    final LibraryRow row = libraryRowsFor(store, DocumentKind.shop).single;
    expect(row.title, 'Bakery');
    expect(row.meta, '2 offers, opened by Baker');
  });

  test('should hand back the same rows until that kind changes', () async {
    final List<LibraryRow> shops = libraryRowsFor(store, DocumentKind.shop);
    final List<LibraryRow> npcs = libraryRowsFor(store, DocumentKind.npc);
    expect(identical(libraryRowsFor(store, DocumentKind.shop), shops), isTrue);

    await store.commit(documents: <DocumentChange>[
      DocumentChange.update(DocumentKind.npc, 'town/guard', (Object? json) => <String, Object?>{...json! as Map<String, Object?>, 'name': 'Watch'}),
    ]);
    expect(identical(libraryRowsFor(store, DocumentKind.npc), npcs), isFalse);
    expect(libraryRowsFor(store, DocumentKind.npc).map((LibraryRow row) => row.title), contains('Watch'));

    await store.commit(documents: <DocumentChange>[
      DocumentChange.update(DocumentKind.npc, 'town/baker', (Object? json) => <String, Object?>{...json! as Map<String, Object?>, 'name': 'Bea'}),
    ]);
    expect(libraryRowsFor(store, DocumentKind.shop).single.meta, '2 offers, opened by Bea', reason: 'a shop line follows the NPC that opens it');
  });

  test('should call a colour-only NPC Unnamed NPC told apart by where it stands, found by its id first', () async {
    await store.commit(documents: <DocumentChange>[
      DocumentChange(DocumentKind.npc, 'world/citizens-60', prettyJson(<String, Object?>{
        ...npc('world/citizens-60', '&f'),
        'position': <String, Object?>{'world': 'world', 'x': 12.5, 'y': 64, 'z': -2.5},
      })),
      DocumentChange(DocumentKind.npc, 'world/citizens-600', prettyJson(npc('world/citizens-600', 'Asian town 5'))),
    ]);
    final List<LibraryRow> rows = libraryRowsFor(store, DocumentKind.npc);
    final LibraryRow blank = rows.firstWhere((LibraryRow row) => row.key.endsWith('citizens-60'));
    expect(blank.title, 'Unnamed NPC');
    expect(blank.meta, 'Player, world at 12, 64, -3');
    expect(blank.advancedMeta, 'world/citizens-60', reason: 'Advanced shows the id');
    expect(queryRows(rows, search: 'citizens-60').first.key, blank.key, reason: 'the exact id comes before citizens-600');
  });

  test('should name a pixel-art hologram from its id, with a lines line', () async {
    await store.commit(documents: <DocumentChange>[
      DocumentChange(DocumentKind.hologram, 'citizens:art-1', prettyJson(<String, Object?>{
        'schemaVersion': 3,
        'anchor': <String, Object?>{'world': 'world', 'position': <num>[0, 64, 0]},
        'lines': <String>[for (int row = 0; row < 16; row++) '[FF0000]\u2588\u2588[00FF00]\u2588\u2588'],
      })),
    ]);
    final LibraryRow row = libraryRowsFor(store, DocumentKind.hologram).single;
    expect(row.title, 'Art 1');
    expect(row.meta, '16 lines, world');
  });
}
