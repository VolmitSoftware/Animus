/// Everything completion can offer outside one field: registry ids (the
/// bundled 26.3 lists, or the paired server's), workspace document ids,
/// flag and quest names already used, server commands, PlaceholderAPI
/// expansions, and the capability catalog.
library;

import '../../model/animus_document.dart';
import '../../model/document_kind.dart';
import '../gui/gui_json.dart';
import '../inspect/completion_catalog.dart';

/// Game registries a field can take an id from.
enum RegistryKind {
  item('item', 'items'),
  block('block', 'blocks'),
  sound('sound', 'sounds'),
  particle('particle', 'particles'),
  entity('entity type', 'entityTypes'),
  enchantment('enchantment', 'enchantments'),
  effect('potion effect', 'effects'),
  attribute('attribute', 'attributes'),
  world('world', 'worlds');

  const RegistryKind(this.label, this.wireName);

  /// The type word rows show.
  final String label;

  /// The key in `registries.json` and the server's `/v1/registries`.
  final String wireName;
}

class CompletionSources {
  const CompletionSources({
    this.registries = const <RegistryKind, List<String>>{},
    this.complete = const <RegistryKind>{},
    this.documents = const <DocumentKind, List<String>>{},
    this.flags = const <String>[],
    this.quests = const <String>[],
    this.commands = const <String>[],
    this.papi = const <String>[],
    this.catalog,
    this.paired = false,
  });

  static const CompletionSources empty = CompletionSources();

  /// Ids without the `minecraft:` namespace, as the bundled lists and the
  /// server both write them.
  final Map<RegistryKind, List<String>> registries;

  /// Registries whose list is the whole set, so an id outside it is wrong.
  final Set<RegistryKind> complete;
  final Map<DocumentKind, List<String>> documents;

  /// Flag and quest names the workspace's behaviors already use.
  final List<String> flags;
  final List<String> quests;

  /// The paired server's command names, without `/`.
  final List<String> commands;

  /// The paired server's PlaceholderAPI identifiers (`player`, `vault`).
  final List<String> papi;
  final CompletionCatalog? catalog;

  /// Whether the lists come from a paired server.
  final bool paired;

  List<String> registry(RegistryKind kind) => registries[kind] ?? const <String>[];

  List<String> ids(DocumentKind kind) => documents[kind] ?? const <String>[];

  CompletionSources copyWith({
    Map<RegistryKind, List<String>>? registries,
    Set<RegistryKind>? complete,
    Map<DocumentKind, List<String>>? documents,
    List<String>? flags,
    List<String>? quests,
    List<String>? commands,
    List<String>? papi,
    CompletionCatalog? catalog,
    bool? paired,
  }) =>
      CompletionSources(
        registries: registries ?? this.registries,
        complete: complete ?? this.complete,
        documents: documents ?? this.documents,
        flags: flags ?? this.flags,
        quests: quests ?? this.quests,
        commands: commands ?? this.commands,
        papi: papi ?? this.papi,
        catalog: catalog ?? this.catalog,
        paired: paired ?? this.paired,
      );
}

/// Registry lists from `registries.json` or a `/v1/registries` body: a map
/// of [RegistryKind.wireName] to id lists. Ids lose `minecraft:`.
Map<RegistryKind, List<String>> parseRegistries(Object? json) {
  final JsonMap root = asMap(json);
  final JsonMap lists = root['registries'] is Map<String, Object?> ? asMap(root['registries']) : root;
  return <RegistryKind, List<String>>{
    for (final RegistryKind kind in RegistryKind.values)
      if (lists[kind.wireName] is List<Object?>)
        kind: <String>{
          for (final Object? id in asList(lists[kind.wireName]))
            if (id is String && id.isNotEmpty) stripMinecraft(id),
        }.toList()
          ..sort(),
  };
}

/// What a paired server's `GET /v1/lookups` lists: its registries, command
/// names and PlaceholderAPI identifiers.
class ServerLookups {
  const ServerLookups({required this.registries, required this.commands, required this.papi});

  factory ServerLookups.fromJson(Object? json) {
    final JsonMap root = asMap(json);
    List<String> strings(Object? value) => <String>{for (final Object? item in asList(value)) if (item is String && item.isNotEmpty) item}.toList()..sort();
    return ServerLookups(registries: parseRegistries(root['registries']), commands: strings(root['commands']), papi: strings(root['papi']));
  }

  final Map<RegistryKind, List<String>> registries;
  final List<String> commands;
  final List<String> papi;

  /// [base] with these lists over the bundled ones; every registry the
  /// server sent is complete.
  CompletionSources over(CompletionSources base) => base.copyWith(
        registries: <RegistryKind, List<String>>{...base.registries, ...registries},
        complete: <RegistryKind>{...base.complete, ...registries.keys},
        commands: commands,
        papi: papi,
        paired: true,
      );
}

String stripMinecraft(String id) => id.startsWith('minecraft:') ? id.substring('minecraft:'.length) : id;

const Set<String> _flagCapabilities = <String>{'animus:flag-state', 'animus:set-flag'};
const Set<String> _questCapabilities = <String>{'animus:quest-state', 'animus:set-quest-state', 'animus:advance-quest-state'};
final RegExp _flagCall = RegExp(r'''\b(?:flag|has_flag|player_flag|player_has_flag)\(\s*(?:['"][a-z]+['"]\s*,\s*)?['"]([a-z0-9][a-z0-9_-]*)['"]''');
final RegExp _questCall = RegExp(r'''\bquest\(\s*['"][a-z]+['"]\s*,\s*['"]([a-z0-9][a-z0-9_-]*)['"]''');

/// Document ids by kind, and the flag and quest names [documents] use:
/// `name` arguments of the flag and quest capabilities, and names read by
/// `flag(...)`, `has_flag(...)` and `quest(...)` in expressions.
CompletionSources workspaceSources(List<AnimusDocument> documents, {CompletionSources base = CompletionSources.empty}) {
  final Map<DocumentKind, Set<String>> ids = <DocumentKind, Set<String>>{};
  final Set<String> flags = <String>{};
  final Set<String> quests = <String>{};
  void visit(Object? value) {
    if (value is Map<String, Object?>) {
      final String? capability = asString(value['capability']);
      final String? name = asString(asMap(value['arguments'])['name']);
      if (name != null && name.isNotEmpty) {
        if (_flagCapabilities.contains(capability)) flags.add(name);
        if (_questCapabilities.contains(capability)) quests.add(name);
      }
      value.values.forEach(visit);
    } else if (value is List<Object?>) {
      value.forEach(visit);
    } else if (value is String && value.contains('(')) {
      for (final RegExpMatch match in _flagCall.allMatches(value)) {
        flags.add(match.group(1)!);
      }
      for (final RegExpMatch match in _questCall.allMatches(value)) {
        quests.add(match.group(1)!);
      }
    }
  }

  for (final AnimusDocument document in documents) {
    ids.putIfAbsent(document.kind, () => <String>{}).add(document.id);
    if (document.kind == DocumentKind.behavior || document.kind == DocumentKind.npc) visit(document.json);
  }
  return base.copyWith(
    documents: <DocumentKind, List<String>>{for (final MapEntry<DocumentKind, Set<String>> entry in ids.entries) entry.key: entry.value.toList()..sort()},
    flags: flags.toList()..sort(),
    quests: quests.toList()..sort(),
  );
}
