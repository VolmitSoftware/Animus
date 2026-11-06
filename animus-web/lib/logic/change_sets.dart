import 'dart:math' as math;

import '../model/animus_document.dart';
import '../model/document_kind.dart';
import '../model/server_binding.dart';

/// How a workspace document stands against the server version it came from.
enum DraftState {
  /// Same as the server's version.
  clean,

  /// Edited in this browser.
  modified,

  /// Made in this browser; the server does not have it.
  created,

  /// Removed in this browser; the server still has it.
  deleted,
}

class DraftChange {
  const DraftChange(this.key, this.kind, this.id, this.state, this.change, {this.problem});

  final String key;
  final DocumentKind kind;
  final String id;
  final DraftState state;

  /// The `change` entry for a change set; null when [problem] blocks it.
  final Map<String, Object?>? change;

  /// Why the draft cannot be sent (its text is not a JSON object).
  final String? problem;
}

DraftState draftState(AnimusDocument? document, ServerBase? base) {
  if (document == null) return base == null ? DraftState.clean : DraftState.deleted;
  if (base == null) return DraftState.created;
  return jsonEquals(document.json, base.source) ? DraftState.clean : DraftState.modified;
}

/// Every draft that differs from its server base, as `change` entries in
/// workspace order, deletions last. [only] limits it to those keys.
List<DraftChange> pendingDrafts(List<AnimusDocument> documents, ServerBinding binding, {Set<String>? only}) {
  final List<DraftChange> drafts = <DraftChange>[];
  final Set<String> present = <String>{};
  for (final AnimusDocument document in documents) {
    final String key = documentKey(document.kind, document.id);
    present.add(key);
    if (only != null && !only.contains(key)) continue;
    final ServerBase? base = binding.bases[key];
    final DraftState state = draftState(document, base);
    if (state == DraftState.clean) continue;
    final Object? json = document.json;
    if (json is! Map<String, Object?>) {
      drafts.add(DraftChange(key, document.kind, document.id, state, null, problem: 'The draft is not a JSON object.'));
      continue;
    }
    drafts.add(DraftChange(key, document.kind, document.id, state, <String, Object?>{
      'kind': document.kind.wireName,
      'id': document.id,
      'baseRevision': base?.revision,
      'baseHash': base?.hash,
      'source': json,
    }));
  }
  for (final MapEntry<String, ServerBase> entry in binding.bases.entries) {
    if (present.contains(entry.key) || (only != null && !only.contains(entry.key))) continue;
    final (DocumentKind, String)? parsed = parseDocumentKey(entry.key);
    if (parsed == null) continue;
    drafts.add(DraftChange(entry.key, parsed.$1, parsed.$2, DraftState.deleted, <String, Object?>{
      'kind': parsed.$1.wireName,
      'id': parsed.$2,
      'baseRevision': entry.value.revision,
      'baseHash': entry.value.hash,
      'delete': true,
    }));
  }
  return drafts;
}

/// A fresh idempotency key: 24 base64url characters (144 random bits).
String newIdempotencyKey([math.Random? random]) {
  final math.Random source = random ?? math.Random.secure();
  const String alphabet = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_';
  return String.fromCharCodes(<int>[for (int index = 0; index < 24; index++) alphabet.codeUnitAt(source.nextInt(64))]);
}

/// The server's answer to a change set or undo, from `change-response`.
sealed class ChangeOutcome {
  const ChangeOutcome(this.idempotencyKey);

  final String idempotencyKey;

  static ChangeOutcome? parse(Object? body) {
    if (body is! Map<String, Object?>) return null;
    final String key = '${body['idempotencyKey']}';
    switch (body['status']) {
      case 'applied':
        return ChangeApplied(
          key,
          '${body['changeId']}',
          <AppliedDocument>[
            for (final Object? raw in (body['documents'] as List<Object?>?) ?? const <Object?>[])
              if (raw is Map<String, Object?>)
                AppliedDocument(DocumentKind.parse('${raw['kind']}')!, '${raw['id']}', (raw['revision']! as num).toInt(), raw['hash'] as String?),
          ],
        );
      case 'conflict':
        return ChangeConflicted(key, <ServerConflict>[
          for (final Object? raw in (body['conflicts'] as List<Object?>?) ?? const <Object?>[])
            if (raw is Map<String, Object?>) ServerConflict.fromJson(raw),
        ]);
      case 'invalid':
        return ChangeInvalid(key, <String, List<Diagnostic>>{
          for (final Object? raw in (body['documents'] as List<Object?>?) ?? const <Object?>[])
            if (raw is Map<String, Object?>) '${raw['kind']}:${raw['id']}': diagnosticsOf(raw['diagnostics']),
        });
    }
    return null;
  }
}

class AppliedDocument {
  const AppliedDocument(this.kind, this.id, this.revision, this.hash);

  final DocumentKind kind;
  final String id;
  final int revision;

  /// Null after a delete.
  final String? hash;

  String get key => documentKey(kind, id);
}

class ChangeApplied extends ChangeOutcome {
  const ChangeApplied(super.idempotencyKey, this.changeId, this.documents);

  final String changeId;
  final List<AppliedDocument> documents;
}

class ChangeConflicted extends ChangeOutcome {
  const ChangeConflicted(super.idempotencyKey, this.conflicts);

  final List<ServerConflict> conflicts;
}

class ChangeInvalid extends ChangeOutcome {
  const ChangeInvalid(super.idempotencyKey, this.diagnostics);

  /// Per document key.
  final Map<String, List<Diagnostic>> diagnostics;
}

/// One `conflict`: the server's version now, or null when it is gone.
class ServerConflict {
  const ServerConflict(this.kind, this.id, this.current);

  final DocumentKind kind;
  final String id;
  final ServerBase? current;

  String get key => documentKey(kind, id);

  static ServerConflict fromJson(Map<String, Object?> json) {
    final Object? current = json['current'];
    final DocumentKind kind = DocumentKind.parse('${json['kind']}')!;
    final String id = '${json['id']}';
    return ServerConflict(
      kind,
      id,
      current is Map<String, Object?>
          ? ServerBase(revision: (current['revision']! as num).toInt(), hash: '${current['hash']}', path: '', source: current['source'])
          : null,
    );
  }
}

List<Diagnostic> diagnosticsOf(Object? raw) => <Diagnostic>[
      for (final Object? entry in raw is List<Object?> ? raw : const <Object?>[])
        if (entry is Map<String, Object?> && entry['severity'] is String && entry['path'] is String && entry['message'] is String)
          Diagnostic.fromJson(entry),
    ];

/// The binding after the server applied [applied]: new bases for the
/// documents it wrote ([sources] holds what the editor sent, with the
/// server's new revision), none for deleted ones, the pending entry gone and
/// the change in the history.
ServerBinding recordApplied(
  ServerBinding binding,
  ChangeApplied applied,
  Map<String, ({String path, Object? source})> sources, {
  String? undoOf,
  DateTime? at,
}) {
  final Map<String, ServerBase> bases = Map<String, ServerBase>.of(binding.bases);
  for (final AppliedDocument document in applied.documents) {
    final String? hash = document.hash;
    if (hash == null) {
      bases.remove(document.key);
      continue;
    }
    final ({String path, Object? source})? sent = sources[document.key];
    if (sent == null) continue;
    bases[document.key] = ServerBase(revision: document.revision, hash: hash, path: sent.path, source: sent.source);
  }
  final List<AppliedChange> history = <AppliedChange>[
    for (final AppliedChange change in binding.history)
      if (change.changeId == undoOf) change.markUndone() else change,
    if (!binding.history.any((AppliedChange change) => change.changeId == applied.changeId))
      AppliedChange(
        changeId: applied.changeId,
        idempotencyKey: applied.idempotencyKey,
        documents: <String>[for (final AppliedDocument document in applied.documents) document.key],
        appliedAt: at ?? DateTime.now().toUtc(),
        undoOf: undoOf,
      ),
  ];
  return binding.copyWith(
    bases: bases,
    pending: <PendingChange>[for (final PendingChange change in binding.pending) if (change.idempotencyKey != applied.idempotencyKey) change],
    history: history,
  );
}

/// The source a document holds after the server stored it: the server sets
/// `revision` to its new revision on revisioned kinds.
Object? withServerRevision(DocumentKind kind, Object? source, int revision) {
  if (source is! Map<String, Object?> || !revisionedKinds.contains(kind)) return source;
  return <String, Object?>{...source, 'revision': revision};
}

/// Kinds whose stored `revision` the server raises on apply.
const Set<DocumentKind> revisionedKinds = <DocumentKind>{
  DocumentKind.npc,
  DocumentKind.group,
  DocumentKind.behavior,
  DocumentKind.shop,
  DocumentKind.inventory,
  DocumentKind.hologram,
  DocumentKind.presets,
  DocumentKind.rig,
  DocumentKind.animation,
};
