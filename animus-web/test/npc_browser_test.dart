import 'dart:convert';
import 'dart:io';
import 'dart:typed_data';

import 'package:animus_editor/components/npc/npc_list_rows.dart';
import 'package:animus_editor/logic/data_folder.dart';
import 'package:animus_editor/logic/gui/gui_json.dart';
import 'package:animus_editor/logic/npc/npc_bulk.dart';
import 'package:animus_editor/logic/npc/npc_catalog.dart';
import 'package:animus_editor/logic/npc/npc_paths.dart';
import 'package:animus_editor/logic/npc/route_model.dart';
import 'package:animus_editor/logic/shared_schemas.dart';
import 'package:animus_editor/model/animus_document.dart';
import 'package:animus_editor/model/document_kind.dart';
import 'package:animus_editor/model/document_summary.dart';
import 'package:animus_editor/state/editor_store.dart';
import 'package:animus_editor/state/library.dart';
import 'package:animus_editor/state/workspace_repository.dart';
import 'package:animus_editor/ui/library_query.dart';
import 'package:animus_editor/ui/library_rows.dart';
import 'package:test/test.dart';

Map<String, Uint8List> _folder(String name) {
  final Directory directory = Directory('test/fixtures/$name');
  return <String, Uint8List>{
    for (final File file in directory.listSync(recursive: true).whereType<File>())
      file.path.substring(directory.path.length + 1).replaceAll(r'\', '/'): file.readAsBytesSync(),
  };
}

List<AnimusDocument> _documents() => openDataFolder(_folder('npc_browser'), SharedSchemas.embedded).documents;

List<NpcEntry> _entries() => npcEntries(_documents());

NpcEntry _entry(String key) => _entries().firstWhere((NpcEntry entry) => entry.key == key);

List<String> _keys(Iterable<NpcEntry> entries) => <String>[for (final NpcEntry entry in entries) entry.key];

JsonMap _json(DocumentChange change) => asMap(jsonDecode(change.source!));

Set<String> _taken() => <String>{for (final NpcEntry entry in _entries()) entry.key};

/// The fixture opened as a local workspace, for its library.
Future<EditorStore> _store() async {
  final EditorStore store = EditorStore(repository: MemoryWorkspaceRepository(), schemas: SharedSchemas.embedded, newId: () => 'ws-npc');
  await store.openFiles('npc browser', _folder('npc_browser'));
  return store;
}

Future<List<NpcListItem>> _items() async => npcListItems('ws-npc', (await _store()).library(DocumentKind.npc));

List<String> _rowIds(List<LibraryRow> rows) => <String>[for (final LibraryRow row in rows) row.key.substring('npc:'.length)];

/// A catalog entry of a server NPC not read yet: its summary only.
LibraryEntry _unread(String id, Map<String, Object?> summary) => LibraryEntry(
      kind: DocumentKind.npc,
      id: id,
      path: 'npcs/$id.json',
      name: plainName(summary['name']) ?? id,
      summary: DocumentSummary(<String, Object?>{...summary, 'name': plainName(summary['name'])}),
      loaded: false,
    );

void main() {
  group('fixture', () {
    test('every npc_browser document passes the shared schemas', () {
      for (final AnimusDocument document in _documents()) {
        expect(document.diagnostics.where((Diagnostic d) => d.severity == DiagnosticSeverity.error), isEmpty, reason: '${document.kind.wireName}:${document.id}');
      }
    });

    test('tags outside the bounds are refused by the schema', () {
      final AnimusDocument document = _documents().firstWhere((AnimusDocument d) => d.id == 'town/baker');
      final JsonMap json = <String, Object?>{...asMap(document.json), 'tags': <String>['Merchant', 'merchant', 'merchant']};
      final AnimusDocument edited = validateSource(DocumentLocation(DocumentKind.npc, document.id), document.path, prettyJson(json), SharedSchemas.embedded);
      expect(edited.valid, isFalse);
    });
  });

  group('catalog', () {
    test('entries read folder, file name and tags', () {
      final NpcEntry guard = _entry('town/guard-north');
      expect(guard.folder, 'town');
      expect(guard.localId, 'guard-north');
      expect(guard.tags, <String>['guard', 'patrol']);
      expect(_entry('town/market/stalls/cartwright').folder, 'town/market/stalls');
      expect(_entry('town/market/stalls/cartwright').localId, 'cartwright');
      expect(_entry('crier').folder, '');
    });

    test('entries sort by folder then name', () {
      expect(_keys(_entries()), <String>[
        'crier',
        'harbor/lamplighter',
        'harbor/dockhand',
        'harbor/ferryman',
        'town/baker',
        'town/guard-north',
        'town/guard-south',
        'town/market/fishmonger',
        'town/market/stalls/cartwright',
        'wild/golem-keeper',
      ]);
    });

    test('npcTags counts each tag, most used first', () {
      expect(npcTags(_entries()).first, ('guard', 2));
      expect(npcTags(_entries()).map(((String, int) tag) => tag.$1), containsAll(<String>['herald', 'merchant', 'patrol', 'quest']));
    });
  });

  group('list rows', () {
    test('every NPC is a row with its plain name, body and world, read from summaries', () async {
      final List<NpcListItem> items = await _items();
      expect(items, hasLength(10));
      final LibraryRow baker = items.firstWhere((NpcListItem item) => item.id == 'town/baker').row;
      expect(baker.title, 'Baker');
      expect(baker.meta, 'Player, world');
      expect(baker.href, '#/workspace/ws-npc/npc/town%2Fbaker');
      final LibraryRow dockhand = items.firstWhere((NpcListItem item) => item.id == 'harbor/dockhand').row;
      expect(dockhand.meta, 'Armor stand, harbor');
      expect(dockhand.badges.map((LibraryBadge badge) => badge.label), contains('Despawned'));
      expect(dockhand.tags, contains(despawnedFilter.id));
    });

    test('an NPC named only with a colour code is Unnamed NPC told apart by where it stands, and still opens', () async {
      final NpcListItem blank = (await _items()).firstWhere((NpcListItem item) => item.id == 'harbor/lamplighter');
      expect(blank.row.title, 'Unnamed NPC');
      expect(blank.row.href, '#/workspace/ws-npc/npc/harbor%2Flamplighter');
      expect(blank.row.meta, 'Villager, harbor at 30, 64, 6');
      expect(blank.row.advancedMeta, startsWith('npcs/harbor/lamplighter'), reason: 'Advanced keeps the path');
    });

    test('the Advanced line holds the file path and tags', () async {
      final List<NpcListItem> items = await _items();
      expect(items.firstWhere((NpcListItem item) => item.id == 'town/guard-north').row.advancedMeta, 'npcs/town/guard-north · guard, patrol');
      expect(items.firstWhere((NpcListItem item) => item.id == 'crier').row.advancedMeta, 'npcs/crier · herald');
      expect(items.firstWhere((NpcListItem item) => item.id == 'harbor/dockhand').row.advancedMeta, 'npcs/harbor/dockhand');
    });

    test('rows of server NPCs not read yet come from their catalog summaries', () {
      final List<NpcListItem> items = npcListItems('server-x', <LibraryEntry>[
        _unread('world/citizens-12', <String, Object?>{'name': '&f', 'type': 'minecraft:iron_golem', 'world': 'spawn', 'spawned': false, 'folder': 'world', 'tags': <String>['gate']}),
        _unread('world/citizens-7', <String, Object?>{'name': '&6Baker', 'type': 'minecraft:player', 'world': 'spawn', 'folder': 'world'}),
      ]);
      expect(items[0].row.title, 'world/citizens-12');
      expect(items[0].row.meta, 'Iron golem, spawn');
      expect(items[0].row.badges.map((LibraryBadge badge) => badge.label), contains('Despawned'));
      expect(items[0].folder, 'world');
      expect(items[0].tags, <String>['gate']);
      expect(items[1].row.title, 'Baker');
    });

    test('a face is asked for each row with the entry it shows', () async {
      final List<String> asked = <String>[];
      npcListItems('ws-npc', (await _store()).library(DocumentKind.npc), face: (LibraryEntry entry) {
        asked.add(entry.id);
        return null;
      });
      expect(asked, hasLength(10));
    });

    test('folder counts hold each folder with its subfolders, in tree order', () async {
      expect(npcFolderCounts(await _items()), <(String, int)>[('harbor', 3), ('town', 5), ('town/market', 2), ('town/market/stalls', 1), ('wild', 1)]);
      expect(folderLabel(''), 'npcs/');
      expect(folderLabel('town/market'), 'npcs/town/market/');
    });

    test('a folder filter shows the folder and its subfolders', () async {
      final List<NpcListItem> items = await _items();
      expect(_rowIds(npcRows(items, const NpcListFilter(folder: 'town/market'))), unorderedEquals(<String>['town/market/fishmonger', 'town/market/stalls/cartwright']));
      expect(_rowIds(npcRows(items, const NpcListFilter(folder: 'wild'))), <String>['wild/golem-keeper']);
      expect(npcRows(items, const NpcListFilter()), hasLength(10));
    });

    test('body, world and tag filters narrow together; tags match any picked', () async {
      final List<NpcListItem> items = await _items();
      expect(_rowIds(npcRows(items, const NpcListFilter(body: 'minecraft:armor_stand'))), unorderedEquals(<String>['harbor/dockhand', 'wild/golem-keeper']));
      expect(_rowIds(npcRows(items, const NpcListFilter(body: 'minecraft:armor_stand', world: 'harbor'))), <String>['harbor/dockhand']);
      expect(_rowIds(npcRows(items, const NpcListFilter(tags: <String>{'guard', 'quest'}))), unorderedEquals(<String>['town/guard-north', 'town/guard-south', 'harbor/ferryman']));
      expect(npcRows(items, const NpcListFilter(folder: 'harbor', tags: <String>{'guard'})), isEmpty);
    });

    test('filtered rows are the same list until the rows or the filter change', () async {
      final List<NpcListItem> items = await _items();
      final NpcRowCache cache = NpcRowCache();
      final List<LibraryRow> first = cache.rows(items, const NpcListFilter(world: 'harbor'));
      expect(identical(cache.rows(items, const NpcListFilter(world: 'harbor')), first), isTrue);
      expect(identical(cache.rows(items, const NpcListFilter(world: 'world')), first), isFalse);
      expect(identical(cache.rows(<NpcListItem>[...items], const NpcListFilter(world: 'world')), first), isFalse);
    });

    test('bodies, worlds and top tags come from the summaries', () async {
      final List<NpcListItem> items = await _items();
      expect(npcBodies(items), <String>['minecraft:armor_stand', 'minecraft:player', 'minecraft:villager']);
      expect(npcWorlds(items), <String>['harbor', 'world']);
      expect(npcTopTags(items).first, ('guard', 2));
      expect(npcTopTags(items, limit: 2), hasLength(2));
      expect(mostUsedWorld(items), 'world');
      expect(mostUsedWorld(const <NpcListItem>[]), 'world');
    });

    test('New NPC offers a player body first', () {
      expect(commonBodies.first, 'minecraft:player');
    });

    test('filtering 1500 NPCs stays well under a frame', () {
      final List<LibraryEntry> entries = <LibraryEntry>[
        for (int index = 0; index < 1500; index++)
          _unread('f${index % 12}/sub${index % 3}/npc-$index', <String, Object?>{
            'name': 'NPC $index',
            'type': index.isEven ? 'minecraft:player' : 'minecraft:villager',
            'world': 'w${index % 4}',
            'folder': 'f${index % 12}/sub${index % 3}',
            'tags': <String>['t${index % 20}'],
          }),
      ];
      final List<NpcListItem> items = npcListItems('ws', entries);
      final Stopwatch watch = Stopwatch()..start();
      for (int round = 0; round < 20; round++) {
        npcRows(items, NpcListFilter(folder: 'f${round % 12}', world: 'w${round % 4}', tags: <String>{'t${round % 20}'}));
        npcFolderCounts(items);
      }
      expect(watch.elapsedMilliseconds / 20, lessThan(16));
    });
  });

  group('bulk', () {
    test('addTag skips NPCs that have it and refuses a bad tag', () {
      final List<NpcEntry> selected = <NpcEntry>[_entry('town/guard-north'), _entry('town/guard-south'), _entry('town/baker')];
      final NpcBulkResult result = addTag(selected, 'patrol');
      expect(result.changed, 2);
      expect(result.skipped.keys, <String>['town/guard-north']);
      expect(_json(result.changes.first)['tags'], <String>['guard', 'patrol']);
      expect(addTag(selected, 'Bad Tag').changes, isEmpty);
      expect(addTag(selected, 'Bad Tag').skipped, hasLength(3));
    });

    test('addTag stops at the tag limit', () {
      final AnimusDocument document = _documents().firstWhere((AnimusDocument d) => d.id == 'town/baker');
      final JsonMap json = <String, Object?>{...asMap(document.json), 'tags': <String>[for (int index = 0; index < npcTagLimit; index++) 't$index']};
      final NpcEntry full = NpcEntry.of(document.copyWith(json: json));
      expect(addTag(<NpcEntry>[full], 'one-more').skipped.values.single, contains('$npcTagLimit'));
    });

    test('removeTag drops an emptied tags list', () {
      final NpcBulkResult result = removeTag(<NpcEntry>[_entry('town/baker'), _entry('harbor/dockhand')], 'merchant');
      expect(result.changed, 1);
      expect(_json(result.changes.single).containsKey('tags'), isFalse);
    });

    test('bulk edits keep every key they do not change, byte for byte', () {
      String without(JsonMap json, Set<String> keys) => prettyJson(<String, Object?>{
            for (final MapEntry<String, Object?> entry in json.entries)
              if (!keys.contains(entry.key)) entry.key: entry.value,
          });
      final NpcEntry guard = _entry('town/guard-north');
      expect(without(_json(addTag(<NpcEntry>[guard], 'night').changes.single), <String>{'tags'}), without(guard.json, <String>{'tags'}));
      expect(without(_json(setSpawned(<NpcEntry>[guard], false).changes.single), <String>{'spawned'}), without(guard.json, <String>{'spawned'}));
      expect(without(_json(moveToFolder(<NpcEntry>[guard], 'watch', _taken()).changes.last), <String>{'group'}), without(guard.json, <String>{'group'}));
      final JsonMap off = _json(setModuleEnabled(<NpcEntry>[guard], 'animus:combat', false).changes.single);
      expect(without(off, <String>{'modules'}), without(guard.json, <String>{'modules'}));
      expect(prettyJson(asMap(off['modules'])['animus:navigation']), prettyJson(guard.modules['animus:navigation']));
    });

    test('renameNpcFolder moves the whole subtree in one edit', () {
      final NpcFolderEdit edit = renameNpcFolder(_entries(), 'town/market', 'bazaar', _taken());
      expect(edit.problem, isNull);
      expect(edit.moved, 2);
      expect(<String?>[for (final DocumentChange change in edit.changes) change.source == null ? '-${change.id}' : '+${change.id}'],
          <String>['-town/market/fishmonger', '+bazaar/fishmonger', '-town/market/stalls/cartwright', '+bazaar/stalls/cartwright']);
      expect(_json(edit.changes[3])['group'], 'bazaar/stalls');
      expect(edit.changes[3].path, 'npcs/bazaar/stalls/cartwright.json');
      expect(_json(edit.changes[3])['uuid'], _entry('town/market/stalls/cartwright').json['uuid']);
    });

    test('renameNpcFolder needs only the NPCs inside the folder', () {
      final List<NpcEntry> inside = <NpcEntry>[_entry('wild/golem-keeper')];
      expect(renameNpcFolder(inside, 'wild', 'forest', _taken()).changes.last.id, 'forest/golem-keeper');
      expect(renameNpcFolder(inside, 'wild', 'harbor', <String>{..._taken(), 'harbor/golem-keeper'}).problem, 'harbor/golem-keeper already exists.');
    });

    test('renameNpcFolder puts a folder under another one, or at the top', () {
      final NpcFolderEdit under = renameNpcFolder(_entries(), 'town/market', 'harbor/market', _taken());
      expect(under.changes.where((DocumentChange change) => change.source != null).map((DocumentChange change) => change.id),
          <String>['harbor/market/fishmonger', 'harbor/market/stalls/cartwright']);
      expect(renameNpcFolder(_entries(), 'town/market/stalls', 'stalls', _taken()).changes.last.id, 'stalls/cartwright');
    });

    test('folder edits refuse what the server refuses, before anything is written', () {
      expect(renameNpcFolder(_entries(), 'town', 'town/market/x', _taken()).problem, 'npcs/town/ cannot move into itself.');
      expect(renameNpcFolder(_entries(), 'town', 'Town', _taken()).problem, 'npcs/Town/: $npcFolderRule.');
      expect(renameNpcFolder(_entries(), '', 'x', _taken()).problem, isNotNull);
      expect(renameNpcFolder(_entries(), 'town', 'a/b/c/d/e/f/g', _taken()).problem, contains('town/market/stalls/cartwright would land in npcs/a/b/c/d/e/f/g/market/stalls/'));
      expect(renameNpcFolder(_entries(), 'wild', 'harbor', _taken()).problem, isNull);
      expect(renameNpcFolder(_entries(), 'wild', 'wild', _taken()).problem, isNotNull);
    });

    test('folder rules follow NpcPaths', () {
      expect(npcFolderProblem(''), isNull);
      expect(npcFolderProblem('a/b/c/d/e/f/g/h'), isNull);
      expect(npcFolderProblem('a/b/c/d/e/f/g/h/i'), isNotNull);
      expect(npcFolderProblem('-town'), isNotNull);
      expect(npcFolderProblem('town//market'), isNotNull);
      expect(normalizeNpcFolder(' NPCS/Town/Market/ '), 'town/market');
      expect(npcIdIn('', 'crier'), 'crier');
      expect(npcFolderWithin('town/market', 'town'), isTrue);
      expect(npcFolderWithin('townhall', 'town'), isFalse);
      expect(<String>['town/market', 'town-hall', '', 'town']..sort(compareNpcFolders), <String>['', 'town', 'town/market', 'town-hall']);
    });

    test('moveToFolder rewrites group and path and refuses collisions', () {
      final Set<String> taken = <String>{..._taken(), 'harbor/baker'};
      final NpcBulkResult result = moveToFolder(<NpcEntry>[_entry('town/guard-north'), _entry('town/baker'), _entry('harbor/ferryman')], 'harbor', taken);
      expect(result.changed, 1);
      expect(result.skipped.keys, unorderedEquals(<String>['town/baker', 'harbor/ferryman']));
      expect(result.changes.first.source, isNull);
      expect(result.changes.first.id, 'town/guard-north');
      expect(result.changes.last.id, 'harbor/guard-north');
      expect(result.changes.last.path, 'npcs/harbor/guard-north.json');
      expect(_json(result.changes.last)['group'], 'harbor');
      expect(moveToFolder(<NpcEntry>[_entry('town/baker')], 'Bad/Folder', taken).changes, isEmpty);
      final NpcBulkResult nested = moveToFolder(<NpcEntry>[_entry('crier')], 'town/market/stalls', taken);
      expect(nested.changes.last.path, 'npcs/town/market/stalls/crier.json');
      expect(_json(nested.changes.last)['group'], 'town/market/stalls');
      final NpcBulkResult top = moveToFolder(<NpcEntry>[_entry('town/baker')], '', taken);
      expect(top.changes.last.path, 'npcs/baker.json');
      expect(_json(top.changes.last)['group'], '');
    });

    test('two NPCs with one id cannot move into one folder', () {
      final AnimusDocument document = _documents().firstWhere((AnimusDocument d) => d.id == 'town/baker');
      final NpcEntry twin = NpcEntry.of(AnimusDocument(kind: DocumentKind.npc, id: 'harbor/baker', path: 'npcs/harbor/baker.json', source: document.source, json: document.json, diagnostics: const <Diagnostic>[]));
      final NpcBulkResult result = moveToFolder(<NpcEntry>[_entry('town/baker'), twin], 'wild', <String>{'town/baker', 'harbor/baker'});
      expect(result.changed, 1);
      expect(result.skipped.keys, <String>['harbor/baker']);
    });

    test('setModuleEnabled switches present modules and creates only standalone ones', () {
      final List<NpcEntry> selected = <NpcEntry>[_entry('town/guard-north'), _entry('town/baker')];
      final NpcBulkResult off = setModuleEnabled(selected, 'animus:combat', false);
      expect(off.changed, 1);
      expect(asMap(asMap(_json(off.changes.single)['modules'])['animus:combat'])['enabled'], isFalse);
      expect(asMap(asMap(_json(off.changes.single)['modules'])['animus:combat'])['melee'], isNotNull, reason: 'settings are kept');
      final NpcBulkResult on = setModuleEnabled(selected, 'animus:combat', true);
      expect(on.changed, 1);
      expect(asMap(_json(on.changes.single)['modules'])['animus:combat'], <String, Object?>{'enabled': true});
      expect(asMap(_json(on.changes.single)['modules'])['animus:shop'], isNotNull);
      final NpcBulkResult navigation = setModuleEnabled(<NpcEntry>[_entry('town/baker')], 'animus:navigation', true);
      expect(navigation.changes, isEmpty);
      expect(navigation.skipped.values.single, contains('configure'));
    });

    test('removeModule drops an emptied modules object', () {
      final NpcBulkResult result = removeModule(<NpcEntry>[_entry('town/guard-south')], 'animus:combat');
      expect(asMap(_json(result.changes.single)['modules']).keys, <String>['animus:health', 'animus:navigation']);
      final NpcBulkResult both = removeModule(<NpcEntry>[NpcEntry.of(_documents().firstWhere((AnimusDocument d) => d.id == 'town/baker'))], 'animus:shop');
      expect(asMap(_json(both.changes.single)['modules']).keys, <String>['animus:look-close']);
    });

    test('selectionModules lists the modules the picked NPCs have, switchable ones first', () {
      expect(selectionModules(<NpcEntry>[_entry('town/guard-south'), _entry('town/baker')]),
          <String>['animus:combat', 'animus:navigation', 'animus:look-close', 'animus:health', 'animus:shop']);
      expect(selectionModules(<NpcEntry>[_entry('crier')]), isEmpty);
    });

    test('setSpawned and deleteNpcs', () {
      final NpcBulkResult spawned = setSpawned(<NpcEntry>[_entry('harbor/dockhand'), _entry('harbor/ferryman')], true);
      expect(spawned.changed, 1);
      expect(_json(spawned.changes.single)['spawned'], isTrue);
      final NpcBulkResult deleted = deleteNpcs(<NpcEntry>[_entry('harbor/dockhand')]);
      expect(deleted.changed, 1);
      expect(deleted.changes.single.source, isNull);
    });

    test('describeBulk names changed and skipped counts', () {
      expect(describeBulk('Tagged', addTag(<NpcEntry>[_entry('town/baker')], 'shopkeeper')), 'Tagged 1 NPC.');
      expect(describeBulk('Tagged', addTag(<NpcEntry>[_entry('town/guard-north')], 'guard')), startsWith('Nothing to change; skipped 1'));
    });

    test('one bulk change set is one undo step', () async {
      final EditorStore store = await _store();
      final List<NpcEntry> all = npcEntries(store.workspace!.documents);
      await store.commit(documents: addTag(all, 'reviewed').changes);
      await store.commit(documents: moveToFolder(<NpcEntry>[npcEntries(store.workspace!.documents).firstWhere((NpcEntry e) => e.key == 'wild/golem-keeper')], 'town', <String>{for (final NpcEntry e in all) e.key}).changes);
      expect(store.document(DocumentKind.npc, 'town/golem-keeper'), isNotNull);
      expect(store.document(DocumentKind.npc, 'town/golem-keeper')!.valid, isTrue);
      expect(store.document(DocumentKind.npc, 'wild/golem-keeper'), isNull);
      await store.undo();
      expect(store.document(DocumentKind.npc, 'wild/golem-keeper'), isNotNull);
      expect(npcEntries(store.workspace!.documents).every((NpcEntry e) => e.tags.contains('reviewed')), isTrue);
      await store.undo();
      expect(npcEntries(store.workspace!.documents).any((NpcEntry e) => e.tags.contains('reviewed')), isFalse);
    });
  });

  group('new NPC', () {
    test('newNpcId proposes a free file name from the name', () {
      expect(newNpcId('Town Crier', '', _taken()), 'town-crier');
      expect(newNpcId('&6Baker', 'town', _taken()), 'baker-2');
      expect(newNpcId('', 'town', _taken()), 'npc');
    });

    test('newNpcProblem names what keeps an NPC from being made', () {
      expect(newNpcProblem('baker', 'town', _taken()), 'npcs/town/baker already exists.');
      expect(newNpcProblem('Bad Id', 'town', _taken()), isNotNull);
      expect(newNpcProblem('cook', 'Bad Folder', _taken()), isNotNull);
      expect(newNpcProblem('cook', 'town/kitchen', _taken()), isNull);
    });
  });

  group('routes', () {
    JsonMap navigation() => asMap(_entry('town/guard-north').modules[navigationModule]);

    test('sequence links follow the order and close a loop', () {
      final RouteSpec spec = RouteSpec(navigation());
      expect(spec.points, hasLength(4));
      expect(spec.points[1].waitTicks, const RouteWait(60, 60));
      expect(spec.links, <(int, int)>[(0, 1), (1, 2), (2, 3), (3, 0)]);
      expect(RouteSpec(<String, Object?>{...navigation(), 'loop': false}).links, hasLength(3));
      expect(RouteSpec(<String, Object?>{...navigation(), 'order': 'pingpong'}).links, hasLength(3));
    });

    test('random links points and guides within linkDistance', () {
      final AnimusDocument route = _documents().firstWhere((AnimusDocument d) => d.kind == DocumentKind.route);
      final RouteSpec spec = RouteSpec(asMap(route.json));
      expect(spec.nodes, hasLength(4));
      expect(spec.nodes.last.guide, isTrue);
      expect(spec.links, unorderedEquals(<(int, int)>[(0, 1), (1, 2), (0, 3), (1, 3), (2, 3)]));
      expect(spec.problems(), isEmpty);
    });

    test('problems name the field the runtime refuses', () {
      final JsonMap wrong = <String, Object?>{'order': 'sequence', 'guides': <Object?>[<String, Object?>{'x': 0, 'y': 64, 'z': 0}], 'points': <Object?>[<String, Object?>{'x': 0, 'y': 64.5, 'z': 0}]};
      final List<String> fields = <String>[for (final (String field, String _) in RouteSpec(wrong).problems()) field];
      expect(fields, containsAll(<String>['guides', 'points[0].y']));
      expect(RouteSpec(const <String, Object?>{'order': 'sequence'}).problems().single.$1, 'points');
      expect(RouteSpec(const <String, Object?>{'order': 'wander'}).problems(), isEmpty);
    });

    test('edits add, move, remove and reorder nodes', () {
      JsonMap route = navigation();
      route = addRouteNode(route, 20, 64, 0);
      expect(asList(route['points']), hasLength(5));
      route = moveRouteNode(route, 'points', 4, 21.5, -3);
      expect(asList(route['points']).last, <String, Object?>{'x': 21.5, 'y': 64, 'z': -3});
      route = moveRouteNode(route, 'points', 1, 13, -7);
      expect(asMap(asList(route['points'])[1])['waitTicks'], 60, reason: 'other fields are kept');
      route = reorderRoutePoint(route, 4, 0);
      expect(asMap(asList(route['points']).first)['x'], 21.5);
      route = removeRouteNode(route, 'points', 0);
      expect(asList(route['points']), hasLength(4));
      route = addRouteNode(route, 1, 64, 1, guide: true);
      expect(asList(route['guides']), hasLength(1));
      expect(removeRouteNode(route, 'guides', 0).containsKey('guides'), isFalse);
    });

    test('setRouteOrder removes fields the order refuses', () {
      final JsonMap random = setRouteOrder(<String, Object?>{'loop': true, 'wander': <String, Object?>{'radius': 4}, 'points': <Object?>[]}, 'random');
      expect(random.containsKey('loop'), isFalse);
      expect(random.containsKey('wander'), isFalse);
      final JsonMap sequence = setRouteOrder(<String, Object?>{'guides': <Object?>[], 'linkDistance': 8}, 'sequence');
      expect(sequence.keys, <String>['order']);
    });

    test('bounds cover nodes and the NPC with a square and a sensible step', () {
      final RouteSpec spec = RouteSpec(navigation());
      final RouteBounds bounds = RouteBounds.around(spec.placed, extra: <(double, double)>[(4.5, -6.5)]);
      expect(bounds.minX <= 4 && bounds.maxX >= 12 && bounds.minZ <= -6.5 && bounds.maxZ >= 4, isTrue);
      expect(bounds.width, bounds.depth);
      expect(bounds.step, 1);
      expect(RouteBounds.around(const <RouteNode>[RouteNode(0, 0, 0), RouteNode(300, 0, 300)]).step, greaterThan(8));
    });
  });
}
