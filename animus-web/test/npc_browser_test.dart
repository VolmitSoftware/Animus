import 'dart:convert';
import 'dart:io';
import 'dart:typed_data';

import 'package:animus_editor/logic/data_folder.dart';
import 'package:animus_editor/logic/gui/gui_json.dart';
import 'package:animus_editor/logic/npc/npc_bulk.dart';
import 'package:animus_editor/logic/npc/npc_catalog.dart';
import 'package:animus_editor/logic/npc/npc_paths.dart';
import 'package:animus_editor/logic/npc/route_model.dart';
import 'package:animus_editor/logic/shared_schemas.dart';
import 'package:animus_editor/model/animus_document.dart';
import 'package:animus_editor/model/document_kind.dart';
import 'package:animus_editor/state/editor_store.dart';
import 'package:test/test.dart';

import 'package:animus_editor/state/workspace_repository.dart';

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
    test('entries read folder, tags, world and features', () {
      final NpcEntry guard = _entry('town/guard-north');
      expect(guard.folder, 'town');
      expect(guard.localId, 'guard-north');
      expect(guard.tags, <String>['guard', 'patrol']);
      expect(guard.world, 'world');
      expect(guard.features, containsAll(<NpcFeature>[NpcFeature.route, NpcFeature.combat]));
      expect(_entry('town/guard-south').has(NpcFeature.route), isTrue, reason: 'a referenced route counts');
      expect(_entry('town/baker').has(NpcFeature.shop), isTrue);
      expect(_entry('wild/golem-keeper').has(NpcFeature.rig), isTrue);
      expect(_entry('harbor/ferryman').has(NpcFeature.skin), isTrue);
      expect(_entry('harbor/dockhand').features, isEmpty);
    });

    test('entries sort by folder then name', () {
      expect(_keys(_entries()), <String>[
        'crier',
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

    test('plain words match name, id, type, world and tags', () {
      expect(_keys(NpcQuery.parse('guard').filter(_entries())), <String>['town/guard-north', 'town/guard-south']);
      expect(_keys(NpcQuery.parse('FERRY').filter(_entries())), <String>['harbor/ferryman']);
      expect(_keys(NpcQuery.parse('harbor').filter(_entries())), <String>['harbor/dockhand', 'harbor/ferryman']);
      expect(_keys(NpcQuery.parse('armor_stand').filter(_entries())), <String>['harbor/dockhand', 'wild/golem-keeper']);
      expect(_keys(NpcQuery.parse('merch').filter(_entries())), <String>['town/baker', 'town/market/fishmonger']);
    });

    test('field terms narrow and a leading dash negates', () {
      expect(_keys(NpcQuery.parse('tag:guard -tag:patrol').filter(_entries())), <String>['town/guard-south']);
      expect(_keys(NpcQuery.parse('folder:harbor is:despawned').filter(_entries())), <String>['harbor/dockhand']);
      expect(_keys(NpcQuery.parse('group:wild').filter(_entries())), <String>['wild/golem-keeper']);
      expect(_keys(NpcQuery.parse('has:combat has:route').filter(_entries())), <String>['town/guard-north', 'town/guard-south']);
      expect(_keys(NpcQuery.parse('type:armor_stand world:harbor').filter(_entries())), <String>['harbor/dockhand']);
      expect(_keys(NpcQuery.parse('has:nothing').filter(_entries())), isEmpty);
      expect(NpcQuery.parse('   ').terms, isEmpty);
    });

    test('toggleTerm adds and removes one term', () {
      expect(toggleTerm('guard', 'has', 'combat'), 'guard has:combat');
      expect(toggleTerm('guard has:combat', 'has', 'combat'), 'guard');
      expect(hasTerm('guard has:combat', 'has', 'combat'), isTrue);
    });

    test('the folder tree nests folders and counts each one and its subtree', () {
      final NpcFolderNode root = npcFolderTree(_entries(), extra: <String>['wild/caves/deep']);
      expect(<String>[for (final NpcFolderNode node in root.all) '${node.path}=${node.own}/${node.total}'], <String>[
        '=1/9',
        'harbor=2/2',
        'town=3/5',
        'town/market=1/2',
        'town/market/stalls=1/1',
        'wild=1/1',
        'wild/caves=0/0',
        'wild/caves/deep=0/0',
      ]);
      final List<NpcFolderRow> rows = npcFolderRows(root, <String>{'town', 'wild/caves'});
      expect(<String>[for (final NpcFolderRow row in rows) '${row.level}:${row.node.path}:${row.parent}'], <String>['1::null', '2:harbor:0', '2:town:0', '2:wild:0', '3:wild/caves:3']);
      expect(rows[2].expanded, isFalse);
      expect(rows[2].expandable, isTrue);
      expect(rows[1].expandable, isFalse);
      expect(npcFolderTree(_entries(), extra: <String>['Bad Folder']).all.map((NpcFolderNode node) => node.path), isNot(contains('Bad Folder')));
      expect(npcTags(_entries()).first, ('guard', 2));
      expect(npcValues(_entries(), (NpcEntry entry) => entry.world), <String>['harbor', 'world']);
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

    test('folder terms match a subtree, or one folder with =', () {
      expect(_keys(NpcQuery.parse('folder:town').filter(_entries())), <String>['town/baker', 'town/guard-north', 'town/guard-south', 'town/market/fishmonger', 'town/market/stalls/cartwright']);
      expect(_keys(NpcQuery.parse('folder:=town/market').filter(_entries())), <String>['town/market/fishmonger']);
      expect(_keys(NpcQuery.parse('folder:=').filter(_entries())), <String>['crier']);
      expect(_keys(NpcQuery.parse('folder:npcs/town/market/').filter(_entries())), <String>['town/market/fishmonger', 'town/market/stalls/cartwright']);
      expect(_keys(NpcQuery.parse('-folder:town').filter(_entries())), isNot(contains('town/baker')));
    });

    test('nested ids read their folder and file name', () {
      expect(_entry('town/market/stalls/cartwright').folder, 'town/market/stalls');
      expect(_entry('town/market/stalls/cartwright').localId, 'cartwright');
      expect(_entry('crier').folder, '');
    });

    test('renameNpcFolder moves the whole subtree in one edit', () {
      final NpcFolderEdit edit = renameNpcFolder(_entries(), 'town/market', 'bazaar');
      expect(edit.problem, isNull);
      expect(edit.moved, 2);
      expect(<String?>[for (final DocumentChange change in edit.changes) change.source == null ? '-${change.id}' : '+${change.id}'],
          <String>['-town/market/fishmonger', '+bazaar/fishmonger', '-town/market/stalls/cartwright', '+bazaar/stalls/cartwright']);
      expect(_json(edit.changes[3])['group'], 'bazaar/stalls');
      expect(edit.changes[3].path, 'npcs/bazaar/stalls/cartwright.json');
      expect(_json(edit.changes[3])['uuid'], _entry('town/market/stalls/cartwright').json['uuid']);
    });

    test('moveNpcFolder puts a folder under another one, or at the top', () {
      final NpcFolderEdit under = moveNpcFolder(_entries(), 'town/market', 'harbor');
      expect(under.changes.where((DocumentChange change) => change.source != null).map((DocumentChange change) => change.id),
          <String>['harbor/market/fishmonger', 'harbor/market/stalls/cartwright']);
      expect(moveNpcFolder(_entries(), 'town/market/stalls', '').changes.last.id, 'stalls/cartwright');
    });

    test('folder edits refuse what the server refuses, before anything is written', () {
      expect(renameNpcFolder(_entries(), 'town', 'town/market/x').problem, 'npcs/town/ cannot move into itself.');
      expect(renameNpcFolder(_entries(), 'town', 'Town').problem, 'npcs/Town/: $npcFolderRule.');
      expect(renameNpcFolder(_entries(), '', 'x').problem, isNotNull);
      expect(renameNpcFolder(_entries(), 'town', 'a/b/c/d/e/f/g').problem, contains('town/market/stalls/cartwright would land in npcs/a/b/c/d/e/f/g/market/stalls/'));
      expect(renameNpcFolder(_entries(), 'wild', 'harbor').problem, isNull);
      final NpcEntry clash = NpcEntry.of(const AnimusDocument(kind: DocumentKind.npc, id: 'harbor/golem-keeper', path: 'npcs/harbor/golem-keeper.json', source: '{}', json: <String, Object?>{}, diagnostics: <Diagnostic>[]));
      expect(renameNpcFolder(<NpcEntry>[..._entries(), clash], 'wild', 'harbor').problem, 'harbor/golem-keeper already exists.');
      expect(renameNpcFolder(_entries(), 'wild', 'wild').problem, isNotNull);
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
      final Set<String> taken = <String>{for (final NpcEntry entry in _entries()) entry.key, 'harbor/baker'};
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
      expect(asMap(_json(result.changes.single)['modules']).keys, <String>['animus:navigation']);
      final NpcBulkResult both = removeModule(<NpcEntry>[NpcEntry.of(_documents().firstWhere((AnimusDocument d) => d.id == 'town/baker'))], 'animus:shop');
      expect(asMap(_json(both.changes.single)['modules']).keys, <String>['animus:look-close']);
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
      final EditorStore store = EditorStore(repository: MemoryWorkspaceRepository(), schemas: SharedSchemas.embedded, newId: () => 'ws-npc');
      await store.openFiles('npc browser', _folder('npc_browser'));
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
