/// The NPC browser's model: one [NpcEntry] per NPC definition, the search
/// language, folders (the `npcs/<group>/` directories) and tags.
///
/// Search text is split on spaces. A plain word matches the name, id, type,
/// folder, world or a tag. `tag:`, `folder:` (or `group:`), `type:`,
/// `world:`, `has:` (route, combat, shop, rig, skin, behaviors) and `is:`
/// (spawned, despawned, invalid) narrow by one property; a leading `-`
/// negates any term.
library;

import '../../model/animus_document.dart';
import '../../model/document_kind.dart';
import '../gui/gui_json.dart';

/// Tags as the NPC schema and `NpcDefinition` accept them.
final RegExp npcTagPattern = RegExp(r'^[a-z0-9][a-z0-9_-]{0,31}$');

/// Folder (group) names as the NPC schema accepts them.
final RegExp npcFolderPattern = RegExp(r'^[a-z0-9][a-z0-9_-]{0,63}$');

/// The most tags one definition may carry.
const int npcTagLimit = 32;

/// Module keys that take an `enabled` flag, in the order the browser offers them.
const List<String> toggleableModules = <String>['animus:combat', 'animus:navigation', 'animus:look-close', 'animus:riding', 'animus:mount', 'animus:hire'];

/// Modules that are valid as `{"enabled": true}` alone, so enabling one on an
/// NPC without it adds it.
const Set<String> creatableModules = <String>{'animus:combat', 'animus:look-close'};

/// Features the browser filters on.
enum NpcFeature {
  route('route', 'Route'),
  combat('combat', 'Combat'),
  shop('shop', 'Shop'),
  rig('rig', 'Rig'),
  skin('skin', 'Skin'),
  behaviors('behaviors', 'Behaviors');

  const NpcFeature(this.key, this.label);

  final String key;
  final String label;

  static NpcFeature? parse(String key) {
    for (final NpcFeature feature in values) {
      if (feature.key == key) return feature;
    }
    return null;
  }
}

/// What the browser lists for one NPC definition.
class NpcEntry {
  NpcEntry._(this.document, this.json);

  factory NpcEntry.of(AnimusDocument document) => NpcEntry._(document, asMap(document.json));

  final AnimusDocument document;
  final JsonMap json;

  /// The workspace id, `<folder>/<id>`.
  String get key => document.id;

  String get folder {
    final int slash = document.id.indexOf('/');
    return slash < 0 ? '' : document.id.substring(0, slash);
  }

  String get localId {
    final int slash = document.id.indexOf('/');
    return slash < 0 ? document.id : document.id.substring(slash + 1);
  }

  String get name => asString(json['name'])?.trim().isNotEmpty == true ? asString(json['name'])! : localId;
  String get type => asString(json['type']) ?? '';
  String get world => asString(asMap(json['position'])['world']) ?? '';
  bool get spawned => json['spawned'] == true;
  bool get valid => document.valid;

  int get errors => document.diagnostics.where((Diagnostic diagnostic) => diagnostic.severity == DiagnosticSeverity.error).length;

  List<String> get tags => <String>[
    for (final Object? tag in asList(json['tags']))
      if (tag is String) tag,
  ];

  JsonMap get modules => asMap(json['modules']);

  /// Whether a module is present and not switched off.
  bool moduleOn(String key) {
    final Object? module = modules[key];
    if (module == null) return false;
    return module is! Map<String, Object?> || module['enabled'] != false;
  }

  bool has(NpcFeature feature) => switch (feature) {
    NpcFeature.route => _hasRoute,
    NpcFeature.combat => asMap(modules['animus:combat'])['enabled'] == true,
    NpcFeature.shop => asString(asMap(modules['animus:shop'])['shop']) != null,
    NpcFeature.rig => asString(asMap(json['rig'])['id']) != null,
    NpcFeature.skin => asString(json['skin']) != null,
    NpcFeature.behaviors => asList(json['behaviors']).isNotEmpty,
  };

  bool get _hasRoute {
    final JsonMap navigation = asMap(modules['animus:navigation']);
    if (navigation['enabled'] != true) return false;
    return asString(navigation['route']) != null || asList(navigation['points']).isNotEmpty || navigation['order'] == 'wander';
  }

  List<NpcFeature> get features => <NpcFeature>[
    for (final NpcFeature feature in NpcFeature.values)
      if (has(feature)) feature,
  ];
}

/// Every NPC definition of a workspace, sorted by folder then name.
List<NpcEntry> npcEntries(Iterable<AnimusDocument> documents) {
  final List<NpcEntry> entries = <NpcEntry>[
    for (final AnimusDocument document in documents)
      if (document.kind == DocumentKind.npc) NpcEntry.of(document),
  ];
  entries.sort((NpcEntry a, NpcEntry b) {
    final int folder = a.folder.compareTo(b.folder);
    if (folder != 0) return folder;
    final int name = a.name.toLowerCase().compareTo(b.name.toLowerCase());
    return name != 0 ? name : a.key.compareTo(b.key);
  });
  return entries;
}

/// One search term.
class NpcTerm {
  const NpcTerm(this.field, this.value, {this.negated = false});

  /// Null for a plain word.
  final String? field;
  final String value;
  final bool negated;

  bool matches(NpcEntry entry) => negated != _test(entry);

  bool _test(NpcEntry entry) {
    final String value = this.value;
    switch (field) {
      case null:
        return entry.name.toLowerCase().contains(value) ||
            entry.key.contains(value) ||
            entry.type.contains(value) ||
            entry.world.toLowerCase().contains(value) ||
            entry.tags.any((String tag) => tag.contains(value));
      case 'tag':
        return entry.tags.contains(value);
      case 'folder':
        return entry.folder == value;
      case 'type':
        return entry.type == value || entry.type == 'minecraft:$value';
      case 'world':
        return entry.world.toLowerCase() == value;
      case 'has':
        final NpcFeature? feature = NpcFeature.parse(value);
        return feature != null && entry.has(feature);
      case 'is':
        return switch (value) {
          'spawned' => entry.spawned,
          'despawned' => !entry.spawned,
          'invalid' => !entry.valid,
          'valid' => entry.valid,
          _ => false,
        };
      default:
        return false;
    }
  }

  @override
  String toString() => '${negated ? '-' : ''}${field == null ? '' : '$field:'}$value';
}

/// A parsed search: every term must match.
class NpcQuery {
  const NpcQuery(this.terms);

  static const NpcQuery all = NpcQuery(<NpcTerm>[]);

  final List<NpcTerm> terms;

  static const Map<String, String> _fields = <String, String>{
    'tag': 'tag',
    'folder': 'folder',
    'group': 'folder',
    'type': 'type',
    'world': 'world',
    'has': 'has',
    'is': 'is',
  };

  static NpcQuery parse(String text) {
    final List<NpcTerm> terms = <NpcTerm>[];
    for (final String raw in text.trim().toLowerCase().split(RegExp(r'\s+'))) {
      if (raw.isEmpty || raw == '-') continue;
      final bool negated = raw.startsWith('-');
      final String word = negated ? raw.substring(1) : raw;
      final int colon = word.indexOf(':');
      final String? field = colon > 0 ? _fields[word.substring(0, colon)] : null;
      if (field != null) {
        final String value = word.substring(colon + 1);
        if (value.isNotEmpty) terms.add(NpcTerm(field, value, negated: negated));
      } else {
        terms.add(NpcTerm(null, word, negated: negated));
      }
    }
    return NpcQuery(terms);
  }

  bool matches(NpcEntry entry) => terms.every((NpcTerm term) => term.matches(entry));

  List<NpcEntry> filter(Iterable<NpcEntry> entries) => <NpcEntry>[
    for (final NpcEntry entry in entries)
      if (matches(entry)) entry,
  ];
}

/// The search text with `field:value` added, or removed when present.
String toggleTerm(String text, String field, String value) {
  final String term = '$field:$value';
  final List<String> words = text.trim().split(RegExp(r'\s+')).where((String word) => word.isNotEmpty).toList();
  if (words.any((String word) => word.toLowerCase() == term)) {
    words.removeWhere((String word) => word.toLowerCase() == term);
  } else {
    words.add(term);
  }
  return words.join(' ');
}

/// Whether the search text holds `field:value`.
bool hasTerm(String text, String field, String value) => text.trim().toLowerCase().split(RegExp(r'\s+')).contains('$field:$value');

/// A folder with how many NPCs it holds.
class NpcFolder {
  const NpcFolder(this.name, this.count);

  final String name;
  final int count;
}

/// The `npcs/` folders, sorted by name.
List<NpcFolder> npcFolders(Iterable<NpcEntry> entries) {
  final Map<String, int> counts = <String, int>{};
  for (final NpcEntry entry in entries) {
    counts[entry.folder] = (counts[entry.folder] ?? 0) + 1;
  }
  final List<String> names = counts.keys.toList()..sort();
  return <NpcFolder>[for (final String name in names) NpcFolder(name, counts[name]!)];
}

/// Every tag in use with how many NPCs carry it, most used first.
List<(String, int)> npcTags(Iterable<NpcEntry> entries) {
  final Map<String, int> counts = <String, int>{};
  for (final NpcEntry entry in entries) {
    for (final String tag in entry.tags) {
      counts[tag] = (counts[tag] ?? 0) + 1;
    }
  }
  final List<(String, int)> tags = <(String, int)>[for (final MapEntry<String, int> entry in counts.entries) (entry.key, entry.value)];
  tags.sort(((String, int) a, (String, int) b) => a.$2 != b.$2 ? b.$2.compareTo(a.$2) : a.$1.compareTo(b.$1));
  return tags;
}

/// Distinct values of one property, sorted, for the filter pickers.
List<String> npcValues(Iterable<NpcEntry> entries, String Function(NpcEntry entry) read) {
  final Set<String> values = <String>{for (final NpcEntry entry in entries) read(entry)}..remove('');
  return values.toList()..sort();
}
