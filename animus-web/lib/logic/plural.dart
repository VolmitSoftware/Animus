/// A count with its noun: `1 document`, `2 documents`, `0 files`. Pass
/// [plural] for nouns that do not just add an s (`entry`, `entries`).
String countOf(int count, String noun, {String? plural}) => '$count ${count == 1 ? noun : (plural ?? '${noun}s')}';

/// Names for a line of text: "Baker", "Baker and Smith", "Baker, Smith and
/// 2 more".
String namesLine(List<String> names) => switch (names.length) {
      0 => '',
      1 => names.single,
      2 => '${names[0]} and ${names[1]}',
      _ => '${names[0]}, ${names[1]} and ${names.length - 2} more',
    };
