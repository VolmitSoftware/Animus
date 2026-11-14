/// A paired server's catalog: every document it has, with the summary a
/// list shows, read page by page with no cap and kept current from catalog
/// events.
library;

import '../model/document_kind.dart';
import '../services/server/error_copy.dart';
import '../services/server/transport.dart';
import 'catalog_entry.dart';

class ServerCatalog {
  final Map<String, CatalogEntry> _byKey = <String, CatalogEntry>{};
  Map<DocumentKind, List<CatalogEntry>>? _byKind;
  List<CatalogEntry>? _all;

  /// The whole catalog has arrived at least once.
  bool loaded = false;

  /// Why the last load failed, until one succeeds.
  ErrorCopy? problem;

  CatalogEntry? operator [](String key) => _byKey[key];

  int get length => _byKey.length;

  /// Every entry, by kind then path.
  List<CatalogEntry> get entries => _all ??= (_byKey.values.toList()
    ..sort((CatalogEntry a, CatalogEntry b) => a.kind != b.kind ? a.kind.index.compareTo(b.kind.index) : a.path.compareTo(b.path)));

  List<CatalogEntry> ofKind(DocumentKind kind) {
    final Map<DocumentKind, List<CatalogEntry>> byKind = _byKind ??= () {
      final Map<DocumentKind, List<CatalogEntry>> grouped = <DocumentKind, List<CatalogEntry>>{};
      for (final CatalogEntry entry in entries) {
        grouped.putIfAbsent(entry.kind, () => <CatalogEntry>[]).add(entry);
      }
      return grouped;
    }();
    return byKind[kind] ?? const <CatalogEntry>[];
  }

  Iterable<String> get keys => _byKey.keys;

  void _changed() {
    _byKind = null;
    _all = null;
  }

  /// Changes heard since [beginLoad] (an entry, or null for a delete): the
  /// pages being read may predate them, so [replace] puts them back.
  List<(String, CatalogEntry?)>? _heard;

  /// A read of every page starts now.
  void beginLoad() => _heard = <(String, CatalogEntry?)>[];

  /// The whole catalog from a read of every page, with the changes heard
  /// while it was read laid over it; a page newer than such a change wins.
  void replace(List<CatalogEntry> entries) {
    final List<(String, CatalogEntry?)> heard = _heard ?? const <(String, CatalogEntry?)>[];
    _heard = null;
    _byKey
      ..clear()
      ..addEntries(entries.map((CatalogEntry entry) => MapEntry<String, CatalogEntry>(entry.key, entry)));
    for (final (String key, CatalogEntry? entry) in heard) {
      final CatalogEntry? page = _byKey[key];
      if (page != null && entry != null && page.revision > entry.revision) continue;
      if (entry == null) {
        // A page that still lists it was read before the delete; a later
        // re-creation comes as its own event.
        _byKey.remove(key);
      } else {
        _byKey[key] = entry;
      }
    }
    loaded = true;
    problem = null;
    _changed();
  }

  void upsert(CatalogEntry entry) {
    _byKey[entry.key] = entry;
    _heard?.add((entry.key, entry));
    _changed();
  }

  void remove(String key) {
    _heard?.add((key, null));
    if (_byKey.remove(key) != null) _changed();
  }

  /// Every page of `GET /v1/catalog`, sent through [send]. Returns the
  /// entries, or the problem that stopped the read.
  static Future<(List<CatalogEntry>?, ErrorCopy?)> fetchAll(Future<ApiResponse> Function(ApiRequest request) send) async {
    final List<CatalogEntry> entries = <CatalogEntry>[];
    final Set<String> cursors = <String>{};
    String? after;
    while (true) {
      final ApiResponse response = await send(ApiRequest('GET', after == null ? '/v1/catalog' : '/v1/catalog?after=${Uri.encodeQueryComponent(after)}'));
      final Object? body = response.body;
      if (!response.ok || body is! Map<String, Object?>) return (null, response.problem);
      for (final Object? raw in (body['documents'] as List<Object?>?) ?? const <Object?>[]) {
        final CatalogEntry? entry = CatalogEntry.fromJson(raw);
        if (entry != null) entries.add(entry);
      }
      after = body['next'] as String?;
      // A server that repeats a cursor would loop forever; what it sent is kept.
      if (after == null || !cursors.add(after)) return (entries, null);
    }
  }
}
