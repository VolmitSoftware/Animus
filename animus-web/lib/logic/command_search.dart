/// The command palette's entries and its filter.
library;

/// One command: what the list shows, the group it sits under and what runs.
class PaletteCommand {
  const PaletteCommand(this.group, this.label, this.run, {this.hint, this.keywords = const <String>[], this.content = const <String>[], this.file = false});

  final String group;
  final String label;
  final void Function() run;

  /// A short note on the right, such as the shortcut or a file's kind.
  final String? hint;
  final List<String> keywords;

  /// Words from inside a file, such as the items it uses. They match
  /// after everything else.
  final List<String> content;

  /// A file to open; files list before commands.
  final bool file;
}

List<String> _words(String query) => query.trim().toLowerCase().split(RegExp(r'\s+')).where((String word) => word.isNotEmpty).toList();

bool _named(PaletteCommand command, String word) =>
    command.label.toLowerCase().contains(word) ||
    (command.hint?.toLowerCase().contains(word) ?? false) ||
    command.keywords.any((String keyword) => keyword.toLowerCase().contains(word));

String? _inContent(PaletteCommand command, String word) {
  for (final String term in command.content) {
    if (term.toLowerCase().contains(word)) return term;
  }
  return null;
}

/// The commands matching [query]: every word must appear in the label, the
/// hint, a keyword or the content, ignoring case. Files come first, then
/// commands; within each, best matches first, otherwise in order: a label,
/// keyword or id part (`world/citizens-60` has the parts `world` and
/// `citizens-60`) equal to the query, then one starting with it, then a
/// word of the label starting with it, then the rest, then matches found
/// only in the content.
List<PaletteCommand> filterCommands(List<PaletteCommand> commands, String query) {
  final String whole = query.trim().toLowerCase();
  final List<String> words = _words(query);
  if (words.isEmpty) return commands;
  final List<(int, int, int, PaletteCommand)> matched = <(int, int, int, PaletteCommand)>[];
  for (int at = 0; at < commands.length; at++) {
    final PaletteCommand command = commands[at];
    bool inContent = false;
    bool found = true;
    for (final String word in words) {
      if (_named(command, word)) continue;
      if (_inContent(command, word) == null) {
        found = false;
        break;
      }
      inContent = true;
    }
    if (found) matched.add((command.file ? 0 : 1, inContent ? 4 : _rank(command, whole), at, command));
  }
  matched.sort(((int, int, int, PaletteCommand) a, (int, int, int, PaletteCommand) b) {
    if (a.$1 != b.$1) return a.$1.compareTo(b.$1);
    if (a.$2 != b.$2) return a.$2.compareTo(b.$2);
    return a.$3.compareTo(b.$3);
  });
  return <PaletteCommand>[for (final (int, int, int, PaletteCommand) each in matched) each.$4];
}

/// The content term [command] matched [query] by, such as "Bread" for a
/// shop that uses bread; null when its name, hint or keywords matched every
/// word.
String? contentMatch(PaletteCommand command, String query) {
  for (final String word in _words(query)) {
    if (_named(command, word)) continue;
    final String? term = _inContent(command, word);
    if (term != null) return term;
  }
  return null;
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
