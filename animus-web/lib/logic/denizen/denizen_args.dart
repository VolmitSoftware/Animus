/// A line-for-line copy of core's `DenizenArgs`: command arguments, tags in text, and durations.
library;

final RegExp _prefixName = RegExp(r'^[A-Za-z_][A-Za-z0-9_]*$');
final RegExp _duration = RegExp(r'^[0-9]{1,7}(?:\.[0-9]{1,4})?[tsmh]?$');

/// One argument. [prefix] is lowercase, or empty when the argument has none.
class DenizenArg {
  const DenizenArg(this.prefix, this.value, this.quoted);

  final String prefix;
  final String value;
  final bool quoted;

  bool isWord(String word) => prefix.isEmpty && !quoted && value.toLowerCase() == word.toLowerCase();
}

/// A command line: its lowercase name, without a leading `~`, and its arguments.
class DenizenCommand {
  const DenizenCommand(this.name, this.args);

  final String name;
  final List<DenizenArg> args;

  DenizenArg? prefixed(String prefix) {
    for (final DenizenArg arg in args) {
      if (arg.prefix == prefix) return arg;
    }
    return null;
  }

  List<DenizenArg> plain() => <DenizenArg>[for (final DenizenArg arg in args) if (arg.prefix.isEmpty) arg];
}

DenizenCommand denizenCommand(String line) {
  final List<String> tokens = <String>[];
  final List<bool> quoted = <bool>[];
  denizenSplit(line, tokens, quoted);
  if (tokens.isEmpty) return const DenizenCommand('', <DenizenArg>[]);
  String name = tokens.first.toLowerCase();
  if (name.startsWith('~')) name = name.substring(1);
  final List<DenizenArg> args = <DenizenArg>[];
  for (int index = 1; index < tokens.length; index++) {
    args.add(_arg(tokens[index], quoted[index]));
  }
  return DenizenCommand(name, args);
}

DenizenArg _arg(String token, bool quoted) {
  if (!quoted) {
    final int colon = token.indexOf(':');
    if (colon > 0 && _prefixName.hasMatch(token.substring(0, colon))) {
      return DenizenArg(token.substring(0, colon).toLowerCase(), token.substring(colon + 1), false);
    }
  }
  return DenizenArg('', token, quoted);
}

/// Splits on spaces outside quotes and outside `<...>` tags and their `[...]` parameters.
void denizenSplit(String line, List<String> tokens, List<bool> quoted) {
  final StringBuffer current = StringBuffer();
  bool wasQuoted = false;
  String quote = '';
  final StringBuffer stack = StringBuffer();
  for (int index = 0; index < line.length; index++) {
    final String at = line[index];
    if (quote.isNotEmpty) {
      if (at == quote && stack.isEmpty && (index + 1 == line.length || line[index + 1] == ' ')) {
        quote = '';
        continue;
      }
      current.write(at);
      _trackTag(line, index, at, stack);
      continue;
    }
    if (at == ' ' && stack.isEmpty) {
      if (current.isNotEmpty || wasQuoted) {
        tokens.add(current.toString());
        quoted.add(wasQuoted);
      }
      current.clear();
      wasQuoted = false;
      continue;
    }
    if ((at == '"' || at == "'") && stack.isEmpty && (current.isEmpty || _prefixOpen(current.toString()))) {
      quote = at;
      wasQuoted = current.isEmpty;
      continue;
    }
    current.write(at);
    _trackTag(line, index, at, stack);
  }
  if (current.isNotEmpty || wasQuoted) {
    tokens.add(current.toString());
    quoted.add(wasQuoted);
  }
}

bool _prefixOpen(String current) =>
    current.length > 1 && current[current.length - 1] == ':' && _prefixName.hasMatch(current.substring(0, current.length - 1));

void _trackTag(String line, int index, String at, StringBuffer stack) {
  final String text = stack.toString();
  if (at == '<' && denizenOpensTag(line, index)) {
    stack.write('<');
  } else if (at == '[' && text.isNotEmpty) {
    stack.write('[');
  } else if (at == ']' && text.isNotEmpty && text[text.length - 1] == '[') {
    stack
      ..clear()
      ..write(text.substring(0, text.length - 1));
  } else if (at == '>' && text.isNotEmpty && text[text.length - 1] == '<') {
    stack
      ..clear()
      ..write(text.substring(0, text.length - 1));
  }
}

/// A `<` starts a tag when a non-space follows that is not `=`, `<` or `>`.
bool denizenOpensTag(String text, int index) {
  if (index + 1 >= text.length) return false;
  final String next = text[index + 1];
  return next != ' ' && next != '=' && next != '<' && next != '>';
}

/// Text broken into literal runs and tags.
class DenizenSegment {
  const DenizenSegment(this.text, this.tag);

  final String text;
  final bool tag;
}

List<DenizenSegment> denizenSegments(String text) {
  final List<DenizenSegment> segments = <DenizenSegment>[];
  final StringBuffer literal = StringBuffer();
  int index = 0;
  while (index < text.length) {
    final String at = text[index];
    if (at == '<' && denizenOpensTag(text, index)) {
      final int end = denizenTagEnd(text, index);
      if (end > 0) {
        if (literal.isNotEmpty) {
          segments.add(DenizenSegment(literal.toString(), false));
          literal.clear();
        }
        segments.add(DenizenSegment(text.substring(index + 1, end), true));
        index = end + 1;
        continue;
      }
    }
    literal.write(at);
    index++;
  }
  if (literal.isNotEmpty) segments.add(DenizenSegment(literal.toString(), false));
  return segments;
}

/// The index of the `>` closing the tag opened at [start], or -1.
int denizenTagEnd(String text, int start) {
  final StringBuffer stack = StringBuffer('<');
  for (int index = start + 1; index < text.length; index++) {
    _trackTag(text, index, text[index], stack);
    if (stack.isEmpty) return index;
  }
  return -1;
}

class DenizenPart {
  const DenizenPart(this.name, this.parameter);

  final String name;
  final String? parameter;
}

class DenizenTag {
  const DenizenTag(this.parts, this.fallback);

  final List<DenizenPart> parts;
  final String? fallback;

  String get base => parts.isEmpty ? '' : parts.first.name;

  /// A short name for reports: the base and its first attribute, without parameters, such as `player.flag`.
  String get label {
    if (parts.isEmpty) return '';
    final DenizenPart first = parts.first;
    if (first.name.isEmpty && first.parameter != null) return '[definition]';
    final String lowered = first.name.toLowerCase();
    if (lowered.startsWith('&') || parts.length == 1) return lowered;
    return '$lowered.${parts[1].name.toLowerCase()}';
  }
}

DenizenTag denizenTag(String content) {
  String body = content;
  String? fallback;
  int depth = 0;
  for (int index = 0; index + 1 < content.length; index++) {
    final String at = content[index];
    if (at == '[') {
      depth++;
    } else if (at == ']' && depth > 0) {
      depth--;
    } else if (depth == 0 && at == '|' && content[index + 1] == '|') {
      body = content.substring(0, index);
      fallback = content.substring(index + 2);
      break;
    }
  }
  final List<DenizenPart> parts = <DenizenPart>[];
  final StringBuffer name = StringBuffer();
  StringBuffer? parameter;
  depth = 0;
  for (int index = 0; index < body.length; index++) {
    final String at = body[index];
    if (depth == 0 && at == '.') {
      parts.add(DenizenPart(name.toString(), parameter?.toString()));
      name.clear();
      parameter = null;
      continue;
    }
    if (at == '[') {
      depth++;
      if (depth == 1) {
        parameter = StringBuffer();
        continue;
      }
    } else if (at == ']' && depth > 0) {
      depth--;
      if (depth == 0) continue;
    }
    if (depth > 0) {
      parameter!.write(at);
    } else {
      name.write(at);
    }
  }
  parts.add(DenizenPart(name.toString(), parameter?.toString()));
  return DenizenTag(parts, fallback);
}

/// Every tag in the text, outermost only.
List<DenizenTag> denizenTags(String text) =>
    <DenizenTag>[for (final DenizenSegment segment in denizenSegments(text)) if (segment.tag) denizenTag(segment.text)];

bool _digit(String character) => character.codeUnitAt(0) >= 0x30 && character.codeUnitAt(0) <= 0x39;

/// A Denizen duration in ticks, rounding half up like `BigDecimal.HALF_UP`; null when it is not one.
int? denizenTicks(String text) {
  final String value = text.trim().toLowerCase();
  if (!_duration.hasMatch(value)) return null;
  final String last = value[value.length - 1];
  final String unit = _digit(last) ? 's' : last;
  final String number = _digit(last) ? value : value.substring(0, value.length - 1);
  final int factor = switch (unit) { 't' => 1, 's' => 20, 'm' => 1200, _ => 72000 };
  final int dot = number.indexOf('.');
  final String digits = dot < 0 ? number : number.substring(0, dot) + number.substring(dot + 1);
  final int scale = dot < 0 ? 0 : number.length - dot - 1;
  final BigInt scaled = BigInt.parse(digits) * BigInt.from(factor);
  final BigInt divisor = BigInt.from(10).pow(scale);
  BigInt ticks = scaled ~/ divisor;
  if ((scaled % divisor) * BigInt.two >= divisor && scale > 0) ticks += BigInt.one;
  if (ticks > BigInt.from(10000000)) return null;
  return ticks.toInt();
}
