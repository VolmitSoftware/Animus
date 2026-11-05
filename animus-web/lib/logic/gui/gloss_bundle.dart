/// Gloss Web workspace bundles (`gloss-editor-workspace` version 1, Gloss
/// workspace schema 2) to and from Animus documents, for the kinds Animus
/// adopts: chest GUIs, holographic menus, holograms and presets. A GUI made
/// in Gloss Web opens here, and the reverse (docs/editor-design.md, section 3).
library;

import 'dart:convert';
import 'dart:math' as math;

import '../../model/animus_document.dart';
import '../../model/document_kind.dart';
import '../data_folder.dart';
import '../data_folder_export.dart';
import 'gui_json.dart';

const String glossBundleFormat = 'gloss-editor-workspace';
const int glossBundleVersion = 1;
const int glossWorkspaceSchemaVersion = 2;

/// Animus kind for each adopted Gloss `WorkspaceDocKind` name.
const Map<String, DocumentKind> glossKinds = <String, DocumentKind>{
  'inventory': DocumentKind.inventory,
  'menu': DocumentKind.menu,
  'hologram': DocumentKind.hologram,
  'presets': DocumentKind.presets,
};

/// One document read from a bundle, ready for the workspace.
final class GlossImportedDocument {
  const GlossImportedDocument(this.location, this.path, this.source, this.glossId);

  final DocumentLocation location;
  final String path;
  final String source;

  /// The Gloss runtime id it came from.
  final String glossId;
}

final class GlossImport {
  const GlossImport({required this.documents, required this.skipped, this.error});

  final List<GlossImportedDocument> documents;

  /// Documents left out, each with the reason.
  final List<String> skipped;

  /// Set when the file is not a readable bundle; nothing is imported.
  final String? error;
}

/// Reads a bundle. Gloss kinds Animus does not adopt are skipped by name.
GlossImport readGlossBundle(String raw) {
  GlossImport fail(String error) => GlossImport(documents: const <GlossImportedDocument>[], skipped: const <String>[], error: error);
  final Object? decoded;
  try {
    decoded = jsonDecode(raw);
  } on FormatException {
    return fail('That file is not valid JSON.');
  }
  final JsonMap bundle = asMap(decoded);
  if (bundle['format'] != glossBundleFormat) return fail('That is not a Gloss editor workspace bundle (format $glossBundleFormat).');
  if (bundle['version'] != glossBundleVersion) return fail('Bundle version ${bundle['version']} is not supported; Animus reads version $glossBundleVersion.');
  final JsonMap workspace = asMap(bundle['workspace']);
  final Object? schema = workspace['schemaVersion'];
  if (schema != glossWorkspaceSchemaVersion) {
    return fail('Gloss workspace schema $schema is not supported; Animus reads schema $glossWorkspaceSchemaVersion.');
  }
  final List<GlossImportedDocument> documents = <GlossImportedDocument>[];
  final List<String> skipped = <String>[];
  final Set<DocumentLocation> seen = <DocumentLocation>{};
  for (final Object? raw in asList(workspace['documents'])) {
    final JsonMap doc = asMap(raw);
    final String kindName = asString(doc['kind']) ?? 'menu';
    final String title = asString(doc['title']) ?? asString(doc['runtimeId']) ?? '?';
    final DocumentKind? kind = glossKinds[kindName];
    if (kind == null) {
      skipped.add('$title: Gloss $kindName documents have no Animus equivalent');
      continue;
    }
    final String? source = asString(doc['json']);
    if (source == null) {
      skipped.add('$title: no document text');
      continue;
    }
    final String glossId = asString(doc['runtimeId']) ?? title;
    final String? id = animusIdForGloss(kind, glossId);
    final String? path = id == null ? null : documentPath(kind, id);
    if (id == null || path == null) {
      skipped.add('$title: id "$glossId" has no Animus file name');
      continue;
    }
    final DocumentLocation location = DocumentLocation(kind, id);
    if (!seen.add(location)) {
      skipped.add('$title: a second ${kind.label.toLowerCase()} document maps to $id');
      continue;
    }
    documents.add(GlossImportedDocument(location, path, source.endsWith('\n') ? source : '$source\n', glossId));
  }
  return GlossImport(documents: documents, skipped: skipped);
}

String _segment(String raw) {
  final String lowered = raw.toLowerCase().replaceAll(RegExp(r'[^a-z0-9_-]'), '-');
  final String trimmed = lowered.length > 64 ? lowered.substring(0, 64) : lowered;
  return trimmed.isEmpty ? 'unnamed' : trimmed;
}

/// Gloss runtime ids are `/` paths (`town/menu`); Animus chest GUIs sit in a
/// namespace folder (`town:menu`), and a one-segment Gloss id goes under
/// `gloss`. Menus and holograms keep the path.
String? animusIdForGloss(DocumentKind kind, String glossId) {
  final List<String> segments = <String>[
    for (final String part in glossId.split('/'))
      if (part.isNotEmpty && part != '.' && part != '..') _segment(part),
  ];
  if (segments.isEmpty) return null;
  switch (kind) {
    case DocumentKind.inventory:
      if (segments.length == 1) return 'gloss:${segments.first}';
      return '${segments.first}:${segments.skip(1).join('/')}';
    case DocumentKind.presets:
      return 'presets';
    default:
      return segments.join('/');
  }
}

/// The Gloss runtime id for an Animus document id.
String glossIdForAnimus(DocumentKind kind, String id) => switch (kind) {
      DocumentKind.inventory => id.replaceFirst(':', '/'),
      _ => id,
    };

/// Whether any action in [json] is Animus-only (`animus:` type), which Gloss
/// cannot run.
bool usesAnimusActions(Object? json) {
  if (json is Map<String, Object?>) {
    final Object? type = json['type'];
    if (type is String && type.startsWith('animus:')) return true;
    return json.values.any(usesAnimusActions);
  }
  if (json is List<Object?>) return json.any(usesAnimusActions);
  return false;
}

final class GlossExport {
  const GlossExport(this.bundle, this.exported, this.skipped);

  /// The bundle text.
  final String bundle;
  final int exported;

  /// Documents left out, each with the reason.
  final List<String> skipped;
}

/// A bundle of the adopted documents in [documents]. Documents with Animus
/// actions are left out and listed, as `/animus gui export gloss` does.
GlossExport writeGlossBundle(Iterable<AnimusDocument> documents, {String Function()? newId, int? now}) {
  final String Function() id = newId ?? _uuid;
  final int stamp = now ?? DateTime.now().millisecondsSinceEpoch;
  final List<JsonMap> out = <JsonMap>[];
  final List<String> skipped = <String>[];
  for (final AnimusDocument document in documents) {
    final String? kindName = <DocumentKind, String>{for (final MapEntry<String, DocumentKind> entry in glossKinds.entries) entry.value: entry.key}[document.kind];
    if (kindName == null) continue;
    if (document.json == null) {
      skipped.add('${document.kind.label}: ${document.id} is not valid JSON');
      continue;
    }
    if (usesAnimusActions(document.json)) {
      skipped.add('${document.kind.label}: ${document.id} uses animus: actions, which Gloss cannot run');
      continue;
    }
    final String runtimeId = glossIdForAnimus(document.kind, document.id);
    out.add(<String, Object?>{
      'id': id(),
      'title': runtimeId,
      'runtimeId': runtimeId,
      'json': document.source,
      'updatedAt': stamp,
      'kind': kindName,
      'folderId': null,
    });
  }
  final JsonMap bundle = <String, Object?>{
    'format': glossBundleFormat,
    'version': glossBundleVersion,
    'workspace': <String, Object?>{
      'schemaVersion': glossWorkspaceSchemaVersion,
      'workspaceId': id(),
      'folders': const <Object?>[],
      'documents': out,
      'activeDocumentId': out.isEmpty ? null : out.first['id'],
    },
    'images': const <Object?>[],
  };
  return GlossExport(const JsonEncoder.withIndent('  ').convert(bundle), out.length, skipped);
}

/// A random version 4 UUID, the id form Gloss workspaces require.
String _uuid() {
  final math.Random random = math.Random.secure();
  final List<int> bytes = List<int>.generate(16, (int _) => random.nextInt(256));
  bytes[6] = (bytes[6] & 0x0f) | 0x40;
  bytes[8] = (bytes[8] & 0x3f) | 0x80;
  final String hex = bytes.map((int byte) => byte.toRadixString(16).padLeft(2, '0')).join();
  return '${hex.substring(0, 8)}-${hex.substring(8, 12)}-${hex.substring(12, 16)}-${hex.substring(16, 20)}-${hex.substring(20)}';
}
