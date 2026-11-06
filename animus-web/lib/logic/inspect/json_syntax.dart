/// The first JSON syntax problem in a text, with its line and column and a
/// plain sentence ("expected a comma after the value"), for the code view.
/// The browser's own parser reports only "at position 98".
library;

/// Where the text stops being JSON, and why.
class JsonSyntaxProblem {
  const JsonSyntaxProblem(this.offset, this.line, this.column, this.message);

  final int offset;

  /// 1-based.
  final int line;

  /// 1-based.
  final int column;
  final String message;

  /// "Line 5, column 3: expected a comma after the value."
  String get text => 'Line $line, column $column: $message';
}

class _Stop implements Exception {
  const _Stop(this.offset, this.message);

  final int offset;
  final String message;
}

/// The first problem in [text], or null when it parses as one JSON value.
JsonSyntaxProblem? jsonSyntaxProblem(String text) {
  try {
    final _Reader reader = _Reader(text);
    reader.space();
    if (reader.done) throw const _Stop(0, 'the document is empty; it needs a JSON object');
    reader.value(0);
    reader.space();
    if (!reader.done) throw _Stop(reader.at, 'unexpected text after the end of the document');
    return null;
  } on _Stop catch (stop) {
    final int offset = stop.offset.clamp(0, text.length);
    final String before = text.substring(0, offset);
    final int line = '\n'.allMatches(before).length + 1;
    final int column = offset - (before.lastIndexOf('\n') + 1) + 1;
    return JsonSyntaxProblem(offset, line, column, stop.message);
  }
}

class _Reader {
  _Reader(this.text);

  final String text;
  int at = 0;

  bool get done => at >= text.length;

  String get _here => done ? '' : text[at];

  void space() {
    while (!done && ' \t\r\n'.contains(text[at])) {
      at++;
    }
  }

  Never _end(String what) => throw _Stop(at, 'the document ends early: $what');

  String _shown() {
    final String glyph = _here;
    return glyph == '"' ? 'a quote' : (glyph == "'" ? "a single quote (JSON strings use double quotes)" : "'$glyph'");
  }

  void value(int depth) {
    if (depth > 256) throw _Stop(at, 'nested more than 256 levels deep');
    space();
    if (done) _end('a value is missing');
    final String glyph = _here;
    switch (glyph) {
      case '{':
        _object(depth);
      case '[':
        _array(depth);
      case '"':
        _string();
      case 't':
        _word('true');
      case 'f':
        _word('false');
      case 'n':
        _word('null');
      default:
        if (glyph == '-' || RegExp('[0-9]').hasMatch(glyph)) {
          _number();
        } else if (glyph == '}' || glyph == ']') {
          throw _Stop(at, 'a value is missing before $glyph; remove the comma before it if there is one');
        } else {
          throw _Stop(at, 'expected a value (text in double quotes, a number, true, false, null, an object or a list), found ${_shown()}');
        }
    }
  }

  void _object(int depth) {
    at++;
    space();
    if (_here == '}') {
      at++;
      return;
    }
    while (true) {
      space();
      if (done) _end('a } is missing to close an object');
      if (_here != '"') {
        if (_here == '}') throw _Stop(at, 'remove the comma before }');
        throw _Stop(at, 'expected a key in double quotes, found ${_shown()}');
      }
      _string();
      space();
      if (done) _end('a : and a value are missing');
      if (_here != ':') throw _Stop(at, 'expected a colon after the key, found ${_shown()}');
      at++;
      value(depth + 1);
      space();
      if (done) _end('a } is missing to close an object');
      if (_here == ',') {
        at++;
        continue;
      }
      if (_here == '}') {
        at++;
        return;
      }
      throw _Stop(at, 'expected a comma or } after the value, found ${_shown()}');
    }
  }

  void _array(int depth) {
    at++;
    space();
    if (_here == ']') {
      at++;
      return;
    }
    while (true) {
      value(depth + 1);
      space();
      if (done) _end('a ] is missing to close a list');
      if (_here == ',') {
        at++;
        space();
        if (_here == ']') throw _Stop(at, 'remove the comma before ]');
        continue;
      }
      if (_here == ']') {
        at++;
        return;
      }
      throw _Stop(at, 'expected a comma or ] after the item, found ${_shown()}');
    }
  }

  void _string() {
    final int start = at;
    at++;
    while (!done) {
      final int code = text.codeUnitAt(at);
      if (code == 0x22) {
        at++;
        return;
      }
      if (code == 0x5C) {
        at++;
        if (done) break;
        final String escape = text[at];
        if (escape == 'u') {
          if (at + 4 >= text.length || !RegExp(r'^[0-9a-fA-F]{4}$').hasMatch(text.substring(at + 1, at + 5))) throw _Stop(at - 1, r'\u needs four hex digits');
          at += 5;
          continue;
        }
        if (!r'"\/bfnrt'.contains(escape)) throw _Stop(at - 1, 'unknown escape \\$escape; write \\\\ for a backslash');
        at++;
        continue;
      }
      if (code < 0x20) throw _Stop(at, 'a line break inside text; write \\n instead, or close the quote');
      at++;
    }
    throw _Stop(start, 'this text is never closed with a double quote');
  }

  void _word(String word) {
    if (text.startsWith(word, at)) {
      at += word.length;
      return;
    }
    throw _Stop(at, 'expected $word, or text in double quotes');
  }

  void _number() {
    final Match? match = RegExp(r'-?(0|[1-9][0-9]*)(\.[0-9]+)?([eE][-+]?[0-9]+)?').matchAsPrefix(text, at);
    if (match == null || match.end == at) throw _Stop(at, 'not a number');
    at = match.end;
  }
}
