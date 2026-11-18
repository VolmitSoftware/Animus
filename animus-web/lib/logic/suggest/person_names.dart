/// Players and NPCs named for a person: the names a picker of UUIDs shows,
/// and what it says for a UUID the lists do not hold.
library;

import 'completion_sources.dart';

/// Whose UUID a picker writes: a player's or an NPC's.
enum PersonKind { player, npc }

final RegExp _uuid = RegExp(r'^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$');

/// The names a [kind] picker offers, by UUID.
Map<String, String> personNames(CompletionSources sources, PersonKind kind) => kind == PersonKind.player ? sources.players : sources.npcs;

/// What a picker calls [value] when the lists do not name it.
String unknownPersonLabel(PersonKind kind, String value, {required bool advanced}) {
  if (advanced) return value;
  if (!_uuid.hasMatch(value)) return kind == PersonKind.player ? 'Not a player UUID' : 'Not an NPC UUID';
  return kind == PersonKind.player ? 'A player this server has not seen' : 'An NPC not in this workspace';
}
