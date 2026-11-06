import 'dart:convert';
import 'dart:io';

import 'package:animus_editor/logic/data_folder.dart';
import 'package:animus_editor/model/document_kind.dart';

import 'protocol.dart';

/// One stored document: its exact text, revision and `sha256:` hash.
class StoredDocument {
  StoredDocument(this.kind, this.id, this.path, this.content)
      : revision = revisionOf(content),
        hash = 'sha256:${sha256Text(content)}';

  final DocumentKind kind;
  final String id;
  final String path;
  final String content;
  final int revision;
  final String hash;

  Map<String, Object?> get version => <String, Object?>{
        'kind': kind.wireName,
        'id': id,
        'path': path,
        'revision': revision,
        'hash': hash,
      };
}

/// Where a document is, or would go.
class Located {
  const Located(this.kind, this.id, this.path);

  final DocumentKind kind;
  final String id;
  final String path;
}

/// The in-memory data folder. Loading reads the folder once; nothing is
/// ever written back to disk.
class FakeDocuments {
  FakeDocuments(this.dataDirectory);

  /// The folder loaded at start and on reset, or null for an empty store.
  final String? dataDirectory;
  final Map<String, StoredDocument> _byPath = <String, StoredDocument>{};

  void load() {
    _byPath.clear();
    final String? root = dataDirectory;
    if (root == null) return;
    final Directory directory = Directory(root);
    if (!directory.existsSync()) throw ArgumentError('No data folder at $root');
    final String base = directory.absolute.path;
    for (final File file in directory.absolute.listSync(recursive: true, followLinks: false).whereType<File>()) {
      final String path = file.path.substring(base.length + 1).replaceAll(r'\', '/');
      final DocumentLocation? location = locateDocument(path);
      if (location == null) continue;
      String content;
      try {
        content = utf8.decode(file.readAsBytesSync());
      } on FormatException {
        continue;
      }
      _byPath[path] = StoredDocument(location.kind, location.id, path, content);
    }
  }

  /// Every document, or those of [kind], sorted by kind then path.
  List<StoredDocument> list([DocumentKind? kind]) {
    final List<StoredDocument> found = <StoredDocument>[
      for (final StoredDocument document in _byPath.values)
        if (kind == null || document.kind == kind) document,
    ];
    found.sort((StoredDocument a, StoredDocument b) =>
        a.kind != b.kind ? a.kind.index.compareTo(b.kind.index) : a.path.compareTo(b.path));
    return found;
  }

  StoredDocument? at(String path) => _byPath[path];

  /// An existing document of this id (a behavior may be inside a pack), else
  /// the path a new one gets. Refuses ids that map to no path.
  Located locate(DocumentKind kind, String id) {
    for (final StoredDocument document in _byPath.values) {
      if (document.kind == kind && document.id == id) return Located(kind, id, document.path);
    }
    return Located(kind, id, pathFor(kind, id));
  }

  StoredDocument? find(DocumentKind kind, String id) {
    for (final StoredDocument document in _byPath.values) {
      if (document.kind == kind && document.id == id) return document;
    }
    return null;
  }

  StoredDocument write(Located location, String content) {
    final StoredDocument stored = StoredDocument(location.kind, location.id, location.path, content);
    _byPath[location.path] = stored;
    return stored;
  }

  void delete(String path) => _byPath.remove(path);

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
