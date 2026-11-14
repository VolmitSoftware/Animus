import 'dart:convert';
import 'dart:typed_data';

import '../logic/json_merge.dart';
import '../model/animus_document.dart';
import '../model/document_kind.dart';
import '../model/server_binding.dart';
import 'workspace_migration.dart';
import 'workspace_records.dart';

/// A workspace: drafts of documents opened from a data folder or a paired
/// server. Drafts live only in the browser until they are applied.
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

  /// Set for a server workspace: the server version each loaded document
  /// started from, unanswered change sets and the applied history.
  final ServerBinding? server;

  static final Expando<Map<String, AnimusDocument>> _indexes = Expando<Map<String, AnimusDocument>>('documents by key');

  /// [documents] by `kind:id`, built once per document list.
  Map<String, AnimusDocument> get byKey => _indexes[documents] ??= <String, AnimusDocument>{
        for (final AnimusDocument document in documents) documentKey(document.kind, document.id): document,
      };

  AnimusDocument? document(DocumentKind kind, String id) => byKey[documentKey(kind, id)];

  /// The next generation. [byKey], when given, is [documents] by key in the
  /// same order, kept as [this.byKey] so it is not built again.
  WorkspaceSnapshot next({
    String? name,
    List<AnimusDocument>? documents,
    Map<String, AnimusDocument>? byKey,
    Map<String, Uint8List>? resources,
    ServerBinding? server,
  }) {
    if (documents != null && byKey != null) _indexes[documents] = byKey;
    return WorkspaceSnapshot(
      id: id,
      name: name ?? this.name,
      generation: generation + 1,
      documents: documents ?? this.documents,
      resources: resources ?? this.resources,
      server: server ?? this.server,
    );
  }

  /// The same content at [generation]: an edit kept in memory after its save
  /// failed stays at the stored generation, so the next save follows it.
  WorkspaceSnapshot atGeneration(int generation) =>
      WorkspaceSnapshot(id: id, name: name, generation: generation, documents: documents, resources: resources, server: server);
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

/// One undo or redo step put back over a workspace: its documents and
/// resources, or the document keys it could not be put back on.
typedef StepResult = ({List<AnimusDocument> documents, Map<String, Uint8List> resources, List<String> conflicts});

/// One undo or redo step: the documents (and resources) it puts back, by
/// `kind:id`, null for one it removes; each document as the change it
/// reverts left it ([after]); and what that change did, such as
/// `rename Baker`. Only what the change touched is kept.
class HistoryStep {
  const HistoryStep(this.label, this.documents, this.after, {this.resources = const <String, Uint8List?>{}});

  final String label;
  final Map<String, AnimusDocument?> documents;

  /// Each of [documents]' keys as the change left it, null where it removed
  /// the document. A document that no longer matches was changed since by
  /// something outside the history (a server refresh): only this step's own
  /// change is taken back from it.
  final Map<String, AnimusDocument?> after;
  final Map<String, Uint8List?> resources;

  /// [documents] and [resources] of [from] where [to] differs, as the step
  /// that turns [to] back into [from].
  static HistoryStep between(String label, WorkspaceSnapshot from, WorkspaceSnapshot to) {
    final Map<String, AnimusDocument?> documents = <String, AnimusDocument?>{};
    final Map<String, AnimusDocument?> after = <String, AnimusDocument?>{};
    if (!identical(from.documents, to.documents)) {
      for (final MapEntry<String, AnimusDocument> entry in to.byKey.entries) {
        if (identical(from.byKey[entry.key], entry.value)) continue;
        documents[entry.key] = from.byKey[entry.key];
        after[entry.key] = entry.value;
      }
      for (final MapEntry<String, AnimusDocument> entry in from.byKey.entries) {
        if (to.byKey.containsKey(entry.key)) continue;
        documents[entry.key] = entry.value;
        after[entry.key] = null;
      }
    }
    final Map<String, Uint8List?> resources = <String, Uint8List?>{};
    if (!identical(from.resources, to.resources)) {
      for (final String path in <String>{...from.resources.keys, ...to.resources.keys}) {
        if (!identical(from.resources[path], to.resources[path])) resources[path] = from.resources[path];
      }
    }
    return HistoryStep(label, documents, after, resources: resources);
  }

  /// This step widened with a later [other] of the same coalesced edit: it
  /// still puts back what was there before the first, and holds what the
  /// latest left.
  HistoryStep widen(HistoryStep other) => HistoryStep(
        label,
        <String, AnimusDocument?>{
          ...documents,
          for (final MapEntry<String, AnimusDocument?> entry in other.documents.entries)
            if (!documents.containsKey(entry.key)) entry.key: entry.value,
        },
        <String, AnimusDocument?>{...after, ...other.after},
        resources: <String, Uint8List?>{
          ...resources,
          for (final MapEntry<String, Uint8List?> entry in other.resources.entries)
            if (!resources.containsKey(entry.key)) entry.key: entry.value,
        },
      );

  /// [workspace]'s documents and resources with this step put back, sorted
  /// by path as a workspace keeps them. A document still as the change left
  /// it is put back whole; one changed since gets only this step's own
  /// change, as a three-way merge ([rebuild] makes the merged document), and
  /// one changed since in a field this step touched too is a conflict.
  StepResult applyTo(WorkspaceSnapshot workspace, AnimusDocument Function(AnimusDocument like, Object? json) rebuild) {
    final Map<String, AnimusDocument> byKey = Map<String, AnimusDocument>.of(workspace.byKey);
    final List<String> conflicts = <String>[];
    documents.forEach((String key, AnimusDocument? back) {
      final AnimusDocument? now = workspace.byKey[key];
      final AnimusDocument? left = after[key];
      if (_same(now, left)) {
        back == null ? byKey.remove(key) : byKey[key] = back;
        return;
      }
      final MergeResult merge = mergeJson(_jsonOf(left), _jsonOf(back), _jsonOf(now));
      if (!merge.clean) {
        conflicts.add(key);
        return;
      }
      final Object? merged = merge.merged;
      if (isAbsent(merged)) {
        byKey.remove(key);
      } else if (now == null || !jsonEquals(merged, now.json)) {
        byKey[key] = rebuild((now ?? back ?? left)!, merged);
      }
    });
    final List<AnimusDocument> next = byKey.values.toList()..sort((AnimusDocument a, AnimusDocument b) => a.path.compareTo(b.path));
    final Map<String, Uint8List> nextResources = resources.isEmpty ? workspace.resources : Map<String, Uint8List>.of(workspace.resources);
    resources.forEach((String path, Uint8List? bytes) => bytes == null ? nextResources.remove(path) : nextResources[path] = bytes);
    return (documents: next, resources: nextResources, conflicts: conflicts);
  }

  static bool _same(AnimusDocument? a, AnimusDocument? b) =>
      identical(a, b) || (a != null && b != null && a.path == b.path && a.source == b.source);

  static Object? _jsonOf(AnimusDocument? document) => document == null ? absentValue : document.json;
}

/// What another tab's small save changed: the stored header, and each named
/// document key's document and base now (both null once it is gone).
class WorkspacePatch {
  const WorkspacePatch(this.name, this.generation, this.server, this.records, this.resources);

  final String name;
  final int generation;

  /// The binding without bases; the patched bases come from [records].
  final ServerBinding? server;
  final Map<String, (AnimusDocument?, ServerBase?)> records;

  /// Every resource, when the save changed resources.
  final Map<String, Uint8List>? resources;

  /// [workspace] with this patch laid over the documents [keys] named.
  WorkspaceSnapshot applyTo(WorkspaceSnapshot workspace, Iterable<String> keys) {
    final Map<String, AnimusDocument> byKey = Map<String, AnimusDocument>.of(workspace.byKey);
    final Map<String, ServerBase> bases = Map<String, ServerBase>.of(workspace.server?.bases ?? const <String, ServerBase>{});
    for (final String key in keys) {
      final (AnimusDocument?, ServerBase?) record = records[key] ?? (null, null);
      record.$1 == null ? byKey.remove(key) : byKey[key] = record.$1!;
      record.$2 == null ? bases.remove(key) : bases[key] = record.$2!;
    }
    return WorkspaceSnapshot(
      id: workspace.id,
      name: name,
      generation: generation,
      documents: byKey.values.toList()..sort((AnimusDocument a, AnimusDocument b) => a.path.compareTo(b.path)),
      resources: resources ?? workspace.resources,
      server: server?.copyWith(bases: bases),
    );
  }
}

/// Workspaces over [records]: turns snapshots into the records a save
/// changes, and moves workspaces stored by older editors (one record of JSON
/// text) onto split records, with their undo and redo steps, the first time
/// they are read.
class WorkspaceRepository {
  WorkspaceRepository(this.records);

  final WorkspaceRecords records;

  Future<List<WorkspaceSummary>> list() async {
    final List<WorkspaceSummary> summaries = <WorkspaceSummary>[];
    for (final Map<String, Object?> header in await records.headers()) {
      try {
        if (isLegacyHeader(header)) {
          summaries.add(summarize(await _migrate(header)));
        } else {
          summaries.add(WorkspaceSummary(
            header['id']! as String,
            header['name']! as String,
            (header['generation']! as num).toInt(),
            (header['documentCount'] as num?)?.toInt() ?? 0,
            serverId: header['server'] is Map<String, Object?> ? (header['server']! as Map<String, Object?>)['serverId'] as String? : null,
          ));
        }
      } on FormatException {
        continue;
      }
    }
    summaries.sort((WorkspaceSummary a, WorkspaceSummary b) => a.name.compareTo(b.name));
    return summaries;
  }

  Future<WorkspaceSnapshot?> load(String id) async {
    final StoredWorkspace? stored = await records.read(id);
    if (stored == null) return null;
    if (isLegacyHeader(stored.header)) return _migrate(stored.header);
    return _snapshotOf(stored);
  }

  /// The stored header and the records of [keys], for following another
  /// tab's save of them; null when the workspace is gone or still in the old
  /// layout.
  Future<WorkspacePatch?> loadPatch(String id, Iterable<String> keys, {bool resources = false}) async {
    final StoredWorkspace? stored = await records.readSome(id, keys, resources: resources);
    if (stored == null || isLegacyHeader(stored.header)) return null;
    final Object? server = stored.header['server'];
    return WorkspacePatch(
      stored.header['name']! as String,
      (stored.header['generation']! as num).toInt(),
      server is Map<String, Object?> ? ServerBinding.fromJson(server) : null,
      <String, (AnimusDocument?, ServerBase?)>{
        for (final MapEntry<String, Map<String, Object?>> entry in stored.documents.entries) entry.key: readDocumentRecord(entry.value),
      },
      resources ? _resourcesOf(stored.resources) : null,
    );
  }

  /// Stores [next] only if the stored copy is at `next.generation - 1` (or
  /// absent when the generation is 1), checked and written atomically.
  /// [previous] is the copy stored now: only what differs from it is written,
  /// the documents [changed] names when the caller has worked them out
  /// ([changedKeys]). Without [previous] every record is written. Throws
  /// [WorkspaceConflict] or [WorkspaceWriteFailure].
  Future<void> save(WorkspaceSnapshot next, {WorkspaceSnapshot? previous, Set<String>? changed}) => records.write(
        next.id,
        next.generation - 1,
        previous == null || previous.id != next.id ? _whole(next) : _difference(previous, next, changed ?? changedKeys(previous, next)),
      );

  Future<void> delete(String id) => records.remove(id);

  /// The workspace's steps on [stack], oldest first. Moving an older
  /// workspace converts its whole-workspace steps; a stack that still holds
  /// one, or a step stored without what its change left, is dropped.
  Future<List<HistoryStep>> loadHistory(String id, HistoryStack stack) async {
    final List<Map<String, Object?>> stored = await records.history(id, stack);
    if (stored.any((Map<String, Object?> step) => isLegacyStep(step) || step['after'] is! Map<String, Object?>)) {
      await records.clearHistory(id, stack);
      return const <HistoryStep>[];
    }
    return <HistoryStep>[for (final Map<String, Object?> step in stored) _stepOf(step)];
  }

  /// Adds the newest step to [stack] and drops the oldest beyond [limit].
  Future<void> pushHistory(String id, HistoryStack stack, HistoryStep step, int limit) =>
      records.pushHistory(id, stack, _record(step), limit);

  /// Replaces the newest step of [stack], after a coalesced edit widened it.
  Future<void> replaceHistoryTop(String id, HistoryStack stack, HistoryStep step) =>
      records.replaceTop(id, stack, _record(step));

  /// Drops the newest step of [stack].
  Future<void> popHistory(String id, HistoryStack stack) => records.popHistory(id, stack);

  Future<void> clearHistory(String id, HistoryStack stack) => records.clearHistory(id, stack);

  /// Splits an older single-record workspace into records at its stored
  /// generation, converts its undo and redo steps, and returns it.
  Future<WorkspaceSnapshot> _migrate(Map<String, Object?> header) async {
    final WorkspaceSnapshot snapshot = decodeLegacyWorkspace(header['text']! as String);
    try {
      await records.write(snapshot.id, snapshot.generation, _whole(snapshot));
    } on WorkspaceConflict {
      // Another tab moved it first, history included; its copy is the same workspace.
      return snapshot;
    }
    for (final HistoryStack stack in HistoryStack.values) {
      await _migrateHistory(snapshot, stack);
    }
    return snapshot;
  }

  /// Rewrites [stack]'s whole-workspace steps as [convertLegacyHistory] steps.
  /// A stack that fails part way is cleared, since a partial one undoes wrongly.
  Future<void> _migrateHistory(WorkspaceSnapshot snapshot, HistoryStack stack) async {
    try {
      final List<Map<String, Object?>> stored = await records.history(snapshot.id, stack);
      if (!stored.any(isLegacyStep)) return;
      final List<HistoryStep> steps = convertLegacyHistory(stored, snapshot, limit: historyLimit);
      await records.clearHistory(snapshot.id, stack);
      for (final HistoryStep step in steps) {
        await pushHistory(snapshot.id, stack, step, historyLimit);
      }
    } on Object {
      await records.clearHistory(snapshot.id, stack).catchError((Object _) {});
    }
  }

  static Map<String, Object?> _header(WorkspaceSnapshot snapshot) => <String, Object?>{
        'id': snapshot.id,
        'name': snapshot.name,
        'generation': snapshot.generation,
        'schemaVersion': splitLayoutVersion,
        'documentCount': snapshot.documents.length,
        if (snapshot.server != null) 'server': snapshot.server!.copyWith(bases: const <String, ServerBase>{}).toJson(),
      };

  static RecordWrite _whole(WorkspaceSnapshot snapshot) {
    final Map<String, ServerBase> bases = snapshot.server?.bases ?? const <String, ServerBase>{};
    return RecordWrite(
      header: _header(snapshot),
      documents: <String, Map<String, Object?>?>{
        for (final String key in <String>{...snapshot.byKey.keys, ...bases.keys}) key: documentRecord(snapshot.byKey[key], bases[key]),
      },
      resources: <String, String?>{for (final MapEntry<String, Uint8List> entry in snapshot.resources.entries) entry.key: base64Encode(entry.value)},
      replace: true,
    );
  }

  static RecordWrite _difference(WorkspaceSnapshot previous, WorkspaceSnapshot next, Set<String> changed) {
    final Map<String, ServerBase> after = next.server?.bases ?? const <String, ServerBase>{};
    final Map<String, String?> resources = <String, String?>{
      for (final String path in changedResources(previous, next))
        path: next.resources[path] == null ? null : base64Encode(next.resources[path]!),
    };
    return RecordWrite(
      header: _header(next),
      documents: <String, Map<String, Object?>?>{
        for (final String key in changed) key: next.byKey[key] == null && after[key] == null ? null : documentRecord(next.byKey[key], after[key]),
      },
      resources: resources,
    );
  }

  static WorkspaceSnapshot _snapshotOf(StoredWorkspace stored) {
    final Map<String, Object?> header = stored.header;
    final List<AnimusDocument> documents = <AnimusDocument>[];
    final Map<String, ServerBase> bases = <String, ServerBase>{};
    for (final MapEntry<String, Map<String, Object?>> entry in stored.documents.entries) {
      final (AnimusDocument? document, ServerBase? base) = readDocumentRecord(entry.value);
      if (document != null) documents.add(document);
      if (base != null) bases[entry.key] = base;
    }
    documents.sort((AnimusDocument a, AnimusDocument b) => a.path.compareTo(b.path));
    final Object? server = header['server'];
    return WorkspaceSnapshot(
      id: header['id']! as String,
      name: header['name']! as String,
      generation: (header['generation']! as num).toInt(),
      documents: documents,
      resources: _resourcesOf(stored.resources),
      server: server is Map<String, Object?> ? ServerBinding.fromJson(server).copyWith(bases: bases) : null,
    );
  }

  static Map<String, Uint8List> _resourcesOf(Map<String, String> stored) =>
      <String, Uint8List>{for (final MapEntry<String, String> entry in stored.entries) entry.key: base64Decode(entry.value)};

  /// One undo or redo step as stored: what it puts back and what its change
  /// left, with null for a document removed.
  static Map<String, Object?> _record(HistoryStep step) => <String, Object?>{
        'label': step.label,
        'documents': <String, Object?>{for (final MapEntry<String, AnimusDocument?> entry in step.documents.entries) entry.key: entry.value?.toJson()},
        'after': <String, Object?>{for (final MapEntry<String, AnimusDocument?> entry in step.after.entries) entry.key: entry.value?.toJson()},
        if (step.resources.isNotEmpty)
          'resources': <String, Object?>{
            for (final MapEntry<String, Uint8List?> entry in step.resources.entries) entry.key: entry.value == null ? null : base64Encode(entry.value!),
          },
      };

  static HistoryStep _stepOf(Map<String, Object?> step) => HistoryStep(
        step['label'] is String ? step['label']! as String : unlabeledStepLabel,
        _documentsOf(step['documents']),
        _documentsOf(step['after']),
        resources: <String, Uint8List?>{
          for (final MapEntry<String, Object?> entry in ((step['resources'] as Map<String, Object?>?) ?? const <String, Object?>{}).entries)
            entry.key: entry.value is String ? base64Decode(entry.value! as String) : null,
        },
      );

  static Map<String, AnimusDocument?> _documentsOf(Object? stored) => <String, AnimusDocument?>{
        for (final MapEntry<String, Object?> entry in ((stored as Map<String, Object?>?) ?? const <String, Object?>{}).entries)
          entry.key: entry.value is Map<String, Object?> ? AnimusDocument.fromJson(entry.value! as Map<String, Object?>) : null,
      };
}

/// Workspaces kept in memory: tests, and browsers without IndexedDB.
class MemoryWorkspaceRepository extends WorkspaceRepository {
  MemoryWorkspaceRepository([WorkspaceRecords? records]) : super(records ?? MemoryWorkspaceRecords());
}

/// Document keys whose document or server base differs between [from] and
/// [to], compared by identity: an unchanged document is the same object.
Set<String> changedKeys(WorkspaceSnapshot from, WorkspaceSnapshot to) {
  final Set<String> keys = <String>{};
  if (!identical(from.documents, to.documents)) {
    for (final MapEntry<String, AnimusDocument> entry in to.byKey.entries) {
      if (!identical(from.byKey[entry.key], entry.value)) keys.add(entry.key);
    }
    keys.addAll(from.byKey.keys.where((String key) => !to.byKey.containsKey(key)));
  }
  final Map<String, ServerBase> before = from.server?.bases ?? const <String, ServerBase>{};
  final Map<String, ServerBase> after = to.server?.bases ?? const <String, ServerBase>{};
  if (!identical(before, after)) {
    for (final MapEntry<String, ServerBase> entry in after.entries) {
      if (!identical(before[entry.key], entry.value)) keys.add(entry.key);
    }
    keys.addAll(before.keys.where((String key) => !after.containsKey(key)));
  }
  return keys;
}

/// Resource paths whose bytes differ between [from] and [to].
Set<String> changedResources(WorkspaceSnapshot from, WorkspaceSnapshot to) => identical(from.resources, to.resources)
    ? const <String>{}
    : <String>{
        for (final String path in <String>{...from.resources.keys, ...to.resources.keys})
          if (!identical(from.resources[path], to.resources[path])) path,
      };

WorkspaceSummary summarize(WorkspaceSnapshot snapshot) =>
    WorkspaceSummary(snapshot.id, snapshot.name, snapshot.generation, snapshot.documents.length, serverId: snapshot.server?.serverId);
