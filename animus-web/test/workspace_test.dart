import 'dart:convert';
import 'dart:typed_data';

import 'package:animus_editor/logic/shared_schemas.dart';
import 'package:animus_editor/model/animus_document.dart';
import 'package:animus_editor/model/document_kind.dart';
import 'package:animus_editor/state/editor_store.dart';
import 'package:animus_editor/state/workspace_repository.dart';
import 'package:animus_editor/state/workspace_route.dart';
import 'package:test/test.dart';

import 'fixtures.dart';

void main() {
  group('routes', () {
    test('should round-trip every route through its hash', () {
      final List<EditorRoute> routes = <EditorRoute>[
        const HomeRoute(),
        const WorkspaceRoute('ws-1'),
        const DocumentRoute('ws-1', DocumentKind.behavior, 'town:greet/morning'),
        const DocumentRoute('ws 2', DocumentKind.npc, 'town/guard', ViewMode.split),
        const PairRoute('ANM1.eyJ2IjoxfQ'),
        const ServerRoute('sha256:ab', <String>['npcs', 'town/guard']),
      ];
      for (final EditorRoute route in routes) {
        expect(EditorRoute.parse(route.format()), route, reason: route.format());
      }
    });

    test('should fall back to home or the workspace for unknown routes', () {
      expect(EditorRoute.parse(''), const HomeRoute());
      expect(EditorRoute.parse('#/nowhere'), const HomeRoute());
      expect(EditorRoute.parse('#/workspace/ws-1/unknown/x'), const WorkspaceRoute('ws-1'));
      expect(EditorRoute.parse('#/workspace/ws-1/npc/a%2Fb?view=bogus'), const DocumentRoute('ws-1', DocumentKind.npc, 'a/b'));
    });
  });

  group('store', () {
    late MemoryWorkspaceRepository repository;
    late EditorStore store;
    int ids = 0;

    setUp(() {
      repository = MemoryWorkspaceRepository();
      store = EditorStore(repository: repository, schemas: SharedSchemas.embedded, newId: () => 'ws-${++ids}');
    });

    Future<WorkspaceSnapshot> openExport() => store.openFiles('Export', fixtureFiles('export'));

    test('should open a folder into a saved workspace', () async {
      final WorkspaceSnapshot snapshot = await openExport();
      expect(snapshot.generation, 1);
      expect(await repository.load(snapshot.id), isNotNull);
      expect(store.workspaces.single.documentCount, 10);
      expect(store.ignored, contains('config.toml'));
    });

    test('should revalidate an edited document and undo the edit', () async {
      final WorkspaceSnapshot snapshot = await openExport();
      final AnimusDocument broken = store.document(DocumentKind.npc, 'examples/broken')!;
      expect(broken.valid, isFalse);
      final Map<String, Object?> repaired = <String, Object?>{
        ...(broken.json! as Map<String, Object?>),
        'uuid': '00000000-0000-0000-0000-000000000001',
      };
      await store.editSource(DocumentKind.npc, 'examples/broken', jsonEncode(repaired));
      expect(store.document(DocumentKind.npc, 'examples/broken')!.valid, isTrue);
      expect(store.workspace!.generation, 2);
      expect((await repository.load(snapshot.id))!.generation, 2);
      await store.undo();
      expect(store.document(DocumentKind.npc, 'examples/broken')!.valid, isFalse);
      expect(store.workspace!.generation, 3);
    });

    test('should refuse a save from a stale tab and reload the stored copy', () async {
      final WorkspaceSnapshot snapshot = await openExport();
      // Another tab saves generation 2 first.
      await repository.save(snapshot.next(name: 'Other tab'));
      final AnimusDocument greeter = store.document(DocumentKind.npc, 'examples/greeter')!;
      await store.editSource(DocumentKind.npc, 'examples/greeter', '${greeter.source} ');
      expect(store.problem, contains('Another tab'));
      expect(store.workspace!.name, 'Other tab');
      expect(store.workspace!.generation, 2);
    });

    test('should keep unknown fields when a workspace is stored and loaded', () async {
      final Map<String, Uint8List> files = <String, Uint8List>{
        'npcs/a/b.json': Uint8List.fromList(utf8.encode('{"futureField": {"x": 1}}')),
      };
      final WorkspaceSnapshot snapshot = await store.openFiles('One', files);
      final WorkspaceSnapshot loaded = (await repository.load(snapshot.id))!;
      expect(loaded.documents.single.source, '{"futureField": {"x": 1}}');
      expect((loaded.documents.single.json! as Map<String, Object?>)['futureField'], <String, Object?>{'x': 1});
    });
  });
}
