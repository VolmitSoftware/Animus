// Writes every stylesheet in web/styles in the one standard format, and
// counts the custom CSS that is left (test/css_scan_test.dart uses both):
//
//   dart run tool/format_css.dart [folder]
//
// The format: each selector of a list on its own line, the last followed by
// ` {`; one declaration per line, indented two spaces per level; the closing
// brace on its own line; one blank line between rules. A comment sits on the
// line above the rule it explains. Nothing about what a rule does changes.
import 'dart:io';

/// A comment, a rule (selectors and a body) or an at-rule (a prelude and a
/// body of declarations or rules, or no body at all).
sealed class CssNode {}

final class CssComment extends CssNode {
  CssComment(this.text);

  final String text;
}

final class CssDeclaration extends CssNode {
  CssDeclaration(this.text);

  final String text;
}

final class CssBlock extends CssNode {
  CssBlock(this.head, this.body, {required this.atRule});

  final String head;

  /// Null for a statement such as `@import url(...);`.
  final List<CssNode>? body;
  final bool atRule;
}

/// How much custom CSS a stylesheet holds: its rule and at-rule heads and
/// its declarations. Comments, blank lines and line breaks do not count.
typedef CssSize = ({int rules, int declarations});

final class _Reader {
  _Reader(this.text);

  final String text;
  int at = 0;

  bool get done => at >= text.length;

  void skipSpace() {
    while (!done && text[at].trim().isEmpty) {
      at++;
    }
  }

  String comment() {
    final int end = text.indexOf('*/', at + 2);
    if (end < 0) throw FormatException('Unclosed comment', text, at);
    final String found = text.substring(at, end + 2);
    at = end + 2;
    return found;
  }

  /// Reads up to (not past) the first `{`, `;` or `}` outside strings and
  /// brackets.
  String until(Set<String> stops) {
    final int start = at;
    int depth = 0;
    while (!done) {
      final String char = text[at];
      if (char == '"' || char == "'") {
        final int close = text.indexOf(char, at + 1);
        at = close < 0 ? text.length : close + 1;
        continue;
      }
      if (text.startsWith('/*', at)) {
        comment();
        continue;
      }
      if (char == '(' || char == '[') depth++;
      if (char == ')' || char == ']') depth--;
      if (depth == 0 && stops.contains(char)) break;
      at++;
    }
    return text.substring(start, at);
  }

  List<CssNode> nodes({required bool nested}) {
    final List<CssNode> found = <CssNode>[];
    while (true) {
      skipSpace();
      if (done) break;
      if (text[at] == '}') {
        if (!nested) throw FormatException('Unexpected }', text, at);
        at++;
        break;
      }
      if (text.startsWith('/*', at)) {
        found.add(CssComment(comment()));
        continue;
      }
      final String head = until(<String>{'{', ';', '}'});
      if (done || text[at] == '}') {
        if (head.trim().isNotEmpty) found.add(CssDeclaration(head));
        continue;
      }
      if (text[at] == ';') {
        at++;
        found.add(head.trimLeft().startsWith('@') ? CssBlock(head, null, atRule: true) : CssDeclaration(head));
        continue;
      }
      at++;
      found.add(CssBlock(head, nodes(nested: true), atRule: head.trimLeft().startsWith('@')));
    }
    return found;
  }
}

String _collapse(String text) => text.trim().replaceAll(RegExp(r'\s+'), ' ');

/// The parts of [list] split at commas outside brackets.
List<String> _selectors(String list) {
  final List<String> parts = <String>[];
  int depth = 0;
  int start = 0;
  for (int index = 0; index < list.length; index++) {
    final String char = list[index];
    if (char == '(' || char == '[') depth++;
    if (char == ')' || char == ']') depth--;
    if (char == ',' && depth == 0) {
      parts.add(_collapse(list.substring(start, index)));
      start = index + 1;
    }
  }
  parts.add(_collapse(list.substring(start)));
  return parts;
}

/// The parsed stylesheet.
List<CssNode> parseCss(String css) => _Reader(css).nodes(nested: false);

/// A blank line before [node] when it follows [previous]: between rules and
/// before a comment that follows a rule or a comment, never inside a list
/// of declarations and never between a comment and the rule it explains.
bool _gap(CssNode? previous, CssNode node) => switch ((previous, node)) {
      (null, _) || (_, CssDeclaration()) => false,
      (CssComment(), CssBlock()) || (CssDeclaration(), CssComment()) => false,
      _ => true,
    };

void _write(List<CssNode> nodes, String indent, List<String> out) {
  for (int index = 0; index < nodes.length; index++) {
    final CssNode node = nodes[index];
    if (_gap(index == 0 ? null : nodes[index - 1], node)) out.add('');
    switch (node) {
      case CssComment(:final String text):
        final List<String> lines = text.split('\n');
        out.add('$indent${lines.first.trim()}');
        for (final String line in lines.skip(1)) {
          out.add('$indent   ${line.trim()}');
        }
      case CssDeclaration(:final String text):
        final int colon = text.indexOf(':');
        final String property = colon < 0 ? _collapse(text) : text.substring(0, colon).trim();
        final String value = colon < 0 ? '' : _collapse(text.substring(colon + 1));
        out.add(colon < 0 ? '$indent$property;' : '$indent$property: $value;');
      case CssBlock(:final String head, :final List<CssNode>? body, :final bool atRule):
        if (body == null) {
          out.add('$indent${_collapse(head)};');
        } else {
          final List<String> heads = atRule ? <String>[_collapse(head)] : _selectors(head);
          for (int each = 0; each < heads.length - 1; each++) {
            out.add('$indent${heads[each]},');
          }
          out.add('$indent${heads.last} {');
          _write(body, '$indent  ', out);
          out.add('$indent}');
        }
    }
  }
}

/// [css] in the standard format.
String formatCss(String css) {
  final List<String> out = <String>[];
  _write(parseCss(css), '', out);
  return '${out.join('\n')}\n';
}

/// The rule heads and declarations in [css].
CssSize cssSize(String css) {
  int rules = 0;
  int declarations = 0;
  void count(List<CssNode> nodes) {
    for (final CssNode node in nodes) {
      switch (node) {
        case CssDeclaration():
          declarations++;
        case CssBlock(:final List<CssNode>? body):
          rules++;
          if (body != null) count(body);
        case CssComment():
          break;
      }
    }
  }

  count(parseCss(css));
  return (rules: rules, declarations: declarations);
}

/// The editor's stylesheets, in name order.
List<File> stylesheets([String folder = 'web/styles']) => <File>[
      for (final FileSystemEntity entity in Directory(folder).listSync())
        if (entity is File && entity.path.endsWith('.css')) entity,
    ]..sort((File a, File b) => a.path.compareTo(b.path));

void main(List<String> arguments) {
  for (final File file in stylesheets(arguments.isEmpty ? 'web/styles' : arguments.first)) {
    final String text = file.readAsStringSync();
    final String formatted = formatCss(text);
    final CssSize before = cssSize(text), after = cssSize(formatted);
    if (before != after) throw StateError('${file.path}: formatting changed the rules: $before -> $after');
    if (formatted != text) file.writeAsStringSync(formatted);
    stdout.writeln('${file.path}: ${after.rules} rules, ${after.declarations} declarations, ${formatted.split('\n').length - 1} lines');
  }
}
