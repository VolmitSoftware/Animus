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
/// hint or a keyword, ignoring case. Order is kept.
List<PaletteCommand> filterCommands(List<PaletteCommand> commands, String query) {
  final List<String> words = query.toLowerCase().split(RegExp(r'\s+')).where((String word) => word.isNotEmpty).toList();
  if (words.isEmpty) return commands;
  return <PaletteCommand>[
    for (final PaletteCommand command in commands)
      if (words.every((String word) =>
          command.label.toLowerCase().contains(word) ||
          (command.hint?.toLowerCase().contains(word) ?? false) ||
          command.keywords.any((String keyword) => keyword.toLowerCase().contains(word))))
        command,
  ];
}
