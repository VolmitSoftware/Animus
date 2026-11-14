import '../model/document_kind.dart';
import '../model/document_summary.dart';
import '../model/server_binding.dart';

/// One catalog entry: a `documentVersion` and the document's `summary`.
class CatalogEntry {
  const CatalogEntry(this.kind, this.id, this.path, this.revision, this.hash, {this.summary = DocumentSummary.empty});

  final DocumentKind kind;
  final String id;
  final String path;
  final int revision;
  final String hash;

  /// What a list shows before the document is read.
  final DocumentSummary summary;

  String get key => documentKey(kind, id);

  static CatalogEntry? fromJson(Object? json) {
    if (json is! Map<String, Object?>) return null;
    final DocumentKind? kind = DocumentKind.parse('${json['kind']}');
    if (kind == null || json['id'] is! String || json['revision'] is! num || json['hash'] is! String) return null;
    return CatalogEntry(
      kind,
      json['id']! as String,
      '${json['path']}',
      (json['revision']! as num).toInt(),
      json['hash']! as String,
      summary: DocumentSummary.fromJson(json['summary']),
    );
  }
}
