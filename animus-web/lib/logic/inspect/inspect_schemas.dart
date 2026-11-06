/// The schemas the inspectors read: the connected server's live ones (which
/// include loaded modules) when present, otherwise the generated copies
/// embedded in the app.
library;

import 'dart:convert';

import '../../config/shared_schemas.g.dart';
import '../../model/document_kind.dart';
import '../gui/gui_json.dart';
import 'completion_catalog.dart';
import 'schema_fields.dart';

class InspectSchemas {
  InspectSchemas._({required this.npc, required this.behavior, required this.pack, required this.serverBehaviors, required this.catalog, required this.live});

  /// Builds the set from a `GET /v1/schemas` map; any schema it lacks comes
  /// from the embedded copy.
  factory InspectSchemas.from(Map<String, Object?>? live) {
    if (live == null) return embedded;
    final Map<String, Object?>? previous = _liveSource;
    final InspectSchemas? cached = _liveCache;
    if (cached != null && identical(previous, live)) return cached;
    final InspectSchemas fallback = embedded;
    SchemaView pick(String key, SchemaView otherwise) {
      final Object? schema = live[key];
      return schema is Map<String, Object?> ? SchemaView(schema) : otherwise;
    }

    final Object? completions = live['completions'];
    final InspectSchemas built = InspectSchemas._(
      npc: pick('npc', fallback.npc),
      behavior: pick('behavior', fallback.behavior),
      pack: pick('pack', fallback.pack),
      serverBehaviors: fallback.serverBehaviors,
      catalog: completions is Map<String, Object?> ? CompletionCatalog.fromJson(completions) : fallback.catalog,
      live: true,
    );
    _liveSource = live;
    _liveCache = built;
    return built;
  }

  /// Builds the set from schema sources keyed like [sharedSchemaSources].
  factory InspectSchemas.fromSources(Map<String, String> sources) {
    Map<String, Object?> decode(String name) => asMap(jsonDecode(sources[name] ?? '{}'));
    return InspectSchemas._(
      npc: SchemaView(decode('generated/npc.schema.json')),
      behavior: SchemaView(decode('generated/behavior.schema.json')),
      pack: SchemaView(decode('generated/pack.schema.json')),
      serverBehaviors: SchemaView(decode('server-behaviors-v1.schema.json')),
      catalog: CompletionCatalog.fromJson(decode('generated/completions.json')),
      live: false,
    );
  }

  static InspectSchemas? _embedded;
  static Map<String, Object?>? _liveSource;
  static InspectSchemas? _liveCache;

  static InspectSchemas get embedded => _embedded ??= InspectSchemas.fromSources(sharedSchemaSources);

  final SchemaView npc;
  final SchemaView behavior;
  final SchemaView pack;

  /// `server-behaviors.json`, for the code view's completion.
  final SchemaView serverBehaviors;
  final CompletionCatalog catalog;

  /// Whether these came from a connected server.
  final bool live;

  /// The schema for [kind], or null when the inspectors have none.
  SchemaView? forKind(DocumentKind kind) => switch (kind) {
        DocumentKind.npc => npc,
        DocumentKind.behavior => behavior,
        DocumentKind.pack => pack,
        DocumentKind.serverBehaviors => serverBehaviors,
        _ => null,
      };
}
