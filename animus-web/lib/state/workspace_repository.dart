import 'dart:convert';
import 'dart:typed_data';

import '../model/animus_document.dart';

/// A local workspace: drafts of documents opened from a data folder. Drafts
/// live only in the browser; the server holds none.
class WorkspaceSnapshot {
  const WorkspaceSnapshot({
    required this.id,
    required this.name,
    required this.generation,
    required this.documents,
    this.resources = const <String, Uint8List>{},
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

  WorkspaceSnapshot next({String? name, List<AnimusDocument>? documents, Map<String, Uint8List>? resources}) => WorkspaceSnapshot(
        id: id,
        name: name ?? this.name,
        generation: generation + 1,
        documents: documents ?? this.documents,
        resources: resources ?? this.resources,
      );

  String encode() => jsonEncode(<String, Object?>{
        'schemaVersion': 1,
        'id': id,
        'name': name,
        'generation': generation,
        'documents': <Map<String, Object?>>[for (final AnimusDocument document in documents) document.toJson()],
        if (resources.isNotEmpty)
          'resources': <String, String>{
            for (final String path in resources.keys.toList()..sort()) path: base64Encode(resources[path]!),
          },
      });

  static WorkspaceSnapshot decode(String text) {
    final Map<String, Object?> json = jsonDecode(text) as Map<String, Object?>;
    if (json['schemaVersion'] != 1) throw FormatException('Unsupported workspace schemaVersion ${json['schemaVersion']}');
    return WorkspaceSnapshot(
      id: json['id']! as String,
      name: json['name']! as String,
      generation: (json['generation']! as num).toInt(),
      documents: <AnimusDocument>[
        for (final Object? document in json['documents']! as List<Object?>)
          AnimusDocument.fromJson(document! as Map<String, Object?>),
      ],
      resources: <String, Uint8List>{
        if (json['resources'] is Map<String, Object?>)
          for (final MapEntry<String, Object?> entry in (json['resources']! as Map<String, Object?>).entries)
            if (entry.value is String) entry.key: base64Decode(entry.value! as String),
      },
    );
  }
}

class WorkspaceSummary {
  const WorkspaceSummary(this.id, this.name, this.generation, this.documentCount);

  final String id;
  final String name;
  final int generation;
  final int documentCount;
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

abstract class WorkspaceRepository {
  Future<List<WorkspaceSummary>> list();

  Future<WorkspaceSnapshot?> load(String id);

  /// Stores [snapshot] only if the stored copy is at `snapshot.generation - 1`
  /// (or absent when the generation is 1), checked and written atomically.
  /// Throws [WorkspaceConflict] otherwise.
  Future<void> save(WorkspaceSnapshot snapshot);

  Future<void> delete(String id);
}

/// In-memory repository for tests and for browsers without IndexedDB.
class MemoryWorkspaceRepository implements WorkspaceRepository {
  final Map<String, String> _stored = <String, String>{};

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
    WorkspaceSummary(snapshot.id, snapshot.name, snapshot.generation, snapshot.documents.length);
