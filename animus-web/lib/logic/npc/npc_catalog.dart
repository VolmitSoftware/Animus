/// NPC definitions as the bulk edits, the NPC form and trait groups read
/// them: one [NpcEntry] per loaded NPC document with its folder (the
/// `npcs/` directories, nested up to 8 deep), file name, tags and modules.
/// The NPC list itself reads the library's summaries, not these.
library;

import '../../model/animus_document.dart';
import '../../model/document_kind.dart';
import '../gui/gui_json.dart';
import 'npc_paths.dart';

/// Tags as the NPC schema and `NpcDefinition` accept them.
final RegExp npcTagPattern = RegExp(r'^[a-z0-9][a-z0-9_-]{0,31}$');

/// The most tags one definition may carry.
const int npcTagLimit = 32;

/// Module keys that take an `enabled` flag, in the order the list offers them.
const List<String> toggleableModules = <String>['animus:combat', 'animus:navigation', 'animus:schedule', 'animus:look-close', 'animus:riding', 'animus:mount', 'animus:hire', 'animus:modelengine'];

/// Modules that are valid as `{"enabled": true}` alone, so enabling one on an
/// NPC without it adds it.
const Set<String> creatableModules = <String>{'animus:combat', 'animus:look-close'};

/// One NPC definition.
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

  /// The name as written (colour codes kept); the file name when empty.
  String get name => asString(json['name'])?.trim().isNotEmpty == true ? asString(json['name'])! : localId;

  /// The body type as written (`minecraft:villager`), `''` when unset.
  String get type => asString(json['type']) ?? '';

  String get world => asString(asMap(json['position'])['world']) ?? '';

  bool get spawned => json['spawned'] == true;

  List<String> get tags => <String>[
    for (final Object? tag in asList(json['tags']))
      if (tag is String) tag,
  ];

  JsonMap get modules => asMap(json['modules']);
}

/// Every NPC definition of a workspace, sorted by folder (as `npcs/` nests
/// them) then name.
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
