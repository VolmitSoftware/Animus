import '../logic/change_sets.dart';
import '../logic/plural.dart';
import '../services/server/error_copy.dart';

/// The live log line after an apply or undo, naming the moves in it. The
/// change id stays in Review's history.
String appliedLine(ChangeApplied applied, {required bool undo}) {
  final int count = applied.documents.length;
  final int moved = applied.documents.where((AppliedDocument document) => document.from != null).length;
  final String what = '${countOf(count, 'document')}${moved == 0 ? '' : ', $moved moved'}';
  return '${undo ? 'Undid' : 'Applied'} $what.';
}

/// The live log line after a server validation.
String validationLine(bool valid, int documents, int errors) {
  if (valid) return 'The server accepts ${countOf(documents, 'document')}.';
  if (errors == 0) return 'The server refused the drafts but named no error. Check its console.';
  return 'The server found ${countOf(errors, 'error')}. Fix the marked fields.';
}

/// The live log line after spawning or despawning a selection.
String spawnLine({required bool spawn, required int total, required int sent, required int accepted, required List<String> refused, ErrorCopy? reason}) {
  final String verb = spawn ? 'Spawned' : 'Despawned';
  final String npcs = 'NPC${total == 1 ? '' : 's'}';
  final List<String> parts = <String>[
    if (sent == total && refused.isEmpty) '$verb $accepted $npcs on the server.' else '$verb $accepted of $total $npcs.',
    if (refused.isNotEmpty) 'The server refused ${refused.take(3).join(', ')}${refused.length > 3 ? ' and ${refused.length - 3} more' : ''}: ${reason?.text ?? ''}',
    if (sent < total) 'Connection failed with ${total - sent} unsent. Select them again after reconnecting.',
  ];
  return parts.join(' ').trim();
}

/// The live log line once a reconnect has reloaded the catalog: clean copies
/// it replaced, and drafts the server changed too, which need a merge.
String resyncLine({required int refreshed, required int stale}) {
  if (refreshed == 0 && stale == 0) return 'Resynced with the server; nothing changed while disconnected.';
  final List<String> parts = <String>[
    if (refreshed > 0) 'Resynced with the server: ${countOf(refreshed, 'document')} refreshed.' else 'Resynced with the server.',
    if (stale > 0) '${countOf(stale, 'change')} ${stale == 1 ? 'conflicts' : 'conflict'} with the server; review before applying.',
  ];
  return parts.join(' ');
}

/// The live log line after Reload read the catalog again: documents new on
/// the server, gone from it, and clean copies read again; drafts the server
/// changed too need a merge.
String reloadLine({required int added, required int removed, required int refreshed, required int stale}) {
  final List<String> found = <String>[
    if (added > 0) '$added new',
    if (removed > 0) '$removed removed',
    if (refreshed > 0) '$refreshed refreshed',
  ];
  return <String>[
    if (found.isEmpty) 'Reloaded the catalog; nothing changed.' else 'Reloaded the catalog: ${found.join(', ')}.',
    if (stale > 0) '${countOf(stale, 'change')} ${stale == 1 ? 'conflicts' : 'conflict'} with the server; review before applying.',
  ].join(' ');
}
