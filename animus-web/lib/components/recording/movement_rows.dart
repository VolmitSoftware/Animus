/// The rows of the Movement page. Routes: every route document, with the
/// NPCs that use it, and every NPC that carries points of its own as
/// "name's route" (it opens that NPC). Recordings: every recording, with
/// its length, world and the NPCs that play it. An NPC's own route comes
/// from its summary's point count, the one the nav and Home count, so the
/// list is whole before the NPCs are read; who uses a route or recording
/// is read from the NPCs loaded so far.
library;

import '../../logic/plural.dart';
import '../../logic/npc/npc_tabs.dart' show NpcTab;
import '../../logic/npc/route_usage.dart';
import '../../logic/recording/recording_facts.dart';
import '../../model/animus_document.dart';
import '../../model/document_kind.dart';
import '../../state/library.dart';
import '../../state/workspace_route.dart';
import '../../ui/library_query.dart';
import '../../ui/library_rows.dart';

const LibraryFilter sharedRoutesFilter = LibraryFilter('shared', 'Shared');
/// The page's line counts these "on NPCs" too; the row title ("Baker's
/// route") already says whose it is, so the rows carry no badge for it.
const LibraryFilter ownRoutesFilter = LibraryFilter('own', 'On NPCs');

/// Finds a document of the workspace, or null when it is not read yet.
typedef DocumentLookup = AnimusDocument? Function(DocumentKind kind, String id);

/// What an NPC's navigation uses, read once per document source.
typedef _Movement = ({String? route, String? recording});

final Map<String, (String, _Movement)> _movementCache = <String, (String, _Movement)>{};

_Movement _movementOf(LibraryEntry entry, AnimusDocument document) {
  final (String, _Movement)? cached = _movementCache[entry.key];
  if (cached != null && identical(cached.$1, document.source)) return cached.$2;
  final _Movement movement = (
    route: routeIdOf(document.json),
    recording: recordingIdOf(document.json),
  );
  _movementCache[entry.key] = (document.source, movement);
  return movement;
}


String? _join(List<String?> parts) {
  final List<String> shown = <String>[for (final String? part in parts) if (part != null && part.isNotEmpty) part];
  return shown.isEmpty ? null : shown.join(' · ');
}

/// The NPC users of each route and recording among the NPCs read so far.
({Map<String, List<String>> routes, Map<String, List<String>> recordings}) _scan(List<LibraryEntry> npcs, DocumentLookup document) {
  final Map<String, List<String>> routes = <String, List<String>>{};
  final Map<String, List<String>> recordings = <String, List<String>>{};
  for (final LibraryEntry entry in npcs) {
    final List<String> features = entry.summary.features;
    if (!features.contains('route') && !features.contains('recording')) continue;
    final AnimusDocument? read = document(DocumentKind.npc, entry.id);
    if (read == null) continue;
    final _Movement movement = _movementOf(entry, read);
    if (movement.route case final String route) (routes[route] ??= <String>[]).add(entry.name);
    if (movement.recording case final String recording) (recordings[recording] ??= <String>[]).add(entry.name);
  }
  return (routes: routes, recordings: recordings);
}

/// Every route document and every NPC route of its own, as list rows.
List<LibraryRow> routeRows({required String workspaceId, required List<LibraryEntry> routes, required List<LibraryEntry> npcs, required DocumentLookup document}) {
  final Map<String, List<String>> users = _scan(npcs, document).routes;
  return <LibraryRow>[
    for (final LibraryEntry entry in routes) _shared(workspaceId, entry, users[entry.id] ?? const <String>[]),
    for (final LibraryEntry npc in npcs)
      if (npc.summary.count('points') case final int points when points > 0) _own(workspaceId, npc, points),
  ];
}

LibraryRow _shared(String workspaceId, LibraryEntry entry, List<String> users) {
  final LibraryRow base = entryRow(workspaceId, entry);
  return LibraryRow(
    key: base.key,
    title: base.title,
    meta: _join(<String?>[countOf(entry.summary.count('points') ?? 0, 'point'), usedByLine(users)]),
    advancedMeta: base.advancedMeta,
    badges: base.badges,
    tags: <String>{...base.tags, sharedRoutesFilter.id},
    href: base.href,
    keywords: '${base.keywords} ${users.join(' ')}',
  );
}

LibraryRow _own(String workspaceId, LibraryEntry npc, int points) {
  final LibraryRow base = entryRow(workspaceId, npc);
  final String title = "${npc.name}'s route";
  return LibraryRow(
    key: 'own-route:${npc.id}',
    title: title,
    meta: _join(<String?>[countOf(points, 'point'), npc.summary.world]),
    advancedMeta: npc.id,
    badges: <LibraryBadge>[for (final LibraryBadge badge in base.badges) if (badge.label != 'Despawned') badge],
    tags: <String>{...base.tags.where((String tag) => tag != spawnedFilter.id && tag != despawnedFilter.id), ownRoutesFilter.id},
    href: DocumentRoute(workspaceId, DocumentKind.npc, npc.id, ViewMode.visual, NpcTab.movement.slug).format(),
    keywords: '${npc.id} ${npc.name}',
    sortValues: <String, String>{LibrarySort.name.id: title},
  );
}

/// Every recording, as list rows.
List<LibraryRow> recordingRows({required String workspaceId, required List<LibraryEntry> recordings, required List<LibraryEntry> npcs, required DocumentLookup document}) {
  final Map<String, List<String>> players = _scan(npcs, document).recordings;
  return <LibraryRow>[
    for (final LibraryEntry entry in recordings) _recording(workspaceId, entry, players[entry.id] ?? const <String>[]),
  ];
}

LibraryRow _recording(String workspaceId, LibraryEntry entry, List<String> players) {
  final LibraryRow base = entryRow(workspaceId, entry);
  final double? seconds = entry.summary.seconds;
  return LibraryRow(
    key: base.key,
    title: base.title,
    meta: _join(<String?>[if (seconds != null) lengthText((seconds * 20).round()), entry.summary.world, usedByLine(players)]),
    advancedMeta: base.advancedMeta,
    badges: base.badges,
    tags: base.tags,
    href: base.href,
    keywords: '${base.keywords} ${players.join(' ')}',
  );
}
