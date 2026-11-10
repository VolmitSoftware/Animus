import 'dart:convert';
import 'dart:typed_data';

import 'package:animus_editor/logic/data_folder.dart';
import 'package:animus_editor/logic/data_folder_export.dart';
import 'package:animus_editor/logic/gui/gui_json.dart';
import 'package:animus_editor/logic/schema_upgrades.dart';
import 'package:animus_editor/logic/shared_schemas.dart';
import 'package:animus_editor/model/animus_document.dart';
import 'package:animus_editor/model/document_kind.dart';
import 'package:animus_editor/state/editor_store.dart';
import 'package:animus_editor/state/workspace_repository.dart';
import 'package:test/test.dart';

import 'fixtures.dart';

/// schema/fixtures/upgrades: core's SchemaMigrationsTest holds the server's
/// migration to the same files, so both sides write the same bytes.
void main() {
  final Map<String, Uint8List> before = fixtureFiles('upgrades/before');
  final Map<String, Uint8List> after = fixtureFiles('upgrades/after');
  const List<String> paths = <String>['groups/guards.json', 'npcs/town/citizens-10.json', 'npcs/town/citizens-9.json'];

  bool schemaFieldProblem(AnimusDocument document) =>
      document.diagnostics.any((Diagnostic diagnostic) => diagnostic.path.contains('schemaVersion') || diagnostic.path.contains('reviewAccepted'));

  test('should upgrade each shared fixture to the exact bytes the server writes', () {
    expect(before.keys.toList()..sort(), paths);
    for (final String path in paths) {
      final DocumentLocation location = locateDocument(path)!;
      final SchemaUpgrade upgrade = upgradeSource(location.kind, utf8.decode(before[path]!))!;
      expect(upgrade.from, 1, reason: path);
      expect(upgrade.to, 2, reason: path);
      expect(upgrade.source, utf8.decode(after[path]!), reason: path);
      expect(upgradeSource(location.kind, upgrade.source), isNull, reason: 'an upgraded $path is current');
    }
  });

  test('should leave current, newer, unversioned and other documents alone', () {
    final String current = utf8.decode(after['npcs/town/citizens-9.json']!);
    expect(upgradeSource(DocumentKind.npc, current), isNull);
    expect(upgradeSource(DocumentKind.npc, current.replaceFirst('"schemaVersion": 2', '"schemaVersion": 3')), isNull);
    expect(upgradeSource(DocumentKind.npc, current.replaceFirst('"schemaVersion": 2', '"schemaVersion": "1"')), isNull);
    expect(upgradeSource(DocumentKind.npc, '{"schemaVersion": 1,'), isNull);
    expect(upgradeSource(DocumentKind.behavior, '{"schemaVersion": 1, "id": "a:b"}'), isNull);
    final SchemaUpgrade decimal = upgradeSource(DocumentKind.npc, '{"schemaVersion": 1.0, "modules": {"animus:health": {"reviewAccepted": true}}}')!;
    expect(jsonDecode(decimal.source), <String, Object?>{
      'schemaVersion': 2,
      'modules': <String, Object?>{'animus:health': <String, Object?>{'reviewAccepted': true}},
    }, reason: 'only the sections an import could review lose the flag, as on the server');
  });

  test('should upgrade a data folder or zip as it opens and say which files', () {
    for (final Map<String, Uint8List> files in <Map<String, Uint8List>>[before, filesFromZip(zipFiles(before))]) {
      final DataFolderResult opened = openDataFolder(files, SharedSchemas.embedded);
      expect(opened.upgraded, paths);
      for (final AnimusDocument document in opened.documents) {
        expect(document.source, utf8.decode(after[document.path]!), reason: document.path);
        expect(schemaFieldProblem(document), isFalse, reason: '${document.path}: ${document.diagnostics}');
      }
      final Map<String, Uint8List> exported = filesFromZip(zipFiles(workspaceFiles(WorkspaceSnapshot(id: 'x', name: 'x', generation: 1, documents: opened.documents))));
      for (final String path in paths) {
        expect(utf8.decode(exported[path]!), utf8.decode(after[path]!), reason: 'the export writes version 2: $path');
      }
    }
    expect(openDataFolder(after, SharedSchemas.embedded).upgraded, isEmpty);
  });

  test('should upgrade a stored workspace when it opens and keep the upgrade', () async {
    final MemoryWorkspaceRepository repository = MemoryWorkspaceRepository();
    await repository.save(WorkspaceSnapshot(
      id: 'old',
      name: 'Old',
      generation: 1,
      documents: <AnimusDocument>[
        for (final String path in paths)
          validateSource(locateDocument(path)!, path, utf8.decode(before[path]!), SharedSchemas.embedded),
      ],
    ));
    final EditorStore store = EditorStore(repository: repository, schemas: SharedSchemas.embedded);
    final WorkspaceSnapshot listed = (await store.loadWorkspace('old'))!;
    expect(listed.documents.map((AnimusDocument document) => document.source), <String>[for (final String path in paths) utf8.decode(after[path]!)],
        reason: 'an export or copy from the list writes version 2 without opening the workspace');
    expect(await store.openWorkspace('old'), isTrue);
    expect(store.upgraded, paths);
    expect(store.canUndo, isFalse, reason: 'the upgrade is not an undo step');
    final WorkspaceSnapshot stored = (await repository.load('old'))!;
    for (final AnimusDocument document in stored.documents) {
      expect(document.source, utf8.decode(after[document.path]!), reason: document.path);
      expect(schemaFieldProblem(document), isFalse, reason: '${document.path}: ${document.diagnostics}');
    }
    final EditorStore again = EditorStore(repository: repository, schemas: SharedSchemas.embedded);
    expect(await again.openWorkspace('old'), isTrue);
    expect(again.upgraded, isEmpty, reason: 'nothing is left to upgrade');
  });

  test('should upgrade a picked folder opened as a workspace', () async {
    final EditorStore store = EditorStore(repository: MemoryWorkspaceRepository(), schemas: SharedSchemas.embedded, newId: () => 'picked');
    final WorkspaceSnapshot opened = await store.openFiles('Picked', before);
    expect(store.upgraded, paths);
    expect(opened.documents.map((AnimusDocument document) => document.source), <String>[for (final String path in paths) utf8.decode(after[path]!)]);
    expect(prettyJson(jsonDecode(opened.documents.first.source)), opened.documents.first.source);
  });
}
