/// Writing a workspace back out as a data folder zip, and the file path a new
/// document of each kind gets, the reverse of `locateDocument`.
library;

import 'dart:convert';
import 'dart:typed_data';

import 'package:archive/archive.dart';

import '../model/animus_document.dart';
import '../model/document_kind.dart';

/// A zip whose entries are the documents' paths and exact sources, sorted,
/// with a fixed timestamp so the same workspace always gives the same bytes.
Uint8List exportDataFolderZip(Iterable<AnimusDocument> documents) {
  final Archive archive = Archive();
  final List<AnimusDocument> sorted = documents.toList()..sort((AnimusDocument a, AnimusDocument b) => a.path.compareTo(b.path));
  for (final AnimusDocument document in sorted) {
    final ArchiveFile file = ArchiveFile.bytes(document.path, utf8.encode(document.source))..lastModTime = _fixedTime;
    archive.addFile(file);
  }
  return ZipEncoder().encodeBytes(archive);
}

/// 2026-01-01T00:00:00Z: zip times have no zone, so any fixed value works.
const int _fixedTime = 1767225600;

/// The path relative to the data folder that [kind] and [id] live at, or null
/// for an id the kind cannot hold.
String? documentPath(DocumentKind kind, String id) {
  String namespaced(String folder) {
    final int colon = id.indexOf(':');
    return '$folder/${id.substring(0, colon)}/${id.substring(colon + 1)}.json';
  }

  final bool hasNamespace = RegExp(r'^[a-z0-9_-]{1,64}:[a-z0-9_-]{1,64}(/[a-z0-9_-]{1,64})*$').hasMatch(id);
  final bool plainPath = RegExp(r'^[a-z0-9_-]{1,64}(/[a-z0-9_-]{1,64})*$').hasMatch(id);
  return switch (kind) {
    DocumentKind.inventory => hasNamespace ? namespaced('gui/inventories') : null,
    DocumentKind.shop => hasNamespace ? namespaced('shops') : null,
    DocumentKind.behavior => hasNamespace ? namespaced('behaviors') : null,
    DocumentKind.rig => hasNamespace ? namespaced('rigs') : null,
    DocumentKind.animation => hasNamespace ? namespaced('animations') : null,
    DocumentKind.menu => plainPath ? 'gui/menus/$id.json' : null,
    DocumentKind.hologram => plainPath ? 'gui/holograms/$id.json' : null,
    DocumentKind.textAnimation => plainPath ? 'gui/text-animations/$id.json' : null,
    DocumentKind.presets => id == 'presets' ? 'gui/presets.json' : null,
    _ => null,
  };
}
