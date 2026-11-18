/// Structural edits of behavior steps for the step editor: what a type
/// change drops, a single step grown into a list, moves within and between
/// lists, copy and paste (also into another behavior, with the variables
/// the step names), the names undo notices use, and the wording of typed
/// times, held values and refused routine names.
library;

import '../../model/server_binding.dart' show jsonEquals;
import '../gui/gui_json.dart';
import '../offline/new_documents.dart' show slug;
import '../plural.dart';
import 'behavior_edit.dart';
import 'behavior_summary.dart';
import 'behavior_words.dart';
import 'completion_catalog.dart';

/// The steps [node] holds directly, in its lists and single-step fields.
List<Object?> _children(JsonMap node, CompletionCatalog catalog) {
  final String? type = asString(node['type']);
  return <Object?>[
    for (final String key in nodeListFields(type, catalog)) ...asList(node[key]),
    for (final String key in nodeSlotFields(type, catalog))
      if (node[key] is Map<String, Object?>) node[key],
  ];
}

/// Whether [node] or a step inside it equals [wanted].
bool _holds(Object? node, Object? wanted, CompletionCatalog catalog) {
  if (node is! Map<String, Object?>) return false;
  if (jsonEquals(node, wanted)) return true;
  return _children(node, catalog).any((Object? child) => _holds(child, wanted, catalog));
}

/// What changing [from] into [to] (from [changeNodeType]) drops, in words:
/// the step's own header ("Say: Hello") when its settings go, else the
/// count of steps that go ("2 steps"); null when nothing worth a question
/// is lost, such as a step left as it was added.
String? typeChangeLoss(JsonMap from, JsonMap to, CompletionCatalog catalog) {
  if (_children(to, catalog).any((Object? child) => _holds(child, from, catalog))) return null;
  final String? type = asString(from['type']);
  final Set<String> childFields = <String>{...nodeListFields(type, catalog), ...nodeSlotFields(type, catalog)};
  final JsonMap fresh = type == null ? const <String, Object?>{} : defaultNode(type, catalog);
  final bool ownLost = from.entries.any((MapEntry<String, Object?> entry) =>
      entry.key != 'type' && !childFields.contains(entry.key) && !jsonEquals(to[entry.key], entry.value) && !jsonEquals(fresh[entry.key], entry.value));
  if (ownLost) return nodeSummary(from);
  final int lost = _children(from, catalog).where((Object? child) => !_holds(to, child, catalog)).length;
  return lost == 0 ? null : countOf(lost, 'step');
}

/// [existing] and [added] as steps in order; a list takes [added] at its end.
JsonMap wrapWithStep(JsonMap existing, JsonMap added) {
  if (existing['type'] == 'sequence') return <String, Object?>{...existing, 'steps': <Object?>[...asList(existing['steps']), added]};
  return <String, Object?>{'type': 'sequence', 'steps': <Object?>[existing, added]};
}

bool _startsWith(List<JsonStep> path, List<JsonStep> prefix) {
  if (path.length < prefix.length) return false;
  for (int index = 0; index < prefix.length; index++) {
    if (path[index] != prefix[index]) return false;
  }
  return true;
}

/// [document] with the step at index [from] of the list at [fromList] moved
/// into the list at [intoList], before the step now at index [to] (its
/// length puts it last). Null when the step would go inside itself.
JsonMap? moveStep(JsonMap document, List<JsonStep> fromList, int from, List<JsonStep> intoList, int to) {
  final JsonList source = asList(readIn(document, fromList));
  if (from < 0 || from >= source.length) return null;
  if (_startsWith(intoList, <JsonStep>[...fromList, from])) return null;
  final Object? step = source[from];
  final JsonList without = <Object?>[...source]..removeAt(from);
  final JsonMap removed = asMap(writeIn(document, fromList, without));
  List<JsonStep> target = intoList;
  int at = to;
  if (_listsEqual(intoList, fromList)) {
    if (to > from) at = to - 1;
  } else if (intoList.length > fromList.length && _startsWith(intoList, fromList)) {
    // A list inside a later step of the same list moved up one place.
    final Object index = intoList[fromList.length];
    if (index is int && index > from) target = <JsonStep>[...fromList, index - 1, ...intoList.sublist(fromList.length + 1)];
  }
  final JsonList into = asList(readIn(removed, target));
  return asMap(writeIn(removed, target, insertItem(into, at, step)));
}

bool _listsEqual(List<JsonStep> a, List<JsonStep> b) => a.length == b.length && _startsWith(a, b);

/// A copied step, with the declarations of the variables it names so a
/// paste into another behavior declares them too.
class CopiedStep {
  const CopiedStep(this.node, this.variables, this.name);

  final JsonMap node;
  final JsonMap variables;

  /// The step's name for notices: "Say", "Wait".
  final String name;
}

/// The step the editor copies last, kept while the page is open, so it can
/// be pasted into another routine or behavior.
CopiedStep? copiedStep;

final RegExp _word = RegExp(r'[A-Za-z_][A-Za-z0-9_]*');

/// Names [value] reads: variable fields, bound variables, call argument
/// names, and the words of expressions and template holes.
void _namesIn(Object? value, Set<String> out, {String? key}) {
  if (value is String) {
    if (const <String>{'variable', 'as', 'index', 'key', 'result'}.contains(key)) out.add(value);
    if (key == 'expression' || key == 'expressions') out.addAll(_word.allMatches(value).map((Match m) => m[0]!));
    if (key == 'template' || key == 'templates') {
      for (final Match hole in RegExp(r'\{\{([\s\S]*?)\}\}').allMatches(value)) {
        out.addAll(_word.allMatches(hole[1]!).map((Match m) => m[0]!));
      }
    }
    return;
  }
  if (value is List<Object?>) {
    for (final Object? item in value) {
      _namesIn(item, out, key: key);
    }
    return;
  }
  if (value is Map<String, Object?>) {
    final bool call = value['type'] == 'call';
    for (final MapEntry<String, Object?> entry in value.entries) {
      if (call && entry.key == 'arguments') out.addAll(asMap(entry.value).keys);
      // Slot maps and call arguments pass their own field name down.
      final String? inner = const <String>{'expressions', 'templates'}.contains(key) ? key : entry.key;
      _namesIn(entry.value, out, key: inner);
    }
  }
}

/// [node] of [document] as a copied step.
CopiedStep copyStep(JsonMap document, Object? node, CompletionCatalog catalog) {
  final JsonMap step = asMap(deepCopy(node));
  final Set<String> names = <String>{};
  _namesIn(step, names);
  final JsonMap declared = asMap(document['variables']);
  return CopiedStep(
    step,
    <String, Object?>{for (final String name in names) if (declared.containsKey(name)) name: deepCopy(declared[name])},
    stepName(step),
  );
}

/// [document] with the variables [copied] needs that it does not declare.
JsonMap _withVariables(JsonMap document, CopiedStep copied) {
  final JsonMap variables = asMap(document['variables']);
  final JsonMap missing = <String, Object?>{
    for (final MapEntry<String, Object?> entry in copied.variables.entries)
      if (!variables.containsKey(entry.key)) entry.key: deepCopy(entry.value),
  };
  if (missing.isEmpty) return document;
  return <String, Object?>{...document, 'variables': <String, Object?>{...variables, ...missing}};
}

/// [document] with [copied] inserted at index [at] of the list at [list].
JsonMap pasteStep(JsonMap document, List<JsonStep> list, int at, CopiedStep copied) {
  final JsonMap base = _withVariables(document, copied);
  return asMap(writeIn(base, list, insertItem(asList(readIn(base, list)), at, deepCopy(copied.node))));
}

/// [document] with [copied] put in the single-step field at [slot]: alone
/// when it is empty, else after the step there, both in order.
JsonMap pasteInto(JsonMap document, List<JsonStep> slot, CopiedStep copied) {
  final JsonMap base = _withVariables(document, copied);
  final Object? existing = readIn(base, slot);
  final JsonMap node = asMap(deepCopy(copied.node));
  return asMap(writeIn(base, slot, existing is Map<String, Object?> ? wrapWithStep(existing, node) : node));
}

/// A step's name as its header gives it: what an action does ("Say",
/// "Play sound"), else its type in words ("If", "Wait").
String stepName(Object? node) {
  final JsonMap step = asMap(node);
  if (step['type'] == 'action' && step['capability'] is String) return capabilityWords(step['capability']! as String);
  return nodeLabel(asString(step['type']));
}

const Map<String, int> _tickUnits = <String, int>{
  't': 1, 'tick': 1, 'ticks': 1,
  's': 20, 'sec': 20, 'secs': 20, 'second': 20, 'seconds': 20,
  'm': 1200, 'min': 1200, 'mins': 1200, 'minute': 1200, 'minutes': 1200,
  'h': 72000, 'hr': 72000, 'hrs': 72000, 'hour': 72000, 'hours': 72000,
};

/// Ticks from what a person types: a number of ticks, or a time such as
/// "2 seconds", "2s", "1.5 sec", "3 minutes" or "1 hour". Null when it is
/// neither.
int? parseTicks(String text) {
  final RegExpMatch? match = RegExp(r'^\s*(\d+(?:\.\d+)?|\.\d+)\s*([a-z]*)\s*$').firstMatch(text.toLowerCase());
  if (match == null) return null;
  final double amount = double.parse(match[1]!);
  final String unit = match[2]!;
  final int? per = unit.isEmpty ? 1 : _tickUnits[unit];
  if (per == null) return null;
  return (amount * per).round();
}

/// [value] held to [minimum]..[maximum], and the note that says it was.
(num, String?) fitNumber(num value, num minimum, num maximum, {String? unit}) {
  String amount(num shown) => unit == null ? '$shown' : '$shown ${shown == 1 && unit.endsWith('s') ? unit.substring(0, unit.length - 1) : unit}';
  if (value > maximum) return (maximum, 'Set to ${amount(maximum)}, the most it takes.');
  if (value < minimum) return (minimum, 'Set to ${amount(minimum)}, the least it takes.');
  return (value, null);
}

/// Why [name] cannot name a routine, or null when it can.
String? routineNameProblem(String name, Iterable<String> taken) {
  if (name.trim().isEmpty) return 'A routine needs a name.';
  if (taken.contains(name)) return 'Another routine is already called $name.';
  if (!behaviorIdentifier.hasMatch(name)) return 'Use lowercase letters, digits, _ and -, such as ${slug(name, fallback: 'main')}.';
  return null;
}

/// How long [ticks] lasts in the largest whole unit: "2 seconds", "1.5
/// seconds", "3 minutes", "1 hour".
String durationWords(int ticks) {
  String of(num count, String unit) => count == 1 ? '1 $unit' : '$count ${unit}s';
  if (ticks > 0 && ticks % 72000 == 0) return of(ticks ~/ 72000, 'hour');
  if (ticks > 0 && ticks % 1200 == 0) return of(ticks ~/ 1200, 'minute');
  final double seconds = ticks / 20;
  return of(seconds == seconds.roundToDouble() ? seconds.toInt() : double.parse(seconds.toStringAsFixed(2)), 'second');
}
