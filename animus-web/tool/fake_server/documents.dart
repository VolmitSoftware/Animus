import 'dart:convert';
import 'dart:io';

import 'package:animus_editor/logic/data_folder.dart';
import 'package:animus_editor/model/document_kind.dart';
import 'package:animus_editor/model/document_summary.dart';

import 'protocol.dart';
import 'scale.dart';
import 'validation.dart';

/// One stored document: its exact text, revision and `sha256:` hash. The
/// parse, summary and diagnostics are worked out once per content.
class StoredDocument {
  StoredDocument(this.kind, this.id, this.path, this.content)
      : hash = 'sha256:${sha256Text(content)}',
        _parsed = parseObject(content);

  final DocumentKind kind;
  final String id;
  final String path;
  final String content;
  final String hash;
  final ({Map<String, Object?>? json, String? problem}) _parsed;

  late final int revision = revisionIn(json);

  /// The parsed content, null when it is not one JSON object. Shared by
  /// every answer, so nothing may change it.
  Map<String, Object?>? get json => _parsed.json;

  /// An unreadable file is summarized as an empty object, as EditorCatalog
  /// does, so an NPC still reports its folder and no features.
  late final Map<String, Object?> summary = summarizeDocument(kind, id, json ?? const <String, Object?>{}).fields;

  late final List<Map<String, Object?>> diagnostics = json == null
      ? <Map<String, Object?>>[diagnostic('error', r'$', 'The stored file is not one JSON object: ${_parsed.problem}', 'json')]
      : validateDocument(kind, id, path, json!);

  Map<String, Object?> get version => <String, Object?>{
        'kind': kind.wireName,
        'id': id,
        'path': path,
        'revision': revision,
        'hash': hash,
      };

  /// A catalog entry: the version and its summary.
  Map<String, Object?> get entry => <String, Object?>{...version, 'summary': summary};

  late final int entryBytes = utf8.encode(jsonEncode(entry)).length;

  /// What `GET /v1/documents/{kind}/{id}` answers, and each batch read entry.
  Map<String, Object?> get response => <String, Object?>{
        ...version,
        'source': json ?? const <String, Object?>{},
        'summary': summary,
        if (diagnostics.isNotEmpty) 'diagnostics': diagnostics,
      };

  late final int responseBytes = utf8.encode(jsonEncode(response)).length;
}

/// Where a document is, or would go.
class Located {
  const Located(this.kind, this.id, this.path);

  final DocumentKind kind;
  final String id;
  final String path;
}

/// The in-memory data folder. Loading reads the folder once, or builds the
/// generated `--scale` set; nothing is ever written back to disk.
class FakeDocuments {
  FakeDocuments(this.dataDirectory, {this.scale});

  /// The folder loaded at start and on reset, or null for an empty store.
  final String? dataDirectory;

  /// When set, the generated data folder of this many documents replaces
  /// [dataDirectory].
  final int? scale;
  Map<String, String>? _generated;

  final Map<String, StoredDocument> _byPath = <String, StoredDocument>{};
  final Map<String, StoredDocument> _byRef = <String, StoredDocument>{};
  List<StoredDocument>? _sorted;

  static String _ref(DocumentKind kind, String id) => '${kind.index}\n$id';

  void load() {
    _byPath.clear();
    _byRef.clear();
    _sorted = null;
    if (scale != null) {
      for (final MapEntry<String, String> file in (_generated ??= scaleDataFolder(scale!)).entries) {
        _add(file.key, file.value);
      }
      return;
    }
    final String? root = dataDirectory;
    if (root == null) return;
    final Directory directory = Directory(root);
    if (!directory.existsSync()) throw ArgumentError('No data folder at $root');
    final String base = directory.absolute.path;
    for (final File file in directory.absolute.listSync(recursive: true, followLinks: false).whereType<File>()) {
      final String path = file.path.substring(base.length + 1).replaceAll(r'\', '/');
      String content;
      try {
        content = utf8.decode(file.readAsBytesSync());
      } on FormatException {
        continue;
      }
      _add(path, content);
    }
  }

  /// A loaded file; the first of two files with one id (a behavior in a pack
  /// and in behaviors/) is the one found by id.
  void _add(String path, String content) {
    final DocumentLocation? location = locateDocument(path);
    if (location == null) return;
    final StoredDocument stored = StoredDocument(location.kind, location.id, path, content);
    _byPath[path] = stored;
    _byRef.putIfAbsent(_ref(location.kind, location.id), () => stored);
  }

  /// Every document, or those of [kind], sorted by kind then path.
  List<StoredDocument> list([DocumentKind? kind]) {
    final List<StoredDocument> sorted = _sorted ??= _byPath.values.toList()
      ..sort((StoredDocument a, StoredDocument b) => a.kind != b.kind ? a.kind.index.compareTo(b.kind.index) : a.path.compareTo(b.path));
    return kind == null ? List<StoredDocument>.of(sorted) : <StoredDocument>[for (final StoredDocument document in sorted) if (document.kind == kind) document];
  }

  StoredDocument? at(String path) => _byPath[path];

  /// An existing document of this id (a behavior may be inside a pack), else
  /// the path a new one gets. Refuses ids that map to no path.
  Located locate(DocumentKind kind, String id) => Located(kind, id, _byRef[_ref(kind, id)]?.path ?? pathFor(kind, id));

  StoredDocument? find(DocumentKind kind, String id) => _byRef[_ref(kind, id)];

  StoredDocument write(Located location, String content) {
    final StoredDocument stored = StoredDocument(location.kind, location.id, location.path, content);
    _byPath[location.path] = stored;
    _byRef[_ref(location.kind, location.id)] = stored;
    _sorted = null;
    return stored;
  }

  void delete(String path) {
    final StoredDocument? removed = _byPath.remove(path);
    if (removed == null) return;
    _sorted = null;
    final String ref = _ref(removed.kind, removed.id);
    if (!identical(_byRef[ref], removed)) return;
    _byRef.remove(ref);
    for (final StoredDocument other in _byPath.values) {
      if (other.kind == removed.kind && other.id == removed.id) {
        _byRef[ref] = other;
        break;
      }
    }
  }

  /// The path a new document of this id gets; the editor's own location
  /// rules (locateDocument) must map it back to the same kind and id.
  static String pathFor(DocumentKind kind, String id) {
    final String path = switch (kind) {
      DocumentKind.skin => 'skins/$id.json',
      DocumentKind.pack => 'packs/$id/pack.json',
      DocumentKind.presets => 'gui/presets.json',
      DocumentKind.serverBehaviors => DocumentKind.serverBehaviorsPath,
      _ => '${kind.folder}/${id.replaceFirst(':', '/')}.json',
    };
    final DocumentLocation? back = locateDocument(path);
    if (back == null || back.kind != kind || back.id != id) {
      throw invalid("'$id' is not a valid ${kind.wireName} id.");
    }
    return path;
  }
}
