/// Comparing a local workspace with a paired server's documents, for the
/// later sync: pushing local documents as one change set, or pulling server
/// documents into the workspace. A push sends new documents with no base and
/// documents the server already has against the server's revision and hash,
/// so nothing is overwritten unless the user chose to and the server's copy
/// has not moved since.
library;

import '../../model/animus_document.dart';
import '../../model/document_kind.dart';
import '../../model/server_binding.dart';
import '../change_sets.dart';
import '../gui/gui_json.dart';

enum SyncState {
  /// Only one side has it.
  added,

  /// Both sides hold the same JSON.
  same,

  /// Both sides have it and they differ.
  conflict,
}

class PushEntry {
  const PushEntry(this.document, this.state, this.server, {this.problem});

  final AnimusDocument document;
  final SyncState state;

  /// The server's copy, for [SyncState.same] and [SyncState.conflict].
  final ServerBase? server;

  /// Why the document cannot be sent (not a JSON object).
  final String? problem;

  String get key => documentKey(document.kind, document.id);

  /// Errors the browser found; the server is asked anyway, and refuses or
  /// accepts by its own validators.
  int get browserErrors => document.diagnostics.where((Diagnostic diagnostic) => diagnostic.severity == DiagnosticSeverity.error).length;
}

class PushPlan {
  const PushPlan(this.entries);

  /// In workspace order.
  final List<PushEntry> entries;

  List<PushEntry> where(SyncState state) => <PushEntry>[for (final PushEntry entry in entries) if (entry.state == state) entry];
}

/// How one document of a push is handled.
enum PushChoice {
  /// New documents: create on the server. Conflicts: replace the server's
  /// copy, checked against the revision and hash it had when compared.
  send,

  /// Leave the server's copy (or absence) alone.
  skip,
}

/// [local] against [server] (the server's copies of the documents it shares
/// with the workspace, by `kind:id`).
PushPlan planPush(List<AnimusDocument> local, Map<String, ServerBase> server) => PushPlan(<PushEntry>[
      for (final AnimusDocument document in local)
        () {
          final ServerBase? copy = server[documentKey(document.kind, document.id)];
          final SyncState state = copy == null ? SyncState.added : (jsonEquals(document.json, copy.source) ? SyncState.same : SyncState.conflict);
          return PushEntry(document, state, copy, problem: document.json is Map<String, Object?> ? null : 'The document is not a JSON object.');
        }(),
    ]);

/// The choice a push entry starts with: new documents are sent, conflicts
/// wait for the user (skip), identical ones have nothing to send.
PushChoice defaultPushChoice(PushEntry entry) => entry.state == SyncState.added && entry.problem == null ? PushChoice.send : PushChoice.skip;

/// The entries [choices] send (missing keys take [defaultPushChoice]).
List<PushEntry> pushSelection(PushPlan plan, Map<String, PushChoice> choices) => <PushEntry>[
      for (final PushEntry entry in plan.entries)
        if (entry.state != SyncState.same && entry.problem == null && (choices[entry.key] ?? defaultPushChoice(entry)) == PushChoice.send) entry,
    ];

/// The `change` entries of a change set for [selection].
List<Map<String, Object?>> pushChanges(List<PushEntry> selection) => <Map<String, Object?>>[
      for (final PushEntry entry in selection)
        <String, Object?>{
          'kind': entry.document.kind.wireName,
          'id': entry.document.id,
          'baseRevision': entry.server?.revision,
          'baseHash': entry.server?.hash,
          'source': entry.document.json,
        },
    ];

/// After an applied push: the local documents with the revision the server
/// stored (so the next compare finds them the same), by key.
Map<String, Object?> pushedSources(List<PushEntry> selection, ChangeApplied applied) {
  final Map<String, PushEntry> byKey = <String, PushEntry>{for (final PushEntry entry in selection) entry.key: entry};
  return <String, Object?>{
    for (final AppliedDocument document in applied.documents)
      if (byKey[document.key] != null && document.hash != null)
        document.key: withServerRevision(document.kind, byKey[document.key]!.document.json, document.revision),
  };
}

class PullEntry {
  const PullEntry(this.kind, this.id, this.server, this.state, this.local);

  final DocumentKind kind;
  final String id;
  final ServerBase server;
  final SyncState state;
  final AnimusDocument? local;

  String get key => documentKey(kind, id);
}

class PullPlan {
  const PullPlan(this.entries);

  final List<PullEntry> entries;

  List<PullEntry> where(SyncState state) => <PullEntry>[for (final PullEntry entry in entries) if (entry.state == state) entry];
}

enum PullChoice {
  /// Write the server's copy into the workspace.
  take,

  /// Keep what the workspace has (or lacks).
  keep,
}

/// Server copies against the workspace's documents.
PullPlan planPull(Map<String, ServerBase> server, List<AnimusDocument> local) {
  final Map<String, AnimusDocument> byKey = <String, AnimusDocument>{
    for (final AnimusDocument document in local) documentKey(document.kind, document.id): document,
  };
  final List<String> keys = server.keys.toList()..sort();
  return PullPlan(<PullEntry>[
    for (final String key in keys)
      if (parseDocumentKey(key) case final (DocumentKind, String) parsed)
        PullEntry(
          parsed.$1,
          parsed.$2,
          server[key]!,
          byKey[key] == null ? SyncState.added : (jsonEquals(byKey[key]!.json, server[key]!.source) ? SyncState.same : SyncState.conflict),
          byKey[key],
        ),
  ]);
}

/// The choice a pull entry starts with: documents the workspace lacks are
/// taken, ones it holds differently are kept until the user picks.
PullChoice defaultPullChoice(PullEntry entry) => entry.state == SyncState.added ? PullChoice.take : PullChoice.keep;

/// What a pull writes into the workspace: the document's text and the path
/// the server keeps it at.
List<({DocumentKind kind, String id, String source, String path})> pullWrites(PullPlan plan, Map<String, PullChoice> choices) =>
    <({DocumentKind kind, String id, String source, String path})>[
      for (final PullEntry entry in plan.entries)
        if (entry.state != SyncState.same && (choices[entry.key] ?? defaultPullChoice(entry)) == PullChoice.take)
          (kind: entry.kind, id: entry.id, source: prettyJson(entry.server.source), path: entry.local?.path ?? entry.server.path),
    ];

/// Top-level fields whose values differ between two documents, for the
/// conflict line ("name, position differ").
List<String> differingFields(Object? a, Object? b) {
  final JsonMap left = asMap(a), right = asMap(b);
  return <String>[
    for (final String key in <String>{...left.keys, ...right.keys})
      if (!jsonEquals(left[key], right[key])) key,
  ]..sort();
}
