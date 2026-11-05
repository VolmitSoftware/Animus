import 'dart:convert';

import 'package:animus_editor/logic/data_folder.dart';
import 'package:animus_editor/logic/gui/gloss_bundle.dart';
import 'package:animus_editor/logic/gui/text_animation.dart';
import 'package:animus_editor/model/animus_document.dart';
import 'package:animus_editor/model/document_kind.dart';
import 'package:animus_editor/model/server_binding.dart';
import 'package:animus_editor/state/editor_store.dart';
import 'package:animus_editor/state/workspace_repository.dart';
import 'package:test/test.dart';

import 'fixtures.dart';

/// schema/fixtures/gui/ids.json, which core's GuiIdContractTest checks too.
Map<String, Object?> _ids() => readFixture('gui/ids.json')! as Map<String, Object?>;

List<Map<String, Object?>> _entries(String key) => <Map<String, Object?>>[
      for (final Object? entry in _ids()[key]! as List<Object?>) entry! as Map<String, Object?>,
    ];

DocumentKind _kind(Map<String, Object?> entry) => DocumentKind.parse(entry['kind']! as String)!;

void main() {
  test('should locate hologram and text-animation paths with the server namespace:path ids', () {
    final List<Map<String, Object?>> locations = _entries('locations');
    expect(locations, hasLength(greaterThanOrEqualTo(8)));
    for (final Map<String, Object?> entry in locations) {
      final String path = entry['path']! as String;
      final String? id = entry['id'] as String?;
      expect(locateDocument(path), id == null ? isNull : DocumentLocation(_kind(entry), id), reason: path);
      if (id != null) expect(documentPath(_kind(entry), id), path, reason: path);
    }
  });

  test('should resolve animation tokens relative to the referencing namespace', () {
    for (final Map<String, Object?> entry in _entries('references')) {
      expect(resolveAnimationId(entry['from']! as String, entry['token']! as String), entry['resolved'], reason: '${entry['token']}');
    }
  });

  test('should map hologram and text-animation ids to and from Gloss as the server does', () {
    for (final Map<String, Object?> entry in _entries('gloss')) {
      expect(glossIdForAnimus(_kind(entry), entry['animus']! as String), entry['gloss'], reason: '${entry['animus']}');
    }
    for (final Map<String, Object?> entry in _entries('glossImport')) {
      expect(animusIdForGloss(_kind(entry), entry['gloss']! as String), entry['animus'], reason: '${entry['gloss']}');
    }
  });

  test('should migrate a saved workspace from plain-path ids to namespace:path ids', () {
    final List<Map<String, Object?>> cases = _entries('migration');
    final List<AnimusDocument> documents = <AnimusDocument>[
      for (final Map<String, Object?> entry in cases)
        AnimusDocument(
          kind: _kind(entry),
          id: entry['id']! as String,
          path: entry['path']! as String,
          source: '{}\n',
          json: const <String, Object?>{},
          diagnostics: const <Diagnostic>[],
        ),
    ];
    final String first = '${cases.first['kind']}:${cases.first['id']}';
    final WorkspaceSnapshot old = WorkspaceSnapshot(
      id: 'ws',
      name: 'old',
      generation: 3,
      documents: documents,
      server: ServerBinding(
        serverId: 's',
        fingerprint: 'sha256:00',
        bases: <String, ServerBase>{first: ServerBase(revision: 1, hash: 'sha256:aa', path: cases.first['path']! as String, source: null)},
        pending: <PendingChange>[PendingChange(idempotencyKey: 'k', documents: <String>[first], sentAt: DateTime.utc(2026))],
        history: const <AppliedChange>[],
      ),
    );
    final Map<String, Object?> encoded = jsonDecode(old.encode()) as Map<String, Object?>;
    encoded['schemaVersion'] = 1;
    final WorkspaceSnapshot migrated = WorkspaceSnapshot.decode(jsonEncode(encoded));
    expect(<String>[for (final AnimusDocument d in migrated.documents) '${d.kind.wireName} ${d.id} ${d.path}'], <String>[
      for (final Map<String, Object?> entry in cases) '${entry['kind']} ${entry['migratedId']} ${entry['migratedPath']}',
    ]);
    final String moved = '${cases.first['kind']}:${cases.first['migratedId']}';
    expect(migrated.server!.bases.keys, <String>[moved]);
    expect(migrated.server!.pending.single.documents, <String>[moved]);
    expect(migrated.generation, 3);
    expect(WorkspaceSnapshot.decode(migrated.encode()).documents.map((AnimusDocument d) => d.id), migrated.documents.map((AnimusDocument d) => d.id));
  });
}
