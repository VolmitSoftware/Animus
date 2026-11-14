/// What a skin goes by in lists, headings and pickers (pure): its label, or
/// the NPCs that wear it, never its texture hash.
library;

/// A label that is only a hash or a UUID names nothing.
final RegExp _hashLike = RegExp(r'^([0-9a-fA-F]{16,}|[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})$');

/// [label] when it reads as a name; else "Worn by Baker" (and how many
/// more wear it, [wornBy] in reading order); else "Unused skin".
String skinLabel(String id, {String? label, List<String> wornBy = const <String>[]}) {
  final String? named = label?.trim();
  if (named != null && named.isNotEmpty && !_hashLike.hasMatch(named)) return named;
  if (wornBy.isEmpty) return 'Unused skin';
  return wornBy.length == 1 ? 'Worn by ${wornBy.first}' : 'Worn by ${wornBy.first} and ${wornBy.length - 1} more';
}
