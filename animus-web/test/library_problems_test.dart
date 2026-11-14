// A document's problem count in the library (Home, lists, the nav) is the
// one its page shows: an error the server reports again is counted once.
import 'package:animus_editor/logic/change_sets.dart';
import 'package:animus_editor/logic/gui/gui_json.dart';
import 'package:animus_editor/logic/shared_schemas.dart';
import 'package:animus_editor/model/animus_document.dart';
import 'package:animus_editor/model/document_kind.dart';
import 'package:animus_editor/model/server_binding.dart';
import 'package:animus_editor/state/catalog_entry.dart';
import 'package:animus_editor/state/editor_store.dart';
import 'package:animus_editor/state/library.dart';
import 'package:animus_editor/state/workspace_repository.dart';
import 'package:test/test.dart';

/// A server side that reports [errors] for every document.
class _Server implements ServerLibrary {
  _Server(this.errors, this.libraryVersion);

  final List<Diagnostic> errors;

  @override
  final int libraryVersion;

  @override
  bool get catalogLoaded => true;

  @override
  List<CatalogEntry> catalogOf(DocumentKind kind) => const <CatalogEntry>[];

  @override
  bool isStale(String key) => false;

  @override
  bool isLoading(String key) => false;

  @override
  List<Diagnostic> serverErrors(String key) => errors;

  @override
  Iterable<String> get attentionKeys => const <String>[];

  @override
  Set<String> get movedFrom => const <String>{};

  @override
  DraftState stateOf(DocumentKind kind, String id) => DraftState.clean;
}

void main() {
  test('should count an error the server repeats once, and a new one too', () async {
    final EditorStore store = EditorStore(repository: MemoryWorkspaceRepository(), schemas: SharedSchemas.embedded, newId: () => 'ws-problems');
    await store.openServerWorkspace('server-s1', 'Town', const ServerBinding(serverId: 's1', fingerprint: 'sha256:00'));
    await store.commit(documents: <DocumentChange>[
      DocumentChange(DocumentKind.npc, 'town/broken', prettyJson(<String, Object?>{'schemaVersion': 1, 'id': 'broken', 'group': 'town', 'name': 'Broken'})),
    ]);
    final List<Diagnostic> local = <Diagnostic>[
      for (final Diagnostic diagnostic in store.document(DocumentKind.npc, 'town/broken')!.diagnostics)
        if (diagnostic.severity == DiagnosticSeverity.error) diagnostic,
    ];
    expect(local, isNotEmpty);
    expect(store.libraryEntry(DocumentKind.npc, 'town/broken')!.problems, local.length);

    store.attachServer(_Server(<Diagnostic>[local.first], 1));
    expect(store.libraryEntry(DocumentKind.npc, 'town/broken')!.problems, local.length, reason: 'the same error from the server is not a second problem');

    store.attachServer(_Server(<Diagnostic>[local.first, const Diagnostic(DiagnosticSeverity.error, r'$.name', 'name is longer than 16')], 2));
    expect(store.libraryEntry(DocumentKind.npc, 'town/broken')!.problems, local.length + 1);
  });
}
