/// Item ids for the item pickers: the registry list bundled with the
/// Minecraft pack (`registries.json`), searched by words, with a field's own
/// suggestions first. Any `namespace:item` stays allowed for modded items.
library;

import 'inspect/schema_fields.dart';

final RegExp _itemId = RegExp(r'^[a-z0-9_.-]+:[a-z0-9_./-]+$');
final RegExp _bareKey = RegExp(r'^[a-z0-9_./-]+$');

/// Options shown at once; typing narrows the list.
const int itemOptionLimit = 40;

/// Whether [text] is a `namespace:item` id as the server reads one.
bool isItemId(String text) => _itemId.hasMatch(text);

/// The item ids of a `registries.json` body (`{"registries": {"items": [..]}}`)
/// or a bare `{"items": [..]}`, namespaced, in order, without repeats.
List<String> parseItemRegistry(Object? json) {
  if (json is! Map<String, Object?>) return const <String>[];
  final Object? registries = json['registries'];
  final Object? items = registries is Map<String, Object?> ? registries['items'] : json['items'];
  if (items is! List<Object?>) return const <String>[];
  final Set<String> out = <String>{};
  for (final Object? item in items) {
    if (item is! String) continue;
    if (isItemId(item)) {
      out.add(item);
    } else if (_bareKey.hasMatch(item)) {
      out.add('minecraft:$item');
    }
  }
  return out.toList();
}

/// Ids matching [query] (every word, `_` read as a space), [preferred] ones
/// first, then those whose item key starts with the query, at most [limit].
List<FieldOption> itemOptions(List<String> registry, String query, {List<String> preferred = const <String>[], int limit = itemOptionLimit}) {
  final List<String> words = query.toLowerCase().replaceAll('_', ' ').split(RegExp(r'\s+')).where((String word) => word.isNotEmpty).toList();
  bool matches(String id) {
    final String text = id.replaceAll('_', ' ');
    return words.every(text.contains);
  }

  final String lead = words.isEmpty ? '' : words.first;
  bool leads(String id) => lead.isNotEmpty && (id.substring(id.indexOf(':') + 1).startsWith(lead) || id.startsWith(lead));
  final Set<String> seen = <String>{};
  final List<FieldOption> suggested = <FieldOption>[];
  final List<FieldOption> first = <FieldOption>[];
  final List<FieldOption> rest = <FieldOption>[];
  for (final String id in preferred) {
    if (matches(id) && seen.add(id)) suggested.add(FieldOption(id, description: 'suggested'));
  }
  for (final String id in registry) {
    if (!matches(id) || !seen.add(id)) continue;
    (leads(id) ? first : rest).add(FieldOption(id));
  }
  return <FieldOption>[...suggested, ...first, ...rest].take(limit).toList();
}
