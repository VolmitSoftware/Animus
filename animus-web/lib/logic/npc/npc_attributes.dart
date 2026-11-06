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
  const AttributeSpec({required this.id, required this.minimum, required this.maximum, required this.defaultValue, this.description});

  /// The bare id, such as `scale`.
  final String id;
  final double minimum;
  final double maximum;

  /// The vanilla default, which applies while the definition leaves it unset.
  final double defaultValue;

  /// What the attribute does to an NPC.
  final String? description;

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
