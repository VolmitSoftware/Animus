import 'dart:convert';

import '../config/shared_schemas.g.dart';
import '../model/animus_document.dart';
import '../model/document_kind.dart';
import 'json_schema.dart';

/// The repository's shared schemas, compiled once, with the kind-to-schema
/// table from editor-v1.endpoints.json.
class SharedSchemas {
  SharedSchemas._(this.registry, this.endpoints, this._kindSchemas);

  factory SharedSchemas.fromSources(Map<String, String> sources) {
    final SchemaRegistry registry = SchemaRegistry();
    final List<String> names = sources.keys.where((String name) => name.endsWith('.schema.json')).toList()..sort();
    // References resolve at validation time, so cross-schema $ref order does not matter.
    for (final String name in names) {
      registry.register(name, jsonDecode(sources[name]!) as Map<String, Object?>);
    }
    final Map<String, Object?> endpoints = jsonDecode(sources[endpointsFile]!) as Map<String, Object?>;
    final Map<DocumentKind, String> kindSchemas = <DocumentKind, String>{};
    final Map<String, Object?> kinds = endpoints['kinds']! as Map<String, Object?>;
    for (final MapEntry<String, Object?> entry in kinds.entries) {
      final DocumentKind? kind = DocumentKind.parse(entry.key);
      if (kind == null) throw StateError('editor-v1.endpoints.json names unknown kind ${entry.key}');
      final Object? schema = (entry.value! as Map<String, Object?>)['schema'];
      if (schema is String) kindSchemas[kind] = schema;
    }
    return SharedSchemas._(registry, endpoints, kindSchemas);
  }

  static SharedSchemas? _embedded;

  /// The schemas compiled into the app by tool/embed_schemas.dart.
  static SharedSchemas get embedded => _embedded ??= SharedSchemas.fromSources(sharedSchemaSources);

  static const String endpointsFile = 'editor-v1.endpoints.json';
  static const String editorSchema = 'editor-v1.schema.json';

  final SchemaRegistry registry;
  final Map<String, Object?> endpoints;
  final Map<DocumentKind, String> _kindSchemas;

  /// The schema file that validates [kind], or null when the kind has none yet.
  String? schemaFor(DocumentKind kind) => _kindSchemas[kind];

  /// Schema diagnostics for a parsed document of [kind].
  List<Diagnostic> validate(DocumentKind kind, Object? json) {
    final String? schema = schemaFor(kind);
    if (schema == null) {
      return const <Diagnostic>[
        Diagnostic(DiagnosticSeverity.info, r'$', 'No shared schema for this kind yet; the server validates it.', code: 'no-schema'),
      ];
    }
    return <Diagnostic>[
      for (final SchemaError error in registry.validate(schema, json))
        Diagnostic(DiagnosticSeverity.error, error.path, error.message, code: 'schema'),
    ];
  }

  /// Errors for an editor API message against the `$defs` entry [definition].
  List<SchemaError> validateMessage(String definition, Object? json) =>
      registry.validate(editorSchema, json, definition: definition);
}
