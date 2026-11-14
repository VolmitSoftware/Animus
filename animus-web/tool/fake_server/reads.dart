import 'dart:convert';
import 'dart:math';

import 'package:animus_editor/model/document_kind.dart';

import 'documents.dart';
import 'messages.dart';
import 'protocol.dart';
import 'tokens.dart';

/// The most refs one `POST /v1/documents/read` names.
const int batchReadRefs = 200;

/// Bytes of entries one catalog page or batch read answer holds: 512 KiB,
/// and over the relay never more than a relay frame less 16 KiB.
int answerBytes(Transport transport) =>
    transport == Transport.relay ? min(512 * 1024, _relayRoom) : 512 * 1024;

int get _relayRoom => limit('relayFrameBytes') - 16 * 1024;

/// The read endpoints: the catalog, one document and the batch read.
class FakeReads {
  FakeReads(this.documents, this.pageSize);

  final FakeDocuments documents;

  /// The most entries one catalog page holds.
  final int pageSize;

  /// `GET /v1/catalog?kind=&after=`; NPCs outside the token's folders are
  /// left out.
  FakeResponse catalog(FakeRequest request, FakeToken token) {
    final Map<String, String> query = request.query;
    for (final String key in query.keys) {
      if (key != 'kind' && key != 'after') throw invalid('\$query.$key: unknown field');
    }
    DocumentKind? kind;
    final String? kindName = query['kind'];
    if (kindName != null && kindName.isNotEmpty) {
      kind = DocumentKind.parse(kindName) ?? (throw invalid('kind: unknown kind $kindName'));
    }
    final String? rawAfter = query['after'];
    final (int, String)? after = rawAfter == null || rawAfter.isEmpty ? null : _cursor(rawAfter);
    final int budget = answerBytes(request.transport);
    final List<Map<String, Object?>> page = <Map<String, Object?>>[];
    int bytes = 0;
    StoredDocument? last;
    String? next;
    for (final StoredDocument document in documents.list(kind)) {
      if (after != null && _compare(document.kind.index, document.path, after) <= 0) continue;
      if (!token.sees(document.kind, document.id)) continue;
      if (page.length == pageSize || (page.isNotEmpty && bytes + document.entryBytes > budget)) {
        next = base64UrlNoPadding(utf8.encode('${last!.kind.index}\n${last.path}'));
        break;
      }
      page.add(document.entry);
      bytes += document.entryBytes;
      last = document;
    }
    return FakeResponse(200, body: <String, Object?>{'documents': page, 'next': next});
  }

  static int _compare(int kind, String path, (int, String) after) =>
      kind != after.$1 ? kind.compareTo(after.$1) : path.compareTo(after.$2);

  static (int, String) _cursor(String value) {
    try {
      final String key = utf8.decode(base64Url.decode(base64Url.normalize(value)));
      final RegExpMatch? match = RegExp(r'^([0-9]{1,2})\n([a-z0-9_./-]{1,1024})$').firstMatch(key);
      if (match != null) return (int.parse(match.group(1)!), match.group(2)!);
    } on FormatException {
      // Refused below.
    }
    throw invalid('after: not a catalog cursor');
  }

  /// `GET /v1/documents/{kind}/{id}`; [segments] start at `documents`.
  FakeResponse document(List<String> segments, FakeToken token) {
    final DocumentKind kind = DocumentKind.parse(segments[1]) ?? (throw ApiError('not-found', 'Unknown kind ${segments[1]}.'));
    final String id = <String>[for (final String segment in segments.sublist(2)) decodePathSegment(segment)].join('/');
    if (!documentIdPattern.hasMatch(id)) throw invalid("'$id' is not a document id.");
    token.reach(kind, id);
    final StoredDocument stored = _find(kind, id) ?? (throw ApiError('not-found', 'No ${kind.wireName} $id.'));
    return FakeResponse(200, body: stored.response);
  }

  /// The document, or null; an id that maps to no path of its kind is
  /// refused as EditorDocuments.pathFor refuses it.
  StoredDocument? _find(DocumentKind kind, String id) {
    documents.locate(kind, id);
    return documents.find(kind, id);
  }

  /// `POST /v1/documents/read`: documents in request order until the answer
  /// is full, per-ref errors, and the refs left for the next call.
  FakeResponse batch(FakeRequest request, FakeToken token) {
    final Map<String, Object?> body = bodyObject(request);
    fields(body, r'$', <String>{'refs'});
    final Object? refs = body['refs'];
    if (refs is! List<Object?> || refs.isEmpty || refs.length > batchReadRefs) throw invalid('refs: 1 to $batchReadRefs {kind, id} references.');
    final List<(String, String)> wanted = <(String, String)>[];
    final Set<String> seen = <String>{};
    for (int index = 0; index < refs.length; index++) {
      final String at = '\$.refs[$index]';
      final Object? ref = refs[index];
      if (ref is! Map<String, Object?>) throw invalid('$at: expected {kind, id}.');
      fields(ref, at, <String>{'kind', 'id'});
      final String kind = _text(ref, 'kind', at), id = _text(ref, 'id', at);
      if (!seen.add('$kind\n$id')) throw invalid('$at: $kind $id is named twice.');
      wanted.add((kind, id));
    }
    final int budget = answerBytes(request.transport);
    final List<Map<String, Object?>> answered = <Map<String, Object?>>[];
    final List<Map<String, Object?>> errors = <Map<String, Object?>>[];
    final List<Map<String, Object?>> remaining = <Map<String, Object?>>[];
    Map<String, Object?> refused(String kind, String id, String code, String message) =>
        <String, Object?>{'kind': kind, 'id': id, 'code': code, 'message': message};
    int bytes = 0;
    for (final (String kindName, String id) in wanted) {
      if (remaining.isNotEmpty || bytes >= budget) {
        remaining.add(<String, Object?>{'kind': kindName, 'id': id});
        continue;
      }
      final DocumentKind? kind = DocumentKind.parse(kindName);
      if (kind == null) {
        errors.add(refused(kindName, id, 'invalid-request', 'Unknown kind $kindName.'));
        continue;
      }
      if (!documentIdPattern.hasMatch(id)) {
        errors.add(refused(kindName, id, 'invalid-request', "'$id' is not a document id."));
        continue;
      }
      final StoredDocument? stored;
      try {
        token.reach(kind, id);
        stored = _find(kind, id);
      } on ApiError catch (refusal) {
        errors.add(refused(kindName, id, refusal.code, refusal.message));
        continue;
      }
      if (stored == null) {
        errors.add(refused(kindName, id, 'not-found', 'No $kindName $id.'));
        continue;
      }
      // A document bigger than the whole answer still comes, alone, unless it
      // would not fit one relay frame.
      if (answered.isNotEmpty && bytes + stored.responseBytes > budget) {
        remaining.add(<String, Object?>{'kind': kindName, 'id': id});
        continue;
      }
      if (request.transport == Transport.relay && stored.responseBytes > _relayRoom) {
        errors.add(refused(kindName, id, 'too-large', 'Over the relay frame limit; read it on the direct path.'));
        continue;
      }
      answered.add(stored.response);
      bytes += stored.responseBytes;
    }
    return FakeResponse(200, body: <String, Object?>{'documents': answered, 'errors': errors, 'remaining': remaining});
  }

  static String _text(Map<String, Object?> ref, String key, String at) {
    final Object? value = ref[key];
    if (value is! String || value.isEmpty || value.length > 512) throw invalid('$at.$key: expected a string.');
    return value;
  }
}
