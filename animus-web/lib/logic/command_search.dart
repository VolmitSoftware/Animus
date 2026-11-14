/// The command palette's entries and its filter.
library;

/// One command: what the list shows, the group it sits under and what runs.
class PaletteCommand {
  const PaletteCommand(this.group, this.label, this.run, {this.hint, this.keywords = const <String>[]});

  final String group;
  final String label;
  final void Function() run;

  /// A short note on the right, such as the shortcut or a document's kind.
  final String? hint;
  final List<String> keywords;
}

/// The commands matching [query]: every word must appear in the label, the
/// hint or a keyword, ignoring case. Best matches first, otherwise in order:
/// a label, keyword or id part (`world/citizens-60` has the parts
/// `world` and `citizens-60`) equal to the query, then one starting with it,
/// then a word of the label starting with it, then the rest.
List<PaletteCommand> filterCommands(List<PaletteCommand> commands, String query) {
  final String whole = query.trim().toLowerCase();
  final List<String> words = whole.split(RegExp(r'\s+')).where((String word) => word.isNotEmpty).toList();
  if (words.isEmpty) return commands;
  final List<(int, int, PaletteCommand)> matched = <(int, int, PaletteCommand)>[
    for (int at = 0; at < commands.length; at++)
      if (words.every((String word) =>
          commands[at].label.toLowerCase().contains(word) ||
          (commands[at].hint?.toLowerCase().contains(word) ?? false) ||
          commands[at].keywords.any((String keyword) => keyword.toLowerCase().contains(word))))
        (_rank(commands[at], whole), at, commands[at]),
  ]..sort(((int, int, PaletteCommand) a, (int, int, PaletteCommand) b) => a.$1 != b.$1 ? a.$1.compareTo(b.$1) : a.$2.compareTo(b.$2));
  return <PaletteCommand>[for (final (int, int, PaletteCommand) each in matched) each.$3];
}

int _rank(PaletteCommand command, String query) {
  final String label = command.label.toLowerCase();
  final List<String> names = <String>[
    label,
    for (final String keyword in command.keywords) ...<String>[keyword.toLowerCase(), ...keyword.toLowerCase().split(RegExp('[/:]'))],
  ];
  if (names.contains(query)) return 0;
  if (names.any((String name) => name.startsWith(query))) return 1;
  if (label.split(RegExp(r'\s+')).any((String word) => word.startsWith(query))) return 2;
  return 3;
}
