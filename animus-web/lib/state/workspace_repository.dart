import 'dart:convert';
import 'dart:typed_data';

import '../model/animus_document.dart';
import '../model/server_binding.dart';
import 'workspace_migration.dart';

/// A local workspace: drafts of documents opened from a data folder. Drafts
/// live only in the browser; the server holds none.
class WorkspaceSnapshot {
  const WorkspaceSnapshot({
    required this.id,
    required this.name,
    required this.generation,
    required this.documents,
    this.resources = const <String, Uint8List>{},
    this.server,
  });

  final String id;
  final String name;

  /// Raised on every save. A save based on an older generation is a conflict,
  /// which is how two tabs editing one workspace are kept apart.
  final int generation;
  final List<AnimusDocument> documents;

  /// Data-folder files that are not documents but belong to the workspace:
  /// resource pack sources under `pack/assets/` (item models, textures).
  final Map<String, Uint8List> resources;

  /// Set for a server workspace: documents opened from a paired server, with
  /// the server version each draft started from.
  final ServerBinding? server;

  WorkspaceSnapshot next({String? name, List<AnimusDocument>? documents, Map<String, Uint8List>? resources, ServerBinding? server}) =>
      WorkspaceSnapshot(
        id: id,
        name: name ?? this.name,
        generation: generation + 1,
        documents: documents ?? this.documents,
        resources: resources ?? this.resources,
        server: server ?? this.server,
      );

  /// The same content at [generation]: an edit kept in memory after its save
  /// failed stays at the stored generation, so the next save follows it.
  WorkspaceSnapshot atGeneration(int generation) =>
      WorkspaceSnapshot(id: id, name: name, generation: generation, documents: documents, resources: resources, server: server);

  String encode() => jsonEncode(<String, Object?>{
        'schemaVersion': workspaceSchemaVersion,
        'id': id,
        'name': name,
        'generation': generation,
        'documents': <Map<String, Object?>>[for (final AnimusDocument document in documents) document.toJson()],
        if (resources.isNotEmpty)
          'resources': <String, String>{
            for (final String path in resources.keys.toList()..sort()) path: base64Encode(resources[path]!),
          },
        if (server != null) 'server': server!.toJson(),
      });

  static WorkspaceSnapshot decode(String text) {
    final Map<String, Object?> json = jsonDecode(text) as Map<String, Object?>;
    final Object? version = json['schemaVersion'];
    if (version is! int || version < 1 || version > workspaceSchemaVersion) throw FormatException('Unsupported workspace schemaVersion $version');
    List<AnimusDocument> documents = <AnimusDocument>[
      for (final Object? document in json['documents']! as List<Object?>) AnimusDocument.fromJson(document! as Map<String, Object?>),
    ];
    ServerBinding? server = json['server'] is Map<String, Object?> ? ServerBinding.fromJson(json['server']! as Map<String, Object?>) : null;
    if (version < workspaceSchemaVersion) {
      final ({List<AnimusDocument> documents, ServerBinding? server}) migrated = migrateWorkspace(documents, server);
      documents = migrated.documents;
      server = migrated.server;
    }
    return WorkspaceSnapshot(
      id: json['id']! as String,
      name: json['name']! as String,
      generation: (json['generation']! as num).toInt(),
      documents: documents,
      resources: <String, Uint8List>{
        if (json['resources'] is Map<String, Object?>)
          for (final MapEntry<String, Object?> entry in (json['resources']! as Map<String, Object?>).entries)
            if (entry.value is String) entry.key: base64Decode(entry.value! as String),
      },
      server: server,
    );
  }
}

class WorkspaceSummary {
  const WorkspaceSummary(this.id, this.name, this.generation, this.documentCount, {this.serverId});

  final String id;
  final String name;
  final int generation;
  final int documentCount;

  /// The paired server a server workspace belongs to.
  final String? serverId;
}

/// Another tab (or an older copy) saved the workspace first.
class WorkspaceConflict implements Exception {
  const WorkspaceConflict(this.id, this.storedGeneration, this.attemptedGeneration);

  final String id;
  final int storedGeneration;
  final int attemptedGeneration;

  @override
  String toString() => 'Workspace $id is at generation $storedGeneration; this save expected ${attemptedGeneration - 1}';
}

/// The browser would not write the workspace (out of space, storage
/// switched off, a broken database).
class WorkspaceWriteFailure implements Exception {
  const WorkspaceWriteFailure(this.reason, {this.quota = false});

  /// What the browser said, such as `QuotaExceededError`.
  final String reason;

  /// True when the browser is out of storage space for this site.
  final bool quota;

  @override
  String toString() => 'The browser refused to write the workspace: $reason';
}

/// Undo and redo, kept beside the workspace.
enum HistoryStack { undo, redo }

/// One undo or redo step: the workspace to go back (or forward) to, and
/// what the change it reverts did, such as `rename town:baker`.
class HistoryStep {
  const HistoryStep(this.label, this.snapshot);

  final String label;
  final WorkspaceSnapshot snapshot;
}

abstract class WorkspaceRepository {
  Future<List<WorkspaceSummary>> list();

  Future<WorkspaceSnapshot?> load(String id);

  /// Stores [snapshot] only if the stored copy is at `snapshot.generation - 1`
  /// (or absent when the generation is 1), checked and written atomically.
  /// Throws [WorkspaceConflict] otherwise, and [WorkspaceWriteFailure] when
  /// the browser refuses the write.
  Future<void> save(WorkspaceSnapshot snapshot);

  Future<void> delete(String id);

  /// The workspace's steps on [stack], oldest first, kept so undo and redo
  /// work after a reload.
  Future<List<HistoryStep>> loadHistory(String id, HistoryStack stack);

  /// Adds the newest step to [stack] and drops the oldest beyond [limit].
  Future<void> pushHistory(String id, HistoryStack stack, HistoryStep step, int limit);

  /// Drops the newest step of [stack].
  Future<void> popHistory(String id, HistoryStack stack);

  Future<void> clearHistory(String id, HistoryStack stack);
}

/// In-memory repository for tests and for browsers without IndexedDB.
class MemoryWorkspaceRepository implements WorkspaceRepository {
  final Map<String, String> _stored = <String, String>{};
  final Map<String, List<(String, String)>> _history = <String, List<(String, String)>>{};

  static String _key(String id, HistoryStack stack) => '${stack.name}:$id';

  @override
  Future<List<HistoryStep>> loadHistory(String id, HistoryStack stack) async => <HistoryStep>[
        for (final (String label, String text) in _history[_key(id, stack)] ?? const <(String, String)>[])
          HistoryStep(label, WorkspaceSnapshot.decode(text)),
      ];

  @override
  Future<void> pushHistory(String id, HistoryStack stack, HistoryStep step, int limit) async {
    final List<(String, String)> steps = _history.putIfAbsent(_key(id, stack), () => <(String, String)>[])
      ..add((step.label, step.snapshot.encode()));
    while (steps.length > limit) {
      steps.removeAt(0);
    }
  }

  @override
  Future<void> popHistory(String id, HistoryStack stack) async {
    final List<(String, String)>? steps = _history[_key(id, stack)];
    if (steps != null && steps.isNotEmpty) steps.removeLast();
  }

  @override
  Future<void> clearHistory(String id, HistoryStack stack) async {
    _history.remove(_key(id, stack));
  }

  @override
  Future<List<WorkspaceSummary>> list() async {
    final List<WorkspaceSummary> summaries = <WorkspaceSummary>[
      for (final String text in _stored.values) summarize(WorkspaceSnapshot.decode(text)),
    ];
    summaries.sort((WorkspaceSummary a, WorkspaceSummary b) => a.name.compareTo(b.name));
    return summaries;
  }

  @override
  Future<WorkspaceSnapshot?> load(String id) async {
    final String? text = _stored[id];
    return text == null ? null : WorkspaceSnapshot.decode(text);
  }

  @override
  Future<void> save(WorkspaceSnapshot snapshot) async {
    final String? existing = _stored[snapshot.id];
    final int stored = existing == null ? 0 : WorkspaceSnapshot.decode(existing).generation;
    if (stored != snapshot.generation - 1) throw WorkspaceConflict(snapshot.id, stored, snapshot.generation);
    _stored[snapshot.id] = snapshot.encode();
  }

  @override
  Future<void> delete(String id) async {
    _stored.remove(id);
  }
}

WorkspaceSummary summarize(WorkspaceSnapshot snapshot) =>
    WorkspaceSummary(snapshot.id, snapshot.name, snapshot.generation, snapshot.documents.length, serverId: snapshot.server?.serverId);
