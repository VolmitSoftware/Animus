/// Gloss Web workspace bundles (`gloss-editor-workspace` version 1, Gloss
/// workspace schema 2) to and from Animus documents, for the kinds Animus
/// adopts: chest GUIs, holographic menus, holograms, text animations and
/// presets. A GUI made
/// in Gloss Web opens here, and the reverse (dev/editor-design.md, section 3).
///
/// Ids map as the server's GlossIds maps them. That mapping loses the
/// namespace (`town:menu` leaves as `town.menu`), so an export also writes
/// the Animus id as the document's Gloss title, which Gloss Web keeps; an
/// import takes a title back as the id when it is an Animus id that maps to
/// the document's Gloss id, so a bundle round-trips to the same ids.
library;

import 'dart:convert';
import 'dart:math' as math;

import '../../model/animus_document.dart';
import '../../model/document_kind.dart';
import '../../state/editor_store.dart';
import '../data_folder.dart';
import 'gui_json.dart';

const String glossBundleFormat = 'gloss-editor-workspace';
const int glossBundleVersion = 1;
const int glossWorkspaceSchemaVersion = 2;

/// Animus kind for each adopted Gloss `WorkspaceDocKind` name.
const Map<String, DocumentKind> glossKinds = <String, DocumentKind>{
  'inventory': DocumentKind.inventory,
  'menu': DocumentKind.menu,
  'hologram': DocumentKind.hologram,
  'animation': DocumentKind.textAnimation,
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
  const GlossImport({required this.documents, required this.skipped, this.notes = const <String>[], this.error});

  final List<GlossImportedDocument> documents;

  /// Documents left out, each with the reason.
  final List<String> skipped;

  /// Bundle parts Animus does not keep (folders, titles, images), each with
  /// what happens to it.
  final List<String> notes;

  /// Set when the file is not a readable bundle; nothing is imported.
  final String? error;
}

/// Reads a bundle. Gloss kinds Animus does not adopt are skipped by name.
GlossImport readGlossBundle(String raw) {
  GlossImport fail(String error) => GlossImport(documents: const <GlossImportedDocument>[], skipped: const <String>[], error: error);
  final List<String> notes = <String>[];
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
  final Map<String, String> folders = <String, String>{
    for (final Object? folder in asList(workspace['folders']))
      if (asString(asMap(folder)['id']) != null) asString(asMap(folder)['id'])!: asString(asMap(folder)['name']) ?? asString(asMap(folder)['id'])!,
  };
  if (folders.isNotEmpty) {
    notes.add('Folders (${folders.values.join(', ')}) are not kept: Animus files GUI documents by id, so they open in their kind\'s list.');
  }
  final int images = asList(bundle['images']).length;
  if (images > 0) {
    notes.add('$images ${images == 1 ? 'image is' : 'images are'} not imported: Animus has no image glyphs, so textImage icons are refused.');
  }
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
    final String? id = animusIdFromBundle(kind, glossId, asString(doc['title']));
    final String? path = id == null ? null : documentPath(kind, id);
    if (id == null || path == null || locateDocument(path) != DocumentLocation(kind, id)) {
      skipped.add('$title: id "$glossId" has no Animus file name');
      continue;
    }
    final DocumentLocation location = DocumentLocation(kind, id);
    if (!seen.add(location)) {
      skipped.add('$title: a second ${kind.label.toLowerCase()} document maps to $id');
      continue;
    }
    documents.add(GlossImportedDocument(location, path, source.endsWith('\n') ? source : '$source\n', glossId));
    final String? glossTitle = asString(doc['title']);
    if (glossTitle != null && glossTitle != glossId && glossTitle != id) {
      notes.add('$title: the Gloss title is not kept; the document is $id.');
    }
  }
  return GlossImport(documents: documents, skipped: skipped, notes: notes);
}

final RegExp _animusId = RegExp(r'^[a-z0-9_-]{1,64}:[a-z0-9_/-]{1,191}$');

/// The Animus id for a bundle document: its [title] when that is an Animus
/// id whose Gloss id is [glossId] (written by an Animus export), otherwise
/// the server's mapping of [glossId].
String? animusIdFromBundle(DocumentKind kind, String glossId, String? title) {
  if (kind != DocumentKind.presets && title != null && _animusId.hasMatch(title) && glossIdForAnimus(kind, title) == glossId) return title;
  return animusIdForGloss(kind, glossId);
}

/// Imported documents whose id the workspace already has; importing them
/// replaces those documents, so the caller asks first.
List<DocumentLocation> glossConflicts(GlossImport imported, Iterable<AnimusDocument> existing) {
  final Set<DocumentLocation> have = <DocumentLocation>{for (final AnimusDocument document in existing) DocumentLocation(document.kind, document.id)};
  return <DocumentLocation>[
    for (final GlossImportedDocument document in imported.documents)
      if (have.contains(document.location)) document.location,
  ];
}

/// The Animus id for a Gloss runtime id, as the server's GlossIds imports it:
/// the lowercased Gloss id, with every character outside `a-z0-9_/-` as `-`,
/// in the `gloss` namespace. Presets have the one id `presets`.
String? animusIdForGloss(DocumentKind kind, String glossId) {
  if (kind == DocumentKind.presets) return 'presets';
  final String path = glossId.trim().toLowerCase().replaceAll(RegExp(r'[^a-z0-9_/-]'), '-');
  return path.isEmpty ? null : '$glossDefaultNamespace:$path';
}

/// The namespace Gloss documents are imported into, as on the server.
const String glossDefaultNamespace = 'gloss';

final RegExp _glossSegment = RegExp(r'^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$');

/// The Gloss runtime id for an Animus document id, or null when Gloss has
/// none, as the server's GlossIds exports it: the path in the `gloss`
/// namespace, otherwise `namespace.path`. Menus may nest with `/`; Gloss
/// keeps the other kinds flat, so their `/` becomes `.`.
String? glossIdForAnimus(DocumentKind kind, String id) {
  if (kind == DocumentKind.presets) return id;
  final int colon = id.indexOf(':');
  if (colon < 1) return null;
  final String namespace = id.substring(0, colon);
  final String path = id.substring(colon + 1);
  final String joined = namespace == glossDefaultNamespace ? path : '$namespace.$path';
  final String gloss = kind == DocumentKind.menu ? joined : joined.replaceAll('/', '.');
  if (gloss.isEmpty || gloss.length > 255) return null;
  return gloss.split('/').every(_glossSegment.hasMatch) ? gloss : null;
}

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
    final String? runtimeId = glossIdForAnimus(document.kind, document.id);
    if (runtimeId == null) {
      skipped.add('${document.kind.label}: ${document.id} has no valid Gloss id');
      continue;
    }
    out.add(<String, Object?>{
      'id': id(),
      // The Animus id rides in the title so an import restores it.
      'title': document.kind == DocumentKind.presets ? runtimeId : document.id,
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
