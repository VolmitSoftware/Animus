/// The NPC list's rows and filters, read from the library's summaries only:
/// a server's NPCs list with their names before any document is read, and
/// nothing here walks a document. Plain Dart, so it is unit tested.
library;

import 'package:jaspr/jaspr.dart' show Component;

import '../../model/display_name.dart';
import '../../logic/npc/npc_appearance.dart';
import '../../logic/npc/npc_paths.dart';
import '../../logic/offline/new_documents.dart';
import '../../state/library.dart';
import '../../ui/library_query.dart';
import '../../ui/library_rows.dart';

/// One NPC of the list: its row and the values the filters read.
class NpcListItem {
  NpcListItem(this.entry, this.row) : folder = entry.summary.folder ?? npcFolderOf(entry.id);

  final LibraryEntry entry;
  final LibraryRow row;

  /// The folder under `npcs/`, `''` for `npcs/` itself.
  final String folder;

  String get id => entry.id;

  /// The body type as written (`minecraft:villager`), `''` when unknown.
  String get body => entry.summary.type ?? '';
  String get world => entry.summary.world ?? '';
  List<String> get tags => entry.summary.tags;
}

/// A row per NPC of [entries] (`store.library(DocumentKind.npc)`, which
/// lists a moved NPC once, at its new path), with the face [face] draws for
/// it in front.
List<NpcListItem> npcListItems(String workspaceId, List<LibraryEntry> entries, {Component? Function(LibraryEntry entry)? face}) => <NpcListItem>[
      for (final LibraryEntry entry in entries) _item(workspaceId, entry, face?.call(entry)),
    ];

NpcListItem _item(String workspaceId, LibraryEntry entry, Component? leading) {
  final LibraryRow base = entryRow(workspaceId, entry);
  final List<String> tags = entry.summary.tags;
  return NpcListItem(
    entry,
    LibraryRow(
      key: base.key,
      title: base.title,
      // An NPC with no readable name is told apart by where it stands.
      meta: npcLine(entry.summary, place: needsPath(entry.kind, entry.name)),
      advancedMeta: <String>['npcs/${entry.id}', if (tags.isNotEmpty) tags.join(', ')].join(' · '),
      badges: base.badges,
      tags: base.tags,
      href: base.href,
      leading: leading,
      keywords: base.keywords,
    ),
  );
}

/// What the list is narrowed to above its own search and chips.
class NpcListFilter {
  const NpcListFilter({this.folder, this.body, this.world, this.tags = const <String>{}});

  /// A folder: it and every folder below it. Null for all folders.
  final String? folder;
  final String? body;
  final String? world;

  /// NPCs carrying any of these tags; empty for every NPC.
  final Set<String> tags;

  bool get active => folder != null || body != null || world != null || tags.isNotEmpty;

  bool matches(NpcListItem item) =>
      (folder == null || npcFolderWithin(item.folder, folder!)) &&
      (body == null || item.body == body) &&
      (world == null || item.world == world) &&
      (tags.isEmpty || item.tags.any(tags.contains));

  @override
  bool operator ==(Object other) =>
      other is NpcListFilter && other.folder == folder && other.body == body && other.world == world && other.tags.length == tags.length && other.tags.containsAll(tags);

  @override
  int get hashCode => Object.hash(folder, body, world, Object.hashAllUnordered(tags));
}

/// The rows of [items] that [filter] lets through.
List<LibraryRow> npcRows(List<NpcListItem> items, NpcListFilter filter) => <LibraryRow>[
      for (final NpcListItem item in items)
        if (filter.matches(item)) item.row,
    ];

/// [npcRows] kept while the items and the filter stay the same, so the list
/// keeps its search results (it caches them on the rows' identity).
class NpcRowCache {
  List<NpcListItem>? _from;
  NpcListFilter? _filter;
  List<LibraryRow> _rows = const <LibraryRow>[];

  List<LibraryRow> rows(List<NpcListItem> items, NpcListFilter filter) {
    if (!identical(items, _from) || filter != _filter) {
      _rows = filter.active ? npcRows(items, filter) : <LibraryRow>[for (final NpcListItem item in items) item.row];
      _from = items;
      _filter = filter;
    }
    return _rows;
  }
}

/// `npcs/town/market/` for `town/market`, `npcs/` for the root.
String folderLabel(String folder) => folder.isEmpty ? 'npcs/' : 'npcs/$folder/';

/// Every folder under `npcs/` that holds NPCs, with how many it holds
/// together with its subfolders, in tree order.
List<(String, int)> npcFolderCounts(Iterable<NpcListItem> items) {
  final Map<String, int> counts = <String, int>{};
  for (final NpcListItem item in items) {
    String folder = item.folder;
    while (folder.isNotEmpty) {
      counts[folder] = (counts[folder] ?? 0) + 1;
      folder = npcParentFolder(folder);
    }
  }
  final List<String> folders = counts.keys.toList()..sort(compareNpcFolders);
  return <(String, int)>[for (final String folder in folders) (folder, counts[folder]!)];
}

/// The body types in use, in label order.
List<String> npcBodies(Iterable<NpcListItem> items) =>
    (<String>{for (final NpcListItem item in items) item.body}..remove('')).toList()..sort((String a, String b) => bodyLabel(a).compareTo(bodyLabel(b)));

/// The worlds in use, sorted.
List<String> npcWorlds(Iterable<NpcListItem> items) => (<String>{for (final NpcListItem item in items) item.world}..remove('')).toList()..sort();

/// The [limit] most used tags with their counts, most used first.
List<(String, int)> npcTopTags(Iterable<NpcListItem> items, {int limit = 10}) {
  final Map<String, int> counts = <String, int>{};
  for (final NpcListItem item in items) {
    for (final String tag in item.tags) {
      counts[tag] = (counts[tag] ?? 0) + 1;
    }
  }
  final List<(String, int)> tags = <(String, int)>[for (final MapEntry<String, int> entry in counts.entries) (entry.key, entry.value)]
    ..sort(((String, int) a, (String, int) b) => a.$2 != b.$2 ? b.$2.compareTo(a.$2) : a.$1.compareTo(b.$1));
  return tags.take(limit).toList();
}

/// The world most NPCs stand in, where a new one starts; `world` when none.
String mostUsedWorld(Iterable<NpcListItem> items) {
  final Map<String, int> counts = <String, int>{};
  for (final NpcListItem item in items) {
    if (item.world.isNotEmpty) counts[item.world] = (counts[item.world] ?? 0) + 1;
  }
  if (counts.isEmpty) return 'world';
  return counts.entries.reduce((MapEntry<String, int> best, MapEntry<String, int> next) => next.value > best.value ? next : best).key;
}

/// The bodies New NPC offers before "Other".
const List<String> commonBodies = <String>[
  'minecraft:player',
  'minecraft:villager',
  'minecraft:wandering_trader',
  'minecraft:zombie',
  'minecraft:skeleton',
  'minecraft:iron_golem',
  'minecraft:armor_stand',
  'minecraft:piglin',
  'minecraft:horse',
  'minecraft:cat',
];

/// The file name a new NPC called [name] gets in [folder]: its name as a
/// slug, numbered when [taken] (every NPC id) already has it.
String newNpcId(String name, String folder, Set<String> taken) {
  final Set<String> here = <String>{
    for (final String id in taken)
      if (npcFolderOf(id) == folder) npcLocalIdOf(id),
  };
  return freeId(slug(name, fallback: 'npc'), here);
}

/// Why NPC [id] cannot be made in [folder], or null when it can.
String? newNpcProblem(String id, String folder, Set<String> taken) {
  final String? folderProblem = npcFolderProblem(folder);
  if (folderProblem != null) return folderProblem;
  if (!npcSegmentPattern.hasMatch(id)) return 'Use 1-64 lowercase letters, digits, _ or -, starting with a letter or digit.';
  if (taken.contains(npcIdIn(folder, id))) return '${folderLabel(folder)}$id already exists.';
  return null;
}
