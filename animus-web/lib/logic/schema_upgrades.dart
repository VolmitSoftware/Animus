/// The browser's copy of the server's schema migrations (core's
/// `SchemaMigrations`) for the kinds that have a step: an NPC or trait group
/// at schemaVersion 1 loses the `reviewAccepted` flag the removed import
/// accept step left, and becomes schemaVersion 2. Both sides are held to
/// `schema/fixtures/upgrades` byte for byte.
library;

import 'dart:convert';

import '../model/document_kind.dart';
import 'gui/gui_json.dart';

/// The version each kind with a migration step is written at.
const Map<DocumentKind, int> currentSchemaVersions = <DocumentKind, int>{DocumentKind.npc: 2, DocumentKind.group: 2};

/// The module sections an import before version 2 could mark with `reviewAccepted`.
const List<String> reviewedModules = <String>['animus:combat', 'animus:navigation', 'animus:shop', 'animus:riding', 'animus:schedule', 'animus:builder'];

/// A document a migration rewrote: the version it was written at and its new
/// text, formatted as the server writes it.
class SchemaUpgrade {
  const SchemaUpgrade(this.from, this.to, this.source);

  final int from;
  final int to;
  final String source;
}

/// The upgrade of [source], or null when it needs none: another kind, a
/// current or newer version (the schema reports a newer one), a missing or
/// non-integer `schemaVersion`, or text that does not parse.
SchemaUpgrade? upgradeSource(DocumentKind kind, String source) {
  final int? current = currentSchemaVersions[kind];
  if (current == null) return null;
  final Object? json;
  try {
    json = jsonDecode(source);
  } on FormatException {
    return null;
  }
  if (json is! Map<String, Object?>) return null;
  final int? version = _version(json['schemaVersion']);
  if (version == null || version < 1 || version >= current) return null;
  final Map<String, Object?> next = _copy(json)! as Map<String, Object?>;
  final Object? holder = kind == DocumentKind.group ? next['values'] : next;
  // Version 2 has no import review: the flag goes whatever its value, so a
  // section an older import left at false runs as converted.
  if (holder is Map<String, Object?> && holder['modules'] is Map<String, Object?>) {
    final Map<String, Object?> modules = holder['modules']! as Map<String, Object?>;
    for (final String module in reviewedModules) {
      final Object? section = modules[module];
      if (section is Map<String, Object?>) section.remove('reviewAccepted');
    }
  }
  next['schemaVersion'] = current;
  return SchemaUpgrade(version, current, prettyJson(next));
}

/// An integral, non-negative number, as the server reads `schemaVersion`.
int? _version(Object? value) {
  if (value is int) return value;
  if (value is double && value.isFinite && value == value.truncateToDouble()) return value.toInt();
  return null;
}

Object? _copy(Object? value) {
  if (value is Map<String, Object?>) return <String, Object?>{for (final MapEntry<String, Object?> entry in value.entries) entry.key: _copy(entry.value)};
  if (value is List<Object?>) return <Object?>[for (final Object? item in value) _copy(item)];
  return value;
}
