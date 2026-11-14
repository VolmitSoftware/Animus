/// Schema text fields that hold a game id, which Simple shows by name: an
/// item, a block (its state kept) or a painting. Their schema descriptions
/// teach id syntax, which Simple's tips leave out. Values are written as
/// the pickers give them; a field left alone keeps what it holds.
library;

/// The kinds of id a schema text field can hold that Simple names.
enum NamedId { item, block, painting }

const String _idPart = '[a-z0-9_.-]+:)?';

/// What the text field [key] at [path] with [schema] holds, or null for
/// text Simple shows as written.
NamedId? namedIdOf(String path, String key, Map<String, Object?> schema) {
  final Object? pattern = schema['pattern'];
  if (pattern is! String || !pattern.contains(_idPart)) return null;
  if (path.endsWith('.item.id')) return NamedId.item;
  if (key == 'block') return NamedId.block;
  if (path.endsWith('painting.variant')) return NamedId.painting;
  return null;
}

/// The keys of the object at [path] with fields [keys] that Simple keeps
/// without showing: an item's component text and import record.
Set<String> simpleHiddenKeys(String path, Set<String> keys) =>
    path.endsWith('.item') && keys.contains('id') ? keys.intersection(const <String>{'components', 'imported'}) : const <String>{};

/// The namespaced block of a block state: `oak_stairs[facing=east]` is
/// `minecraft:oak_stairs`.
String blockOfState(String state) {
  final int bracket = state.indexOf('[');
  final String block = (bracket < 0 ? state : state.substring(0, bracket)).trim();
  if (block.isEmpty) return '';
  return block.contains(':') ? block : 'minecraft:$block';
}

/// An id in words, another namespace in brackets: `minecraft:polar_bear`
/// reads "Polar bear", `mymod:dragon` "Dragon (mymod)".
String idName(String id) {
  final int colon = id.indexOf(':');
  final String namespace = colon < 0 ? 'minecraft' : id.substring(0, colon);
  final String words = (colon < 0 ? id : id.substring(colon + 1)).replaceAll(RegExp(r'[_./-]+'), ' ').trim();
  if (words.isEmpty) return '';
  final String name = '${words[0].toUpperCase()}${words.substring(1)}';
  return namespace == 'minecraft' ? name : '$name ($namespace)';
}

/// A painting variant by name: `minecraft:kebab` reads "Kebab".
String paintingName(String variant) => idName(variant);

/// A particle by name: `minecraft:happy_villager` reads "Happy villager".
String particleName(String particle) => idName(particle);

final RegExp _id = RegExp(r'\b[a-z0-9_.-]+:[a-z0-9_./-]+');

/// [text] without its sentences that name a game id, for a Simple tip.
String withoutIdSentences(String text) => <String>[
      for (final String sentence in text.trim().split(RegExp(r'(?<=\.)\s+')))
        if (sentence.isNotEmpty && !_id.allMatches(sentence).any((RegExpMatch id) => !RegExp(r'^(https?|wss?):').hasMatch(id.group(0)!))) sentence,
    ].join(' ');

/// "minecraft:cat bodies only." as "Cat bodies only.": the body a schema
/// description names, by name.
String bodiesInWords(String text) => text.replaceAllMapped(RegExp(r'\b([a-z0-9_.-]+:[a-z0-9_./-]+) bodies'), (Match match) => '${idName(match.group(1)!)} bodies');
