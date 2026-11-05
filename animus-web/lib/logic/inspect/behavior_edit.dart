/// Edits on behavior documents for the node editor: new nodes and triggers,
/// type, capability and event changes, list moves and routine renames.
/// Every edit returns new JSON and keeps keys it does not own, so module
/// data and newer fields survive.
library;

import '../gui/gui_json.dart';
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

CapabilitySpec? _firstCapability(String kind, CompletionCatalog catalog) {
  final List<CapabilitySpec> all = catalog.capabilitiesFor(kind);
  return all.where((CapabilitySpec spec) => spec.id == 'animus:say').firstOrNull ??
      all.where((CapabilitySpec spec) => spec.available).firstOrNull ??
      all.firstOrNull;
}

/// A new node of [type] with its required fields filled. [routine] is the
/// call target; [variables] the document's declared variables, for `set`.
JsonMap defaultNode(String type, CompletionCatalog catalog, {String? routine, JsonMap variables = const <String, Object?>{}}) {
  switch (type) {
    case 'action' || 'condition':
      final CapabilitySpec? capability = _firstCapability(type, catalog);
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
        'value': asMap(first?.value)['initial'] ?? '',
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
JsonMap changeNodeType(JsonMap node, String type, CompletionCatalog catalog, {String? routine, JsonMap variables = const <String, Object?>{}}) {
  final String? old = asString(node['type']);
  if (old == type) return node;
  final Set<String> oldFields = <String>{for (final FieldSpec field in catalog.nodes[old]?.fields ?? const <FieldSpec>[]) field.key};
  final Set<String> newFields = <String>{for (final FieldSpec field in catalog.nodes[type]?.fields ?? const <FieldSpec>[]) field.key};
  final List<Object?> children = <Object?>[
    for (final String key in <String>{...nodeListFields(old, catalog), ..._listFields.where(oldFields.contains)}) ...asList(node[key]),
    for (final String key in <String>{...nodeSlotFields(old, catalog), ..._singleFields.where(oldFields.contains)})
      if (node[key] is Map<String, Object?>) node[key],
  ];
  final JsonMap fresh = defaultNode(type, catalog, routine: routine, variables: variables);
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
JsonMap defaultTrigger(String event, CompletionCatalog catalog, Iterable<String> taken) {
  final JsonMap trigger = <String, Object?>{'id': uniqueName(event.replaceAll('_', '-'), taken), 'event': event};
  for (final FieldSpec field in catalog.triggers[event]?.fields ?? const <FieldSpec>[]) {
    if (field.required && !trigger.containsKey(field.key)) trigger[field.key] = defaultValue(field);
  }
  return trigger;
}

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

/// [base], or [base] with the lowest free number appended.
String uniqueName(String base, Iterable<String> taken) {
  final Set<String> used = taken.toSet();
  if (!used.contains(base)) return base;
  for (int index = 2;; index++) {
    final String name = '$base-$index';
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
