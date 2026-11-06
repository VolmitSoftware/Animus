/// The NPC browser's model: one [NpcEntry] per NPC definition, the search
/// language, the folder tree (the `npcs/` directories, nested up to 8 deep)
/// and tags.
///
/// Search text is split on spaces. A plain word matches the name, id, type,
/// folder, world or a tag. `tag:`, `folder:` (or `group:`), `type:`,
/// `world:`, `has:` (route, combat, shop, rig, skin, behaviors) and `is:`
/// (spawned, despawned, invalid) narrow by one property; a leading `-`
/// negates any term. `folder:town` matches `npcs/town/` and every folder
/// below it; `folder:=town` matches `npcs/town/` alone and `folder:=` the
/// NPCs directly in `npcs/`.
library;

import '../../model/animus_document.dart';
import '../../model/document_kind.dart';
import '../gui/gui_json.dart';
import 'npc_paths.dart';

/// Tags as the NPC schema and `NpcDefinition` accept them.
final RegExp npcTagPattern = RegExp(r'^[a-z0-9][a-z0-9_-]{0,31}$');

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

  /// The workspace id, `<folders>/<id>` or `<id>` for `npcs/` itself.
  String get key => document.id;

  /// The folder below `npcs/`, empty for `npcs/` itself.
  String get folder => npcFolderOf(document.id);

  String get localId => npcLocalIdOf(document.id);

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

/// Every NPC definition of a workspace, sorted by folder (as the tree
/// lists them) then name.
List<NpcEntry> npcEntries(Iterable<AnimusDocument> documents) {
  final List<NpcEntry> entries = <NpcEntry>[
    for (final AnimusDocument document in documents)
      if (document.kind == DocumentKind.npc) NpcEntry.of(document),
  ];
  entries.sort((NpcEntry a, NpcEntry b) {
    final int folder = compareNpcFolders(a.folder, b.folder);
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
        return value.startsWith('=') ? entry.folder == value.substring(1) : npcFolderWithin(entry.folder, value);
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
        String value = word.substring(colon + 1);
        if (field == 'folder') value = value.startsWith('=') ? '=${normalizeNpcFolder(value.substring(1))}' : normalizeNpcFolder(value);
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

/// One folder of the tree: `npcs/` itself has the empty [path].
class NpcFolderNode {
  NpcFolderNode(this.path);

  final String path;

  /// NPCs directly in this folder.
  int own = 0;

  /// NPCs in this folder and every folder below it.
  int total = 0;

  /// Sorted by name.
  final List<NpcFolderNode> children = <NpcFolderNode>[];

  String get name => npcFolderName(path);

  /// This folder and every folder below it, depth first.
  Iterable<NpcFolderNode> get all sync* {
    yield this;
    for (final NpcFolderNode child in children) {
      yield* child.all;
    }
  }
}

/// The folder tree of [entries] under `npcs/`, with the folders of [extra]
/// (made in the browser, still empty) and every folder above each one.
NpcFolderNode npcFolderTree(Iterable<NpcEntry> entries, {Iterable<String> extra = const <String>[]}) {
  final NpcFolderNode root = NpcFolderNode('');
  final Map<String, NpcFolderNode> nodes = <String, NpcFolderNode>{'': root};
  NpcFolderNode node(String path) {
    final NpcFolderNode? known = nodes[path];
    if (known != null) return known;
    final NpcFolderNode created = NpcFolderNode(path);
    nodes[path] = created;
    node(npcParentFolder(path)).children.add(created);
    return created;
  }

  for (final String path in extra) {
    if (npcFolderProblem(path) == null) node(path);
  }
  for (final NpcEntry entry in entries) {
    NpcFolderNode at = node(entry.folder);
    at.own++;
    at.total++;
    String path = entry.folder;
    while (path.isNotEmpty) {
      path = npcParentFolder(path);
      at = nodes[path]!;
      at.total++;
    }
  }
  for (final NpcFolderNode each in nodes.values) {
    each.children.sort((NpcFolderNode a, NpcFolderNode b) => a.name.compareTo(b.name));
  }
  return root;
}

/// One visible row of the folder tree.
class NpcFolderRow {
  const NpcFolderRow(this.node, this.level, this.parent, {required this.expanded});

  final NpcFolderNode node;

  /// 1 for `npcs/`.
  final int level;

  /// The row index of the parent folder, null for `npcs/`.
  final int? parent;

  /// False when the folder has children and they are hidden.
  final bool expanded;

  bool get expandable => node.children.isNotEmpty;
}

/// The rows a tree shows with the folders of [collapsed] closed.
List<NpcFolderRow> npcFolderRows(NpcFolderNode root, Set<String> collapsed) {
  final List<NpcFolderRow> rows = <NpcFolderRow>[];
  void visit(NpcFolderNode node, int level, int? parent) {
    final bool open = !collapsed.contains(node.path);
    final int index = rows.length;
    rows.add(NpcFolderRow(node, level, parent, expanded: open));
    if (!open) return;
    for (final NpcFolderNode child in node.children) {
      visit(child, level + 1, index);
    }
  }

  visit(root, 1, null);
  return rows;
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

/// Behavior ids an NPC can run: every workspace behavior except server-wide
/// ones (`"server": true`), which have no NPC and use server events only.
List<String> npcBehaviorIds(Iterable<AnimusDocument> documents) => <String>[
      for (final AnimusDocument document in documents)
        if (document.kind == DocumentKind.behavior && asMap(document.json)['server'] != true) document.id,
    ];

/// The NPCs whose `skin` is [hash].
List<NpcEntry> npcsWearing(Iterable<NpcEntry> entries, String hash) => <NpcEntry>[
      for (final NpcEntry entry in entries)
        if (entry.json['skin'] == hash) entry,
    ];
