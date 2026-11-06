import '../logic/change_sets.dart';
import '../services/server/error_copy.dart';

String _plural(int count, String noun) => '$count $noun${count == 1 ? '' : 's'}';

/// The live log line after an apply or undo, naming the moves in it.
String appliedLine(ChangeApplied applied, {required bool undo}) {
  final int count = applied.documents.length;
  final int moved = applied.documents.where((AppliedDocument document) => document.from != null).length;
  final String what = '${_plural(count, 'document')}${moved == 0 ? '' : ', $moved moved'}';
  return '${undo ? 'Undid' : 'Applied'} $what (change ${applied.changeId}).';
}

/// The live log line after a server validation.
String validationLine(bool valid, int documents, int errors) {
  if (valid) return 'The server accepts ${_plural(documents, 'document')}.';
  if (errors == 0) return 'The server refused the drafts but named no error. Check its console.';
  return 'The server found ${_plural(errors, 'error')}. Fix the marked fields.';
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
    if (refreshed > 0) 'Resynced with the server: ${_plural(refreshed, 'document')} refreshed.' else 'Resynced with the server.',
    if (stale > 0) '${_plural(stale, 'draft')} also changed on the server; merge ${stale == 1 ? 'it' : 'them'} before applying.',
  ];
  return parts.join(' ');
}
