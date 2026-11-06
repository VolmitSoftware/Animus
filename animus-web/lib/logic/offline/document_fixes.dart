/// One-click repairs for the browser's diagnostics: a missing required field
/// gets its default, an id that does not match the file location takes the
/// location's, an unknown field goes, a value outside its bounds or choices
/// moves to the nearest allowed one, a malformed UUID is replaced. Every fix
/// is a JSON-to-JSON function; the store revalidates what it returns.
library;

import 'dart:convert';
import 'dart:math' as math;

import '../../config/shared_schemas.g.dart';
import '../../model/animus_document.dart';
import '../../model/document_kind.dart';
import '../gui/gui_json.dart';
import '../inspect/schema_fields.dart';
import '../npc/npc_paths.dart';
import '../shared_schemas.dart';
import 'new_documents.dart';

class DocumentFix {
  const DocumentFix(this.path, this.label, this.apply);

  /// The diagnostic path the fix repairs.
  final String path;

  /// What the fix does, as a button label.
  final String label;
  final Object? Function(Object? json) apply;
}

/// `$.a.b[0]["#"]` as steps; null when the path does not parse.
List<JsonStep>? parseJsonPath(String path) {
  if (!path.startsWith(r'$')) return null;
  final List<JsonStep> steps = <JsonStep>[];
  int index = 1;
  while (index < path.length) {
    final String char = path[index];
    if (char == '.') {
      int end = index + 1;
      while (end < path.length && path[end] != '.' && path[end] != '[') {
        end++;
      }
      steps.add(path.substring(index + 1, end));
      index = end;
    } else if (char == '[') {
      final int close = path.indexOf(']', index);
      if (close < 0) return null;
      final String inner = path.substring(index + 1, close);
      if (inner.startsWith('"')) {
        try {
          steps.add(jsonDecode(inner) as String);
        } on FormatException {
          return null;
        }
      } else {
        final int? at = int.tryParse(inner);
        if (at == null) return null;
        steps.add(at);
      }
      index = close + 1;
    } else {
      return null;
    }
  }
  return steps;
}

final Map<DocumentKind, SchemaView?> _views = <DocumentKind, SchemaView?>{};

SchemaView? _viewFor(DocumentKind kind) => _views.putIfAbsent(kind, () {
      final String? name = SharedSchemas.embedded.schemaFor(kind);
      final String? source = name == null ? null : sharedSchemaSources[name];
      return source == null ? null : SchemaView(jsonDecode(source) as Map<String, Object?>);
    });

/// The value a missing required field of [kind] at [steps] starts with.
Object? _defaultFor(AnimusDocument document, List<JsonStep> steps, Map<String, Object?>? schema, math.Random? random) {
  final String? key = steps.isEmpty || steps.last is! String ? null : steps.last as String;
  if (steps.length == 1) {
    switch ((document.kind, key)) {
      case (DocumentKind.npc, 'uuid'):
        return newUuid(random);
      case (DocumentKind.npc, 'id'):
        return npcLocalIdOf(document.id);
      case (DocumentKind.npc, 'group'):
        return npcFolderOf(document.id);
      case (DocumentKind.npc, 'name'):
        return npcLocalIdOf(document.id);
      case (DocumentKind.npc, 'type'):
        return 'minecraft:player';
      case (DocumentKind.npc, 'spawned'):
        return true;
      case (DocumentKind.npc, 'position'):
        return <String, Object?>{'world': 'world', 'x': 0.5, 'y': 64, 'z': 0.5, 'yaw': 0, 'pitch': 0};
      case (DocumentKind.behavior, 'entry'):
        final JsonMap routines = asMap(asMap(document.json)['routines']);
        return routines.isEmpty ? 'main' : routines.keys.first;
      case (DocumentKind.behavior, 'routines'):
        return <String, Object?>{
          'main': <String, Object?>{'type': 'sequence', 'steps': <Object?>[]},
        };
      case (_, 'id'):
        return document.id;
      case (_, 'revision'):
        return 1;
      default:
        break;
    }
  }
  if (schema == null) return null;
  if (schema.containsKey('const')) return schema['const'];
  if (schema.containsKey('default')) return schema['default'];
  final List<Object?> options = asList(schema['enum']);
  if (options.isNotEmpty) return options.first;
  final Object? type = schema['type'];
  final String? typeName = type is String ? type : (type is List<Object?> && type.isNotEmpty ? type.first as String? : null);
  final Object? minimum = schema['minimum'];
  return switch (typeName) {
    'object' => <String, Object?>{},
    'array' => <Object?>[],
    'boolean' => false,
    'integer' => minimum is num ? minimum.toInt() : 0,
    'number' => minimum is num ? minimum : 0,
    _ => null,
  };
}

final RegExp _bound = RegExp(r'^expected (at least|at most) (-?[0-9.]+)$');
final RegExp _oneOf = RegExp(r'^expected one of ');

/// The fixes the editor can offer for [document]'s diagnostics, at most one
/// per diagnostic path.
List<DocumentFix> fixesFor(AnimusDocument document, {math.Random? random}) {
  final Object? json = document.json;
  if (json is! Map<String, Object?>) return const <DocumentFix>[];
  final SchemaView? view = _viewFor(document.kind);
  final List<DocumentFix> fixes = <DocumentFix>[];
  final Set<String> seen = <String>{};
  for (final Diagnostic diagnostic in document.diagnostics) {
    if (diagnostic.severity != DiagnosticSeverity.error || seen.contains(diagnostic.path)) continue;
    final List<JsonStep>? steps = parseJsonPath(diagnostic.path);
    if (steps == null || steps.isEmpty) continue;
    final Object? current = readIn(json, steps);
    final Map<String, Object?>? schema = view?.at(steps, json);
    final String field = steps.last is String ? steps.last as String : 'item ${steps.last}';
    DocumentFix? fix;
    if (diagnostic.code == 'id-mismatch') {
      final Object? expected = _defaultFor(document, steps, schema, random);
      if (expected != null) fix = DocumentFix(diagnostic.path, 'Set $field to $expected', (Object? value) => writeIn(value, steps, expected));
    } else if (diagnostic.message == 'is required') {
      final Object? fallback = _defaultFor(document, steps, schema, random);
      if (fallback != null) {
        final String shown = fallback is Map || fallback is List ? 'a default' : jsonEncode(fallback);
        fix = DocumentFix(diagnostic.path, 'Add $field ($shown)', (Object? value) => writeIn(value, steps, fallback));
      }
    } else if (diagnostic.message == 'unknown field') {
      fix = DocumentFix(diagnostic.path, 'Remove $field', (Object? value) => writeIn(value, steps, null));
    } else if (field == 'uuid' && diagnostic.message.startsWith('does not match')) {
      final String uuid = newUuid(random);
      fix = DocumentFix(diagnostic.path, 'Replace with a new UUID', (Object? value) => writeIn(value, steps, uuid));
    } else if (diagnostic.message.startsWith('does not match') && current is String && (field == 'id' || field == 'group' || field == 'tags')) {
      final String cleaned = slug(current, fallback: 'npc');
      if (cleaned != current) fix = DocumentFix(diagnostic.path, 'Use $cleaned', (Object? value) => writeIn(value, steps, cleaned));
    } else if (diagnostic.message.startsWith('expected ') && schema != null && schema.containsKey('const')) {
      final Object? constant = schema['const'];
      fix = DocumentFix(diagnostic.path, 'Set $field to ${jsonEncode(constant)}', (Object? value) => writeIn(value, steps, constant));
    } else if (_oneOf.hasMatch(diagnostic.message) && schema != null && asList(schema['enum']).isNotEmpty) {
      final Object? first = asList(schema['enum']).first;
      fix = DocumentFix(diagnostic.path, 'Use ${jsonEncode(first)}', (Object? value) => writeIn(value, steps, first));
    } else if (current is num) {
      final RegExpMatch? match = _bound.firstMatch(diagnostic.message);
      final num? limit = match == null ? null : num.tryParse(match.group(2)!);
      if (limit != null) {
        final num clamped = current is int ? limit.toInt() : limit;
        fix = DocumentFix(diagnostic.path, 'Use $clamped', (Object? value) => writeIn(value, steps, clamped));
      }
    } else if (current is List<Object?> && diagnostic.message.startsWith('expected at most ') && diagnostic.message.endsWith(' items')) {
      final int? limit = int.tryParse(diagnostic.message.substring(17, diagnostic.message.length - 6));
      if (limit != null) {
        fix = DocumentFix(diagnostic.path, 'Keep the first $limit', (Object? value) => writeIn(value, steps, current.sublist(0, limit)));
      }
    } else if (diagnostic.message.startsWith('duplicates item ') && steps.last is int) {
      fix = DocumentFix(diagnostic.path, 'Remove the duplicate', (Object? value) => writeIn(value, steps, null));
    }
    if (fix != null) {
      seen.add(diagnostic.path);
      fixes.add(fix);
    }
  }
  return fixes;
}

/// [json] with every fix applied, for "Fix all". Later paths go first, so a
/// removed list item does not move the items an earlier fix names.
Object? applyFixes(Object? json, List<DocumentFix> fixes) {
  Object? out = json;
  for (final DocumentFix fix in fixes.reversed) {
    out = fix.apply(out);
  }
  return out;
}
