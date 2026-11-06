/// Edits on behavior documents for the node editor: new nodes and triggers,
/// type, capability and event changes, list moves and routine renames.
/// Every edit returns new JSON and keeps keys it does not own, so module
/// data and newer fields survive.
library;

import '../gui/gui_json.dart';
import 'behavior_operands.dart';
import 'completion_catalog.dart';
import 'schema_fields.dart';

/// Node fields holding a list of nodes and a single node, when the catalog
/// does not say.
const List<String> _listFields = <String>['steps', 'branches'];
const List<String> _singleFields = <String>['body', 'then', 'else'];

/// The node-list fields of [type] (`steps`, `branches`).
List<String> nodeListFields(String? type, CompletionCatalog catalog) {
  final NodeSpec? spec = catalog.nodes[type];
  if (spec == null) return const <String>[];
  return <String>[for (final FieldSpec field in spec.fields) if (fieldType(field) == nodeListType) field.key];
}

/// The single-node fields of [type] (`body`, `then`, `else`).
List<String> nodeSlotFields(String? type, CompletionCatalog catalog) {
  final NodeSpec? spec = catalog.nodes[type];
  if (spec == null) return const <String>[];
  return <String>[for (final FieldSpec field in spec.fields) if (fieldType(field) == nodeType) field.key];
}

/// A starting value for a field: its first option, its lower bound, or empty.
Object defaultValue(FieldSpec field) {
  if (field.options.isNotEmpty) return field.options.first.value;
  final num? minimum = field.minimum;
  switch (field.kind) {
    case FieldKind.integer:
      if (minimum == null) return field.maximum != null && field.maximum! < 0 ? field.maximum!.toInt() : 0;
      return field.exclusiveMinimum ? minimum.floor() + 1 : minimum.ceil();
    case FieldKind.number:
      if (minimum == null) return 0;
      return field.exclusiveMinimum ? minimum + 1 : minimum;
    case FieldKind.boolean:
      return false;
    case FieldKind.array:
      return <Object?>[];
    case FieldKind.object:
      return <String, Object?>{};
    case FieldKind.string || FieldKind.choice || FieldKind.any:
      return '';
  }
}

/// Required arguments of [spec] with starting values.
JsonMap defaultArguments(CapabilitySpec? spec) => <String, Object?>{
      if (spec != null)
        for (final FieldSpec argument in spec.arguments)
          if (argument.required) argument.key: defaultValue(argument),
    };

CapabilitySpec? _firstCapability(String kind, CompletionCatalog catalog, {bool server = false}) {
  final List<CapabilitySpec> all = catalog.capabilitiesFor(kind, server: server);
  return all.where((CapabilitySpec spec) => spec.id == (server ? 'animus:message' : 'animus:say')).firstOrNull ??
      all.where((CapabilitySpec spec) => spec.available).firstOrNull ??
      all.firstOrNull;
}

/// The loop variable a new `foreach` uses: an execution variable of type
/// any, or a free `item`, which [variablesForNewNode] declares.
String _loopItem(JsonMap variables) => executionVariables(variables, types: const <String>['any']).firstOrNull ?? uniqueName('item', variables.keys);

/// Variables a new node of [type] needs declared so it compiles as added:
/// a `foreach` needs an execution variable for its items.
JsonMap variablesForNewNode(String type, JsonMap variables) {
  if (type != 'foreach' || variables.containsKey(_loopItem(variables))) return const <String, Object?>{};
  return <String, Object?>{_loopItem(variables): <String, Object?>{'type': 'any', 'initial': ''}};
}

/// A new node of [type] with its required fields filled, valid as added.
/// [routine] is the call target; [variables] the document's declared
/// variables; [server] keeps NPC-only capabilities out.
JsonMap defaultNode(String type, CompletionCatalog catalog, {String? routine, JsonMap variables = const <String, Object?>{}, bool server = false}) {
  switch (type) {
    case 'while':
      return <String, Object?>{'type': type, 'expression': 'false', 'body': defaultNode('sequence', catalog)};
    case 'foreach':
      final String? list = variables.entries
          .where((MapEntry<String, Object?> entry) => const <String>{'list', 'map'}.contains(asString(asMap(entry.value)['type'])))
          .map((MapEntry<String, Object?> entry) => entry.key)
          .firstOrNull;
      return <String, Object?>{
        'type': type,
        'list': list == null ? <Object?>[] : <String, Object?>{'variable': list},
        'as': _loopItem(variables),
        'body': defaultNode('sequence', catalog),
      };
    case 'repeat':
      return <String, Object?>{'type': type, 'count': 1, 'body': defaultNode('sequence', catalog)};
    case 'action' || 'condition':
      final CapabilitySpec? capability = _firstCapability(type, catalog, server: server);
      return <String, Object?>{
        'type': type,
        'capability': capability?.id ?? '',
        'arguments': defaultArguments(capability),
        if (type == 'condition') 'then': defaultNode('sequence', catalog),
      };
    case 'call':
      return <String, Object?>{'type': type, 'routine': routine ?? 'main'};
    case 'set':
      final MapEntry<String, Object?>? first = variables.entries.firstOrNull;
      return <String, Object?>{
        'type': type,
        'variable': first?.key ?? '',
        'value': literalFor(asString(asMap(first?.value)['type'])),
      };
  }
  final JsonMap node = <String, Object?>{'type': type};
  final NodeSpec? spec = catalog.nodes[type];
  for (final FieldSpec field in spec?.fields ?? const <FieldSpec>[]) {
    if (!field.required) continue;
    final String kind = fieldType(field);
    node[field.key] = kind == nodeListType ? <Object?>[] : (kind == nodeType ? defaultNode('sequence', catalog) : defaultValue(field));
  }
  return node;
}

/// [node] changed to [type]. Fields of the old type that the new one lacks
/// go; child nodes move across where they fit (a sequence's steps become a
/// parallel's branches, or a repeat's body as a sequence); other keys stay.
JsonMap changeNodeType(JsonMap node, String type, CompletionCatalog catalog, {String? routine, JsonMap variables = const <String, Object?>{}, bool server = false}) {
  final String? old = asString(node['type']);
  if (old == type) return node;
  final Set<String> oldFields = <String>{for (final FieldSpec field in catalog.nodes[old]?.fields ?? const <FieldSpec>[]) field.key};
  final Set<String> newFields = <String>{for (final FieldSpec field in catalog.nodes[type]?.fields ?? const <FieldSpec>[]) field.key};
  final List<Object?> children = <Object?>[
    for (final String key in <String>{...nodeListFields(old, catalog), ..._listFields.where(oldFields.contains)}) ...asList(node[key]),
    for (final String key in <String>{...nodeSlotFields(old, catalog), ..._singleFields.where(oldFields.contains)})
      if (node[key] is Map<String, Object?>) node[key],
  ];
  final JsonMap fresh = defaultNode(type, catalog, routine: routine, variables: variables, server: server);
  final JsonMap out = <String, Object?>{'type': type};
  for (final MapEntry<String, Object?> entry in node.entries) {
    if (entry.key == 'type') continue;
    if (oldFields.contains(entry.key) && !newFields.contains(entry.key)) continue;
    out[entry.key] = entry.value;
  }
  // A capability of the wrong kind does not carry from action to condition.
  if (out.containsKey('capability') && catalog.capability(asString(out['capability']))?.kind != type) {
    out.remove('capability');
    out.remove('arguments');
  }
  final List<String> lists = nodeListFields(type, catalog);
  final List<String> slots = nodeSlotFields(type, catalog);
  if (children.isNotEmpty) {
    if (lists.isNotEmpty && asList(out[lists.first]).isEmpty) {
      out[lists.first] = children;
    } else if (slots.isNotEmpty && out[slots.first] == null) {
      out[slots.first] = children.length == 1 ? children.first : <String, Object?>{'type': 'sequence', 'steps': children};
    }
  }
  for (final MapEntry<String, Object?> entry in fresh.entries) {
    out.putIfAbsent(entry.key, () => entry.value);
  }
  return out;
}

/// [node] running capability [id]: arguments the old capability declared
/// and the new one does not are dropped, others stay, and the new required
/// arguments are filled.
JsonMap changeCapability(JsonMap node, String id, CompletionCatalog catalog) {
  final CapabilitySpec? old = catalog.capability(asString(node['capability']));
  final CapabilitySpec? next = catalog.capability(id);
  final Set<String> oldKeys = <String>{for (final FieldSpec field in old?.arguments ?? const <FieldSpec>[]) field.key};
  final Set<String> newKeys = <String>{for (final FieldSpec field in next?.arguments ?? const <FieldSpec>[]) field.key};
  final JsonMap arguments = <String, Object?>{
    for (final MapEntry<String, Object?> entry in asMap(node['arguments']).entries)
      if (!oldKeys.contains(entry.key) || newKeys.contains(entry.key)) entry.key: entry.value,
  };
  for (final MapEntry<String, Object?> entry in defaultArguments(next).entries) {
    arguments.putIfAbsent(entry.key, () => entry.value);
  }
  return <String, Object?>{...node, 'capability': id, 'arguments': arguments};
}

/// A new trigger for [event] with an id not in [taken].
JsonMap defaultTrigger(String event, CompletionCatalog catalog, Iterable<String> taken, {bool server = false}) {
  final JsonMap trigger = <String, Object?>{'id': uniqueName(event, taken), 'event': event};
  for (final FieldSpec field in catalog.triggers[event]?.fields ?? const <FieldSpec>[]) {
    if (field.required && !trigger.containsKey(field.key)) trigger[field.key] = defaultValue(field);
  }
  if (server && event == 'timer') trigger['intervalTicks'] = serverTimerMinimum;
  return trigger;
}

/// The shortest timer interval a server behavior may use, in ticks.
const int serverTimerMinimum = 20;

/// [document] with `server` set to [on]. Turning it on raises timer
/// intervals to [serverTimerMinimum]; nothing else is removed, so NPC-only
/// parts stay visible with their diagnostics until the user removes them.
JsonMap setServerBehavior(JsonMap document, bool on) {
  final JsonMap out = <String, Object?>{...document};
  if (!on) {
    out.remove('server');
    return out;
  }
  out['server'] = true;
  if (document.containsKey('triggers')) {
    out['triggers'] = <Object?>[
      for (final Object? raw in asList(document['triggers']))
        if (raw is Map<String, Object?> && raw['event'] == 'timer' && (asInt(raw['intervalTicks']) ?? 0) < serverTimerMinimum)
          <String, Object?>{...raw, 'intervalTicks': serverTimerMinimum}
        else
          raw,
    ];
  }
  return out;
}

/// Variable scopes a behavior may declare: a server behavior has no NPC.
List<String> scopesFor(List<String> scopes, {required bool server}) =>
    <String>[for (final String scope in scopes) if (!server || (scope != 'npc' && scope != 'relationship')) scope];

/// [trigger] firing on [event]: the old event's own fields go, shared ones
/// (`id`, `routine`, `cooldownTicks`) and unknown keys stay.
JsonMap changeTriggerEvent(JsonMap trigger, String event, CompletionCatalog catalog) {
  final Set<String> oldKeys = <String>{
    for (final FieldSpec field in catalog.triggers[asString(trigger['event'])]?.fields ?? const <FieldSpec>[]) field.key,
  };
  final List<FieldSpec> fields = catalog.triggers[event]?.fields ?? const <FieldSpec>[];
  final Set<String> newKeys = <String>{for (final FieldSpec field in fields) field.key};
  final JsonMap out = <String, Object?>{
    for (final MapEntry<String, Object?> entry in trigger.entries)
      if (!oldKeys.contains(entry.key) || newKeys.contains(entry.key)) entry.key: entry.value,
  };
  out['event'] = event;
  for (final FieldSpec field in fields) {
    if (field.required && !out.containsKey(field.key)) out[field.key] = defaultValue(field);
  }
  return out;
}

/// [base], or [base] with the lowest free number appended after `_`, so
/// the name stays readable in expressions (`flag_2`).
String uniqueName(String base, Iterable<String> taken) {
  final Set<String> used = taken.toSet();
  if (!used.contains(base)) return base;
  for (int index = 2;; index++) {
    final String name = '${base}_$index';
    if (!used.contains(name)) return name;
  }
}

/// [list] with [item] inserted at [index] (clamped).
JsonList insertItem(JsonList list, int index, Object? item) => <Object?>[...list]..insert(index.clamp(0, list.length), item);

/// [list] without the item at [index].
JsonList removeItem(JsonList list, int index) => <Object?>[...list]..removeAt(index);

/// [node] and every node below it passed through [visit], children first.
Object? mapNodes(Object? node, JsonMap Function(JsonMap node) visit, CompletionCatalog catalog) {
  if (node is! Map<String, Object?>) return node;
  final String? type = asString(node['type']);
  final Set<String> lists = <String>{...nodeListFields(type, catalog), if (catalog.nodes[type] == null) ..._listFields};
  final Set<String> slots = <String>{...nodeSlotFields(type, catalog), if (catalog.nodes[type] == null) ..._singleFields};
  final JsonMap out = <String, Object?>{
    for (final MapEntry<String, Object?> entry in node.entries)
      entry.key: lists.contains(entry.key) && entry.value is List<Object?>
          ? <Object?>[for (final Object? child in entry.value! as List<Object?>) mapNodes(child, visit, catalog)]
          : (slots.contains(entry.key) ? mapNodes(entry.value, visit, catalog) : entry.value),
  };
  return visit(out);
}

/// [document] with routine [from] renamed to [to], and `entry`, trigger
/// `routine`s and `call` nodes that named it following.
JsonMap renameRoutine(JsonMap document, String from, String to, CompletionCatalog catalog) {
  if (from == to) return document;
  JsonMap retarget(JsonMap node) => node['type'] == 'call' && node['routine'] == from ? <String, Object?>{...node, 'routine': to} : node;
  final JsonMap routines = renameKey(asMap(document['routines']), from, to);
  return <String, Object?>{
    ...document,
    if (document['entry'] == from) 'entry': to,
    'routines': <String, Object?>{
      for (final MapEntry<String, Object?> entry in routines.entries) entry.key: mapNodes(entry.value, retarget, catalog),
    },
    if (document.containsKey('triggers'))
      'triggers': <Object?>[
        for (final Object? trigger in asList(document['triggers']))
          trigger is Map<String, Object?> && trigger['routine'] == from ? <String, Object?>{...trigger, 'routine': to} : trigger,
      ],
  };
}

/// Keys of [value] that neither [known] nor `type` covers, for "kept" notes.
List<String> unknownKeys(JsonMap value, Iterable<String> known) {
  final Set<String> covered = <String>{'type', ...known};
  return <String>[for (final String key in value.keys) if (!covered.contains(key)) key];
}

/// Variable and routine names: lowercase letters, digits, `_` and `-`,
/// starting with a letter or digit, at most 64 characters.
final RegExp behaviorIdentifier = RegExp(r'^[a-z0-9][a-z0-9_-]{0,63}$');

/// Names expressions reserve for their roots and literals.
const Set<String> reservedVariableNames = <String>{'player', 'npc', 'server', 'context', 'true', 'false'};

/// Why [name] cannot name a new variable, or null when it can.
String? variableNameProblem(String name, Iterable<String> taken) {
  if (name.isEmpty) return 'A variable needs a name.';
  if (!behaviorIdentifier.hasMatch(name)) return 'Use lowercase letters, digits, _ and -, starting with a letter or digit, at most 64 characters.';
  if (reservedVariableNames.contains(name)) return '$name is reserved for expressions; choose another name.';
  if (taken.contains(name)) return 'Another variable is already called $name.';
  return null;
}

/// A note when [name] is valid but expressions cannot read it.
String? variableNameNote(String name) => RegExp(r'^[a-z][a-z0-9_]{0,63}$').hasMatch(name)
    ? null
    : 'Expressions cannot read $name. Start with a letter and use only letters, digits and _.';

final RegExp _quoted = RegExp(r"""("(?:[^"\\]|\\.)*"|'(?:[^'\\]|\\.)*')""");

/// [source] with the root name [from] read as [to], outside quoted text
/// and after no dot.
String renameInExpression(String source, String from, String to) {
  final RegExp word = RegExp('(?<![A-Za-z0-9_.])${RegExp.escape(from)}(?![A-Za-z0-9_])');
  final StringBuffer out = StringBuffer();
  int at = 0;
  for (final RegExpMatch quoted in _quoted.allMatches(source)) {
    out
      ..write(source.substring(at, quoted.start).replaceAll(word, to))
      ..write(quoted.group(0));
    at = quoted.end;
  }
  out.write(source.substring(at).replaceAll(word, to));
  return out.toString();
}

String _renameInTemplate(String text, String from, String to) =>
    text.replaceAllMapped(RegExp(r'\{\{([\s\S]*?)\}\}'), (Match hole) => '{{${renameInExpression(hole.group(1)!, from, to)}}}');

/// [document] with variable [from] renamed [to] everywhere it is named:
/// the declaration, `set`, loop fields, call arguments and results,
/// bindings, variable operands and expression and template text.
JsonMap renameVariable(JsonMap document, String from, String to, CompletionCatalog catalog) {
  if (from == to) return document;
  Object? operand(Object? value) {
    if (value is! Map<String, Object?>) return value;
    if (value['variable'] == from && value.length == 1) return <String, Object?>{'variable': to};
    if (value['expression'] is String && value.length == 1) return <String, Object?>{'expression': renameInExpression(value['expression']! as String, from, to)};
    if (value['template'] is String && value.length == 1) return <String, Object?>{'template': _renameInTemplate(value['template']! as String, from, to)};
    return value;
  }

  JsonMap visit(JsonMap node) {
    final JsonMap out = <String, Object?>{...node};
    for (final String key in const <String>['variable', 'as', 'index', 'key', 'result']) {
      if (out[key] == from) out[key] = to;
    }
    for (final String key in const <String>['value', 'list', 'count']) {
      if (out.containsKey(key)) out[key] = operand(out[key]);
    }
    if (out['expression'] is String) out['expression'] = renameInExpression(out['expression']! as String, from, to);
    if (out['type'] == 'call' && out['arguments'] is Map<String, Object?>) {
      out['arguments'] = <String, Object?>{
        for (final MapEntry<String, Object?> entry in asMap(out['arguments']).entries) entry.key == from ? to : entry.key: operand(entry.value),
      };
    }
    if (out['bindings'] is Map<String, Object?>) {
      out['bindings'] = <String, Object?>{
        for (final MapEntry<String, Object?> entry in asMap(out['bindings']).entries) entry.key: operand(entry.value),
      };
    }
    for (final String field in const <String>['expressions', 'templates']) {
      if (out[field] is! Map<String, Object?>) continue;
      out[field] = <String, Object?>{
        for (final MapEntry<String, Object?> entry in asMap(out[field]).entries)
          entry.key: entry.value is String
              ? (field == 'expressions' ? renameInExpression(entry.value! as String, from, to) : _renameInTemplate(entry.value! as String, from, to))
              : entry.value,
      };
    }
    return out;
  }

  return <String, Object?>{
    ...document,
    'variables': renameKey(asMap(document['variables']), from, to),
    'routines': <String, Object?>{
      for (final MapEntry<String, Object?> entry in asMap(document['routines']).entries) entry.key: mapNodes(entry.value, visit, catalog),
    },
  };
}
