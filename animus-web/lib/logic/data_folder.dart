/// Opening an Animus data folder (plugins/Animus or config/animus) from a
/// directory pick or a zip: which files are documents, their ids, and their
/// validation against the shared schemas.
library;

import 'dart:convert';
import 'dart:typed_data';

import 'package:archive/archive.dart';

import '../model/animus_document.dart';
import '../model/document_kind.dart';
import 'gui/gui_validation.dart';
import 'rig_validation.dart';
import 'shared_schemas.dart';

/// A file's kind and id, from its path relative to the data folder.
class DocumentLocation {
  const DocumentLocation(this.kind, this.id);

  final DocumentKind kind;
  final String id;

  @override
  bool operator ==(Object other) => other is DocumentLocation && other.kind == kind && other.id == id;

  @override
  int get hashCode => Object.hash(kind, id);

  @override
  String toString() => '${kind.wireName}:$id';
}

/// Top-level folders that hold editable documents.
const Set<String> documentRoots = <String>{'npcs', 'routes', 'skins', 'behaviors', 'packs', 'shops', 'gui', 'rigs', 'animations'};

final RegExp _segment = RegExp(r'^[a-z0-9_-]{1,64}$');

/// The document at [path] (relative to the data folder, `/` separated), or
/// null for files the editor does not open (settings, state, backups, imports).
DocumentLocation? locateDocument(String path) {
  if (!path.endsWith('.json')) return null;
  final List<String> parts = path.substring(0, path.length - 5).split('/');
  if (parts.any((String part) => part.isEmpty)) return null;
  String namespaced(List<String> rest) => '${rest.first}:${rest.skip(1).join('/')}';
  bool valid(List<String> rest) => rest.every(_segment.hasMatch);
  switch (parts.first) {
    case 'npcs':
      return parts.length == 3 && valid(parts.sublist(1)) ? DocumentLocation(DocumentKind.npc, '${parts[1]}/${parts[2]}') : null;
    case 'routes':
      return parts.length == 2 && valid(parts.sublist(1)) ? DocumentLocation(DocumentKind.route, parts[1]) : null;
    case 'skins':
      return parts.length == 2 && RegExp(r'^[a-f0-9]{64}$').hasMatch(parts[1]) ? DocumentLocation(DocumentKind.skin, parts[1]) : null;
    case 'behaviors':
      return parts.length >= 3 && valid(parts.sublist(1)) ? DocumentLocation(DocumentKind.behavior, namespaced(parts.sublist(1))) : null;
    case 'packs':
      if (parts.length == 3 && parts[2] == 'pack' && _segment.hasMatch(parts[1])) return DocumentLocation(DocumentKind.pack, parts[1]);
      if (parts.length >= 5 && parts[2] == 'behaviors' && valid(parts.sublist(3)) && _segment.hasMatch(parts[1])) {
        return DocumentLocation(DocumentKind.behavior, namespaced(parts.sublist(3)));
      }
      return null;
    case 'shops':
      return parts.length >= 3 && valid(parts.sublist(1)) ? DocumentLocation(DocumentKind.shop, namespaced(parts.sublist(1))) : null;
    case 'rigs':
      return parts.length >= 3 && valid(parts.sublist(1)) ? DocumentLocation(DocumentKind.rig, namespaced(parts.sublist(1))) : null;
    case 'animations':
      return parts.length >= 3 && valid(parts.sublist(1)) ? DocumentLocation(DocumentKind.animation, namespaced(parts.sublist(1))) : null;
    case 'gui':
      if (parts.length == 2 && parts[1] == 'presets') return const DocumentLocation(DocumentKind.presets, 'presets');
      if (parts.length < 3) return null;
      final List<String> rest = parts.sublist(2);
      if (!valid(rest)) return null;
      return switch (parts[1]) {
        'inventories' => rest.length >= 2 ? DocumentLocation(DocumentKind.inventory, namespaced(rest)) : null,
        'menus' => DocumentLocation(DocumentKind.menu, rest.join('/')),
        'holograms' => DocumentLocation(DocumentKind.hologram, rest.join('/')),
        'text-animations' => DocumentLocation(DocumentKind.textAnimation, rest.join('/')),
        _ => null,
      };
    default:
      return null;
  }
}

/// The data folder's prefix inside an upload: a zip of `plugins/Animus/` or a
/// picked `Animus/` directory carries leading folders. The prefix chosen is
/// the one most document paths share.
String dataFolderPrefix(Iterable<String> paths) {
  final Map<String, int> votes = <String, int>{};
  for (final String path in paths) {
    final List<String> parts = path.split('/');
    for (int index = 0; index < parts.length - 1; index++) {
      if (!documentRoots.contains(parts[index])) continue;
      final String prefix = parts.take(index).map((String part) => '$part/').join();
      if (locateDocument(path.substring(prefix.length)) != null) {
        votes[prefix] = (votes[prefix] ?? 0) + 1;
        break;
      }
    }
  }
  String best = '';
  int bestVotes = 0;
  for (final MapEntry<String, int> vote in votes.entries) {
    if (vote.value > bestVotes || (vote.value == bestVotes && vote.key.length < best.length)) {
      best = vote.key;
      bestVotes = vote.value;
    }
  }
  return best;
}

/// Files from a zip, by `/`-separated path. Directories and entries that
/// escape the archive root are skipped.
Map<String, Uint8List> filesFromZip(Uint8List bytes) {
  final Archive archive = ZipDecoder().decodeBytes(bytes);
  final Map<String, Uint8List> files = <String, Uint8List>{};
  for (final ArchiveFile entry in archive.files) {
    if (!entry.isFile) continue;
    final String name = entry.name.replaceAll(r'\', '/');
    if (name.startsWith('/') || name.split('/').contains('..')) continue;
    files[name] = entry.readBytes() ?? Uint8List(0);
  }
  return files;
}

class DataFolderResult {
  const DataFolderResult(this.documents, this.ignored, this.prefix);

  /// Sorted by path.
  final List<AnimusDocument> documents;

  /// Paths under the data folder that are not documents.
  final List<String> ignored;
  final String prefix;
}

/// Parses and validates every document in [files] (path to bytes).
DataFolderResult openDataFolder(Map<String, Uint8List> files, SharedSchemas schemas) {
  final String prefix = dataFolderPrefix(files.keys);
  final List<AnimusDocument> documents = <AnimusDocument>[];
  final List<String> ignored = <String>[];
  final List<String> paths = files.keys.where((String path) => path.startsWith(prefix)).toList()..sort();
  for (final String fullPath in paths) {
    final String path = fullPath.substring(prefix.length);
    final DocumentLocation? location = locateDocument(path);
    if (location == null) {
      ignored.add(path);
      continue;
    }
    documents.add(parseDocument(location, path, files[fullPath]!, schemas));
  }
  final Map<String, Object?> rigs = <String, Object?>{
    for (final AnimusDocument document in documents)
      if (document.kind == DocumentKind.rig) document.id: document.json,
  };
  return DataFolderResult(<AnimusDocument>[
    for (final AnimusDocument document in documents)
      if (document.kind == DocumentKind.animation)
        document.copyWith(diagnostics: <Diagnostic>[
          ...document.diagnostics,
          ...validateAnimationStructure(document.json, rigs[_string(document.json, 'rig')]),
        ])
      else
        document,
  ], ignored, prefix);
}

AnimusDocument parseDocument(DocumentLocation location, String path, Uint8List bytes, SharedSchemas schemas) {
  String source;
  try {
    source = utf8.decode(bytes);
  } on FormatException {
    return AnimusDocument(kind: location.kind, id: location.id, path: path, source: '', json: null, diagnostics: const <Diagnostic>[
      Diagnostic(DiagnosticSeverity.error, r'$', 'expected UTF-8 text', code: 'encoding'),
    ]);
  }
  return validateSource(location, path, source, schemas);
}

/// Parses [source] and runs every browser-side check for its kind.
AnimusDocument validateSource(DocumentLocation location, String path, String source, SharedSchemas schemas) {
  Object? json;
  try {
    json = jsonDecode(source);
  } on FormatException catch (invalid) {
    return AnimusDocument(kind: location.kind, id: location.id, path: path, source: source, json: null, diagnostics: <Diagnostic>[
      Diagnostic(DiagnosticSeverity.error, r'$', 'invalid JSON: ${invalid.message}', code: 'json'),
    ]);
  }
  final List<Diagnostic> diagnostics = <Diagnostic>[
    ...mergeGuiDiagnostics(schemas.validate(location.kind, json), validateGui(location.kind, json)),
    ..._identity(location, json),
    if (location.kind == DocumentKind.rig) ...validateRigStructure(json),
  ];
  return AnimusDocument(kind: location.kind, id: location.id, path: path, source: source, json: json, diagnostics: diagnostics);
}

String? _string(Object? json, String key) => json is Map<String, Object?> && json[key] is String ? json[key]! as String : null;

/// The id fields the server checks against the file location.
List<Diagnostic> _identity(DocumentLocation location, Object? json) {
  Diagnostic mismatch(String field, String expected) => Diagnostic(
        DiagnosticSeverity.error,
        '\$.$field',
        'expected $expected to match this file\'s location',
        code: 'id-mismatch',
      );
  switch (location.kind) {
    case DocumentKind.npc:
      final List<String> parts = location.id.split('/');
      return <Diagnostic>[
        if (_string(json, 'group') != null && _string(json, 'group') != parts[0]) mismatch('group', parts[0]),
        if (_string(json, 'id') != null && _string(json, 'id') != parts[1]) mismatch('id', parts[1]),
      ];
    case DocumentKind.behavior:
    case DocumentKind.shop:
    case DocumentKind.rig:
    case DocumentKind.animation:
    case DocumentKind.pack:
      final String? id = _string(json, 'id');
      return <Diagnostic>[if (id != null && id != location.id) mismatch('id', location.id)];
    default:
      return const <Diagnostic>[];
  }
}
