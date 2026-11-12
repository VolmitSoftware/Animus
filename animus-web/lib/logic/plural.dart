/// A count with its noun: `1 document`, `2 documents`, `0 files`. Pass
/// [plural] for nouns that do not just add an s (`entry`, `entries`).
String countOf(int count, String noun, {String? plural}) => '$count ${count == 1 ? noun : (plural ?? '${noun}s')}';
