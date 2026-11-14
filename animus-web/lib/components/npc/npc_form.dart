/// What every tab of an NPC or trait group form reads and writes: the
/// definition fields (an NPC's, or a group's `values`), its schema and
/// diagnostics, and the writers. One is made per build of the form; the
/// lookups that walk the schema or the workspace are cached on the objects
/// they read, so a keystroke in one field does not redo them.
library;

import 'dart:typed_data';

import 'package:arcane_jaspr/arcane_jaspr.dart';

import '../../logic/citizens/definition_validation.dart' show effectiveActivationDiagnostics;
import '../../logic/gui/gui_json.dart';
import '../../logic/inspect/inspect_schemas.dart';
import '../../logic/inspect/schema_fields.dart';
import '../../logic/npc/group_model.dart';
import '../../logic/npc/npc_appearance.dart';
import '../../logic/npc/skin_edits.dart';
import '../../logic/suggest/field_profile.dart';
import '../../model/animus_document.dart';
import '../../model/document_kind.dart';
import '../conversation/conversation_attach.dart' show ConversationCommit;
import '../gui/gui_fields.dart' show JsonEdit;
import '../inspect/inspect_fields.dart';

/// [diagnostics] of a group document moved from `$.values` to the root, so
/// the tabs find them where an NPC's would be.
List<Diagnostic> valuesDiagnostics(List<Diagnostic> diagnostics) => <Diagnostic>[
      for (final Diagnostic d in diagnostics)
        if (d.path == r'$.values')
          Diagnostic(d.severity, r'$', d.message, code: d.code)
        else if (d.path.startsWith(r'$.values.') || d.path.startsWith(r'$.values['))
          Diagnostic(d.severity, '\$${d.path.substring(8)}', d.message, code: d.code),
    ];

/// The schema fields of a definition by key, worked out once per
/// definition object and schema.
final Expando<(SchemaView, Map<String, FieldSpec>)> _specs = Expando<(SchemaView, Map<String, FieldSpec>)>('npc fields');

/// The workspace's documents by kind, once per document list.
final Expando<Map<DocumentKind, List<AnimusDocument>>> _byKind = Expando<Map<DocumentKind, List<AnimusDocument>>>('documents by kind');

/// The workspace's trait groups, once per document list.
final Expando<Map<String, JsonMap>> _groups = Expando<Map<String, JsonMap>>('trait groups');

class NpcForm {
  NpcForm({
    required this.document,
    required this.group,
    required this.schemas,
    required List<Diagnostic> diagnostics,
    required this.workspaceDocuments,
    required this.onJson,
    this.onOpen,
    this.onCommit,
    this.serverBehaviors = const <String>[],
    this.serverPlugins = const <String>{},
    this.skinEditing,
    this.onSkinLoaded,
  }) : _rawDiagnostics = diagnostics;

  final AnimusDocument document;

  /// A trait group: the tabs edit its `values`.
  final bool group;
  final InspectSchemas schemas;
  final List<Diagnostic> _rawDiagnostics;
  final List<AnimusDocument> workspaceDocuments;
  final JsonEdit onJson;
  final void Function(DocumentKind kind, String id)? onOpen;

  /// Writes several documents as one undoable change; null hides the
  /// actions that need it.
  final ConversationCommit? onCommit;
  final List<String> serverBehaviors;
  final Set<String> serverPlugins;
  final SkinEditing? skinEditing;
  final void Function()? onSkinLoaded;

  late final JsonMap file = asMap(document.json);

  /// The definition fields: the NPC, or the group's `values`.
  late final JsonMap doc = group ? asMap(file['values']) : file;
  SchemaView get schema => schemas.npc;

  late final List<Diagnostic> diagnostics =
      group ? valuesDiagnostics(_rawDiagnostics) : <Diagnostic>[..._rawDiagnostics, ...effectiveActivationDiagnostics(file, groups)];

  Map<String, Uint8List> get resources => skinEditing?.resources ?? const <String, Uint8List>{};

  /// Every trait group of the workspace by id.
  Map<String, JsonMap> get groups => _groups[workspaceDocuments] ??= workspaceGroups(workspaceDocuments);

  /// The workspace's documents of [kind].
  List<AnimusDocument> documents(DocumentKind kind) {
    final Map<DocumentKind, List<AnimusDocument>> index = _byKind[workspaceDocuments] ??= _index(workspaceDocuments);
    return index[kind] ?? const <AnimusDocument>[];
  }

  static Map<DocumentKind, List<AnimusDocument>> _index(List<AnimusDocument> documents) {
    final Map<DocumentKind, List<AnimusDocument>> index = <DocumentKind, List<AnimusDocument>>{};
    for (final AnimusDocument document in documents) {
      (index[document.kind] ??= <AnimusDocument>[]).add(document);
    }
    return index;
  }

  List<String> ids(DocumentKind kind) => <String>[for (final AnimusDocument document in documents(kind)) document.id];

  /// A group has no body type; armor stand values or one body's own
  /// settings imply one, as the server's check assumes.
  String get type {
    if (!group) return asString(doc['type']) ?? 'minecraft:player';
    if (doc.containsKey('armorStand')) return 'minecraft:armor_stand';
    for (final MapEntry<String, String> settings in bodySettingsTypes.entries) {
      if (doc.containsKey(settings.key)) return settings.value;
    }
    return 'minecraft:player';
  }

  /// The NPC's x, y and z; null for a group or a definition without them.
  (double, double, double)? get origin {
    final JsonMap position = asMap(doc['position']);
    final double? x = asDouble(position['x']), y = asDouble(position['y']), z = asDouble(position['z']);
    return x == null || y == null || z == null ? null : (x, y, z);
  }

  String? get world => asString(asMap(doc['position'])['world']);

  JsonMap get modules => asMap(doc['modules']);

  /// Writes [value] at [steps] of the definition fields (under `values` for
  /// a group). [from] is what an editor saw at [steps] when that is ahead
  /// of the document.
  void emit(List<JsonStep> steps, Object? value, {required String coalesce, Object? from}) {
    final List<JsonStep> at = <JsonStep>[if (group) 'values', ...steps];
    onJson(writeAt(from == null ? file : writeIn(file, at, from), at, value), coalesce: group ? 'group:$coalesce' : coalesce);
  }

  void write(List<JsonStep> steps, Object? value) => emit(steps, value, coalesce: 'npc:${steps.join('.')}');

  /// Writes module [key]; null removes it. [from] is the module an editor
  /// worked [value] out from when that is ahead of the document.
  void putModule(String key, Object? value, {JsonMap? from}) {
    final JsonMap current = modules;
    final JsonMap next = asMap(writeIn(current, <JsonStep>[key], value));
    emit(const <JsonStep>['modules'], next.isEmpty ? null : next, coalesce: 'npc:module:$key', from: from == null ? null : <String, Object?>{...current, key: from});
  }

  /// The schema field [key] of the definition.
  FieldSpec spec(String key) {
    final (SchemaView, Map<String, FieldSpec>)? cached = _specs[doc];
    final Map<String, FieldSpec> fields = cached != null && identical(cached.$1, schema)
        ? cached.$2
        : (_specs[doc] = (schema, <String, FieldSpec>{for (final FieldSpec field in schema.fields(schema.root, doc)) field.key: field})).$2;
    return fields[key] ?? FieldSpec(key: key, kind: FieldKind.any);
  }

  /// Every schema field of the definition, in schema order.
  List<FieldSpec> get fields {
    spec('');
    return _specs[doc]!.$2.values.toList();
  }

  /// One schema field as a labelled row; [bounds] false leaves out the
  /// schema's limits under it.
  Widget field(String key, {List<FieldOption> suggestions = const <FieldOption>[], String? label, FieldProfile profile = FieldProfile.none, bool bounds = true}) => InspectField(
        key: ValueKey<String>('npc:$key'),
        profile: profile,
        bounds: bounds,
        field: spec(key),
        value: doc[key],
        path: childPath(r'$', key),
        diagnostics: diagnostics,
        suggestions: suggestions,
        schema: schema,
        label: label,
        onChanged: (Object? value) => write(<JsonStep>[key], value),
      );
}
