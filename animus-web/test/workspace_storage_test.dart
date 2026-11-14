import 'dart:async';
import 'dart:convert';
import 'dart:io';
import 'dart:typed_data';

import 'package:animus_editor/logic/gui/gui_json.dart';
import 'package:animus_editor/logic/shared_schemas.dart';
import 'package:animus_editor/model/animus_document.dart';
import 'package:animus_editor/model/document_kind.dart';
import 'package:animus_editor/model/server_binding.dart';
import 'package:animus_editor/state/editor_store.dart';
import 'package:animus_editor/state/workspace_coordination.dart';
import 'package:animus_editor/state/workspace_migration.dart';
import 'package:animus_editor/state/workspace_records.dart';
import 'package:animus_editor/state/workspace_repository.dart';
import 'package:test/test.dart';

import 'fixtures.dart';

/// Memory records that note every write and read.
class CountingRecords implements WorkspaceRecords {
  final MemoryWorkspaceRecords inner = MemoryWorkspaceRecords();
  final List<RecordWrite> writes = <RecordWrite>[];

  /// `write` as each write starts, beside what a test adds.
  final List<String> log = <String>[];

  /// Holds every write that starts until it completes.
  Completer<void>? gate;
  final List<Map<String, Object?>> steps = <Map<String, Object?>>[];
  int wholeReads = 0;
  int partialReads = 0;

  @override
  Future<List<Map<String, Object?>>> headers() => inner.headers();

  @override
  Future<StoredWorkspace?> read(String id) {
    wholeReads++;
    return inner.read(id);
  }

  @override
  Future<StoredWorkspace?> readSome(String id, Iterable<String> keys, {bool resources = false}) {
    partialReads++;
    return inner.readSome(id, keys, resources: resources);
  }

  @override
  Future<void> write(String id, int expected, RecordWrite write) async {
    log.add('write');
    final Completer<void>? held = gate;
    if (held != null) await held.future;
    await inner.write(id, expected, write);
    writes.add(write);
  }

  @override
  Future<void> remove(String id) => inner.remove(id);

  @override
  Future<List<Map<String, Object?>>> history(String id, HistoryStack stack) => inner.history(id, stack);

  @override
  Future<void> pushHistory(String id, HistoryStack stack, Map<String, Object?> step, int limit) {
    steps.add(step);
    return inner.pushHistory(id, stack, step, limit);
  }

  @override
  Future<void> replaceTop(String id, HistoryStack stack, Map<String, Object?> step) {
    steps.add(step);
    return inner.replaceTop(id, stack, step);
  }

  @override
  Future<void> popHistory(String id, HistoryStack stack) => inner.popHistory(id, stack);

  @override
  Future<void> clearHistory(String id, HistoryStack stack) => inner.clearHistory(id, stack);
}

Map<String, Object?> npc(String id, String name) => <String, Object?>{
      'schemaVersion': 2,
      'revision': 1,
      'id': id.split('/').last,
      'group': id.split('/').first,
      'name': name,
      'type': 'minecraft:player',
    };

AnimusDocument document(DocumentKind kind, String id, String path, Object? json) =>
    AnimusDocument(kind: kind, id: id, path: path, source: prettyJson(json), json: json, diagnostics: const <Diagnostic>[]);

AnimusDocument npcDocument(String id, String name) => document(DocumentKind.npc, id, 'npcs/$id.json', npc(id, name));

/// A workspace as an older editor stored it: one record of JSON text.
String oldWorkspace(List<AnimusDocument> documents, {int version = 3, Map<String, Uint8List> resources = const <String, Uint8List>{}}) =>
    jsonEncode(<String, Object?>{
      'schemaVersion': version,
      'id': 'ws-old',
      'name': 'Old',
      'generation': 4,
      'documents': <Object?>[for (final AnimusDocument document in documents) document.toJson()],
      'resources': <String, Object?>{for (final MapEntry<String, Uint8List> entry in resources.entries) entry.key: base64Encode(entry.value)},
    });

/// An older editor's undo or redo step: the whole workspace [text].
Map<String, Object?> oldStep(String? label, String text) => <String, Object?>{'label': ?label, 'text': text};

/// Memory records that refuse history pushes once [room] runs out.
class FullHistoryRecords extends MemoryWorkspaceRecords {
  int? room;

  @override
  Future<void> pushHistory(String id, HistoryStack stack, Map<String, Object?> step, int limit) async {
    if (room == 0) throw const WorkspaceWriteFailure('QuotaExceededError', quota: true);
    if (room != null) room = room! - 1;
    await super.pushHistory(id, stack, step, limit);
  }
}

/// [into] (new memory records by default) holding the old workspace [text]
/// and its old steps, oldest first.
Future<MemoryWorkspaceRecords> oldRecords(String text,
    {List<Map<String, Object?>> undo = const <Map<String, Object?>>[],
    List<Map<String, Object?>> redo = const <Map<String, Object?>>[],
    MemoryWorkspaceRecords? into}) async {
  final MemoryWorkspaceRecords old = into ?? MemoryWorkspaceRecords();
  await old.write('ws-old', 0, RecordWrite(header: <String, Object?>{'id': 'ws-old', 'generation': 4, 'text': text}));
  for (final Map<String, Object?> step in undo) {
    await old.pushHistory('ws-old', HistoryStack.undo, step, 100);
  }
  for (final Map<String, Object?> step in redo) {
    await old.pushHistory('ws-old', HistoryStack.redo, step, 100);
  }
  return old;
}

/// [workspace]'s documents and resources, sorted, to compare contents.
List<String> contents(WorkspaceSnapshot workspace) => <String>[
      for (final AnimusDocument document in workspace.documents) '${documentKey(document.kind, document.id)} ${document.path} ${document.source}',
      for (final MapEntry<String, Uint8List> entry in workspace.resources.entries) '${entry.key} ${base64Encode(entry.value)}',
    ]..sort();

void main() {
  late CountingRecords records;
  late WorkspaceRepository repository;
  late EditorStore store;

  setUp(() {
    records = CountingRecords();
    repository = WorkspaceRepository(records);
    store = EditorStore(repository: repository, schemas: SharedSchemas.embedded, newId: () => 'ws-1');
  });

  Future<void> edit(String id, String name) => store.commit(
        documents: <DocumentChange>[DocumentChange.update(DocumentKind.npc, id, (Object? json) => <String, Object?>{...json! as Map<String, Object?>, 'name': name})],
        coalesce: 'name:$id',
      );

  test('should write the header and one document record for a one-document commit', () async {
    await store.openFiles('Export', fixtureFiles('export'));
    records.writes.clear();
    await edit('examples/greeter', 'Greeter two');
    expect(records.writes, hasLength(1));
    expect(records.writes.single.replace, isFalse);
    expect(records.writes.single.documents.keys, <String>['npc:examples/greeter']);
    expect(records.writes.single.resources, isEmpty);
    expect(records.writes.single.header['generation'], 2);
  });

  test('should store an undo step with only the documents it changed and undo from it', () async {
    await store.openFiles('Export', fixtureFiles('export'));
    final String before = store.document(DocumentKind.npc, 'examples/greeter')!.source;
    await edit('examples/greeter', 'Greeter two');
    expect(((records.steps.last['documents'])! as Map<String, Object?>).keys, <String>['npc:examples/greeter']);
    await store.undo();
    expect(store.document(DocumentKind.npc, 'examples/greeter')!.source, before);
    expect(records.writes.last.documents.keys, <String>['npc:examples/greeter'], reason: 'undo writes the one document back');
    final EditorStore reloaded = EditorStore(repository: repository, schemas: SharedSchemas.embedded);
    await reloaded.openWorkspace('ws-1');
    expect((await reloaded.redo())?.label, 'edit Greeter');
    expect((reloaded.document(DocumentKind.npc, 'examples/greeter')!.json! as Map<String, Object?>)['name'], 'Greeter two');
  });

  test('should keep typing one undo step when a server refresh lands in between', () async {
    await store.openFiles('Export', fixtureFiles('export'));
    await edit('examples/greeter', 'G');
    await store.commit(
      documents: <DocumentChange>[DocumentChange(DocumentKind.npc, 'examples/shopkeeper', prettyJson(npc('examples/shopkeeper', 'Refreshed')))],
      undoable: false,
    );
    await edit('examples/greeter', 'Gr');
    await store.undo();
    expect((store.document(DocumentKind.npc, 'examples/greeter')!.json! as Map<String, Object?>)['name'], 'Greeter');
    expect((store.document(DocumentKind.npc, 'examples/shopkeeper')!.json! as Map<String, Object?>)['name'], 'Refreshed',
        reason: 'undo puts back only what the typing changed');
    expect(store.canUndo, isFalse);
  });

  test('should widen a coalesced step that reaches one more document', () async {
    await store.openFiles('Export', fixtureFiles('export'));
    await store.commit(documents: <DocumentChange>[DocumentChange.update(DocumentKind.npc, 'examples/greeter', (Object? json) => <String, Object?>{...json! as Map<String, Object?>, 'name': 'A'})], coalesce: 'run');
    await store.commit(documents: <DocumentChange>[DocumentChange.update(DocumentKind.npc, 'examples/shopkeeper', (Object? json) => <String, Object?>{...json! as Map<String, Object?>, 'name': 'B'})], coalesce: 'run');
    await store.undo();
    expect((store.document(DocumentKind.npc, 'examples/greeter')!.json! as Map<String, Object?>)['name'], 'Greeter');
    expect((store.document(DocumentKind.npc, 'examples/shopkeeper')!.json! as Map<String, Object?>)['name'], isNot('B'));
    expect(store.canUndo, isFalse);
  });

  test('should start storing a keystroke before listeners hear of it, and tell them once when it is stored', () async {
    await store.openFiles('Export', fixtureFiles('export'));
    store.addListener(() => records.log.add('heard'));
    records.log.clear();
    final Future<void> typed = edit('examples/greeter', 'Greeter two');
    expect(records.log, isEmpty, reason: 'the input handler itself does no work');
    await typed;
    expect(records.log, <String>['write', 'heard'], reason: 'the screen redraws once, after the write started');
  });

  test('should show a keystroke whose write is slow after a short wait, then again once stored', () async {
    await store.openFiles('Export', fixtureFiles('export'));
    int heard = 0;
    store.addListener(() => heard++);
    final Completer<void> slow = records.gate = Completer<void>();
    final Future<void> typed = edit('examples/greeter', 'Greeter two');
    await Future<void>.delayed(EditorStore.showDelay ~/ 2);
    expect(heard, 0);
    await Future<void>.delayed(EditorStore.showDelay);
    expect(heard, 1, reason: 'a slow write does not hold the screen back');
    expect((store.shown(DocumentKind.npc, 'examples/greeter')!.json! as Map<String, Object?>)['name'], 'Greeter two');
    slow.complete();
    await typed;
    expect(heard, 2);
  });

  test('should store keystrokes typed while a write is out as one write and one undo step', () async {
    await store.openFiles('Export', fixtureFiles('export'));
    final Completer<void> slow = records.gate = Completer<void>();
    records.writes.clear();
    records.log.clear();
    final Future<void> first = edit('examples/greeter', 'G');
    while (!records.log.contains('write')) {
      await Future<void>.delayed(Duration.zero);
    }
    records.gate = null;
    final Future<void> second = edit('examples/greeter', 'Gr');
    // The save bar asks for settled() on every change; that must not split the run.
    final Future<void> settled = store.settled();
    final List<Future<void>> rest = <Future<void>>[second, edit('examples/greeter', 'Gre'), edit('examples/greeter', 'Gree'), settled];
    expect((store.shown(DocumentKind.npc, 'examples/greeter')!.json! as Map<String, Object?>)['name'], 'Gree', reason: 'queued keystrokes show at once');
    slow.complete();
    await Future.wait(<Future<void>>[first, ...rest]);
    expect(records.writes, hasLength(2), reason: 'the keystrokes queued behind the first write share the next one');
    expect((store.document(DocumentKind.npc, 'examples/greeter')!.json! as Map<String, Object?>)['name'], 'Gree');
    expect(store.undoLabel, 'edit Greeter');
    await store.undo();
    expect((store.document(DocumentKind.npc, 'examples/greeter')!.json! as Map<String, Object?>)['name'], 'Greeter');
    expect(store.canUndo, isFalse);
    final EditorStore reloaded = EditorStore(repository: repository, schemas: SharedSchemas.embedded);
    await reloaded.openWorkspace('ws-1');
    expect((reloaded.document(DocumentKind.npc, 'examples/greeter')!.json! as Map<String, Object?>)['name'], 'Greeter');
  });

  test('should keep a server base source only while the document differs from it', () async {
    final Map<String, Object?> guard = npc('town/guard', 'Guard');
    final ServerBase base = ServerBase(revision: 1, hash: 'sha256:aa', path: 'npcs/town/guard.json', source: guard);
    await store.openServerWorkspace('server-s', 'Town', const ServerBinding(serverId: 's', fingerprint: 'sha256:00'));
    await store.commit(
      documents: <DocumentChange>[DocumentChange(DocumentKind.npc, 'town/guard', prettyJson(guard), path: base.path)],
      server: (ServerBinding binding) => binding.copyWith(bases: <String, ServerBase>{'npc:town/guard': base}),
      undoable: false,
    );
    final Map<String, Object?> clean = records.writes.last.documents['npc:town/guard']!;
    expect((clean['base']! as Map<String, Object?>).containsKey('source'), isFalse);
    await edit('town/guard', 'Captain');
    final Map<String, Object?> edited = records.writes.last.documents['npc:town/guard']!;
    expect((edited['base']! as Map<String, Object?>)['source'], guard);
    final WorkspaceSnapshot loaded = (await repository.load('server-s'))!;
    expect(loaded.server!.bases['npc:town/guard']!.source, guard);
    expect((loaded.document(DocumentKind.npc, 'town/guard')!.json! as Map<String, Object?>)['name'], 'Captain');
  });

  test('should move a workspace an older editor stored as one record onto split records', () async {
    final Map<String, Object?> fixture = jsonDecode(File('test/fixtures/workspace_storage/indexeddb_v2.json').readAsStringSync()) as Map<String, Object?>;
    final Map<String, Object?> record = fixture['workspace']! as Map<String, Object?>;
    final MemoryWorkspaceRecords old = MemoryWorkspaceRecords();
    await old.write('server-sha256:ab', 0, RecordWrite(header: record));
    for (final Object? step in fixture['undo']! as List<Object?>) {
      await old.pushHistory('server-sha256:ab', HistoryStack.undo, step! as Map<String, Object?>, 50);
    }
    final WorkspaceRepository upgraded = WorkspaceRepository(old);
    final List<WorkspaceSummary> listed = await upgraded.list();
    expect(listed.single.name, 'Town server');
    expect(listed.single.documentCount, 2);
    expect(listed.single.serverId, 'sha256:ab');
    final Map<String, Object?> header = (await old.headers()).single;
    expect(header.containsKey('text'), isFalse, reason: 'listing moved it onto split records');
    expect(header['generation'], 7, reason: 'the move keeps the generation, so other tabs see no change');
    final WorkspaceSnapshot loaded = (await upgraded.load('server-sha256:ab'))!;
    expect(loaded.documents.map((AnimusDocument d) => d.id), <String>['town/baker', 'town/guard']);
    expect(loaded.server!.bases.keys, unorderedEquals(<String>['npc:town/guard', 'npc:town/baker', 'npc:town/smith']));
    expect((loaded.server!.bases['npc:town/guard']!.source! as Map<String, Object?>)['name'], '&6Guard');
    expect((loaded.server!.bases['npc:town/smith']!.source! as Map<String, Object?>)['name'], 'Smith', reason: 'deleted here: the base keeps its source');
    expect(loaded.server!.pending.single.idempotencyKey, 'AAAAAAAAAAAAAAAAAAAAAAAA');
    expect(loaded.server!.history.single.changeId, 'chg_00000001');
    expect(loaded.resources.keys, <String>['pack/assets/animus/textures/item/a.png']);
    final StoredWorkspace stored = (await old.read('server-sha256:ab'))!;
    expect((stored.documents['npc:town/baker']!['base']! as Map<String, Object?>).containsKey('source'), isFalse,
        reason: 'an unchanged document is its own base');
    final List<HistoryStep> undo = await upgraded.loadHistory('server-sha256:ab', HistoryStack.undo);
    expect(undo.map((HistoryStep step) => step.label), <String>['delete town/smith', 'rename town/guard']);
    expect(undo.first.documents.keys, <String>['npc:town/smith']);
    expect(undo.first.resources, <String, Uint8List?>{'pack/assets/animus/textures/item/a.png': null});
    expect(undo.last.documents.keys, <String>['npc:town/guard'], reason: 'a converted step holds only the documents that differ');
    expect(undo.last.resources, isEmpty);
    expect((await old.history('server-sha256:ab', HistoryStack.undo)).where(isLegacyStep), isEmpty, reason: 'the steps are converted once, in place');
    final EditorStore reopened = EditorStore(repository: upgraded, schemas: SharedSchemas.embedded);
    await reopened.openWorkspace('server-sha256:ab');
    for (final Object? step in (fixture['undo']! as List<Object?>).reversed) {
      final Map<String, Object?> oldStep = step! as Map<String, Object?>;
      expect((await reopened.undo())?.label, oldStep['label']);
      expect(contents(reopened.workspace!), contents(decodeLegacyWorkspace(oldStep['text']! as String)));
    }
    expect(reopened.canUndo, isFalse);
  });

  test('should convert an old redo stack so redo gives back each old workspace', () async {
    const String texture = 'pack/assets/animus/textures/item/a.png';
    final List<Map<String, Object?>> redo = <Map<String, Object?>>[
      oldStep('rename town/a', oldWorkspace(<AnimusDocument>[npcDocument('town/a', 'Two'), npcDocument('town/b', 'B')],
          resources: <String, Uint8List>{texture: Uint8List.fromList(<int>[9, 9])})),
      oldStep(null, oldWorkspace(<AnimusDocument>[npcDocument('town/a', 'One')], resources: <String, Uint8List>{texture: Uint8List.fromList(<int>[1, 2])})),
    ];
    final WorkspaceRepository upgraded = WorkspaceRepository(await oldRecords(oldWorkspace(<AnimusDocument>[npcDocument('town/a', 'Zero')]), redo: redo));
    final EditorStore reopened = EditorStore(repository: upgraded, schemas: SharedSchemas.embedded);
    await reopened.openWorkspace('ws-old');
    expect(reopened.canUndo, isFalse);
    for (final Map<String, Object?> step in redo.reversed) {
      expect((await reopened.redo())?.label, step['label'] ?? 'an earlier change');
      expect(contents(reopened.workspace!), contents(decodeLegacyWorkspace(step['text']! as String)));
    }
    expect(reopened.canRedo, isFalse);
  });

  test('should keep the newest 50 old undo steps', () async {
    List<AnimusDocument> named(String name) => <AnimusDocument>[npcDocument('town/a', name), npcDocument('town/b', 'Same')];
    final List<Map<String, Object?>> undo = <Map<String, Object?>>[
      for (int i = 0; i < 60; i++) oldStep('rename $i', oldWorkspace(named('Name $i'))),
    ];
    final WorkspaceRepository upgraded = WorkspaceRepository(await oldRecords(oldWorkspace(named('Now')), undo: undo));
    final EditorStore reopened = EditorStore(repository: upgraded, schemas: SharedSchemas.embedded);
    await reopened.openWorkspace('ws-old');
    final List<HistoryStep> steps = await upgraded.loadHistory('ws-old', HistoryStack.undo);
    expect(steps, hasLength(EditorStore.undoLimit));
    expect(steps.first.label, 'rename 10');
    expect(steps.every((HistoryStep step) => step.documents.keys.single == 'npc:town/a'), isTrue);
    for (int i = 59; i >= 10; i--) {
      await reopened.undo();
      expect((reopened.document(DocumentKind.npc, 'town/a')!.json! as Map<String, Object?>)['name'], 'Name $i');
    }
    expect(reopened.canUndo, isFalse);
  });

  test('should convert only the newest 10 old steps when the newest 50 are too large', () {
    final List<Map<String, Object?>> undo = <Map<String, Object?>>[
      for (int i = 0; i < 20; i++) oldStep('rename $i', oldWorkspace(<AnimusDocument>[npcDocument('town/a', 'Name $i')])),
    ];
    final WorkspaceSnapshot current = decodeLegacyWorkspace(oldWorkspace(<AnimusDocument>[npcDocument('town/a', 'Now')]));
    expect(convertLegacyHistory(undo, current, limit: 50), hasLength(20));
    final List<HistoryStep> kept = convertLegacyHistory(undo, current, limit: 50, budget: 1000);
    expect(kept.map((HistoryStep step) => step.label), <String>[for (int i = 10; i < 20; i++) 'rename $i']);
    expect((kept.first.documents['npc:town/a']!.json! as Map<String, Object?>)['name'], 'Name 10');
  });

  test('should stop converting old steps at one that does not decode', () {
    final WorkspaceSnapshot current = decodeLegacyWorkspace(oldWorkspace(<AnimusDocument>[npcDocument('town/a', 'Now')]));
    final List<HistoryStep> steps = convertLegacyHistory(<Map<String, Object?>>[
      oldStep('rename 0', oldWorkspace(<AnimusDocument>[npcDocument('town/a', 'Name 0')])),
      oldStep('broken', '{"schemaVersion": 9}'),
      oldStep('rename 2', oldWorkspace(<AnimusDocument>[npcDocument('town/a', 'Name 2')])),
    ], current, limit: 50);
    expect(steps.map((HistoryStep step) => step.label), <String>['rename 2']);
  });

  test('should drop an old undo stack that fails to write back part way and still list the workspace', () async {
    final FullHistoryRecords full = FullHistoryRecords();
    await oldRecords(oldWorkspace(<AnimusDocument>[npcDocument('town/a', 'Now')]),
        undo: <Map<String, Object?>>[
          oldStep('rename 0', oldWorkspace(<AnimusDocument>[npcDocument('town/a', 'Name 0')])),
          oldStep('rename 1', oldWorkspace(<AnimusDocument>[npcDocument('town/a', 'Name 1')])),
        ],
        into: full);
    full.room = 1;
    expect((await WorkspaceRepository(full).list()).single.name, 'Old');
    expect(await full.history('ws-old', HistoryStack.undo), isEmpty, reason: 'a partly converted stack would undo to the wrong workspace');
  });

  test('should move hologram ids in a version 1 undo step as the workspace moved them', () async {
    AnimusDocument hologram(String id, String path, String line) =>
        document(DocumentKind.hologram, id, path, <String, Object?>{'lines': <String>[line]});
    final String before = oldWorkspace(<AnimusDocument>[
      hologram('spawn', 'gui/holograms/spawn.json', 'Spawn'),
      hologram('town/welcome', 'gui/holograms/town/welcome.json', 'Welcome'),
    ], version: 1);
    final String now = oldWorkspace(<AnimusDocument>[
      hologram('gloss:spawn', 'gui/holograms/gloss/spawn.json', 'Spawn'),
      hologram('town:welcome', 'gui/holograms/town/welcome.json', 'Hello'),
    ]);
    final WorkspaceRepository upgraded = WorkspaceRepository(await oldRecords(now, undo: <Map<String, Object?>>[oldStep('edit town/welcome', before)]));
    final EditorStore reopened = EditorStore(repository: upgraded, schemas: SharedSchemas.embedded);
    await reopened.openWorkspace('ws-old');
    final HistoryStep step = (await upgraded.loadHistory('ws-old', HistoryStack.undo)).single;
    expect(step.documents.keys, <String>['hologram:town:welcome'], reason: 'the moved spawn hologram is unchanged');
    expect(step.documents.values.single!.id, 'town:welcome');
    await reopened.undo();
    expect(contents(reopened.workspace!), contents(decodeLegacyWorkspace(before)));
  });

  test('should follow another tab by reading only the documents its save wrote', () async {
    final SharedWorkspaceEvents tabs = SharedWorkspaceEvents();
    final EditorStore first = EditorStore(repository: repository, schemas: SharedSchemas.embedded, newId: () => 'ws-shared', events: tabs.tab());
    final EditorStore second = EditorStore(repository: repository, schemas: SharedSchemas.embedded, events: tabs.tab());
    await first.openFiles('Export', fixtureFiles('export'));
    await second.openWorkspace('ws-shared');
    final int wholeReads = records.wholeReads;
    await first.commit(documents: <DocumentChange>[DocumentChange.update(DocumentKind.npc, 'examples/greeter', (Object? json) => <String, Object?>{...json! as Map<String, Object?>, 'name': 'Other tab'})]);
    await pumpEventQueue();
    expect((second.document(DocumentKind.npc, 'examples/greeter')!.json! as Map<String, Object?>)['name'], 'Other tab');
    expect(second.workspace!.generation, first.workspace!.generation);
    expect(records.partialReads, 1);
    expect(records.wholeReads, wholeReads, reason: 'nothing else was read');
    expect(identical(second.document(DocumentKind.npc, 'examples/shopkeeper'), second.workspace!.byKey['npc:examples/shopkeeper']), isTrue);
  });

  test('should hand the workspace to a tab that takes it over', () async {
    final SharedWorkspaceEvents tabs = SharedWorkspaceEvents();
    final SharedWorkspaceLocks locks = SharedWorkspaceLocks();
    final EditorStore first = EditorStore(repository: repository, schemas: SharedSchemas.embedded, newId: () => 'ws-owned', events: tabs.tab(), locks: locks.tab());
    final EditorStore second = EditorStore(repository: repository, schemas: SharedSchemas.embedded, events: tabs.tab(), locks: locks.tab());
    await first.openFiles('Export', fixtureFiles('export'));
    await second.openWorkspace('ws-owned');
    expect(second.heldByAnotherTab, isTrue);
    second.takeOver();
    await pumpEventQueue();
    expect(second.ownedByThisTab, isTrue);
    expect(first.ownedByThisTab, isFalse);
    expect(first.heldByAnotherTab, isTrue, reason: 'the first tab now waits');
  });
}
