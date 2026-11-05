/// Moves workspaces saved by older editors onto the current document ids.
///
/// Version 1 workspaces gave holograms and text animations plain path ids
/// (`town/welcome`), and versions 1 and 2 did so for holographic menus
/// (`lobby/main`); the server names them `namespace:path` (`town:welcome`,
/// `lobby:main`), which version 3 uses. A one-segment file
/// (`gui/holograms/spawn.json`) has no namespace, which the server refuses, so
/// it moves into the `gloss` namespace as the server's Gloss import would
/// place it.
library;

import '../logic/data_folder.dart';
import '../model/animus_document.dart';
import '../model/document_kind.dart';
import '../model/server_binding.dart';

const int workspaceSchemaVersion = 3;

/// Kinds that had plain path ids in older workspaces.
const Set<DocumentKind> _namespaced = <DocumentKind>{DocumentKind.hologram, DocumentKind.textAnimation, DocumentKind.menu};

/// The id and path an older document has now, or null when it is unchanged.
({String id, String path})? migratedLocation(DocumentKind kind, String id, String path) {
  if (!_namespaced.contains(kind) || id.contains(':')) return null;
  final DocumentLocation? located = locateDocument(path);
  if (located != null && located.kind == kind) return (id: located.id, path: path);
  final List<String> segments = id.split('/');
  final String moved = segments.length == 1 ? 'gloss:$id' : '${segments.first}:${segments.skip(1).join('/')}';
  final int colon = moved.indexOf(':');
  return (id: moved, path: '${kind.folder}/${moved.substring(0, colon)}/${moved.substring(colon + 1)}.json');
}

/// Older documents and server bookkeeping keyed by `kind:id`, renamed.
({List<AnimusDocument> documents, ServerBinding? server}) migrateWorkspace(List<AnimusDocument> documents, ServerBinding? server) {
  final Map<String, String> renamed = <String, String>{};
  final List<AnimusDocument> moved = <AnimusDocument>[
    for (final AnimusDocument document in documents)
      () {
        final ({String id, String path})? next = migratedLocation(document.kind, document.id, document.path);
        if (next == null) return document;
        renamed['${document.kind.wireName}:${document.id}'] = '${document.kind.wireName}:${next.id}';
        return AnimusDocument(
          kind: document.kind,
          id: next.id,
          path: next.path,
          source: document.source,
          json: document.json,
          diagnostics: document.diagnostics,
        );
      }(),
  ];
  if (server == null || renamed.isEmpty) return (documents: moved, server: server);
  String key(String old) => renamed[old] ?? old;
  return (
    documents: moved,
    server: server.copyWith(
      bases: <String, ServerBase>{for (final MapEntry<String, ServerBase> entry in server.bases.entries) key(entry.key): entry.value},
      pending: <PendingChange>[
        for (final PendingChange change in server.pending)
          PendingChange(
            idempotencyKey: change.idempotencyKey,
            documents: change.documents.map(key).toList(),
            sentAt: change.sentAt,
            undoOf: change.undoOf,
            message: change.message,
          ),
      ],
      history: <AppliedChange>[
        for (final AppliedChange change in server.history)
          AppliedChange(
            changeId: change.changeId,
            idempotencyKey: change.idempotencyKey,
            documents: change.documents.map(key).toList(),
            appliedAt: change.appliedAt,
            undoOf: change.undoOf,
            undone: change.undone,
          ),
      ],
    ),
  );
}
