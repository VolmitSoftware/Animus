/// Workspace to data-folder files: the whole workspace, or one rig with its
/// animations and the resource pack sources its bones use.
library;

import 'dart:convert';
import 'dart:typed_data';

import 'package:archive/archive.dart';

import '../model/animus_document.dart';
import '../model/document_kind.dart';
import '../state/workspace_repository.dart';
import 'bbmodel_import.dart';
import 'rig_edit.dart';

/// Every document at its data-folder path, then every resource.
Map<String, Uint8List> workspaceFiles(WorkspaceSnapshot workspace) => <String, Uint8List>{
  for (final AnimusDocument document in workspace.documents) document.path: Uint8List.fromList(utf8.encode(document.source)),
  ...workspace.resources,
};

/// A reproducible zip: sorted entries, stored, one fixed timestamp.
Uint8List zipFiles(Map<String, Uint8List> files, {String prefix = ''}) {
  final Archive archive = Archive();
  for (final String path in files.keys.toList()..sort()) {
    archive.add(ArchiveFile.bytes('$prefix$path', files[path]!)
      ..lastModTime = _dosEpoch
      ..compression = CompressionType.none);
  }
  return Uint8List.fromList(ZipEncoder().encodeBytes(archive));
}

/// 1980-01-01, the earliest time a zip entry can hold.
const int _dosEpoch = 315532800;

final RegExp _itemModel = RegExp(r'item_model\s*=\s*"([a-z0-9_.-]+:[a-z0-9_./-]+)"');

/// The `item_model` an item's `components` text names, if any.
String? itemModelOf(Object? item) {
  if (item is! JsonMap || item['components'] is! String) return null;
  return _itemModel.firstMatch(item['components']! as String)?.group(1);
}

/// What one rig needs on the server: the rig, its animations, and the item
/// definitions, models and textures its bones reference that the workspace
/// holds. [missing] lists `animus:` models the workspace does not have.
class RigBundle {
  const RigBundle(this.files, this.missing);

  final Map<String, Uint8List> files;
  final List<String> missing;
}

RigBundle rigBundle(WorkspaceSnapshot workspace, String rigId) {
  final Map<String, Uint8List> files = <String, Uint8List>{};
  final List<String> missing = <String>[];
  JsonMap? rig;
  for (final AnimusDocument document in workspace.documents) {
    final Object? json = document.json;
    if (document.kind == DocumentKind.rig && document.id == rigId) {
      files[document.path] = Uint8List.fromList(utf8.encode(document.source));
      if (json is JsonMap) rig = json;
    } else if (document.kind == DocumentKind.animation && json is JsonMap && json['rig'] == rigId) {
      files[document.path] = Uint8List.fromList(utf8.encode(document.source));
    }
  }
  if (rig == null) return RigBundle(files, missing);
  void add(String path) {
    final Uint8List? bytes = workspace.resources[path];
    if (bytes != null) files[path] = bytes;
  }

  for (final JsonMap bone in bonesOf(rig)) {
    final Object? visual = bone['visual'];
    if (visual is! JsonMap || visual['type'] != 'item') continue;
    final String? item = itemModelOf(visual['item']);
    if (item == null || item.startsWith('minecraft:')) continue;
    final ResolvedItemModel? resolved = resolveWorkspaceItemModel(workspace.resources, item);
    if (resolved == null) {
      missing.add(item);
      continue;
    }
    for (final String path in resolved.paths) {
      add(path);
    }
  }
  return RigBundle(files, missing);
}

/// An item model from workspace resources: the parsed model JSON, its
/// textures by variable, and every resource path that took part.
class ResolvedItemModel {
  const ResolvedItemModel(this.model, this.textures, this.paths);

  final JsonMap model;

  /// Texture variable (`0`, `particle`) to texture resource path.
  final Map<String, String> textures;
  final List<String> paths;
}

ResolvedItemModel? resolveWorkspaceItemModel(Map<String, Uint8List> resources, String itemModel) {
  final String definitionPath = itemDefinitionPath(itemModel);
  final JsonMap? definition = _jsonResource(resources, definitionPath);
  final Object? node = definition?['model'];
  if (node is! JsonMap || node['model'] is! String) return null;
  final String path = modelPath(node['model']! as String);
  final JsonMap? model = _jsonResource(resources, path);
  if (model == null) return null;
  final Map<String, String> textures = <String, String>{};
  final Object? declared = model['textures'];
  if (declared is JsonMap) {
    for (final MapEntry<String, Object?> entry in declared.entries) {
      if (entry.value is! String || (entry.value! as String).startsWith('#')) continue;
      final String texture = texturePath(entry.value! as String);
      if (resources.containsKey(texture)) textures[entry.key] = texture;
    }
  }
  return ResolvedItemModel(model, textures, <String>[definitionPath, path, ...textures.values.toSet()]);
}

JsonMap? _jsonResource(Map<String, Uint8List> resources, String path) {
  final Uint8List? bytes = resources[path];
  if (bytes == null) return null;
  try {
    final Object? json = jsonDecode(utf8.decode(bytes));
    return json is JsonMap ? json : null;
  } on FormatException {
    return null;
  }
}
