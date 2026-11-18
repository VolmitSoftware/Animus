/// Capabilities whose arguments come in groups of which exactly one is
/// given (Look at the player or at coordinates; Navigate by route, anchor
/// or coordinates, where coordinates are x, y and z or one location): the groups as one choice in words, which group a step
/// uses, and the step switched to another group.
library;

import '../gui/gui_json.dart';
import 'behavior_operands.dart';
import 'behavior_words.dart' show argumentLabel;
import 'completion_catalog.dart';
import 'schema_fields.dart';

/// One group of a capability's exclusive arguments, in words.
class ArgumentChoice {
  const ArgumentChoice(this.label, this.keys, {required this.group, this.also = const <int>[]});

  final String label;
  final List<String> keys;

  /// The index of its group in the capability's `oneOfRequired`.
  final int group;

  /// Other groups the choice stands for: a `location` written x,y,z stands
  /// under Coordinates, so an action offers one coordinates option.
  final List<int> also;

  /// Whether a step that uses group [index] shows this choice as picked.
  bool stands(int index) => index == group || also.contains(index);
}

const List<String> _coordinates = <String>['x', 'y', 'z'];

/// Words for each group, keyed by its arguments joined with commas.
const Map<String, Map<String, String>> _choiceWords = <String, Map<String, String>>{
  'animus:look': <String, String>{'target': 'The player', 'x,y,z': 'Coordinates'},
  'animus:navigate': <String, String>{'route': 'A route', 'anchor': 'An anchor', 'x,y,z': 'Coordinates'},
  'animus:teleport': <String, String>{'x,y,z': 'Coordinates', 'to': 'The NPC or player', 'anchor': 'An anchor'},
  'animus:set-quest-state': <String, String>{'value': 'A stage', 'random': 'A random stage'},
};

const Map<String, String> _choiceFields = <String, String>{
  'animus:look': 'Looks at',
  'animus:navigate': 'Goes to',
  'animus:teleport': 'To',
  'animus:set-quest-state': 'Sets',
};

/// The exclusive argument groups of [spec] as choices; empty when it has
/// none. A `location` group joins the x, y and z group as one Coordinates
/// choice, and stands alone, as Coordinates, only where there is no x, y and
/// z group.
List<ArgumentChoice> argumentChoices(CapabilitySpec spec) {
  final List<List<String>> groups = spec.oneOfRequired;
  final int coordinates = groups.indexWhere((List<String> group) => group.join(',') == _coordinates.join(','));
  final int location = groups.indexWhere((List<String> group) => group.length == 1 && group.single == 'location');
  return <ArgumentChoice>[
    for (int index = 0; index < groups.length; index++)
      if (index != location || coordinates < 0)
        ArgumentChoice(
          _choiceWords[spec.id]?[groups[index].join(',')] ??
              (index == coordinates || index == location ? 'Coordinates' : groups[index].map(argumentLabel).join(' + ')),
          groups[index],
          group: index,
          also: index == coordinates && location >= 0 ? <int>[location] : const <int>[],
        ),
  ];
}

/// The label of the choice's row.
String argumentChoiceField(CapabilitySpec spec) => _choiceFields[spec.id] ?? 'Uses';

/// Whether argument [key] of [node] is given: a written value, or a
/// placeholder a variable, expression or template fills.
bool argumentGiven(JsonMap node, String key) {
  if (argumentSource(node, key) != ArgumentSource.literal) return true;
  return asMap(node['arguments'])[key] != null;
}

/// The group [node] uses: the first given whole, else the first given in
/// part; null when none is.
int? chosenArgumentGroup(JsonMap node, CapabilitySpec spec) {
  final List<List<String>> groups = spec.oneOfRequired;
  for (int index = 0; index < groups.length; index++) {
    if (groups[index].every((String key) => argumentGiven(node, key))) return index;
  }
  for (int index = 0; index < groups.length; index++) {
    if (groups[index].any((String key) => argumentGiven(node, key))) return index;
  }
  return null;
}

/// [node] using group [index] of [spec]: the other groups' arguments and
/// their placeholders go, and this group's missing arguments start with a
/// value (the constant, the first option, 0, 64, 0 for coordinates, else
/// empty for the user to fill).
JsonMap chooseArgumentGroup(JsonMap node, CapabilitySpec spec, int index) {
  final List<String> keys = spec.oneOfRequired[index];
  final Set<String> others = <String>{
    for (final List<String> group in spec.oneOfRequired)
      for (final String key in group)
        if (!keys.contains(key)) key,
  };
  final JsonMap out = <String, Object?>{...node};
  final Set<String> pointers = <String>{for (final String key in others) argumentPointer(key)};
  for (final String field in slotFields) {
    if (out[field] is! Map<String, Object?>) continue;
    final JsonMap slots = <String, Object?>{
      for (final MapEntry<String, Object?> entry in asMap(out[field]).entries)
        if (!pointers.contains(entry.key)) entry.key: entry.value,
    };
    if (slots.isEmpty) {
      out.remove(field);
    } else {
      out[field] = slots;
    }
  }
  final JsonMap arguments = <String, Object?>{
    for (final MapEntry<String, Object?> entry in asMap(node['arguments']).entries)
      if (!others.contains(entry.key)) entry.key: entry.value,
  };
  for (final String key in keys) {
    if (argumentGiven(<String, Object?>{...out, 'arguments': arguments}, key)) continue;
    final FieldSpec? argument = spec.arguments.where((FieldSpec field) => field.key == key).firstOrNull;
    arguments[key] = _startValue(key, argument);
  }
  out['arguments'] = arguments;
  return out;
}

Object _startValue(String key, FieldSpec? argument) {
  if (argument != null && argument.options.isNotEmpty) return argument.options.first.value;
  if (_coordinates.contains(key) && (argument?.kind == FieldKind.number || argument?.kind == FieldKind.integer)) return key == 'y' ? 64 : 0;
  switch (argument?.kind) {
    case FieldKind.integer || FieldKind.number:
      final num? minimum = argument!.minimum;
      final num? maximum = argument.maximum;
      if ((minimum == null || minimum <= 0) && (maximum == null || maximum >= 0)) return 0;
      return minimum ?? maximum!;
    case FieldKind.boolean:
      return true;
    default:
      return '';
  }
}
