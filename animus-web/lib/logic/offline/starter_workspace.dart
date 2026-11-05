/// The starter workspace: the repository's example packs, embedded at build
/// time by tool/embed_starter_packs.dart, as data-folder files.
library;

import 'dart:convert';
import 'dart:typed_data';

import '../../config/starter_packs.g.dart';
import '../gui/gui_json.dart';

/// Every example-pack file by data-folder path.
Map<String, Uint8List> starterWorkspaceFiles() => <String, Uint8List>{
      for (final MapEntry<String, String> file in starterPackFiles.entries) file.key: Uint8List.fromList(utf8.encode(file.value)),
    };

/// The packs in the starter, as (name, description) from their pack.json.
List<(String, String)> starterPacks() {
  final List<(String, String)> packs = <(String, String)>[];
  final List<String> paths = starterPackFiles.keys.where((String path) => path.startsWith('packs/') && path.endsWith('/pack.json')).toList()..sort();
  for (final String path in paths) {
    final JsonMap pack = asMap(jsonDecode(starterPackFiles[path]!));
    packs.add((asString(pack['name']) ?? path.split('/')[1], asString(pack['description']) ?? ''));
  }
  return packs;
}
