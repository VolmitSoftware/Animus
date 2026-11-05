/// Completion for the JSON code view: keys and values at the caret, from
/// the schema layer, the capability and trigger catalog and per-document
/// hints (routine names, workspace behavior ids).
library;

import 'dart:convert';

import '../gui/gui_json.dart';
import '../../model/document_kind.dart';
import 'completion_catalog.dart';
import 'json_outline.dart';
import 'schema_fields.dart';

/// Extra values for the value at [path] in the best-effort document [root].
typedef ValueHints = List<FieldOption> Function(List<JsonStep> path, Object? root);

/// One completion: what it shows and the edit it makes.
class JsonSuggestion {
  const JsonSuggestion({
    required this.label,
    required this.insert,
    required this.replaceStart,
    required this.replaceEnd,
    this.detail = '',
    this.isKey = false,
    this.caretBack = 0,
  });

  final String label;
  final String insert;
  final String detail;
  final bool isKey;
  final int replaceStart;
  final int replaceEnd;

  /// How far before the end of [insert] the caret lands.
  final int caretBack;

  /// [text] with this completion applied, and the new caret offset.
  (String, int) applyTo(String text) {
    final int start = replaceStart.clamp(0, text.length);
    final int end = replaceEnd.clamp(start, text.length);
    final String next = '${text.substring(0, start)}$insert${text.substring(end)}';
    return (next, start + insert.length - caretBack);
  }
}

const int _limit = 40;

/// Suggestions at [caret] in [text] for a document under [schema].
List<JsonSuggestion> suggestJson(
  String text,
  int caret,
  SchemaView schema, {
  CompletionCatalog? catalog,
  ValueHints? hints,
}) {
  final JsonOutline outline = scanJson(text, caret: caret);
  final CaretContext? context = outline.caret;
  if (context == null) return const <JsonSuggestion>[];
  final Object? root = outline.value;
  final Object? container = readIn(root, context.container);
  final Map<String, Object?>? containerSchema = schema.at(context.container, root);
  if (context.atKey) {
    if (container is! Map<String, Object?>) return const <JsonSuggestion>[];
    return _keys(context, container, containerSchema, schema, catalog, root);
  }
  return _values(context, container, containerSchema, schema, catalog, hints, root);
}

List<JsonSuggestion> _keys(
  CaretContext context,
  Map<String, Object?> container,
  Map<String, Object?>? containerSchema,
  SchemaView schema,
  CompletionCatalog? catalog,
  Object? root,
) {
  final List<FieldSpec> fields = <FieldSpec>[
    ...argumentFields(context.container, root, catalog),
    if (containerSchema != null) ...schema.fields(containerSchema, container),
  ];
  final Set<String> seen = <String>{};
  final String typed = context.prefix;
  final List<JsonSuggestion> out = <JsonSuggestion>[];
  for (final FieldSpec field in fields) {
    if (!seen.add(field.key)) continue;
    // A key already present is not offered again, unless it is the one typed.
    if (container.containsKey(field.key) && field.key != typed) continue;
    if (!_matches(field.key, typed)) continue;
    final String quoted = jsonEncode(field.key);
    out.add(JsonSuggestion(
      label: field.key,
      insert: context.colonFollows ? quoted : '$quoted: ',
      detail: <String>[if (field.required) 'required', if (field.description != null) field.description!].join(' - '),
      isKey: true,
      replaceStart: context.replaceStart,
      replaceEnd: context.replaceEnd,
    ));
  }
  out.sort((JsonSuggestion a, JsonSuggestion b) => _rank(a.label, typed).compareTo(_rank(b.label, typed)));
  return out.take(_limit).toList();
}

List<JsonSuggestion> _values(
  CaretContext context,
  Object? container,
  Map<String, Object?>? containerSchema,
  SchemaView schema,
  CompletionCatalog? catalog,
  ValueHints? hints,
  Object? root,
) {
  final List<JsonStep> path = context.valuePath;
  FieldSpec? field;
  final String? key = context.key;
  if (key != null) {
    field = argumentFields(context.container, root, catalog).where((FieldSpec spec) => spec.key == key).firstOrNull;
    if (field == null && containerSchema != null) {
      field = schema.fields(containerSchema, container).where((FieldSpec spec) => spec.key == key).firstOrNull;
      final Map<String, Object?>? extra = field == null ? schema.additional(containerSchema, container, key) : null;
      if (extra != null) field = schema.field(key, extra);
    }
  } else if (context.index != null && containerSchema != null) {
    final Map<String, Object?>? items = schema.child(containerSchema, container, context.index!);
    if (items != null) field = schema.field('${context.index}', items);
  }
  final List<FieldOption> options = <FieldOption>[];
  if (key == 'capability' && catalog != null) {
    final String type = asString(asMap(container)['type']) ?? 'action';
    for (final CapabilitySpec spec in catalog.capabilitiesFor(type)) {
      options.add(FieldOption(spec.id, description: <String>[spec.description, if (spec.note.isNotEmpty) '(${spec.note})'].join(' ')));
    }
  } else if (key == 'event' && catalog != null && catalog.triggers.isNotEmpty) {
    for (final TriggerSpec spec in catalog.triggers.values) {
      options.add(FieldOption(spec.event, description: spec.description));
    }
  }
  if (field != null && options.isEmpty) {
    options.addAll(field.options);
    if (field.kind == FieldKind.boolean) options.addAll(const <FieldOption>[FieldOption(true), FieldOption(false)]);
  }
  if (hints != null) options.addAll(hints(path, root));
  final Set<String> seen = <String>{};
  final String typed = context.prefix;
  final List<JsonSuggestion> out = <JsonSuggestion>[];
  for (final FieldOption option in options) {
    final String label = option.label;
    if (!seen.add(label) || !_matches(label, typed)) continue;
    // Inside a string only strings fit; a bare word may become anything.
    if (context.quoted && option.value is! String) continue;
    out.add(JsonSuggestion(
      label: label,
      insert: jsonEncode(option.value),
      detail: option.description ?? '',
      replaceStart: context.replaceStart,
      replaceEnd: context.replaceEnd,
    ));
  }
  if (out.isEmpty && !context.quoted && field != null && typed.isEmpty) {
    final (String insert, int back)? skeleton = switch (field.kind) {
      FieldKind.object => ('{}', 1),
      FieldKind.array => ('[]', 1),
      FieldKind.string => ('""', 1),
      _ => null,
    };
    if (skeleton != null) {
      out.add(JsonSuggestion(
        label: skeleton.$1,
        insert: skeleton.$1,
        detail: field.help,
        replaceStart: context.replaceStart,
        replaceEnd: context.replaceEnd,
        caretBack: skeleton.$2,
      ));
    }
  }
  out.sort((JsonSuggestion a, JsonSuggestion b) => _rank(a.label, typed).compareTo(_rank(b.label, typed)));
  return out.take(_limit).toList();
}

/// For an `arguments` object of an action or condition node, the argument
/// fields of its capability from the catalog; otherwise empty.
List<FieldSpec> argumentFields(List<JsonStep> containerPath, Object? root, CompletionCatalog? catalog) {
  if (catalog == null || containerPath.isEmpty || containerPath.last != 'arguments') return const <FieldSpec>[];
  final Object? node = readIn(root, containerPath.sublist(0, containerPath.length - 1));
  final CapabilitySpec? spec = catalog.capability(asString(asMap(node)['capability']));
  return spec?.arguments ?? const <FieldSpec>[];
}

bool _matches(String label, String typed) => typed.isEmpty || label.toLowerCase().contains(typed.toLowerCase());

int _rank(String label, String typed) {
  if (typed.isEmpty) return 0;
  final String lower = label.toLowerCase(), prefix = typed.toLowerCase();
  if (lower.startsWith(prefix)) return 0;
  final int colon = lower.indexOf(':');
  if (colon >= 0 && lower.substring(colon + 1).startsWith(prefix)) return 1;
  return 2;
}

/// Hints for behavior documents: routine names for `entry` and `routine`
/// fields, declared variables for `variable` fields, and workspace ids from
/// [guiIds] for the `gui` argument of `animus:open-gui` (of its `kind`,
/// `inventory` by default) and the `shop` argument of `animus:open-shop`.
ValueHints behaviorHints({Map<DocumentKind, List<String>> guiIds = const <DocumentKind, List<String>>{}}) => (List<JsonStep> path, Object? root) {
      if (path.isEmpty) return const <FieldOption>[];
      final JsonStep last = path.last;
      final JsonMap document = asMap(root);
      if (last == 'entry' || last == 'routine') {
        return <FieldOption>[for (final String name in asMap(document['routines']).keys) FieldOption(name, description: 'routine')];
      }
      if (last == 'variable') {
        return <FieldOption>[
          for (final MapEntry<String, Object?> entry in asMap(document['variables']).entries)
            FieldOption(entry.key, description: asString(asMap(entry.value)['type']) ?? 'variable'),
        ];
      }
      if ((last == 'gui' || last == 'shop') && path.length >= 2 && path[path.length - 2] == 'arguments') {
        final JsonMap node = asMap(readIn(root, path.sublist(0, path.length - 2)));
        final JsonMap arguments = asMap(node['arguments']);
        final String? capability = asString(node['capability']);
        final DocumentKind? kind = switch ((capability, last)) {
          ('animus:open-gui', 'gui') => switch (asString(arguments['kind']) ?? 'inventory') {
              'inventory' => DocumentKind.inventory,
              'menu' => DocumentKind.menu,
              'shop' => DocumentKind.shop,
              _ => null,
            },
          ('animus:open-shop', 'shop') => DocumentKind.shop,
          _ => null,
        };
        if (kind == null) return const <FieldOption>[];
        final String description = '${kind.wireName} in this workspace';
        return <FieldOption>[for (final String id in guiIds[kind] ?? const <String>[]) FieldOption(id, description: description)];
      }
      return const <FieldOption>[];
    };

/// Hints for NPC documents: behavior ids for `behaviors` items and skin
/// hashes for `skin`, from the workspace.
ValueHints npcHints({required List<String> behaviorIds, List<String> skinIds = const <String>[]}) => (List<JsonStep> path, Object? root) {
      if (path.length == 2 && path.first == 'behaviors' && path.last is int) {
        return <FieldOption>[for (final String id in behaviorIds) FieldOption(id, description: 'behavior in this workspace')];
      }
      if (path.length == 1 && path.first == 'skin') {
        return <FieldOption>[for (final String id in skinIds) FieldOption(id, description: 'skin in this workspace')];
      }
      return const <FieldOption>[];
    };
