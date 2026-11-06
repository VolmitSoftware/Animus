import 'package:animus_editor/model/document_kind.dart';

import 'documents.dart';
import 'messages.dart';
import 'protocol.dart';
import 'validation.dart';

/// What a change did. [status] is the HTTP status; [committed] is true when
/// documents were written.
class Outcome {
  const Outcome(this.status, this.body, {this.committed = false, this.catalog = const <Map<String, Object?>>[], this.deleted = const <Map<String, Object?>>[]});

  final int status;
  final Map<String, Object?> body;
  final bool committed;
  final List<Map<String, Object?>> catalog;
  final List<Map<String, Object?>> deleted;
}

/// How to reverse one written document: its content before (null when the
/// change created it) and the hash it had after (null after a delete).
class _Inverse {
  const _Inverse(this.location, this.before, this.afterRevision, this.afterHash);

  final Located location;
  final String? before;
  final int afterRevision;
  final String? afterHash;
}

class _Record {
  _Record(this.key, this.requestHash, this.status, this.changeId, this.result, this.inverses) : created = now();

  final String key;
  final String requestHash;
  final String status;
  final String? changeId;
  final Map<String, Object?> result;
  final List<_Inverse> inverses;
  final DateTime created;
}

class _Write {
  const _Write(this.location, this.content);

  final Located location;
  final String? content;
}

/// Change sets, idempotency records and undo, as EditorChanges with memory
/// in place of the journal.
class FakeChanges {
  FakeChanges(this.documents);

  final FakeDocuments documents;
  final Map<String, _Record> _records = <String, _Record>{};
  final Map<String, String> _byChangeId = <String, String>{};

  /// Applied change ids in apply order (undos included).
  List<String> get appliedChangeIds => <String>[
        for (final _Record record in _records.values)
          if (record.changeId != null) record.changeId!,
      ];

  Map<String, String> get statuses => <String, String>{
        for (final _Record record in _records.values) record.key: record.status,
      };

  void clear() {
    _records.clear();
    _byChangeId.clear();
  }

  Outcome apply(ChangeSet set, String requestHash) {
    final Outcome? replayed = _replay(set.idempotencyKey, requestHash);
    if (replayed != null) return replayed;
    final List<Map<String, Object?>> conflicts = <Map<String, Object?>>[];
    final List<Map<String, Object?>> invalidDocuments = <Map<String, Object?>>[];
    final List<Map<String, Object?>> applied = <Map<String, Object?>>[];
    final List<Map<String, Object?>> catalog = <Map<String, Object?>>[];
    final List<Map<String, Object?>> deleted = <Map<String, Object?>>[];
    final List<_Write> writes = <_Write>[];
    final List<_Inverse> inverses = <_Inverse>[];
    for (final Change change in set.documents) {
      final Located location = documents.locate(change.kind, change.id);
      final StoredDocument? current = documents.at(location.path);
      // A move's base is the document at its old id; the new id must be free.
      final String? from = change.from;
      final Located? origin = from == null ? null : documents.locate(change.kind, from);
      final StoredDocument? base = origin == null ? current : documents.at(origin.path);
      if (origin != null && current != null) {
        conflicts.add(_conflict(change.kind, change.id, null, null, current));
        continue;
      }
      final bool matches = change.baseRevision == null
          ? base == null
          : base != null && base.revision == change.baseRevision && base.hash == change.baseHash;
      if (!matches) {
        conflicts.add(_conflict(change.kind, origin?.id ?? change.id, change.baseRevision, change.baseHash, base));
        continue;
      }
      if (origin != null) {
        writes.add(_Write(origin, null));
        inverses.add(_Inverse(origin, base!.content, base.revision, null));
        deleted.add(<String, Object?>{'kind': change.kind.wireName, 'id': origin.id});
      }
      if (change.delete) {
        writes.add(_Write(location, null));
        inverses.add(_Inverse(location, current!.content, current.revision, null));
        applied.add(_version(change.kind, change.id, current.revision, null));
        deleted.add(<String, Object?>{'kind': change.kind.wireName, 'id': change.id});
        continue;
      }
      final Map<String, Object?> source = copyJson(change.source)! as Map<String, Object?>;
      if (revisioned.contains(change.kind)) source['revision'] = (base?.revision ?? 0) + 1;
      final String content = prettyJson(source);
      // Kinds without a server revision report what their stored text says (0 for Gloss menus).
      final int revision = revisionOf(content);
      final List<Map<String, Object?>> diagnostics = validateDocument(change.kind, change.id, location.path, source);
      if (change.kind == DocumentKind.npc) _identity(location, base, source, diagnostics, leaving: origin);
      if (hasErrors(diagnostics)) invalidDocuments.add(documentDiagnostics(change.kind, change.id, diagnostics));
      final String hash = 'sha256:${sha256Text(content)}';
      writes.add(_Write(location, content));
      inverses.add(_Inverse(location, current?.content, revision, hash));
      applied.add(<String, Object?>{..._version(change.kind, change.id, revision, hash), if (origin != null) 'from': <String, Object?>{'id': origin.id}});
      catalog.add(_entry(location, revision, hash));
    }
    if (conflicts.isNotEmpty) {
      final Map<String, Object?> body = <String, Object?>{'status': 'conflict', 'idempotencyKey': set.idempotencyKey, 'conflicts': conflicts};
      _remember(_Record(set.idempotencyKey, requestHash, 'conflict', null, body, const <_Inverse>[]));
      return Outcome(409, body);
    }
    if (invalidDocuments.isNotEmpty) {
      final Map<String, Object?> body = <String, Object?>{'status': 'invalid', 'idempotencyKey': set.idempotencyKey, 'documents': invalidDocuments};
      _remember(_Record(set.idempotencyKey, requestHash, 'invalid', null, body, const <_Inverse>[]));
      return Outcome(422, body);
    }
    return _commit(set.idempotencyKey, requestHash, writes, inverses, applied, catalog, deleted);
  }

  /// Undoes an applied change. A later edit to one of its documents comes
  /// back as a conflict. [scopes] checks the token may apply every kind.
  Outcome undo(String changeId, String key, void Function(Set<DocumentKind> kinds) scopes) {
    final String? original = _byChangeId[changeId];
    final _Record? record = original == null ? null : _load(original);
    if (record == null || record.status != 'applied') {
      throw ApiError('not-found', 'No applied change $changeId is on record; records are kept ${limit('idempotencyHours')} hours.');
    }
    scopes(<DocumentKind>{for (final _Inverse inverse in record.inverses) inverse.location.kind});
    final String requestHash = 'undo:$changeId';
    final Outcome? replayed = _replay(key, requestHash);
    if (replayed != null) return replayed;
    final List<Map<String, Object?>> conflicts = <Map<String, Object?>>[];
    final Map<String, Map<String, Object?>> result = <String, Map<String, Object?>>{};
    final List<Map<String, Object?>> catalog = <Map<String, Object?>>[];
    final List<Map<String, Object?>> deleted = <Map<String, Object?>>[];
    final List<_Write> writes = <_Write>[];
    final List<_Inverse> inverses = <_Inverse>[];
    // A move's old id is answered with from naming the id it leaves again.
    final Map<String, String> movedBack = <String, String>{
      for (final Object? raw in record.result['documents']! as List<Object?>)
        if (raw is Map<String, Object?> && raw['from'] is Map<String, Object?>)
          '${raw['kind']}\n${(raw['from']! as Map<String, Object?>)['id']}': '${raw['id']}',
    };
    for (final _Inverse inverse in record.inverses) {
      final Located location = inverse.location;
      final StoredDocument? current = documents.at(location.path);
      if (current?.hash != inverse.afterHash) {
        conflicts.add(_conflict(location.kind, location.id, inverse.afterHash == null ? null : inverse.afterRevision, inverse.afterHash, current));
        continue;
      }
      writes.add(_Write(location, inverse.before));
      final String? before = inverse.before;
      final int revision = before == null ? inverse.afterRevision : revisionOf(before);
      final String? hash = before == null ? null : 'sha256:${sha256Text(before)}';
      inverses.add(_Inverse(location, current?.content, revision, hash));
      result['${location.kind.wireName}\n${location.id}'] = _version(location.kind, location.id, revision, hash);
      if (hash == null) {
        deleted.add(<String, Object?>{'kind': location.kind.wireName, 'id': location.id});
      } else {
        catalog.add(_entry(location, revision, hash));
      }
    }
    if (conflicts.isNotEmpty) {
      final Map<String, Object?> body = <String, Object?>{'status': 'conflict', 'idempotencyKey': key, 'conflicts': conflicts};
      _remember(_Record(key, requestHash, 'conflict', null, body, const <_Inverse>[]));
      return Outcome(409, body);
    }
    if (writes.isEmpty) throw ApiError('not-found', 'Change $changeId wrote no editor documents to undo.');
    movedBack.forEach((String back, String leaving) {
      final String kind = back.substring(0, back.indexOf('\n'));
      final Map<String, Object?>? restored = result[back];
      final Map<String, Object?>? gone = result['$kind\n$leaving'];
      if (restored == null || gone == null || gone['hash'] != null) return;
      result.remove('$kind\n$leaving');
      restored['from'] = <String, Object?>{'id': leaving};
    });
    return _commit(key, requestHash, writes, inverses, <Map<String, Object?>>[...result.values], catalog, deleted);
  }

  /// `GET /v1/changes/{key}`.
  Map<String, Object?> status(String key) {
    final _Record? record = _load(key);
    if (record == null) return <String, Object?>{'idempotencyKey': key, 'status': 'unknown'};
    return <String, Object?>{'idempotencyKey': key, 'status': record.status, 'result': record.result};
  }

  Outcome _commit(String key, String requestHash, List<_Write> writes, List<_Inverse> inverses, List<Map<String, Object?>> applied,
      List<Map<String, Object?>> catalog, List<Map<String, Object?>> deleted) {
    final String changeId = randomUuid();
    final Map<String, Object?> body = <String, Object?>{'status': 'applied', 'changeId': changeId, 'idempotencyKey': key, 'documents': applied};
    for (final _Write write in writes) {
      final String? content = write.content;
      if (content == null) {
        documents.delete(write.location.path);
      } else {
        documents.write(write.location, content);
      }
    }
    _remember(_Record(key, requestHash, 'applied', changeId, body, inverses));
    _byChangeId[changeId] = key;
    return Outcome(200, body, committed: true, catalog: catalog, deleted: deleted);
  }

  Outcome? _replay(String key, String requestHash) {
    final _Record? record = _load(key);
    if (record == null) return null;
    if (record.requestHash != requestHash) {
      throw invalid('idempotencyKey $key was already used for a different request; use a new key.');
    }
    final int status = switch (record.status) { 'applied' => 200, 'conflict' => 409, _ => 422 };
    return Outcome(status, record.result);
  }

  _Record? _load(String key) {
    final _Record? record = _records[key];
    if (record == null) return null;
    if (record.created.add(Duration(hours: limit('idempotencyHours'))).isBefore(now())) {
      _records.remove(key);
      if (record.changeId != null) _byChangeId.remove(record.changeId);
      return null;
    }
    return record;
  }

  void _remember(_Record record) => _records[record.key] = record;

  /// An NPC keeps its uuid, and a new NPC's short id and uuid are unique.
  /// [current] is a move's document at its old id, which [leaving] names.
  void _identity(Located location, StoredDocument? current, Map<String, Object?> source, List<Map<String, Object?>> diagnostics, {Located? leaving}) {
    final Object? uuid = source['uuid'];
    if (current != null) {
      final Object? before = sourceOf(current.content)['uuid'];
      if (uuid is String && before is String && before != uuid) {
        diagnostics.add(diagnostic('error', r'$.uuid', 'an NPC keeps its uuid; clone it to make a new one', 'identity'));
      }
      if (leaving == null) return;
    }
    if (hasErrors(diagnostics)) return;
    final String shortId = location.id.substring(location.id.lastIndexOf('/') + 1);
    for (final StoredDocument other in documents.list(DocumentKind.npc)) {
      if (other.path == location.path || other.path == leaving?.path) continue;
      if (other.id.substring(other.id.lastIndexOf('/') + 1) == shortId) {
        diagnostics.add(diagnostic('error', r'$.id', 'NPC id $shortId is already used by ${other.id}', 'identity'));
        return;
      }
      if (uuid is String && uuid.isNotEmpty && sourceOf(other.content)['uuid'] == uuid) {
        diagnostics.add(diagnostic('error', r'$.uuid', 'uuid $uuid is already used by ${other.id}', 'identity'));
        return;
      }
    }
  }

  static Map<String, Object?> _conflict(DocumentKind kind, String id, int? baseRevision, String? baseHash, StoredDocument? current) =>
      <String, Object?>{
        'kind': kind.wireName,
        'id': id,
        'baseRevision': baseRevision,
        'baseHash': baseHash,
        'current': current == null
            ? null
            : <String, Object?>{'revision': current.revision, 'hash': current.hash, 'source': sourceOf(current.content)},
      };

  static Map<String, Object?> _version(DocumentKind kind, String id, int revision, String? hash) =>
      <String, Object?>{'kind': kind.wireName, 'id': id, 'revision': revision, 'hash': hash};

  static Map<String, Object?> _entry(Located location, int revision, String hash) =>
      <String, Object?>{'kind': location.kind.wireName, 'id': location.id, 'path': location.path, 'revision': revision, 'hash': hash};
}
