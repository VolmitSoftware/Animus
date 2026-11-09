/// What the Convert Citizens screen shows about a converted saves.yml, and the aggregate a browser test reads back.
library;

import 'dart:convert';

import 'citizens_converter.dart';
import 'java_json.dart';

class CitizensReview {
  CitizensReview._(this.preview, this.traits, this.types, this.conversions, this.classification, this.issues, this.warnings, this.outputHash);

  factory CitizensReview.of(CitizensPreview preview) {
    final JObject summary = preview.summary;
    Map<String, int> counts(String key) => <String, int>{
          for (final MapEntry<String, JElement> entry in (summary.get(key)! as JObject).entries) entry.key: (entry.value as JInt).value.toInt(),
        };
    final Map<String, int> classification = <String, int>{};
    for (final MapEntry<String, String> file in preview.files.entries) {
      if (!file.key.startsWith('reports/')) continue;
      final Map<String, Object?> report = jsonDecode(file.value) as Map<String, Object?>;
      for (final Object? status in (report['fields']! as Map<String, Object?>).values) {
        classification.update(status! as String, (int count) => count + 1, ifAbsent: () => 1);
      }
    }
    List<String> texts(String key) => <String>[for (final JElement entry in (summary.get(key)! as JArray).elements) entry.asPrimitive.asString];
    return CitizensReview._(preview, counts('traits'), counts('types'), counts('conversions'), classification, texts('issues'), texts('migrationWarnings'),
        preview.outputHash());
  }

  final CitizensPreview preview;
  final Map<String, int> traits;
  final Map<String, int> types;

  /// What runs in Animus: routes and their points, look-close, and generated text and command behaviors.
  final Map<String, int> conversions;

  /// Leaf fields of every record by conversion status.
  final Map<String, int> classification;
  final List<String> issues;
  final List<String> warnings;
  final String outputHash;

  int _count(String key) => (preview.summary.get(key)! as JInt).value.toInt();
  int get resolvedReferences => _count('resolvedReferences');
  int get unresolvedReferences => _count('unresolvedReferences');
  int get remappedLocations => _count('remappedLocations');

  /// Counts and the output hash only; never record contents.
  Map<String, Object?> aggregate() => <String, Object?>{
        'importId': preview.id,
        'records': preview.records,
        'valid': preview.valid,
        'skins': preview.skins,
        'behaviors': _count('behaviors'),
        'conversions': <String, int>{for (final String key in conversions.keys.toList()..sort()) key: conversions[key]!},
        'traitKinds': traits.length,
        'classification': <String, int>{for (final String key in classification.keys.toList()..sort()) key: classification[key]!},
        'issues': issues.length,
        'warnings': warnings.length,
        'resolvedReferences': resolvedReferences,
        'unresolvedReferences': unresolvedReferences,
        'files': preview.files.length,
        'outputHash': outputHash,
      };
}

/// `old=new` lines as a world map. Blank lines are skipped; a line without `=`, a blank side or a repeated
/// source world is an error, as in the converter's `preview` arguments.
Map<String, String> parseWorldMappings(String text) {
  final Map<String, String> worlds = <String, String>{};
  for (final String raw in const LineSplitter().convert(text)) {
    final String line = raw.trim();
    if (line.isEmpty) continue;
    final int equals = line.indexOf('=');
    final String from = equals < 0 ? '' : line.substring(0, equals).trim();
    final String to = equals < 0 ? '' : line.substring(equals + 1).trim();
    if (from.isEmpty || to.isEmpty || worlds.containsKey(from)) throw CitizensImportException('Invalid or duplicate world mapping: $line');
    worlds[from] = to;
  }
  return worlds;
}

/// Each converted NPC's name by its Citizens id, read from the staged definitions.
Map<String, String> citizensNames(CitizensPreview preview) {
  final Map<String, String> names = <String, String>{};
  for (final MapEntry<String, String> file in preview.files.entries) {
    if (!file.key.startsWith('npcs/') || !file.key.endsWith('.json')) continue;
    final Object? json = jsonDecode(file.value);
    if (json is! Map<String, Object?>) continue;
    final Object? id = json['id'];
    final Object? name = json['name'];
    if (id is String && id.startsWith('citizens-') && name is String) names[id.substring('citizens-'.length)] = name;
  }
  return names;
}

final RegExp _npcWarning = RegExp(r'^citizens-(\d+): (.*)$');
final RegExp _pointer = RegExp(r'^/[^\s:]*: ');

/// A converter warning in an admin's words: "NPC #3 (Baker): ..." in place
/// of `citizens-3: /traits/...: ...`. Warnings about the whole save stay as
/// written. The converter's own text stays as it is for the report files.
String plainWarning(String warning, Map<String, String> names) {
  final RegExpMatch? match = _npcWarning.firstMatch(warning);
  if (match == null) return warning;
  final String key = match.group(1)!;
  String text = match.group(2)!.replaceFirst(_pointer, '');
  text = text.replaceFirstMapped(RegExp(r'^1 points (.*) were '), (Match one) => '1 point ${one[1]} was ');
  if (text.isNotEmpty) text = '${text[0].toUpperCase()}${text.substring(1)}';
  final String? name = names[key];
  return 'NPC #$key${name == null ? '' : ' ($name)'}: $text';
}
