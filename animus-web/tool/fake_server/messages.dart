import 'package:animus_editor/model/document_kind.dart';

import 'protocol.dart';

/// One entry of a change set; [source] null means a delete. A null
/// [baseRevision] means the document must not exist yet.
class Change {
  const Change(this.kind, this.id, this.baseRevision, this.baseHash, this.source);

  final DocumentKind kind;
  final String id;
  final int? baseRevision;
  final String? baseHash;
  final Map<String, Object?>? source;

  bool get delete => source == null;

  String get name => '${kind.wireName}:$id';
}

class ChangeSet {
  const ChangeSet(this.idempotencyKey, this.documents, this.message);

  final String idempotencyKey;
  final List<Change> documents;
  final String? message;
}

/// The body as one JSON object.
Map<String, Object?> bodyObject(FakeRequest request) {
  final Object? body = request.body;
  if (body == null) throw invalid('A JSON body is required.');
  if (body is! Map<String, Object?>) throw invalid('The body must be one JSON object.');
  return body;
}

void fields(Map<String, Object?> object, String at, Set<String> allowed) {
  for (final String key in object.keys) {
    if (!allowed.contains(key)) throw invalid('$at.$key: unknown field');
  }
}

String stringField(Map<String, Object?> object, String key, RegExp pattern, String at) {
  final Object? value = object[key];
  if (value is! String || !pattern.hasMatch(value)) throw invalid('$at: expected a string matching ${pattern.pattern}');
  return value;
}

String? optionalString(Map<String, Object?> object, String key, int maximumLength) {
  if (!object.containsKey(key)) return null;
  final Object? value = object[key];
  if (value is! String || value.length > maximumLength) {
    throw invalid('\$.$key: expected a string of at most $maximumLength characters');
  }
  return value;
}

ChangeSet parseChangeSet(Map<String, Object?> body) {
  fields(body, r'$', <String>{'idempotencyKey', 'documents', 'message'});
  final String key = stringField(body, 'idempotencyKey', keyPattern, r'$.idempotencyKey');
  final String? message = optionalString(body, 'message', 512);
  return ChangeSet(key, parseChanges(body), message);
}

int? _integer(Object? value) {
  if (value is int) return value;
  if (value is double && value == value.roundToDouble() && value.isFinite) return value.toInt();
  return null;
}

/// The `documents` array of a change set, validate request or preview request.
List<Change> parseChanges(Map<String, Object?> body) {
  final int maximum = limit('changeSetDocuments');
  final Object? documents = body['documents'];
  if (documents is! List<Object?>) throw invalid(r'$.documents: expected an array');
  if (documents.isEmpty) throw invalid(r'$.documents: at least one document');
  if (documents.length > maximum) throw ApiError('too-large', '\$.documents: at most $maximum documents');
  final List<Change> changes = <Change>[];
  final Set<String> seen = <String>{};
  for (int index = 0; index < documents.length; index++) {
    final String at = '\$.documents[$index]';
    final Object? element = documents[index];
    if (element is! Map<String, Object?>) throw invalid('$at: expected an object');
    fields(element, at, <String>{'kind', 'id', 'baseRevision', 'baseHash', 'source', 'delete'});
    final String kindName = stringField(element, 'kind', RegExp(r'^[a-z-]{1,32}$'), '$at.kind');
    final DocumentKind kind = DocumentKind.parse(kindName) ?? (throw invalid('$at.kind: unknown kind $kindName'));
    final String id = stringField(element, 'id', documentIdPattern, '$at.id');
    if (!element.containsKey('baseRevision') || !element.containsKey('baseHash')) {
      throw invalid('$at: baseRevision and baseHash are required (null for a new document)');
    }
    final Object? rawRevision = element['baseRevision'];
    int? revision;
    if (rawRevision != null) {
      revision = _integer(rawRevision);
      if (revision == null || revision < 0) throw invalid('$at.baseRevision: expected a non-negative integer or null');
    }
    final String? hash = element['baseHash'] == null ? null : stringField(element, 'baseHash', hashPattern, '$at.baseHash');
    if ((revision == null) != (hash == null)) {
      throw invalid('$at: baseRevision and baseHash are both null for a new document or both set');
    }
    final bool delete = element.containsKey('delete');
    if (delete && element['delete'] != true) throw invalid('$at.delete: expected true');
    if (delete == element.containsKey('source')) throw invalid('$at: give exactly one of source and delete');
    if (delete && revision == null) throw invalid('$at: a delete needs the baseRevision and baseHash of the document it removes');
    Map<String, Object?>? source;
    if (!delete) {
      final Object? raw = element['source'];
      if (raw is! Map<String, Object?>) throw invalid('$at.source: expected an object');
      source = raw;
    }
    if (!seen.add('${kind.wireName}\n$id')) throw invalid('$at: ${kind.wireName} $id appears twice');
    changes.add(Change(kind, id, revision, hash, source));
  }
  return changes;
}
