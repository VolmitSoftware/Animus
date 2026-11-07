/// A definition's `attributes`, as the server's `NpcAttributes` reads them:
/// vanilla entity attribute ids mapped to base values. Ids may carry the
/// `minecraft:` namespace and mean the same attribute bare; the editor writes
/// them bare. The attributes, their ranges, vanilla defaults and notes come
/// from the NPC schema, which the server generates from its attribute table.
library;

import '../citizens/java_json.dart' show javaDoubleToString;
import '../gui/gui_json.dart';
import '../inspect/inspect_schemas.dart';
import '../inspect/schema_fields.dart';
import '../select/select_model.dart';

const String attributesField = 'attributes';
const String attributeNamespace = 'minecraft:';

/// [id] without the `minecraft:` namespace.
String bareAttributeId(String id) => id.startsWith(attributeNamespace) ? id.substring(attributeNamespace.length) : id;

/// A number the way `NpcAttributes.format` prints it; Java's largest double
/// (an attribute without a real ceiling) is `any`.
String formatAttribute(double value) {
  if (value >= double.maxFinite) return 'any';
  return value == value.roundToDouble() && value.abs() < 1e15 ? value.toInt().toString() : javaDoubleToString(value);
}

/// One attribute the server knows.
class AttributeSpec {
  const AttributeSpec({required this.id, required this.minimum, required this.maximum, required this.defaultValue, this.description, this.bodies = const <String>{}});

  /// The bare id, such as `scale`.
  final String id;
  final double minimum;
  final double maximum;

  /// The vanilla default, which applies while the definition leaves it unset.
  final double defaultValue;

  /// What the attribute does to an NPC.
  final String? description;

  /// The body types whose viewers are sent this attribute (`x-bodies`).
  final Set<String> bodies;

  /// Whether a body of [type] carries it; any body when [type] is unknown.
  bool carriedBy(String? type) => type == null || bodies.contains(type);

  /// `0.0625..16`.
  String get range => '${formatAttribute(minimum)}..${formatAttribute(maximum)}';

  /// Null when [value] suits the attribute, otherwise why not, worded as the server words it.
  String? problem(Object? value) {
    if (value is! num) return 'expected a number';
    if (!value.isFinite || value < minimum || value > maximum) return 'expected $range for $id';
    return null;
  }
}

final Expando<List<AttributeSpec>> _specs = Expando<List<AttributeSpec>>();

/// The attributes [schema] lists for `attributes`, bare ids in schema order.
List<AttributeSpec> attributeSpecs(SchemaView schema) => _specs[schema] ??= _read(schema);

List<AttributeSpec> _read(SchemaView schema) {
  final FieldSpec? field = schema.fields(schema.root, const <String, Object?>{}).where((FieldSpec field) => field.key == attributesField).firstOrNull;
  if (field == null) return const <AttributeSpec>[];
  final List<AttributeSpec> specs = <AttributeSpec>[];
  for (final MapEntry<String, Object?> entry in asMap(field.schema['properties']).entries) {
    if (entry.key.startsWith(attributeNamespace)) continue;
    final JsonMap node = schema.resolve(entry.value);
    final Object? minimum = node['minimum'], maximum = node['maximum'], fallback = node['default'];
    specs.add(AttributeSpec(
      id: entry.key,
      minimum: minimum is num ? minimum.toDouble() : -double.maxFinite,
      maximum: maximum is num ? maximum.toDouble() : double.maxFinite,
      defaultValue: fallback is num ? fallback.toDouble() : 0,
      description: asString(node['description']),
      bodies: <String>{for (final Object? body in asList(node['x-bodies'])) ?asString(body)},
    ));
  }
  return List<AttributeSpec>.unmodifiable(specs);
}

/// [schema]'s attributes by bare id.
Map<String, AttributeSpec> attributeTable(SchemaView schema) => <String, AttributeSpec>{
      for (final AttributeSpec spec in attributeSpecs(schema)) spec.id: spec,
    };

/// An `attributes` value keyed by bare ids, in written order; when an id is
/// written both ways the later spelling wins, as the group merge has it.
JsonMap bareAttributes(Object? attributes) => <String, Object?>{
      for (final MapEntry<String, Object?> entry in asMap(attributes).entries) bareAttributeId(entry.key): entry.value,
    };

/// The add dropdown's headings, each with its attributes in the order they
/// matter most to an NPC; attributes only players carry go under
/// [_playerOnly] whatever their kind.
const Map<String, List<String>> _attributeGroups = <String, List<String>>{
  'Size and movement': <String>[
    'scale', 'movement_speed', 'step_height', 'jump_strength', 'gravity', 'safe_fall_distance', 'fall_damage_multiplier', //
    'flying_speed', 'water_movement_efficiency', 'movement_efficiency', 'sneaking_speed', 'friction_modifier', 'air_drag_modifier', 'bounciness',
  ],
  'Combat': <String>[
    'max_health', 'armor', 'armor_toughness', 'attack_damage', 'attack_knockback', 'attack_speed', 'knockback_resistance', //
    'explosion_knockback_resistance', 'max_absorption', 'burning_time', 'sweeping_damage_ratio',
  ],
  'Senses': <String>[
    'follow_range', 'tempt_range', 'entity_interaction_range', 'block_interaction_range', 'camera_distance', 'name_tag_distance', //
    'below_name_distance', 'waypoint_transmit_range', 'waypoint_receive_range',
  ],
};
const String _playerOnly = 'Player only';
const String _other = 'Other';

/// The add dropdown's rows: every attribute, grouped, those already [set]
/// disabled, each described by its range and vanilla default. For a body of
/// [bodyType] the attributes it carries come first in each group, and the
/// rest say they have no effect on it; player-only attributes sink below
/// Other for every other body.
List<SelectOption> attributeOptions(List<AttributeSpec> specs, JsonMap set, String? bodyType) {
  final Map<String, AttributeSpec> table = <String, AttributeSpec>{for (final AttributeSpec spec in specs) spec.id: spec};
  String groupOf(AttributeSpec spec) {
    if (spec.bodies.length == 1 && spec.bodies.single == 'minecraft:player') return _playerOnly;
    for (final MapEntry<String, List<String>> group in _attributeGroups.entries) {
      if (group.value.contains(spec.id)) return group.key;
    }
    return _other;
  }

  int rank(AttributeSpec spec) {
    for (final List<String> ids in _attributeGroups.values) {
      if (ids.contains(spec.id)) return ids.indexOf(spec.id);
    }
    return 1 << 20;
  }

  final List<String> groups = <String>[
    ..._attributeGroups.keys,
    if (bodyType == null || bodyType == 'minecraft:player') ...<String>[_playerOnly, _other] else ...<String>[_other, _playerOnly],
  ];
  final List<SelectOption> options = <SelectOption>[];
  for (final String group in groups) {
    final List<AttributeSpec> members = <AttributeSpec>[
      for (final AttributeSpec spec in table.values)
        if (groupOf(spec) == group) spec,
    ]..sort((AttributeSpec a, AttributeSpec b) {
        final int carried = (a.carriedBy(bodyType) ? 0 : 1) - (b.carriedBy(bodyType) ? 0 : 1);
        if (carried != 0) return carried;
        final int ranked = rank(a) - rank(b);
        return ranked != 0 ? ranked : a.id.compareTo(b.id);
      });
    for (final AttributeSpec spec in members) {
      options.add(SelectOption(
        value: spec.id,
        label: spec.id,
        group: group,
        disabled: set.containsKey(spec.id),
        keywords: <String>['$attributeNamespace${spec.id}', ...spec.id.split('_')],
        description: <String>[
          '${spec.range}, vanilla ${formatAttribute(spec.defaultValue)}',
          if (!spec.carriedBy(bodyType)) 'no effect on this body',
        ].join('; '),
      ));
    }
  }
  return options;
}

/// [attributes] with [id] set to [value], or returned to its default when
/// [value] is null; every id comes back bare. Null when none is left, so the
/// field can be dropped.
JsonMap? withAttribute(Object? attributes, String id, num? value) {
  final String bare = bareAttributeId(id);
  final JsonMap next = <String, Object?>{
    for (final MapEntry<String, Object?> entry in bareAttributes(attributes).entries)
      if (entry.key != bare) entry.key: entry.value,
    bare: ?value,
  };
  return next.isEmpty ? null : next;
}

/// The body size multiplier of a definition: its valid `scale`, otherwise
/// the vanilla default.
double definitionScale(JsonMap definition) {
  final AttributeSpec? spec = attributeTable(InspectSchemas.embedded.npc)['scale'];
  final Object? value = bareAttributes(definition[attributesField])['scale'];
  if (spec == null) return 1;
  return value is num && spec.problem(value) == null ? value.toDouble() : spec.defaultValue;
}
