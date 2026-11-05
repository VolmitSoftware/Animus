/// The generated `completions.json`: capabilities with their argument specs
/// and availability, trigger events with their fields, and the behavior
/// language's node types. Field specs here become [FieldSpec]s so the
/// inspectors and the code view's completion treat them like schema fields.
library;

import '../gui/gui_json.dart';
import 'schema_fields.dart';

/// One action or condition capability.
class CapabilitySpec {
  const CapabilitySpec({
    required this.id,
    required this.kind,
    required this.description,
    required this.privileged,
    required this.available,
    required this.reason,
    required this.arguments,
    this.oneOfRequired = const <List<String>>[],
  });

  final String id;

  /// `action` or `condition`.
  final String kind;
  final String description;
  final bool privileged;
  final bool available;

  /// `available`, or why it is not (`platform-unavailable: ...`).
  final String reason;
  final List<FieldSpec> arguments;

  /// Groups of arguments of which one group must be given whole.
  final List<List<String>> oneOfRequired;

  /// A short note for pickers: unavailable reasons and privilege.
  String get note => <String>[if (!available) reason, if (privileged) 'privileged'].join('; ');
}

/// One trigger event and its fields.
class TriggerSpec {
  const TriggerSpec({required this.event, required this.description, required this.carriesPlayer, required this.fields});

  final String event;
  final String description;
  final bool carriesPlayer;
  final List<FieldSpec> fields;
}

/// One behavior node type and its fields.
class NodeSpec {
  const NodeSpec({required this.type, required this.description, required this.fields});

  final String type;
  final String description;
  final List<FieldSpec> fields;

  FieldSpec? field(String key) => fields.where((FieldSpec field) => field.key == key).firstOrNull;
}

/// Field types in completions.json that hold nodes.
const String nodeListType = 'nodes';
const String nodeType = 'node';

class CompletionCatalog {
  const CompletionCatalog({
    required this.capabilities,
    required this.triggers,
    required this.nodes,
    this.variableTypes = const <String>[],
    this.variableScopes = const <String>[],
    this.variableLifetimes = const <String>[],
  });

  factory CompletionCatalog.fromJson(Object? json) {
    final JsonMap root = asMap(json);
    final JsonMap language = asMap(root['language']);
    return CompletionCatalog(
      capabilities: <CapabilitySpec>[
        for (final Object? raw in asList(root['capabilities']))
          if (raw is Map<String, Object?> && raw['id'] is String)
            CapabilitySpec(
              id: raw['id']! as String,
              kind: asString(raw['kind']) ?? 'action',
              description: asString(raw['description']) ?? '',
              privileged: raw['privileged'] == true,
              available: raw['available'] != false,
              reason: asString(raw['reason']) ?? 'available',
              arguments: _fields(raw['arguments']),
              oneOfRequired: <List<String>>[
                for (final Object? group in asList(raw['oneOfRequired'])) <String>[for (final Object? key in asList(group)) if (key is String) key],
              ],
            ),
      ],
      triggers: <String, TriggerSpec>{
        for (final MapEntry<String, Object?> entry in asMap(root['triggers']).entries)
          entry.key: TriggerSpec(
            event: entry.key,
            description: asString(asMap(entry.value)['description']) ?? '',
            carriesPlayer: asMap(entry.value)['carriesPlayer'] == true,
            fields: _fields(asMap(entry.value)['fields']),
          ),
      },
      nodes: <String, NodeSpec>{
        for (final MapEntry<String, Object?> entry in asMap(language['nodes']).entries)
          entry.key: NodeSpec(
            type: entry.key,
            description: asString(asMap(entry.value)['description']) ?? '',
            fields: _fields(asMap(entry.value)['fields']),
          ),
      },
      variableTypes: <String>[for (final Object? value in asList(language['variableTypes'])) if (value is String) value],
      variableScopes: <String>[for (final Object? value in asList(language['variableScopes'])) if (value is String) value],
      variableLifetimes: <String>[for (final Object? value in asList(language['variableLifetimes'])) if (value is String) value],
    );
  }

  final List<CapabilitySpec> capabilities;
  final Map<String, TriggerSpec> triggers;
  final Map<String, NodeSpec> nodes;
  final List<String> variableTypes;
  final List<String> variableScopes;
  final List<String> variableLifetimes;

  CapabilitySpec? capability(String? id) => capabilities.where((CapabilitySpec spec) => spec.id == id).firstOrNull;

  /// Capabilities a node of [nodeType] (`action` or `condition`) may run.
  List<CapabilitySpec> capabilitiesFor(String nodeType) => <CapabilitySpec>[
        for (final CapabilitySpec spec in capabilities)
          if (spec.kind == nodeType) spec,
      ];

  /// The completions.json field map turned into field descriptors. Field
  /// types other than JSON's (`identifier`, `routine`, `node`, ...) keep
  /// their name in [FieldSpec.schema]'s `x-type` for the editors.
  static List<FieldSpec> _fields(Object? raw) => <FieldSpec>[
        for (final MapEntry<String, Object?> entry in asMap(raw).entries) fieldFromSpec(entry.key, asMap(entry.value)),
      ];
}

/// A completions.json argument or field spec as a [FieldSpec].
FieldSpec fieldFromSpec(String key, JsonMap spec) {
  final String type = asString(spec['type']) ?? 'any';
  final Object? options = spec['enum'];
  final bool hasConst = spec.containsKey('const');
  final List<FieldOption> choices = <FieldOption>[
    if (options is List<Object?>)
      for (final Object? option in options)
        if (option != null) FieldOption(option),
    if (hasConst && spec['const'] != null) FieldOption(spec['const']!),
  ];
  final Object? exclusiveMinimum = spec['exclusiveMinimum'];
  final Object? exclusiveMaximum = spec['exclusiveMaximum'];
  return FieldSpec(
    key: key,
    kind: choices.isNotEmpty
        ? FieldKind.choice
        : switch (type) {
            'string' || 'identifier' || 'routine' || 'variable' || 'capability' || 'event' => FieldKind.string,
            'integer' => FieldKind.integer,
            'number' => FieldKind.number,
            'boolean' => FieldKind.boolean,
            'array' || 'nodes' => FieldKind.array,
            'arguments' || 'bindings' || 'node' => FieldKind.object,
            _ => FieldKind.any,
          },
    required: spec['required'] == true,
    description: asString(spec['description']),
    options: choices,
    minimum: exclusiveMinimum is num ? exclusiveMinimum : spec['minimum'] as num?,
    maximum: exclusiveMaximum is num ? exclusiveMaximum : spec['maximum'] as num?,
    exclusiveMinimum: exclusiveMinimum is num,
    exclusiveMaximum: exclusiveMaximum is num,
    minLength: spec['minLength'] as int?,
    maxLength: spec['maxLength'] as int?,
    pattern: asString(spec['pattern']),
    schema: <String, Object?>{'x-type': type},
  );
}

/// The completions.json type name of a field built by [fieldFromSpec].
String fieldType(FieldSpec field) => asString(field.schema['x-type']) ?? '';
