/// The rows of one document kind for a [LibraryList], read from the store's
/// library: a server workspace lists its whole catalog (names and summaries
/// arrive before the documents), a local one its documents. The one adapter
/// between the store and every list and nav count.
library;

import '../logic/change_sets.dart';
import '../logic/gui/shop_board.dart';
import '../logic/npc/npc_appearance.dart' show bodyLabel;
import '../logic/plural.dart';
import '../model/display_name.dart';
import '../model/document_kind.dart';
import '../model/document_summary.dart';
import '../state/editor_store.dart';
import '../state/library.dart';
import '../state/workspace_route.dart';
import 'library_query.dart';
import 'tone.dart';

/// Filter ids every document list offers.
const LibraryFilter problemsFilter = LibraryFilter('problems', 'Has problems');
const LibraryFilter changedFilter = LibraryFilter('changed', 'Not applied');
const LibraryFilter spawnedFilter = LibraryFilter('spawned', 'Spawned');
const LibraryFilter despawnedFilter = LibraryFilter('despawned', 'Despawned');

/// Every entry of [kind] in the open workspace, as list rows. The same list
/// comes back while the library keeps the same rows of [kind] (and, for
/// shops, the NPCs that open them stay the same), so a list can skip work.
List<LibraryRow> libraryRowsFor(EditorStore store, DocumentKind kind) {
  final String? workspaceId = store.workspace?.id;
  if (workspaceId == null) return const <LibraryRow>[];
  final List<LibraryEntry> entries = store.library(kind);
  final int npcs = kind == DocumentKind.shop ? store.libraryRevisionOf(DocumentKind.npc) : 0;
  final _Rows? kept = _rowsOf[entries];
  if (kept != null && kept.workspaceId == workspaceId && kept.npcs == npcs) return kept.rows;
  final Map<String, List<String>>? openers = kind == DocumentKind.shop ? shopOpeners(store.workspace!.documents) : null;
  final List<LibraryRow> rows = <LibraryRow>[for (final LibraryEntry entry in entries) entryRow(workspaceId, entry, openers: openers)];
  _rowsOf[entries] = _Rows(workspaceId, npcs, rows);
  return rows;
}

/// The rows last made from one library list.
class _Rows {
  const _Rows(this.workspaceId, this.npcs, this.rows);

  final String workspaceId;

  /// The NPC rows' revision the shop lines were read at.
  final int npcs;
  final List<LibraryRow> rows;
}

final Expando<_Rows> _rowsOf = Expando<_Rows>('library rows');

/// How many entries of [kinds] [counts] (`store.libraryCounts()`) holds.
int kindTotal(Map<DocumentKind, int> counts, Iterable<DocumentKind> kinds) {
  int total = 0;
  for (final DocumentKind kind in kinds) {
    total += counts[kind] ?? 0;
  }
  return total;
}

/// What a draft says on its row.
String? draftLabel(DraftState draft) => switch (draft) {
      DraftState.clean => null,
      DraftState.modified => 'Not applied',
      DraftState.created => 'New',
      DraftState.deleted => 'Deleted',
      DraftState.moved => 'Moved',
    };

/// One entry as a row: its plain name, a line about it, its state. A shop's
/// line names who opens it from [openers] (`shopOpeners`, by shop id).
LibraryRow entryRow(String workspaceId, LibraryEntry entry, {Map<String, List<String>>? openers}) {
  final bool npc = entry.kind == DocumentKind.npc;
  final bool? spawned = npc ? entry.summary.spawned ?? true : null;
  final String? draft = draftLabel(entry.draft);
  return LibraryRow(
    key: entry.key,
    title: entry.name,
    meta: switch (entry.kind) {
      DocumentKind.npc => npcLine(entry.summary, place: needsPath(entry.kind, entry.name)),
      DocumentKind.shop => shopListLine(entry.summary.count('offers'), openers?[entry.id] ?? const <String>[]),
      DocumentKind.hologram => _hologramLine(entry.summary),
      _ => null,
    },
    advancedMeta: entry.id,
    badges: <LibraryBadge>[
      if (entry.stale) const LibraryBadge('Changed on server', tone: BadgeTone.warning) else if (draft != null) LibraryBadge(draft, tone: BadgeTone.info),
      if (spawned == false) const LibraryBadge('Despawned'),
      if (entry.problems > 0) LibraryBadge('${entry.problems} ${entry.problems == 1 ? 'problem' : 'problems'}', tone: BadgeTone.error),
    ],
    tags: <String>{
      if (entry.problems > 0) problemsFilter.id,
      if (entry.changed) changedFilter.id,
      if (spawned == true) spawnedFilter.id,
      if (spawned == false) despawnedFilter.id,
    },
    href: DocumentRoute(workspaceId, entry.kind, entry.id).format(),
    keywords: '${entry.id} ${entry.summary.tags.join(' ')}',
  );
}

/// "16 lines, world" for a hologram: how tall it is and where it hangs.
String? _hologramLine(DocumentSummary summary) {
  final int? lines = summary.count('lines');
  final String? world = summary.world;
  final List<String> parts = <String>[
    if (lines != null) countOf(lines, 'line'),
    if (world != null && world.isNotEmpty) world,
  ];
  return parts.isEmpty ? null : parts.join(', ');
}

/// "Villager, world" for an NPC: its body and world. With [place], also
/// the block it stands on ("Villager, harbor at 30, 64, 6"), which tells
/// unnamed NPCs apart without their id.
String? npcLine(DocumentSummary summary, {bool place = false}) {
  final String body = bodyLabel(summary.type ?? '');
  final String world = summary.world ?? '';
  final String line = <String>[if (body.isNotEmpty) body, if (world.isNotEmpty) world].join(', ');
  final double? x = summary.x, y = summary.y, z = summary.z;
  final String at = place && x != null && y != null && z != null ? 'at ${x.floor()}, ${y.floor()}, ${z.floor()}' : '';
  final String both = <String>[if (line.isNotEmpty) line, if (at.isNotEmpty) at].join(' ');
  return both.isEmpty ? null : both;
}
