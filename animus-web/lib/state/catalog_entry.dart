import '../model/document_kind.dart';
import '../model/server_binding.dart';

/// One `documentVersion` from the catalog.
class CatalogEntry {
  const CatalogEntry(this.kind, this.id, this.path, this.revision, this.hash);

  final DocumentKind kind;
  final String id;
  final String path;
  final int revision;
  final String hash;

  String get key => documentKey(kind, id);

  static CatalogEntry? fromJson(Object? json) {
    if (json is! Map<String, Object?>) return null;
    final DocumentKind? kind = DocumentKind.parse('${json['kind']}');
    if (kind == null || json['id'] is! String || json['revision'] is! num || json['hash'] is! String) return null;
    return CatalogEntry(kind, json['id']! as String, '${json['path']}', (json['revision']! as num).toInt(), json['hash']! as String);
  }
}
