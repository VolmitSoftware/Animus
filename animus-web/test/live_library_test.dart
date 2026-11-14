import 'dart:async';

import 'package:animus_editor/logic/change_sets.dart';
import 'package:animus_editor/logic/gui/gui_json.dart';
import 'package:animus_editor/logic/shared_schemas.dart';
import 'package:animus_editor/model/document_kind.dart';
import 'package:animus_editor/model/server_binding.dart';
import 'package:animus_editor/services/server/page_activity.dart';
import 'package:animus_editor/services/server/server_connection.dart';
import 'package:animus_editor/services/server/transport.dart';
import 'package:animus_editor/state/document_loader.dart';
import 'package:animus_editor/state/editor_store.dart';
import 'package:animus_editor/state/library.dart';
import 'package:animus_editor/state/live_session.dart';
import 'package:animus_editor/state/save_state.dart';
import 'package:animus_editor/state/workspace_repository.dart';
import 'package:test/test.dart';

import 'live_session_test.dart' show MiniServer;
import 'server_connection_test.dart' show Waits, profile, settle, token;
import 'support/fake_transport.dart';

void main() {
  late MiniServer server;
  late FakeNetwork network;
  late Waits waits;
  late ServerConnection connection;
  late EditorStore store;
  late LiveSession session;

  Map<String, Object?> npc(String id, String name) => <String, Object?>{
        'schemaVersion': 1,
        'id': id.split('/').last,
        'group': id.split('/').first,
        'name': name,
        'type': 'minecraft:player',
        'spawned': true,
        'position': <String, Object?>{'world': 'world', 'x': 1.5, 'y': 64, 'z': 2.5},
      };

  /// Retry waits pass at once; reconnect waits are held for the test.
  Future<void> delay(Duration duration) => duration < const Duration(seconds: 1) ? Future<void>.value() : waits.call(duration);

  Future<void> begin({bool fill = false, Duration typingPause = Duration.zero, bool connect = true}) async {
    network = FakeNetwork(serverHandler(rest: (ApiRequest request) => server.handle(request)));
    connection = ServerConnection(profile: profile, token: token, open: network.open, delay: delay);
    store = EditorStore(repository: MemoryWorkspaceRepository(), schemas: SharedSchemas.embedded, newId: () => 'ws-1');
    await store.openServerWorkspace('server-${profile.id}', 'Town', ServerBinding(serverId: profile.id, fingerprint: profile.fingerprint));
    session = LiveSession(store: store, connection: connection, serverId: profile.id, backgroundFill: fill, typingPause: typingPause);
    if (!connect) return;
    session.start();
    await settle();
  }

  setUp(() {
    waits = Waits();
    server = MiniServer();
    for (int index = 1; index <= 12; index++) {
      server.put(DocumentKind.npc, 'town/citizen-$index', npc('town/citizen-$index', index == 3 ? '&f' : '&6Citizen $index'));
    }
    server.put(DocumentKind.shop, 'town:bakery', <String, Object?>{'schemaVersion': 1, 'id': 'bakery', 'title': '<gold>Bakery', 'pages': <Object?>[]});
  });

  tearDown(() async {
    await session.dispose();
    await connection.dispose();
  });

  Future<void> rename(String id, String name) => store.commit(
        documents: <DocumentChange>[DocumentChange.update(DocumentKind.npc, id, (Object? json) => <String, Object?>{...json! as Map<String, Object?>, 'name': name})],
      );

  test('should list every document with its plain name, in reading order, before any is read', () async {
    await begin();
    final List<LibraryEntry> npcs = store.library(DocumentKind.npc);
    expect(npcs, hasLength(12));
    expect(npcs.take(4).map((LibraryEntry entry) => entry.name), <String>['Citizen 1', 'Citizen 2', 'Citizen 4', 'Citizen 5']);
    expect(npcs.last.name, 'Unnamed NPC', reason: 'an NPC with no visible name is Unnamed NPC, its id on its line');
    expect(npcs.every((LibraryEntry entry) => !entry.loaded), isTrue);
    expect(npcs.first.summary.world, 'world');
    expect(store.library(DocumentKind.shop).single.name, 'Bakery');
    expect(store.libraryCounts()[DocumentKind.npc], 12);
    expect(store.workspace!.documents, isEmpty);
    expect(server.batchReads, 0);
  });

  test('should read every page of a long catalog', () async {
    for (int index = 13; index <= 150; index++) {
      server.put(DocumentKind.npc, 'town/citizen-$index', npc('town/citizen-$index', 'Citizen $index'));
    }
    server.pageSize = 2;
    await begin();
    // 76 pages pass the burst of 40, so the pacer spreads them over real time.
    for (int wait = 0; wait < 200 && !session.catalogLoaded; wait++) {
      await Future<void>.delayed(const Duration(milliseconds: 25));
    }
    expect(store.libraryCounts()[DocumentKind.npc], 150, reason: 'no cap at 64 pages');
    expect(session.catalog, hasLength(151));
  });

  test('should read the whole server in batches of at most 200, four at a time, and show one progress line', () async {
    for (int index = 13; index <= 900; index++) {
      server.put(DocumentKind.npc, 'town/citizen-$index', npc('town/citizen-$index', 'Citizen $index'));
    }
    int running = 0;
    int most = 0;
    final List<LoadProgress> progress = <LoadProgress>[];
    await begin(connect: false);
    network.gate = (ConnectionPath path, ApiRequest request) async {
      if (request.path != '/v1/documents/read') return;
      running++;
      if (running > most) most = running;
      await Future<void>.delayed(Duration.zero);
      running--;
    };
    session.addListener(() {
      if (session.loading != null) progress.add(session.loading!);
    });
    session.start();
    await settle();
    final LoadResult result = await session.ensureLoaded(<DocumentRef>[
      for (final LibraryEntry entry in store.library(DocumentKind.npc)) DocumentRef(entry.kind, entry.id),
    ]);
    expect(result.loaded, hasLength(900));
    expect(store.workspace!.documents, hasLength(900));
    expect(server.readSizes.every((int size) => size <= 200), isTrue);
    expect(most, lessThanOrEqualTo(4));
    expect(most, greaterThan(1));
    expect(progress.last.total, 900);
    expect(session.loadReport!.loaded, 900);
    expect(session.loadReport!.failed, isEmpty);
    expect(session.log.where((LiveNotice notice) => notice.message.contains('Loaded')), isEmpty, reason: 'no notice for a complete load');
    expect(store.library(DocumentKind.npc).every((LibraryEntry entry) => entry.loaded), isTrue);
  });

  test('should retry rate-limited reads after the hint and lose no document', () async {
    await begin();
    server.rateLimited = 3;
    final LoadResult result = await session.ensureLoaded(<DocumentRef>[
      for (final LibraryEntry entry in store.library(DocumentKind.npc)) DocumentRef(entry.kind, entry.id),
    ]);
    expect(result.complete, isTrue);
    expect(store.workspace!.documents, hasLength(12));
  });

  test('should report reads that stayed refused once, with a retry, and no notice per document', () async {
    await begin();
    server.rateLimited = 100;
    await session.ensureLoaded(<DocumentRef>[for (final LibraryEntry entry in store.library(DocumentKind.npc)) DocumentRef(entry.kind, entry.id)]);
    expect(session.loadReport!.failed, hasLength(12));
    expect(session.loadReport!.reason!.cause, 'Server is rate limiting this browser.');
    expect(session.log.where((LiveNotice notice) => notice.level == NoticeLevel.warning), hasLength(1));
    server.rateLimited = 0;
    await session.retryFailedLoads();
    expect(store.workspace!.documents, hasLength(12));
  });

  test('should fill the workspace in the background, wait while the user types, and let an opened document jump ahead', () async {
    await begin(fill: true, typingPause: const Duration(hours: 1));
    expect(store.workspace!.documents, hasLength(13));
    await rename('town/citizen-1', 'Typed');
    for (int index = 13; index <= 30; index++) {
      server.put(DocumentKind.npc, 'town/citizen-$index', npc('town/citizen-$index', 'Citizen $index'));
    }
    await session.loadCatalog();
    final int reads = server.batchReads;
    await settle();
    expect(server.batchReads, reads, reason: 'background reads wait while the user types');
    await session.ensureLoaded(const <DocumentRef>[DocumentRef(DocumentKind.npc, 'town/citizen-20')]);
    expect(store.document(DocumentKind.npc, 'town/citizen-20'), isNotNull);
    expect(server.readSizes.last, 1);
  });

  test('should pause live position polling while a bulk load runs', () async {
    await begin(connect: false);
    final Completer<void> hold = Completer<void>();
    network.gate = (ConnectionPath path, ApiRequest request) async {
      if (request.path == '/v1/documents/read') await hold.future;
    };
    session.start();
    await settle();
    final Future<LoadResult> loading = session.ensureLoaded(<DocumentRef>[
      for (final LibraryEntry entry in store.library(DocumentKind.npc)) DocumentRef(entry.kind, entry.id),
    ]);
    await settle();
    expect(PageActivity.quiet, isTrue);
    hold.complete();
    await loading;
    await settle();
    expect(PageActivity.quiet, isFalse);
  });

  test('should mark a document changed here stale the moment the server changes it, before any apply', () async {
    await begin();
    await session.ensureLoaded(const <DocumentRef>[DocumentRef(DocumentKind.npc, 'town/citizen-1'), DocumentRef(DocumentKind.npc, 'town/citizen-2')]);
    await rename('town/citizen-1', 'Mine');
    server
      ..put(DocumentKind.npc, 'town/citizen-1', npc('town/citizen-1', 'Theirs'))
      ..put(DocumentKind.npc, 'town/citizen-2', npc('town/citizen-2', 'Renamed on the server'));
    final int reads = server.batchReads;
    network.lastStream.emit(<String, Object?>{
      'type': 'catalog',
      'documents': <Object?>[server.version(server.documents['npc:town/citizen-1']!), server.version(server.documents['npc:town/citizen-2']!)],
    });
    expect(session.stale, <String>{'npc:town/citizen-1'}, reason: 'known from the hash, before any read');
    expect(store.libraryEntry(DocumentKind.npc, 'town/citizen-1')!.stale, isTrue);
    await settle();
    expect((store.document(DocumentKind.npc, 'town/citizen-2')!.json! as Map<String, Object?>)['name'], 'Renamed on the server');
    expect(store.libraryEntry(DocumentKind.npc, 'town/citizen-2')!.name, 'Renamed on the server');
    expect((store.document(DocumentKind.npc, 'town/citizen-1')!.json! as Map<String, Object?>)['name'], 'Mine');
    expect(server.batchReads, reads + 1);
    expect(session.saveState.phase, SavePhase.changes);
  });

  test('should read a document the server added while the background fill runs', () async {
    await begin(fill: true);
    await settle();
    expect(store.document(DocumentKind.npc, 'town/citizen-1'), isNotNull, reason: 'the first fill read the catalog');
    server.put(DocumentKind.npc, 'town/newcomer', npc('town/newcomer', 'Newcomer'));
    network.lastStream.emit(<String, Object?>{
      'type': 'catalog',
      'documents': <Object?>[server.version(server.documents['npc:town/newcomer']!)],
    });
    await settle();
    expect(store.libraryEntry(DocumentKind.npc, 'town/newcomer')!.loaded, isTrue);
  });

  test('should list a document the server added and drop one it deleted', () async {
    await begin();
    await session.ensureLoaded(const <DocumentRef>[DocumentRef(DocumentKind.npc, 'town/citizen-1'), DocumentRef(DocumentKind.npc, 'town/citizen-2')]);
    await rename('town/citizen-2', 'Mine');
    server.put(DocumentKind.npc, 'town/newcomer', npc('town/newcomer', 'Newcomer'));
    network.lastStream.emit(<String, Object?>{
      'type': 'catalog',
      'documents': <Object?>[server.version(server.documents['npc:town/newcomer']!)],
      'deleted': <Object?>[
        <String, Object?>{'kind': 'npc', 'id': 'town/citizen-1', 'source': 'server'},
        <String, Object?>{'kind': 'npc', 'id': 'town/citizen-2', 'source': 'server'},
      ],
    });
    await settle();
    expect(store.libraryEntry(DocumentKind.npc, 'town/newcomer')!.name, 'Newcomer');
    expect(store.document(DocumentKind.npc, 'town/citizen-1'), isNull);
    expect(store.libraryEntry(DocumentKind.npc, 'town/citizen-1'), isNull);
    expect(store.document(DocumentKind.npc, 'town/citizen-2'), isNotNull, reason: 'a changed copy stays');
    expect(session.stale, contains('npc:town/citizen-2'));
  });

  test('should read a reload burst with one batch read per event', () async {
    await begin();
    await session.ensureLoaded(<DocumentRef>[for (final LibraryEntry entry in store.library(DocumentKind.npc)) DocumentRef(entry.kind, entry.id)]);
    final int reads = server.batchReads;
    for (int index = 1; index <= 12; index++) {
      server.put(DocumentKind.npc, 'town/citizen-$index', npc('town/citizen-$index', 'Reloaded $index'));
    }
    network.lastStream.emit(<String, Object?>{
      'type': 'catalog',
      'documents': <Object?>[for (int index = 1; index <= 12; index++) server.version(server.documents['npc:town/citizen-$index']!)],
    });
    await settle();
    expect(server.batchReads, reads + 1);
    expect(store.library(DocumentKind.npc).where((LibraryEntry entry) => entry.name.startsWith('Reloaded')), hasLength(12));
    expect(session.log.where((LiveNotice notice) => notice.message.startsWith('Updated')), hasLength(1));
  });

  test('should wait for the connection before reading an opened document and show it as loading', () async {
    await begin();
    network.down.addAll(ConnectionPath.values);
    await network.lastStream.end();
    await settle();
    expect(session.online, isFalse);
    final Future<LoadResult> opening = session.ensureLoaded(const <DocumentRef>[DocumentRef(DocumentKind.npc, 'town/citizen-4')]);
    await settle();
    expect(store.libraryEntry(DocumentKind.npc, 'town/citizen-4')!.loading, isTrue);
    expect(session.saveState.phase, SavePhase.offline);
    network.down.clear();
    connection.retryNow();
    final LoadResult result = await opening;
    expect(result.loaded, <String>['npc:town/citizen-4']);
    expect(store.libraryEntry(DocumentKind.npc, 'town/citizen-4')!.loaded, isTrue);
  });

  test('should say what the save bar shows through an apply', () async {
    await begin();
    await session.ensureLoaded(const <DocumentRef>[DocumentRef(DocumentKind.npc, 'town/citizen-1')]);
    expect(saveStateOf(store, session), const SaveState(SavePhase.upToDate));
    await rename('town/citizen-1', 'Captain');
    expect(saveStateOf(store, session), const SaveState(SavePhase.changes, count: 1));
    expect(saveStateOf(store, session).text, '1 change not applied');
    final List<SavePhase> seen = <SavePhase>[];
    session.addListener(() => seen.add(session.saveState.phase));
    expect(await session.apply(), isTrue);
    expect(seen, contains(SavePhase.applying));
    expect(saveStateOf(store, session), const SaveState(SavePhase.applied, count: 1));
    await rename('town/citizen-1', 'Major');
    expect(saveStateOf(store, session).phase, SavePhase.changes);
    server.put(DocumentKind.npc, 'town/citizen-1', npc('town/citizen-1', 'Moved on'));
    expect(await session.apply(), isFalse);
    expect(saveStateOf(store, session).phase, SavePhase.failed);
    expect(saveStateOf(store, session).reason, '1 document changed on the server');
    expect(session.drafts.single.state, DraftState.modified);
  });

  test('should drop Applied and its Undo once that apply is undone', () async {
    await begin();
    await session.ensureLoaded(const <DocumentRef>[DocumentRef(DocumentKind.npc, 'town/citizen-1')]);
    await rename('town/citizen-1', 'Captain');
    expect(await session.apply(), isTrue);
    final String changeId = store.workspace!.server!.history.last.changeId;
    expect(session.undoableChange, changeId);
    await session.undo(changeId);
    expect(saveStateOf(store, session), const SaveState(SavePhase.undone, count: 1));
    expect(saveStateOf(store, session).text, 'Undid 1 change');
    expect(session.undoableChange, isNull);
  });

  test('should forget the last apply when the connection drops', () async {
    await begin();
    await session.ensureLoaded(const <DocumentRef>[DocumentRef(DocumentKind.npc, 'town/citizen-1')]);
    await rename('town/citizen-1', 'Captain');
    expect(await session.apply(), isTrue);
    expect(saveStateOf(store, session).phase, SavePhase.applied);
    network.down.addAll(ConnectionPath.values);
    await network.lastStream.end();
    await settle();
    network.down.clear();
    connection.retryNow();
    await settle();
    expect(session.online, isTrue);
    expect(saveStateOf(store, session).phase, SavePhase.upToDate);
    expect(session.undoableChange, isNull);
  });

  test('should say a local workspace saves in this browser', () async {
    final EditorStore local = EditorStore(repository: MemoryWorkspaceRepository(), schemas: SharedSchemas.embedded, newId: () => 'ws-local');
    await local.createWorkspace('Sketches');
    expect(saveStateOf(local, null), const SaveState(SavePhase.saved));
    final Future<void> saving = local.commit(documents: <DocumentChange>[DocumentChange(DocumentKind.npc, 'town/a', prettyJson(npc('town/a', 'A')))]);
    expect(saveStateOf(local, null).phase, SavePhase.saving);
    await saving;
    expect(saveStateOf(local, null).text, 'Saved in this browser');
    expect(local.library(DocumentKind.npc).single.name, 'A');
    expect(local.library(DocumentKind.npc).single.loaded, isTrue);
    await begin();
  });

  test('should name a document the same way a server summary does', () async {
    await begin();
    await session.ensureLoaded(const <DocumentRef>[DocumentRef(DocumentKind.npc, 'town/citizen-5')]);
    final LibraryEntry entry = store.libraryEntry(DocumentKind.npc, 'town/citizen-5')!;
    expect(entry.loaded, isTrue);
    expect(entry.name, 'Citizen 5');
    expect(entry.summary.fields, server.version(server.documents['npc:town/citizen-5']!)['summary']);
  });

  test('should rebuild only the edited kind on a keystroke and keep the other lists and the counts', () async {
    await begin();
    await session.ensureLoaded(const <DocumentRef>[DocumentRef(DocumentKind.npc, 'town/citizen-1'), DocumentRef(DocumentKind.shop, 'town:bakery')]);
    final List<LibraryEntry> shops = store.library(DocumentKind.shop);
    final List<LibraryEntry> npcs = store.library(DocumentKind.npc);
    final Map<DocumentKind, int> counts = store.libraryCounts();
    final LibraryEntry untouched = store.libraryEntry(DocumentKind.npc, 'town/citizen-2')!;
    final int shopRevision = store.libraryRevisionOf(DocumentKind.shop);
    final int npcRevision = store.libraryRevisionOf(DocumentKind.npc);
    int npcHeard = 0, shopHeard = 0, countsHeard = 0;
    store.libraryChanges(DocumentKind.npc).addListener(() => npcHeard++);
    store.libraryChanges(DocumentKind.shop).addListener(() => shopHeard++);
    store.countChanges.addListener(() => countsHeard++);

    await rename('town/citizen-1', 'Renamed');

    expect(store.libraryEntry(DocumentKind.npc, 'town/citizen-1')!.name, 'Renamed');
    expect(identical(store.library(DocumentKind.shop), shops), isTrue, reason: 'another kind keeps its list');
    expect(identical(store.libraryCounts(), counts), isTrue, reason: 'no document joined or left');
    expect(identical(store.libraryEntry(DocumentKind.npc, 'town/citizen-2'), untouched), isTrue, reason: 'an unchanged row is kept');
    expect(identical(store.library(DocumentKind.npc), npcs), isFalse);
    expect(store.libraryRevisionOf(DocumentKind.shop), shopRevision);
    expect(store.libraryRevisionOf(DocumentKind.npc), greaterThan(npcRevision));
    expect(npcHeard, greaterThan(0));
    expect(shopHeard, 0);
    expect(countsHeard, 0);

    await store.commit(documents: <DocumentChange>[DocumentChange(DocumentKind.npc, 'town/newcomer', prettyJson(npc('town/newcomer', 'Newcomer')))]);
    expect(store.libraryCounts()[DocumentKind.npc], 13);
    expect(countsHeard, 1, reason: 'a new document changes a count');
    expect(shopHeard, 0);
  });

  test('should list documents with errors or changed on the server, kept between edits', () async {
    // NPCs the schema accepts, so only the edits below raise problems.
    Map<String, Object?> valid(String id, String name) => <String, Object?>{
          ...npc(id, name),
          'schemaVersion': 2,
          'revision': 1,
          'uuid': '0b2c1f3e-5d4a-4e6f-8a7b-9c0d1e2f3a4${id.substring(id.length - 1)}',
          'position': <String, Object?>{'world': 'world', 'x': 1.5, 'y': 64, 'z': 2.5, 'yaw': 0, 'pitch': 0},
        };
    for (final String id in <String>['town/citizen-1', 'town/citizen-2', 'town/citizen-5']) {
      server.put(DocumentKind.npc, id, valid(id, 'Citizen'));
    }
    await begin();
    await session.ensureLoaded(const <DocumentRef>[
      DocumentRef(DocumentKind.npc, 'town/citizen-1'),
      DocumentRef(DocumentKind.npc, 'town/citizen-2'),
      DocumentRef(DocumentKind.npc, 'town/citizen-5'),
    ]);
    expect(store.libraryAttention(), isEmpty);
    await store.editSource(DocumentKind.npc, 'town/citizen-1', '{"schemaVersion": 1, "name": ');
    await rename('town/citizen-2', 'Mine');
    server.put(DocumentKind.npc, 'town/citizen-2', valid('town/citizen-2', 'Theirs'));
    network.lastStream.emit(<String, Object?>{
      'type': 'catalog',
      'documents': <Object?>[server.version(server.documents['npc:town/citizen-2']!)],
    });
    await settle();
    final List<LibraryEntry> attention = store.libraryAttention();
    expect(attention.map((LibraryEntry entry) => entry.key), <String>['npc:town/citizen-2', 'npc:town/citizen-1'], reason: 'reading order: "Mine", then the id of a document with no name');
    expect(attention.first.stale, isTrue);
    expect(attention.last.problems, greaterThan(0));
    expect(identical(store.libraryAttention(), attention), isTrue, reason: 'nothing moved since');
    await rename('town/citizen-5', 'Elsewhere');
    expect(store.libraryAttention().map((LibraryEntry entry) => entry.key), <String>['npc:town/citizen-2', 'npc:town/citizen-1']);
    await store.editSource(DocumentKind.npc, 'town/citizen-1', prettyJson(valid('town/citizen-1', 'Fixed')));
    expect(store.libraryAttention().map((LibraryEntry entry) => entry.key), <String>['npc:town/citizen-2']);
  });
}
