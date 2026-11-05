/// A line-for-line copy of core's `DenizenScripts`: a bounded reader for Denizen script files. Script containers sit
/// at the left margin, keys nest, and `script:` lists hold command lines kept verbatim; an entry ending in `:` opens a
/// nested block. Both readers must give the same tree for the same text.
library;

import '../citizens/java_lang.dart';

const int denizenMaximumChars = 1 << 20;
const int denizenMaximumLines = 20000;
const int denizenMaximumDepth = 32;

sealed class DenizenNode {
  const DenizenNode(this.line);

  final int line;
}

final class DenizenScalar extends DenizenNode {
  const DenizenScalar(this.text, int line) : super(line);

  final String text;
}

class DenizenEntry {
  const DenizenEntry(this.key, this.value, this.line);

  final String key;
  final DenizenNode value;
  final int line;
}

final class DenizenMapping extends DenizenNode {
  const DenizenMapping(this.entries, int line) : super(line);

  final List<DenizenEntry> entries;

  /// The first entry whose key equals [key] ignoring case, as Denizen looks keys up.
  DenizenNode? get(String key) {
    for (final DenizenEntry entry in entries) {
      if (entry.key.toLowerCase() == key) return entry.value;
    }
    return null;
  }

  String? text(String key) {
    final DenizenNode? value = get(key);
    return value is DenizenScalar ? value.text : null;
  }
}

/// One list entry: its text and, for an entry ending in a colon, the nested block it opens.
class DenizenItem {
  const DenizenItem(this.text, this.children, this.line);

  final String text;
  final DenizenSequence? children;
  final int line;
}

final class DenizenSequence extends DenizenNode {
  const DenizenSequence(this.items, int line) : super(line);

  final List<DenizenItem> items;
}

class DenizenContainer {
  const DenizenContainer(this.file, this.name, this.type, this.body, this.line);

  final String file;
  final String name;
  final String type;
  final DenizenMapping body;
  final int line;
}

class DenizenParsed {
  const DenizenParsed(this.containers, this.issues);

  final List<DenizenContainer> containers;
  final List<String> issues;
}

class _Invalid implements Exception {
  const _Invalid(this.message);

  final String message;
}

class _Line {
  const _Line(this.number, this.indent, this.text);

  final int number;
  final int indent;
  final String text;

  bool get item => text == '-' || text.startsWith('- ');
}

/// Reads one file. A file that cannot be read is one issue and no containers; other files are unaffected.
DenizenParsed parseDenizenScripts(String file, String text) {
  final List<DenizenContainer> containers = <DenizenContainer>[];
  final List<String> issues = <String>[];
  final DenizenMapping root;
  try {
    root = _Reader(_lines(text)).document();
  } on _Invalid catch (invalid) {
    issues.add('$file: ${invalid.message}');
    return DenizenParsed(containers, issues);
  }
  for (final DenizenEntry entry in root.entries) {
    final DenizenNode body = entry.value;
    if (body is! DenizenMapping) {
      issues.add('$file:${entry.line}: top-level key ${entry.key} is not a script container');
      continue;
    }
    final String? type = body.text('type');
    if (type == null || javaIsBlank(type)) {
      issues.add('$file:${entry.line}: script ${entry.key} has no type');
      continue;
    }
    containers.add(DenizenContainer(file, entry.key, javaTrim(type).toLowerCase(), body, entry.line));
  }
  return DenizenParsed(containers, issues);
}

/// `String.stripTrailing()`.
String _stripTrailing(String text) {
  int end = text.length;
  while (end > 0 && javaIsWhitespace(text.codeUnitAt(end - 1))) {
    end--;
  }
  return text.substring(0, end);
}

List<_Line> _lines(String text) {
  if (text.length > denizenMaximumChars) throw const _Invalid('file exceeds $denizenMaximumChars characters');
  final String body = text.startsWith('﻿') ? text.substring(1) : text;
  final List<String> raw = body.split('\n');
  if (raw.length > denizenMaximumLines) throw const _Invalid('file exceeds $denizenMaximumLines lines');
  final List<_Line> lines = <_Line>[];
  for (int index = 0; index < raw.length; index++) {
    String line = raw[index];
    if (line.endsWith('\r')) line = line.substring(0, line.length - 1);
    int indent = 0;
    while (indent < line.length && (line[indent] == ' ' || line[indent] == '\t')) {
      if (line[indent] == '\t') throw _Invalid('line ${index + 1}: tabs are not allowed in indentation');
      indent++;
    }
    final String content = _stripTrailing(line.substring(indent));
    if (content.isEmpty || content.startsWith('#')) continue;
    lines.add(_Line(index + 1, indent, content));
  }
  return lines;
}

class _Reader {
  _Reader(this.lines);

  final List<_Line> lines;
  int position = 0;

  DenizenMapping document() {
    if (lines.isEmpty) return const DenizenMapping(<DenizenEntry>[], 1);
    if (lines.first.indent != 0) throw _Invalid('line ${lines.first.number}: the first key must start at the left margin');
    final DenizenMapping root = mapping(0, 0);
    if (position < lines.length) throw _Invalid('line ${lines[position].number}: unexpected indentation');
    return root;
  }

  _Line? peek() => position < lines.length ? lines[position] : null;

  DenizenNode block(int indent, int depth) {
    if (depth > denizenMaximumDepth) throw _Invalid('line ${peek()!.number}: nesting exceeds $denizenMaximumDepth');
    return peek()!.item ? sequence(indent, depth) : mapping(indent, depth);
  }

  DenizenMapping mapping(int indent, int depth) {
    final List<DenizenEntry> entries = <DenizenEntry>[];
    final int first = peek()!.number;
    while (peek() != null && peek()!.indent == indent && !peek()!.item) {
      final _Line line = lines[position++];
      final int split = denizenSeparator(line.text);
      if (split < 0) throw _Invalid('line ${line.number}: expected key: value');
      final String key = denizenUnquote(javaTrim(line.text.substring(0, split)));
      final String value = split + 1 < line.text.length ? javaTrim(line.text.substring(split + 1)) : '';
      final _Line? next = peek();
      final DenizenNode child;
      if (value.isEmpty && next != null && (next.indent > indent || (next.indent == indent && next.item))) {
        child = block(next.indent, depth + 1);
      } else if (value == '|' || value == '>' || value == '|-' || value == '>-') {
        child = DenizenScalar(folded(indent, value.startsWith('|')), line.number);
      } else {
        child = DenizenScalar(denizenUnquote(value + continuation(indent)), line.number);
      }
      entries.add(DenizenEntry(key, child, line.number));
    }
    return DenizenMapping(entries, first);
  }

  DenizenSequence sequence(int indent, int depth) {
    final List<DenizenItem> items = <DenizenItem>[];
    final int first = peek()!.number;
    while (peek() != null && peek()!.indent == indent && peek()!.item) {
      final _Line line = lines[position++];
      final String text = line.text.length > 1 ? javaTrim(line.text.substring(2)) : '';
      final _Line? next = peek();
      if (text.endsWith(':') && next != null && next.indent > indent && next.item) {
        if (depth + 1 > denizenMaximumDepth) throw _Invalid('line ${next.number}: nesting exceeds $denizenMaximumDepth');
        final DenizenSequence children = sequence(next.indent, depth + 1);
        items.add(DenizenItem(javaTrim(text.substring(0, text.length - 1)), children, line.number));
      } else if (next != null && next.indent > indent && !next.item && (text.endsWith(':') || denizenSeparator(text) >= 0)) {
        if (depth + 1 > denizenMaximumDepth) throw _Invalid('line ${next.number}: nesting exceeds $denizenMaximumDepth');
        mapping(next.indent, depth + 1);
        items.add(DenizenItem(text, null, line.number));
      } else {
        final String full = text + continuation(indent);
        items.add(DenizenItem(denizenWholeQuote(full) ? denizenUnquote(full) : full, null, line.number));
      }
    }
    return DenizenSequence(items, first);
  }

  /// Deeper plain lines continue the scalar above them, joined with single spaces as YAML folds them.
  String continuation(int indent) {
    final StringBuffer text = StringBuffer();
    while (peek() != null && peek()!.indent > indent) {
      final _Line line = lines[position];
      if (line.item || denizenSeparator(line.text) >= 0 && !denizenWholeQuote(line.text)) {
        throw _Invalid('line ${line.number}: unexpected indentation');
      }
      text
        ..write(' ')
        ..write(line.text);
      position++;
    }
    return text.toString();
  }

  String folded(int indent, bool literal) {
    final StringBuffer text = StringBuffer();
    while (peek() != null && peek()!.indent > indent) {
      if (text.isNotEmpty) text.write(literal ? '\n' : ' ');
      text.write(lines[position++].text);
    }
    return text.toString();
  }
}

/// The colon that ends a key: the first one followed by a space, or a final one. Quoted keys end at their closing quote.
int denizenSeparator(String text) {
  int start = 0;
  if (text.isNotEmpty && (text[0] == "'" || text[0] == '"')) {
    final int close = text.indexOf(text[0], 1);
    if (close < 0) return -1;
    start = close + 1;
  }
  for (int index = start; index < text.length; index++) {
    if (text[index] != ':') continue;
    if (index + 1 == text.length || text[index + 1] == ' ') return index;
  }
  return -1;
}

bool denizenWholeQuote(String text) {
  if (text.length < 2) return false;
  final String quote = text[0];
  if ((quote != "'" && quote != '"') || text[text.length - 1] != quote) return false;
  for (int index = 1; index < text.length - 1; index++) {
    final String at = text[index];
    if (quote == "'" && at == "'") {
      if (index + 1 < text.length - 1 && text[index + 1] == "'") {
        index++;
        continue;
      }
      return false;
    }
    if (quote == '"' && at == r'\') {
      index++;
      continue;
    }
    if (quote == '"' && at == '"') return false;
  }
  return true;
}

/// YAML quoting on a whole scalar: `'it''s'` and `"a \"b\""`; anything else is returned as written.
String denizenUnquote(String text) {
  if (!denizenWholeQuote(text)) return text;
  final String inner = text.substring(1, text.length - 1);
  if (text[0] == "'") return inner.replaceAll("''", "'");
  final StringBuffer out = StringBuffer();
  for (int index = 0; index < inner.length; index++) {
    final String at = inner[index];
    if (at == r'\' && index + 1 < inner.length) {
      final String next = inner[++index];
      out.write(switch (next) { 'n' => '\n', 't' => '\t', _ => next });
    } else {
      out.write(at);
    }
  }
  return out.toString();
}
