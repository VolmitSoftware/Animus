import 'document_kind.dart';

/// `kind:id`, the key documents are tracked by.
String documentKey(DocumentKind kind, String id) => '${kind.wireName}:$id';

(DocumentKind, String)? parseDocumentKey(String key) {
  final int colon = key.indexOf(':');
  if (colon < 0) return null;
  final DocumentKind? kind = DocumentKind.parse(key.substring(0, colon));
  return kind == null ? null : (kind, key.substring(colon + 1));
}

/// The server version a draft started from: what `baseRevision` and
/// `baseHash` send, and the base of a three-way merge.
class ServerBase {
  const ServerBase({required this.revision, required this.hash, required this.path, required this.source});

  final int revision;
  final String hash;
  final String path;

  /// The stored document, parsed.
  final Object? source;

  Map<String, Object?> toJson() => <String, Object?>{'revision': revision, 'hash': hash, 'path': path, 'source': source};

  static ServerBase fromJson(Map<String, Object?> json) => ServerBase(
        revision: (json['revision']! as num).toInt(),
        hash: json['hash']! as String,
        path: json['path']! as String,
        source: json['source'],
      );
}

/// A change set sent but not yet answered. Kept in the workspace so that
/// after a reload or reconnect `GET /v1/changes/{key}` says what happened.
class PendingChange {
  const PendingChange({required this.idempotencyKey, required this.documents, required this.sentAt, this.undoOf, this.message});

  final String idempotencyKey;

  /// Document keys in the change set.
  final List<String> documents;
  final DateTime sentAt;

  /// For an undo, the change id it undoes.
  final String? undoOf;
  final String? message;

  Map<String, Object?> toJson() => <String, Object?>{
        'idempotencyKey': idempotencyKey,
        'documents': documents,
        'sentAt': sentAt.toUtc().toIso8601String(),
        if (undoOf != null) 'undoOf': undoOf,
        if (message != null) 'message': message,
      };

  static PendingChange fromJson(Map<String, Object?> json) => PendingChange(
        idempotencyKey: json['idempotencyKey']! as String,
        documents: <String>[for (final Object? key in json['documents']! as List<Object?>) '$key'],
        sentAt: DateTime.parse(json['sentAt']! as String),
        undoOf: json['undoOf'] as String?,
        message: json['message'] as String?,
      );
}

/// An applied change set the server can undo through its rollback receipt.
class AppliedChange {
  const AppliedChange({required this.changeId, required this.idempotencyKey, required this.documents, required this.appliedAt, this.undoOf, this.undone = false});

  final String changeId;
  final String idempotencyKey;
  final List<String> documents;
  final DateTime appliedAt;

  /// Set when this change set was itself an undo.
  final String? undoOf;
  final bool undone;

  AppliedChange markUndone() =>
      AppliedChange(changeId: changeId, idempotencyKey: idempotencyKey, documents: documents, appliedAt: appliedAt, undoOf: undoOf, undone: true);

  Map<String, Object?> toJson() => <String, Object?>{
        'changeId': changeId,
        'idempotencyKey': idempotencyKey,
        'documents': documents,
        'appliedAt': appliedAt.toUtc().toIso8601String(),
        if (undoOf != null) 'undoOf': undoOf,
        if (undone) 'undone': true,
      };

  static AppliedChange fromJson(Map<String, Object?> json) => AppliedChange(
        changeId: json['changeId']! as String,
        idempotencyKey: json['idempotencyKey']! as String,
        documents: <String>[for (final Object? key in json['documents']! as List<Object?>) '$key'],
        appliedAt: DateTime.parse(json['appliedAt']! as String),
        undoOf: json['undoOf'] as String?,
        undone: json['undone'] == true,
      );
}

/// Ties a workspace to a paired server: the base of every document opened
/// from it, unanswered change sets and the applied history.
class ServerBinding {
  const ServerBinding({
    required this.serverId,
    required this.fingerprint,
    this.bases = const <String, ServerBase>{},
    this.pending = const <PendingChange>[],
    this.history = const <AppliedChange>[],
  });

  final String serverId;
  final String fingerprint;
  final Map<String, ServerBase> bases;
  final List<PendingChange> pending;

  /// Newest last; at most [historyLimit].
  final List<AppliedChange> history;

  static const int historyLimit = 50;

  ServerBinding copyWith({Map<String, ServerBase>? bases, List<PendingChange>? pending, List<AppliedChange>? history}) => ServerBinding(
        serverId: serverId,
        fingerprint: fingerprint,
        bases: bases ?? this.bases,
        pending: pending ?? this.pending,
        history: history == null
            ? this.history
            : (history.length > historyLimit ? history.sublist(history.length - historyLimit) : history),
      );

  Map<String, Object?> toJson() => <String, Object?>{
        'serverId': serverId,
        'fingerprint': fingerprint,
        'bases': <String, Object?>{for (final MapEntry<String, ServerBase> entry in bases.entries) entry.key: entry.value.toJson()},
        'pending': <Object?>[for (final PendingChange change in pending) change.toJson()],
        'history': <Object?>[for (final AppliedChange change in history) change.toJson()],
      };

  static ServerBinding fromJson(Map<String, Object?> json) => ServerBinding(
        serverId: json['serverId']! as String,
        fingerprint: json['fingerprint']! as String,
        bases: <String, ServerBase>{
          for (final MapEntry<String, Object?> entry in ((json['bases'] as Map<String, Object?>?) ?? const <String, Object?>{}).entries)
            entry.key: ServerBase.fromJson(entry.value! as Map<String, Object?>),
        },
        pending: <PendingChange>[
          for (final Object? entry in (json['pending'] as List<Object?>?) ?? const <Object?>[])
            PendingChange.fromJson(entry! as Map<String, Object?>),
        ],
        history: <AppliedChange>[
          for (final Object? entry in (json['history'] as List<Object?>?) ?? const <Object?>[])
            AppliedChange.fromJson(entry! as Map<String, Object?>),
        ],
      );
}

/// Deep equality of decoded JSON values (maps ignore key order).
bool jsonEquals(Object? a, Object? b) {
  if (a is Map<String, Object?> && b is Map<String, Object?>) {
    if (a.length != b.length) return false;
    for (final MapEntry<String, Object?> entry in a.entries) {
      if (!b.containsKey(entry.key) || !jsonEquals(entry.value, b[entry.key])) return false;
    }
    return true;
  }
  if (a is List<Object?> && b is List<Object?>) {
    if (a.length != b.length) return false;
    for (int index = 0; index < a.length; index++) {
      if (!jsonEquals(a[index], b[index])) return false;
    }
    return true;
  }
  if (a is num && b is num) return a == b;
  return a == b;
}
