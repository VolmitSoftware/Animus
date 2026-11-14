/// The name a person reads for a document in lists, headings, pickers,
/// Search and notices: its plain name when that holds a letter or digit,
/// "Unnamed NPC" for an NPC without one, otherwise the last part of its id
/// in words ("gloss:spawn-icon" reads "Spawn icon"). Ids stay in Advanced.
library;

import 'document_kind.dart';

/// What an NPC without a readable name is called.
const String unnamedNpc = 'Unnamed NPC';

final RegExp _letterOrDigit = RegExp(r'[\p{L}\p{N}]', unicode: true);

/// Whether [name] reads as a name: plain text holding at least one letter or
/// digit. A run of block characters (pixel art) or punctuation does not.
bool readableName(String? name) => name != null && _letterOrDigit.hasMatch(name);

/// The last part of [id] in words: "denizen:world-npc/gate_keeper" reads
/// "Gate keeper", "gloss:rainbow" reads "Rainbow".
String idWords(String id) {
  final String last = id.split(RegExp(r'[:/]')).where((String part) => part.isNotEmpty).lastOrNull ?? id;
  final String spaced = last.replaceAll(RegExp(r'[-_.]+'), ' ').trim();
  if (spaced.isEmpty) return id;
  return '${spaced[0].toUpperCase()}${spaced.substring(1)}';
}

/// The name of [kind] document [id] whose plain summary name is [plain].
String displayName(DocumentKind kind, String id, String? plain) {
  if (readableName(plain)) return plain!;
  return kind == DocumentKind.npc ? unnamedNpc : idWords(id);
}

/// How a sentence names the [kind] document [id] it refers to.
typedef DocumentNames = String Function(DocumentKind kind, String id);

/// A referenced document named by its id in words, where the library is
/// not at hand.
String idDocumentName(DocumentKind kind, String id) => displayName(kind, id, null);

/// The folder path an unnamed NPC is told apart by: its id ("world/citizens-60").
bool needsPath(DocumentKind kind, String name) => kind == DocumentKind.npc && name == unnamedNpc;

/// [name] closing a sentence with [mark], unless the name already ends in
/// one ("Welcome!"), which then closes it alone: no "!." or "!?".
String closing(String name, [String mark = '.']) => RegExp(r'[.!?]$').hasMatch(name.trimRight()) ? name : '$name$mark';
