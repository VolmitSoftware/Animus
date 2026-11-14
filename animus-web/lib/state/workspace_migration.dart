/// Moves workspaces saved by older editors onto the current layout and
/// document ids.
///
/// Versions 1 to 3 stored a workspace as one record of JSON text, which
/// [decodeLegacyWorkspace] reads; the repository then writes it as split
/// records (version 4). Version 1 workspaces gave holograms and text animations plain path ids
/// (`town/welcome`), and versions 1 and 2 did so for holographic menus
/// (`lobby/main`); the server names them `namespace:path` (`town:welcome`,
/// `lobby:main`), which version 3 uses. A one-segment file
/// (`gui/holograms/spawn.json`) has no namespace, which the server refuses, so
/// it moves into the `gloss` namespace as the server's Gloss import would
/// place it. Their undo and redo steps were whole workspaces too, which
/// [convertLegacyHistory] turns into steps of what each changed.
library;

import 'dart:convert';
import 'dart:typed_data';

import '../logic/data_folder.dart';
import '../model/animus_document.dart';
import '../model/document_kind.dart';
import '../model/server_binding.dart';
import 'workspace_repository.dart';

/// The last single-record version; [decodeLegacyWorkspace] reads 1 to this.
const int legacySchemaVersion = 3;

/// A stored header from the single-record layout: the workspace is its `text`.
bool isLegacyHeader(Map<String, Object?> header) => header['text'] is String;

/// An undo or redo step from the single-record layout: a whole workspace.
bool isLegacyStep(Map<String, Object?> step) => step['text'] is String && step['documents'] == null;

/// A workspace stored as one record of JSON text (versions 1 to 3), with
/// older document ids moved.
WorkspaceSnapshot decodeLegacyWorkspace(String text) {
  final (Map<String, Object?> json, int version) = _legacyJson(text);
  List<AnimusDocument> documents = <AnimusDocument>[
    for (final Object? document in json['documents']! as List<Object?>) AnimusDocument.fromJson(document! as Map<String, Object?>),
  ];
  ServerBinding? server = json['server'] is Map<String, Object?> ? ServerBinding.fromJson(json['server']! as Map<String, Object?>) : null;
  if (version < legacySchemaVersion) {
    final ({List<AnimusDocument> documents, ServerBinding? server}) migrated = migrateWorkspace(documents, server);
    documents = migrated.documents;
    server = migrated.server;
  }
  return WorkspaceSnapshot(
    id: json['id']! as String,
    name: json['name']! as String,
    generation: (json['generation']! as num).toInt(),
    documents: documents,
    resources: _legacyResources(json),
    server: server,
  );
}

/// An old workspace text's JSON and its schema version, 1 to [legacySchemaVersion].
(Map<String, Object?>, int) _legacyJson(String text) {
  final Map<String, Object?> json = jsonDecode(text) as Map<String, Object?>;
  final Object? version = json['schemaVersion'];
  if (version is! int || version < 1 || version > legacySchemaVersion) throw FormatException('Unsupported workspace schemaVersion $version');
  return (json, version);
}

Map<String, Uint8List> _legacyResources(Map<String, Object?> json) => <String, Uint8List>{
      if (json['resources'] is Map<String, Object?>)
        for (final MapEntry<String, Object?> entry in (json['resources']! as Map<String, Object?>).entries)
          if (entry.value is String) entry.key: base64Decode(entry.value! as String),
    };

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

/// How much old step text, in characters, the kept steps may hold before
/// only the newest [legacyHistoryFallback] are converted.
const int legacyHistoryBudget = 32 * 1024 * 1024;

/// How many of the newest old steps are converted when they pass [legacyHistoryBudget].
const int legacyHistoryFallback = 10;

/// The label of an older step stored without one.
const String unlabeledStepLabel = 'an earlier change';

/// Old whole-workspace undo or redo [steps], oldest first, as steps that hold
/// only what each one changes. The newest applies to [current], each older
/// one to the result of the one above it. Keeps the newest [limit], or the
/// newest [legacyHistoryFallback] when their text passes [budget]. A step
/// that does not decode ends the conversion; the newer ones are kept.
List<HistoryStep> convertLegacyHistory(
  List<Map<String, Object?>> steps,
  WorkspaceSnapshot current, {
  required int limit,
  int budget = legacyHistoryBudget,
}) {
  List<Map<String, Object?>> kept = steps.length > limit ? steps.sublist(steps.length - limit) : steps;
  final int size = kept.fold(0, (int total, Map<String, Object?> step) => total + (step['text'] is String ? (step['text']! as String).length : 0));
  if (size > budget && kept.length > legacyHistoryFallback) kept = kept.sublist(kept.length - legacyHistoryFallback);
  final List<HistoryStep> converted = <HistoryStep>[];
  _Contents newer = _Contents.of(current);
  for (final Map<String, Object?> step in kept.reversed) {
    if (!isLegacyStep(step)) break;
    final Object? label = step['label'];
    try {
      final _Contents older = _Contents.decode(step['text']! as String);
      converted.add(older.stepFrom(newer, label is String ? label : unlabeledStepLabel));
      newer = older;
    } on FormatException {
      break;
    } on TypeError {
      break;
    }
  }
  return converted.reversed.toList();
}

/// A workspace's documents and resources, compared by value. Documents stay
/// records as stored, by `kind:id`, until a step needs one parsed.
class _Contents {
  const _Contents(this.documents, this.resources);

  _Contents.of(WorkspaceSnapshot workspace)
      : documents = <String, Map<String, Object?>>{
          for (final MapEntry<String, AnimusDocument> entry in workspace.byKey.entries) entry.key: entry.value.toJson(),
        },
        resources = workspace.resources;

  /// An old step's workspace [text], with the ids versions 1 and 2 used moved.
  factory _Contents.decode(String text) {
    final (Map<String, Object?> json, int version) = _legacyJson(text);
    final Map<String, Map<String, Object?>> documents = <String, Map<String, Object?>>{};
    for (final Object? stored in json['documents']! as List<Object?>) {
      Map<String, Object?> record = stored! as Map<String, Object?>;
      final DocumentKind kind = DocumentKind.parse(record['kind']! as String)!;
      final ({String id, String path})? moved =
          version < legacySchemaVersion ? migratedLocation(kind, record['id']! as String, record['path']! as String) : null;
      if (moved != null) record = <String, Object?>{...record, 'id': moved.id, 'path': moved.path};
      documents[documentKey(kind, record['id']! as String)] = record;
    }
    return _Contents(documents, _legacyResources(json));
  }

  final Map<String, Map<String, Object?>> documents;
  final Map<String, Uint8List> resources;

  /// The step that turns [newer] back into these contents: the documents and
  /// resources that differ, null for one only [newer] has, each as [newer]
  /// holds it for what the change left.
  HistoryStep stepFrom(_Contents newer, String label) {
    final Map<String, AnimusDocument?> back = <String, AnimusDocument?>{
      for (final String key in newer.documents.keys)
        if (!documents.containsKey(key)) key: null,
      for (final MapEntry<String, Map<String, Object?>> entry in documents.entries)
        if (!_sameDocument(entry.value, newer.documents[entry.key])) entry.key: AnimusDocument.fromJson(entry.value),
    };
    return HistoryStep(
      label,
      back,
      <String, AnimusDocument?>{
        for (final String key in back.keys) key: newer.documents[key] == null ? null : AnimusDocument.fromJson(newer.documents[key]!),
      },
      resources: <String, Uint8List?>{
        for (final String path in newer.resources.keys)
          if (!resources.containsKey(path)) path: null,
        for (final MapEntry<String, Uint8List> entry in resources.entries)
          if (!_sameBytes(entry.value, newer.resources[entry.key])) entry.key: entry.value,
      },
    );
  }

  static bool _sameDocument(Map<String, Object?> record, Map<String, Object?>? other) =>
      other != null && record['path'] == other['path'] && record['source'] == other['source'];

  static bool _sameBytes(Uint8List bytes, Uint8List? other) {
    if (other == null || bytes.length != other.length) return false;
    for (int i = 0; i < bytes.length; i++) {
      if (bytes[i] != other[i]) return false;
    }
    return true;
  }
}
